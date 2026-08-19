#!/usr/bin/env python3
"""Synchronize the Flask SQLite domain data into the Java MySQL schema.

The Flask applications remain SQLite-only. This process opens both SQLite
databases read-only, writes the Java schema in one InnoDB transaction, and
advances its MySQL cursor only after verification succeeds.
"""

from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import json
import os
import re
import sqlite3
import sys
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable, Iterator, Mapping, Sequence

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from scripts import migrate_python_to_java_mysql as domain


STREAM_NAME = "python_sqlite_domain"
LOCK_NAME = "nxr_python_to_java_mysql_sync"


@dataclass(frozen=True)
class SyncCursor:
    cards_rowid: int = 0
    cards_updated_jd: float = 0.0
    temp_id: int = 0
    temp_event_jd: float = 0.0
    waitlist_id: int = 0
    ai_rowid: int = 0

    @classmethod
    def from_json(cls, value: str | bytes | Mapping[str, Any] | None) -> "SyncCursor":
        if value is None:
            return cls()
        if isinstance(value, bytes):
            value = value.decode("utf-8")
        payload = json.loads(value) if isinstance(value, str) else dict(value)
        return cls(
            cards_rowid=int(payload.get("cards_rowid") or 0),
            cards_updated_jd=float(payload.get("cards_updated_jd") or 0),
            temp_id=int(payload.get("temp_id") or 0),
            temp_event_jd=float(payload.get("temp_event_jd") or 0),
            waitlist_id=int(payload.get("waitlist_id") or 0),
            ai_rowid=int(payload.get("ai_rowid") or 0),
        )

    def to_json(self) -> str:
        return json.dumps(asdict(self), sort_keys=True, separators=(",", ":"))


class SyncSource(domain.SourceBundle):
    TEMP_EVENT_SQL = """
        MAX(
            COALESCE(julianday(updated_at), 0),
            COALESCE(julianday(approved_at), 0),
            COALESCE(julianday(upload_started), 0),
            COALESCE(julianday(upload_completed), 0)
        )
    """

    def __enter__(self) -> "SyncSource":
        super().__enter__()
        self.db.execute("BEGIN")
        return self

    def validate_light(self, *, quick_check: bool = False) -> None:
        required_main = {
            "cards",
            "waitlist",
            "dictionary_groups",
            "dictionary_items",
            "ai_character_cache",
        }
        main_tables = {
            row[0]
            for row in self.db.execute(
                "SELECT name FROM main.sqlite_master WHERE type='table'"
            )
        }
        temp_tables = {
            row[0]
            for row in self.db.execute(
                "SELECT name FROM tempdb.sqlite_master WHERE type='table'"
            )
        }
        missing = sorted(required_main - main_tables)
        if "temp_cards" not in temp_tables:
            missing.append("tempdb.temp_cards")
        if missing:
            raise domain.MigrationError(
                "Missing source tables: " + ", ".join(missing)
            )
        if quick_check:
            for schema in ("main", "tempdb"):
                result = self.db.execute(f"PRAGMA {schema}.quick_check").fetchone()[0]
                if result != "ok":
                    raise domain.MigrationError(
                        f"{schema} SQLite quick check failed: {result}"
                    )

    def capture_cursor(self) -> SyncCursor:
        cards = self.db.execute(
            """
            SELECT COALESCE(MAX(rowid), 0),
                   COALESCE(MAX(julianday(updated_at)), 0)
            FROM main.cards
            """
        ).fetchone()
        temp = self.db.execute(
            f"""
            SELECT COALESCE(MAX(id), 0),
                   COALESCE(MAX({self.TEMP_EVENT_SQL}), 0)
            FROM tempdb.temp_cards
            """
        ).fetchone()
        waitlist_id = self.db.execute(
            "SELECT COALESCE(MAX(id), 0) FROM main.waitlist"
        ).fetchone()[0]
        ai_rowid = self.db.execute(
            "SELECT COALESCE(MAX(rowid), 0) FROM main.ai_character_cache"
        ).fetchone()[0]
        return SyncCursor(
            cards_rowid=int(cards[0] or 0),
            cards_updated_jd=float(cards[1] or 0),
            temp_id=int(temp[0] or 0),
            temp_event_jd=float(temp[1] or 0),
            waitlist_id=int(waitlist_id or 0),
            ai_rowid=int(ai_rowid or 0),
        )

    def changed_cert_ids(self, cursor: SyncCursor) -> list[str]:
        cert_ids: set[str] = set()
        card_rows = self.db.execute(
            """
            SELECT cert_id
            FROM main.cards
            WHERE rowid > ?
               OR (? > 0 AND COALESCE(julianday(updated_at), 0) > ?)
            """,
            (cursor.cards_rowid, cursor.cards_updated_jd, cursor.cards_updated_jd),
        )
        temp_rows = self.db.execute(
            f"""
            SELECT cert_id
            FROM tempdb.temp_cards
            WHERE id > ?
               OR (? > 0 AND {self.TEMP_EVENT_SQL} > ?)
            """,
            (cursor.temp_id, cursor.temp_event_jd, cursor.temp_event_jd),
        )
        for row in list(card_rows) + list(temp_rows):
            cert_ids.add(domain.normalize_cert_id(row[0]))
        return sorted(cert_ids)

    def submission_for_cert(self, cert_id: str) -> dict[str, Any] | None:
        normalized = domain.normalize_cert_id(cert_id)
        card_rows = self.db.execute(
            "SELECT * FROM main.cards WHERE UPPER(TRIM(cert_id)) = ?",
            (normalized,),
        ).fetchall()
        temp_rows = self.db.execute(
            "SELECT * FROM tempdb.temp_cards WHERE UPPER(TRIM(cert_id)) = ?",
            (normalized,),
        ).fetchall()
        if len(card_rows) > 1 or len(temp_rows) > 1:
            raise domain.MigrationError(
                f"Case-insensitive certificate duplicate detected: {normalized}"
            )
        temp_row = temp_rows[0] if temp_rows else None
        if card_rows:
            mapped = dict(card_rows[0])
            mapped.update(
                {
                    "temp_entry_notes": temp_row["entry_notes"] if temp_row else None,
                    "temp_approved_at": temp_row["approved_at"] if temp_row else None,
                    "temp_approval_sequence": (
                        temp_row["approval_sequence"] if temp_row else None
                    ),
                    "temp_upload_completed": (
                        temp_row["upload_completed"] if temp_row else None
                    ),
                }
            )
            return domain.map_published_row(mapped)
        if temp_row is not None:
            return domain.map_temp_only_row(temp_row)
        return None

    def iter_changed_submissions(self, cursor: SyncCursor) -> Iterator[dict[str, Any]]:
        for cert_id in self.changed_cert_ids(cursor):
            row = self.submission_for_cert(cert_id)
            if row is not None:
                yield row

    def iter_waitlist_since(self, cursor: SyncCursor) -> Iterator[tuple[Any, ...]]:
        rows = self.db.execute(
            "SELECT email, created_at FROM main.waitlist WHERE id > ? ORDER BY id",
            (cursor.waitlist_id,),
        )
        for row in rows:
            email = domain.clean(row["email"]).lower()
            if not email:
                raise domain.MigrationError("Waitlist contains an empty email")
            yield (
                email,
                domain.to_datetime(row["created_at"]) or dt.datetime(1970, 1, 1),
            )

    def iter_ai_cache_since(self, cursor: SyncCursor) -> Iterator[tuple[Any, ...]]:
        rows = self.db.execute(
            """
            WITH changed AS (
                SELECT DISTINCT
                    UPPER(TRIM(cert_id)) AS cert_key,
                    LOWER(TRIM(language)) AS language_key
                FROM main.ai_character_cache
                WHERE rowid > ?
            ), ranked AS (
                SELECT a.*,
                       UPPER(TRIM(a.cert_id)) AS cert_key,
                       LOWER(TRIM(a.language)) AS language_key,
                       ROW_NUMBER() OVER (
                           PARTITION BY UPPER(TRIM(a.cert_id)), LOWER(TRIM(a.language))
                           ORDER BY datetime(a.created_at) DESC, a.rowid DESC
                       ) AS rank_no
                FROM main.ai_character_cache a
            )
            SELECT
                a.cert_id,a.language,a.rendered_html,a.created_at,
                c.brand,c.player,c.card_name,c.movie_name
            FROM ranked a
            JOIN changed x
              ON x.cert_key=a.cert_key AND x.language_key=a.language_key
            LEFT JOIN main.cards c ON c.cert_id=a.cert_id
            WHERE a.rank_no=1
            ORDER BY a.cert_key,a.language_key
            """,
            (cursor.ai_rowid,),
        )
        for row in rows:
            cert_id = domain.normalize_cert_id(row["cert_id"])
            language = domain.truncate(
                row["language"], 16, field="AI cache language"
            ).lower()
            character = (
                domain.optional_text(row["player"])
                or domain.optional_text(row["movie_name"])
                or domain.optional_text(row["card_name"])
                or cert_id
            )
            yield (
                cert_id,
                domain.truncate(row["brand"], 128, field="AI cache brand")
                or "Unknown",
                domain.truncate(character, 255, field="AI cache character"),
                language or "en",
                str(row["rendered_html"] or ""),
                "legacy-python",
                domain.to_datetime(row["created_at"])
                or dt.datetime(1970, 1, 1),
            )


@dataclass(frozen=True)
class SyncState:
    cursor: SyncCursor
    last_full_sync_at: dt.datetime | None


@dataclass(frozen=True)
class SyncRows:
    submissions: Iterable[dict[str, Any]]
    waitlist: Iterable[Sequence[Any]]
    brands: Iterable[Sequence[Any]]
    sports_types: Iterable[Sequence[Any]]
    ai_cache: Iterable[Sequence[Any]]


def backup_sqlite_readonly(source_path: Path, destination_path: Path) -> None:
    if not source_path.is_file():
        raise domain.MigrationError(f"Missing SQLite source: {source_path}")
    source = sqlite3.connect(
        f"file:{source_path.resolve()}?mode=ro", uri=True, timeout=30
    )
    destination = sqlite3.connect(destination_path)
    try:
        source.execute("PRAGMA query_only=ON")
        source.backup(destination, pages=2048, sleep=0.05)
    finally:
        destination.close()
        source.close()


@contextlib.contextmanager
def source_snapshots(cards_path: Path, temp_path: Path):
    with tempfile.TemporaryDirectory(prefix="nxr-python-sync-") as directory:
        snapshot_root = Path(directory)
        cards_snapshot = snapshot_root / "cards.db"
        temp_snapshot = snapshot_root / "temp_cards.db"
        backup_sqlite_readonly(cards_path, cards_snapshot)
        backup_sqlite_readonly(temp_path, temp_snapshot)
        print("source_snapshot=ready")
        yield cards_snapshot, temp_snapshot


class PythonToJavaSync:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.connection = None
        self.lock_acquired = False

    @property
    def db(self):
        if self.connection is None:
            raise RuntimeError("MySQL connection is not open")
        return self.connection

    def connect(self) -> None:
        try:
            import pymysql
            from pymysql.cursors import DictCursor
        except ImportError as exc:
            raise domain.MigrationError(
                "PyMySQL is required; install requirements-mysql.txt in an isolated environment"
            ) from exc

        options: dict[str, Any] = {
            "user": self.args.mysql_user,
            "password": os.environ.get(self.args.mysql_password_env, ""),
            "database": self.args.target_database,
            "charset": "utf8mb4",
            "autocommit": False,
            "cursorclass": DictCursor,
            "connect_timeout": 5,
            "read_timeout": 180,
            "write_timeout": 180,
        }
        if self.args.mysql_unix_socket:
            options["unix_socket"] = self.args.mysql_unix_socket
        else:
            options["host"] = self.args.mysql_host
            options["port"] = self.args.mysql_port
        self.connection = pymysql.connect(**options)

    def close(self) -> None:
        if self.connection is not None:
            if self.lock_acquired:
                try:
                    with self.connection.cursor() as cursor:
                        cursor.execute("SELECT RELEASE_LOCK(%s)", (LOCK_NAME,))
                except Exception:
                    pass
            self.connection.close()
            self.connection = None
            self.lock_acquired = False

    def assert_target(self) -> None:
        if self.args.confirm_target_database != self.args.target_database:
            raise domain.MigrationError(
                "--confirm-target-database must exactly match --target-database"
            )
        if not re.fullmatch(r"[A-Za-z0-9_]+", self.args.target_database):
            raise domain.MigrationError("Target database name contains unsupported characters")
        helper = domain.JavaMySqlMigration(None, self.args)
        helper.connection = self.db
        helper.assert_target()

    def acquire_lock(self) -> None:
        with self.db.cursor() as cursor:
            cursor.execute("SELECT GET_LOCK(%s, 0) AS acquired", (LOCK_NAME,))
            acquired = cursor.fetchone()["acquired"]
        if int(acquired or 0) != 1:
            raise domain.MigrationError("Another synchronization run is active")
        self.lock_acquired = True

    def read_state(self) -> SyncState | None:
        with self.db.cursor() as cursor:
            cursor.execute(
                """
                SELECT cursor_json,last_full_sync_at
                FROM nxr_python_sync_state
                WHERE stream_name=%s
                """,
                (STREAM_NAME,),
            )
            row = cursor.fetchone()
        if row is None:
            return None
        return SyncState(
            cursor=SyncCursor.from_json(row["cursor_json"]),
            last_full_sync_at=row["last_full_sync_at"],
        )

    def choose_mode(self, state: SyncState | None) -> str:
        if self.args.mode in {"full", "incremental"}:
            if self.args.mode == "incremental" and state is None:
                raise domain.MigrationError(
                    "Incremental sync requires an existing successful full-sync state"
                )
            return self.args.mode
        if state is None or state.last_full_sync_at is None:
            return "full"
        due_at = state.last_full_sync_at + dt.timedelta(
            hours=self.args.full_if_due_hours
        )
        with self.db.cursor() as cursor:
            cursor.execute("SELECT CURRENT_TIMESTAMP AS database_now")
            database_now = cursor.fetchone()["database_now"]
        return "full" if database_now >= due_at else "incremental"

    def _load(
        self,
        helper: domain.JavaMySqlMigration,
        sql: str,
        rows: Iterable[Sequence[Any]],
        label: str,
    ) -> int:
        return helper._load(sql, rows, label)

    def load_staging(
        self,
        rows: SyncRows,
        helper: domain.JavaMySqlMigration,
    ) -> dict[str, int]:
        submission_placeholders = ",".join(
            ["%s"] * len(domain.STAGING_SUBMISSION_COLUMNS)
        )
        counts = {
            "submissions": self._load(
                helper,
                f"INSERT INTO tmp_nxr_submission "
                f"({','.join(domain.STAGING_SUBMISSION_COLUMNS)}) "
                f"VALUES ({submission_placeholders})",
                (
                    tuple(row[column] for column in domain.STAGING_SUBMISSION_COLUMNS)
                    for row in rows.submissions
                ),
                "submissions",
            ),
            "waitlist": self._load(
                helper,
                "INSERT INTO tmp_nxr_waitlist (email,created_at) VALUES (%s,%s)",
                rows.waitlist,
                "waitlist",
            ),
            "brands": self._load(
                helper,
                """
                INSERT INTO tmp_nxr_brand
                    (name,aliases,sort_order,is_active,created_at,updated_at)
                VALUES (%s,%s,%s,%s,%s,%s)
                """,
                rows.brands,
                "brands",
            ),
            "sports_types": self._load(
                helper,
                """
                INSERT INTO tmp_nxr_sports (value,sort_order,status,remark)
                VALUES (%s,%s,%s,%s)
                """,
                rows.sports_types,
                "sports_types",
            ),
            "ai_cache": self._load(
                helper,
                """
                INSERT INTO tmp_nxr_ai_cache
                    (cert_id,brand_name,character_name,language_code,
                     content_html,provider_code,created_at)
                VALUES (%s,%s,%s,%s,%s,%s,%s)
                """,
                rows.ai_cache,
                "ai_cache",
            ),
        }
        return counts

    @contextlib.contextmanager
    def prepare_rows(self, mode: str, previous: SyncCursor):
        if mode == "full":
            with source_snapshots(self.args.cards_db, self.args.temp_db) as paths:
                with SyncSource(*paths) as source:
                    source.validate_light(quick_check=True)
                    captured = source.capture_cursor()
                    rows = SyncRows(
                        submissions=source.iter_submissions(),
                        waitlist=source.iter_waitlist(),
                        brands=source.iter_brands(),
                        sports_types=source.iter_sports_types(),
                        ai_cache=source.iter_ai_cache(),
                    )
                    yield rows, captured
            return

        # Materialize the small delta first, then close SQLite before MySQL
        # starts. A slow or unavailable MySQL server can never retain a source
        # read lock in the Flask databases.
        with SyncSource(
            self.args.cards_db.resolve(), self.args.temp_db.resolve()
        ) as source:
            source.validate_light()
            captured = source.capture_cursor()
            rows = SyncRows(
                submissions=list(source.iter_changed_submissions(previous)),
                waitlist=list(source.iter_waitlist_since(previous)),
                brands=list(source.iter_brands()),
                sports_types=list(source.iter_sports_types()),
                ai_cache=list(source.iter_ai_cache_since(previous)),
            )
        yield rows, captured

    def assert_no_unmanaged_conflicts(self) -> None:
        with self.db.cursor() as cursor:
            cursor.execute(
                """
                SELECT t.cert_id
                FROM tmp_nxr_submission t
                JOIN grading_submission s ON s.cert_id=t.cert_id
                LEFT JOIN nxr_python_sync_submission m ON m.cert_id=t.cert_id
                WHERE m.cert_id IS NULL
                   OR m.submission_id<>s.id
                LIMIT 1
                """
            )
            row = cursor.fetchone()
        if row:
            raise domain.MigrationError(
                "Refusing to overwrite an unmanaged Java certificate: "
                + row["cert_id"]
            )

    def merge(self) -> None:
        statements = (
            """
            INSERT INTO grading_submission (
                cert_id,card_name,year_label,brand_name,player_name,variety_name,
                set_name,card_number,language_code,population_value,status_code,
                grading_phase_code,card_category_code,product_type_code,
                vintage_classification_code,movie_name,release_year,
                production_company,film_type,sports_type,group_name,approval_sequence,
                entry_notes,entry_by_user_id,approved_by_user_id,approved_at,published_at,
                created_at,updated_at
            )
            SELECT
                cert_id,card_name,year_label,brand_name,player_name,variety_name,
                set_name,card_number,language_code,population_value,status_code,
                grading_phase_code,card_category_code,product_type_code,
                vintage_classification_code,movie_name,release_year,
                production_company,film_type,sports_type,group_name,approval_sequence,
                entry_notes,NULL,NULL,approved_at,published_at,created_at,updated_at
            FROM tmp_nxr_submission
            ON DUPLICATE KEY UPDATE
                card_name=VALUES(card_name),year_label=VALUES(year_label),
                brand_name=VALUES(brand_name),player_name=VALUES(player_name),
                variety_name=VALUES(variety_name),set_name=VALUES(set_name),
                card_number=VALUES(card_number),language_code=VALUES(language_code),
                population_value=VALUES(population_value),status_code=VALUES(status_code),
                grading_phase_code=VALUES(grading_phase_code),
                card_category_code=VALUES(card_category_code),
                product_type_code=VALUES(product_type_code),
                vintage_classification_code=VALUES(vintage_classification_code),
                movie_name=VALUES(movie_name),
                release_year=VALUES(release_year),
                production_company=VALUES(production_company),film_type=VALUES(film_type),
                sports_type=VALUES(sports_type),group_name=VALUES(group_name),
                approval_sequence=VALUES(approval_sequence),entry_notes=VALUES(entry_notes),
                approved_at=VALUES(approved_at),published_at=VALUES(published_at),
                created_at=VALUES(created_at),updated_at=VALUES(updated_at)
            """,
            """
            DELETE g
            FROM grading_score g
            JOIN grading_submission s ON s.id=g.submission_id
            JOIN tmp_nxr_submission t ON t.cert_id=s.cert_id
            WHERE t.product_type_code<>'graded_card'
            """,
            """
            INSERT INTO grading_score (
                submission_id,centering_score,edges_score,corners_score,surface_score,
                final_grade_value,final_grade_label,ai_grade_value,ai_confidence_value,
                decision_method_code,decision_notes,created_at,updated_at
            )
            SELECT
                s.id,t.centering_score,t.edges_score,t.corners_score,t.surface_score,
                t.final_grade_value,t.final_grade_label,t.ai_grade_value,t.ai_confidence_value,
                t.decision_method_code,t.decision_notes,t.created_at,t.updated_at
            FROM tmp_nxr_submission t
            JOIN grading_submission s ON s.cert_id=t.cert_id
            WHERE t.product_type_code='graded_card'
            ON DUPLICATE KEY UPDATE
                centering_score=VALUES(centering_score),edges_score=VALUES(edges_score),
                corners_score=VALUES(corners_score),surface_score=VALUES(surface_score),
                final_grade_value=VALUES(final_grade_value),
                final_grade_label=VALUES(final_grade_label),
                ai_grade_value=VALUES(ai_grade_value),
                ai_confidence_value=VALUES(ai_confidence_value),
                decision_method_code=VALUES(decision_method_code),
                decision_notes=VALUES(decision_notes),updated_at=VALUES(updated_at)
            """,
            """
            INSERT INTO submission_media (
                submission_id,cert_id,media_side_code,media_stage_code,
                storage_provider_code,storage_bucket,storage_key,public_url,
                sort_order,is_active,created_at,updated_at
            )
            SELECT
                s.id,t.cert_id,'front','published','legacy-python','python-public',
                CONCAT(t.cert_id,'/front'),t.published_front_url,1,1,
                t.published_at,t.updated_at
            FROM tmp_nxr_submission t
            JOIN grading_submission s ON s.cert_id=t.cert_id
            WHERE t.is_published=1 AND t.published_front_url IS NOT NULL
            ON DUPLICATE KEY UPDATE
                cert_id=VALUES(cert_id),storage_provider_code=VALUES(storage_provider_code),
                storage_bucket=VALUES(storage_bucket),storage_key=VALUES(storage_key),
                public_url=VALUES(public_url),is_active=1,updated_at=VALUES(updated_at)
            """,
            """
            INSERT INTO submission_media (
                submission_id,cert_id,media_side_code,media_stage_code,
                storage_provider_code,storage_bucket,storage_key,public_url,
                sort_order,is_active,created_at,updated_at
            )
            SELECT
                s.id,t.cert_id,'back','published','legacy-python','python-public',
                CONCAT(t.cert_id,'/back'),t.published_back_url,1,1,
                t.published_at,t.updated_at
            FROM tmp_nxr_submission t
            JOIN grading_submission s ON s.cert_id=t.cert_id
            WHERE t.is_published=1 AND t.published_back_url IS NOT NULL
            ON DUPLICATE KEY UPDATE
                cert_id=VALUES(cert_id),storage_provider_code=VALUES(storage_provider_code),
                storage_bucket=VALUES(storage_bucket),storage_key=VALUES(storage_key),
                public_url=VALUES(public_url),is_active=1,updated_at=VALUES(updated_at)
            """,
            """
            UPDATE submission_media m
            JOIN grading_submission s ON s.id=m.submission_id
            JOIN tmp_nxr_submission t ON t.cert_id=s.cert_id
            SET m.public_url=NULL,m.is_active=0,m.updated_at=t.updated_at
            WHERE t.is_published=1
              AND m.media_stage_code='published'
              AND m.storage_provider_code='legacy-python'
              AND ((m.media_side_code='front' AND t.published_front_url IS NULL)
                OR (m.media_side_code='back' AND t.published_back_url IS NULL))
            """,
            """
            INSERT INTO published_certificate (
                submission_id,cert_id,verification_slug,qr_url,published_at,
                published_front_media_id,published_back_media_id,updated_at
            )
            SELECT
                s.id,t.cert_id,t.verification_slug,t.qr_url,t.published_at,
                front_media.id,back_media.id,t.updated_at
            FROM tmp_nxr_submission t
            JOIN grading_submission s ON s.cert_id=t.cert_id
            LEFT JOIN submission_media front_media
              ON front_media.submission_id=s.id
             AND front_media.media_stage_code='published'
             AND front_media.media_side_code='front'
             AND front_media.sort_order=1
             AND front_media.is_active=1
            LEFT JOIN submission_media back_media
              ON back_media.submission_id=s.id
             AND back_media.media_stage_code='published'
             AND back_media.media_side_code='back'
             AND back_media.sort_order=1
             AND back_media.is_active=1
            WHERE t.is_published=1
            ON DUPLICATE KEY UPDATE
                cert_id=VALUES(cert_id),verification_slug=VALUES(verification_slug),
                qr_url=VALUES(qr_url),published_at=VALUES(published_at),
                published_front_media_id=VALUES(published_front_media_id),
                published_back_media_id=VALUES(published_back_media_id),
                updated_at=VALUES(updated_at)
            """,
            """
            INSERT INTO waitlist_email (email,source_code,status_code,created_at)
            SELECT email,'legacy_python','pending',created_at FROM tmp_nxr_waitlist
            ON DUPLICATE KEY UPDATE email=VALUES(email)
            """,
            """
            INSERT INTO brand_settings (name,aliases,sort_order,is_active,created_at,updated_at)
            SELECT name,aliases,sort_order,is_active,created_at,updated_at FROM tmp_nxr_brand
            ON DUPLICATE KEY UPDATE
                aliases=VALUES(aliases),sort_order=VALUES(sort_order),
                is_active=VALUES(is_active),updated_at=VALUES(updated_at)
            """,
            """
            UPDATE sys_dict_data d
            JOIN tmp_nxr_sports t
              ON d.dict_type='nxr_sports_type' AND d.dict_value=t.value
            SET d.dict_label=t.value,d.dict_sort=t.sort_order,d.status=t.status,
                d.remark=t.remark,d.update_by='python-sync',
                d.update_time=CURRENT_TIMESTAMP
            WHERE NOT (
                d.dict_label <=> t.value
                AND d.dict_sort <=> t.sort_order
                AND d.status <=> t.status
                AND d.remark <=> t.remark
            )
            """,
            """
            INSERT INTO sys_dict_data (
                dict_sort,dict_label,dict_value,dict_type,css_class,list_class,
                is_default,status,create_by,create_time,remark
            )
            SELECT
                t.sort_order,t.value,t.value,'nxr_sports_type','','','N',t.status,
                'python-sync',CURRENT_TIMESTAMP,t.remark
            FROM tmp_nxr_sports t
            WHERE NOT EXISTS (
                SELECT 1 FROM sys_dict_data d
                WHERE d.dict_type='nxr_sports_type' AND d.dict_value=t.value
            )
            """,
            """
            INSERT INTO ai_character_cache (
                cert_id,brand_name,character_name,language_code,content_html,
                provider_code,created_at,updated_at
            )
            SELECT
                cert_id,brand_name,character_name,language_code,content_html,
                provider_code,created_at,created_at
            FROM tmp_nxr_ai_cache
            ON DUPLICATE KEY UPDATE
                brand_name=VALUES(brand_name),character_name=VALUES(character_name),
                content_html=VALUES(content_html),provider_code=VALUES(provider_code),
                updated_at=VALUES(updated_at)
            """,
            """
            INSERT INTO nxr_python_sync_submission (
                cert_id,submission_id,source_fingerprint,source_updated_at,
                first_synced_at,last_synced_at
            )
            SELECT
                t.cert_id,s.id,t.source_fingerprint,t.updated_at,
                CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
            FROM tmp_nxr_submission t
            JOIN grading_submission s ON s.cert_id=t.cert_id
            ON DUPLICATE KEY UPDATE
                last_synced_at=IF(
                    nxr_python_sync_submission.source_fingerprint<>
                        VALUES(source_fingerprint),
                    CURRENT_TIMESTAMP,
                    nxr_python_sync_submission.last_synced_at
                ),
                submission_id=VALUES(submission_id),
                source_fingerprint=VALUES(source_fingerprint),
                source_updated_at=VALUES(source_updated_at)
            """,
        )
        with self.db.cursor() as cursor:
            for index, statement in enumerate(statements, start=1):
                cursor.execute(statement)
                print(f"merge_step_{index}_affected={cursor.rowcount}")

    def verify_staging(self) -> None:
        checks = {
            "submission": """
                SELECT COUNT(*) AS count
                FROM tmp_nxr_submission t
                LEFT JOIN grading_submission s ON s.cert_id=t.cert_id
                WHERE s.id IS NULL OR NOT (
                       s.card_name <=> t.card_name
                   AND s.year_label <=> t.year_label
                   AND s.brand_name <=> t.brand_name
                   AND s.player_name <=> t.player_name
                   AND s.variety_name <=> t.variety_name
                   AND s.set_name <=> t.set_name
                   AND s.card_number <=> t.card_number
                   AND s.language_code <=> t.language_code
                   AND s.population_value <=> t.population_value
                   AND s.status_code <=> t.status_code
                   AND s.grading_phase_code <=> t.grading_phase_code
                   AND s.card_category_code <=> t.card_category_code
                   AND s.product_type_code <=> t.product_type_code
                   AND s.vintage_classification_code <=> t.vintage_classification_code
                   AND s.movie_name <=> t.movie_name
                   AND s.release_year <=> t.release_year
                   AND s.production_company <=> t.production_company
                   AND s.film_type <=> t.film_type
                   AND s.sports_type <=> t.sports_type
                   AND s.group_name <=> t.group_name
                   AND s.approval_sequence <=> t.approval_sequence
                   AND s.entry_notes <=> t.entry_notes
                   AND s.approved_at <=> t.approved_at
                   AND s.published_at <=> t.published_at
                )
            """,
            "score": """
                SELECT COUNT(*) AS count
                FROM tmp_nxr_submission t
                JOIN grading_submission s ON s.cert_id=t.cert_id
                LEFT JOIN grading_score g ON g.submission_id=s.id
                WHERE (t.product_type_code='graded_card' AND (
                       g.submission_id IS NULL OR NOT (
                       g.centering_score <=> t.centering_score
                   AND g.edges_score <=> t.edges_score
                   AND g.corners_score <=> t.corners_score
                   AND g.surface_score <=> t.surface_score
                   AND g.final_grade_value <=> t.final_grade_value
                   AND g.final_grade_label <=> t.final_grade_label
                   AND g.ai_grade_value <=> t.ai_grade_value
                   AND g.ai_confidence_value <=> t.ai_confidence_value
                   AND g.decision_method_code <=> t.decision_method_code
                   AND g.decision_notes <=> t.decision_notes
                       )
                ))
                OR (t.product_type_code<>'graded_card' AND g.submission_id IS NOT NULL)
            """,
            "managed_submission": """
                SELECT COUNT(*) AS count
                FROM tmp_nxr_submission t
                JOIN grading_submission s ON s.cert_id=t.cert_id
                LEFT JOIN nxr_python_sync_submission m ON m.cert_id=t.cert_id
                WHERE m.cert_id IS NULL OR m.submission_id<>s.id
                   OR m.source_fingerprint<>t.source_fingerprint
            """,
            "publication": """
                SELECT COUNT(*) AS count
                FROM tmp_nxr_submission t
                JOIN grading_submission s ON s.cert_id=t.cert_id
                LEFT JOIN published_certificate p ON p.submission_id=s.id
                WHERE t.is_published=1 AND (
                    p.id IS NULL OR p.cert_id<>t.cert_id
                    OR p.verification_slug<>t.verification_slug
                    OR NOT (p.qr_url <=> t.qr_url)
                )
            """,
            "front_media": """
                SELECT COUNT(*) AS count
                FROM tmp_nxr_submission t
                JOIN grading_submission s ON s.cert_id=t.cert_id
                LEFT JOIN submission_media m
                  ON m.submission_id=s.id AND m.media_stage_code='published'
                 AND m.media_side_code='front' AND m.sort_order=1 AND m.is_active=1
                WHERE t.published_front_url IS NOT NULL
                  AND (m.id IS NULL OR NOT (m.public_url <=> t.published_front_url))
            """,
            "back_media": """
                SELECT COUNT(*) AS count
                FROM tmp_nxr_submission t
                JOIN grading_submission s ON s.cert_id=t.cert_id
                LEFT JOIN submission_media m
                  ON m.submission_id=s.id AND m.media_stage_code='published'
                 AND m.media_side_code='back' AND m.sort_order=1 AND m.is_active=1
                WHERE t.published_back_url IS NOT NULL
                  AND (m.id IS NULL OR NOT (m.public_url <=> t.published_back_url))
            """,
            "waitlist": """
                SELECT COUNT(*) AS count FROM tmp_nxr_waitlist t
                LEFT JOIN waitlist_email w ON w.email=t.email WHERE w.id IS NULL
            """,
            "brand": """
                SELECT COUNT(*) AS count FROM tmp_nxr_brand t
                LEFT JOIN brand_settings b ON b.name=t.name
                WHERE b.id IS NULL OR NOT (
                    b.aliases <=> t.aliases AND b.sort_order <=> t.sort_order
                    AND b.is_active <=> t.is_active
                )
            """,
            "sports": """
                SELECT COUNT(*) AS count FROM tmp_nxr_sports t
                LEFT JOIN sys_dict_data d
                  ON d.dict_type='nxr_sports_type' AND d.dict_value=t.value
                WHERE d.dict_code IS NULL OR NOT (
                    d.dict_label <=> t.value AND d.dict_sort <=> t.sort_order
                    AND d.status <=> t.status
                )
            """,
            "ai_cache": """
                SELECT COUNT(*) AS count FROM tmp_nxr_ai_cache t
                LEFT JOIN ai_character_cache a
                  ON a.cert_id=t.cert_id AND a.language_code=t.language_code
                WHERE a.id IS NULL OR NOT (
                    a.brand_name <=> t.brand_name
                    AND a.character_name <=> t.character_name
                    AND a.content_html <=> t.content_html
                    AND a.provider_code <=> t.provider_code
                )
            """,
        }
        failures = []
        with self.db.cursor() as cursor:
            for label, sql in checks.items():
                cursor.execute(sql)
                count = int(cursor.fetchone()["count"])
                if count:
                    failures.append(f"{label}_mismatches={count}")
        if failures:
            raise domain.MigrationError(
                "Synchronization verification failed: " + "; ".join(failures)
            )
        print("verification_precommit=ok")

    def write_state(self, cursor_value: SyncCursor, mode: str) -> None:
        with self.db.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO nxr_python_sync_state (
                    stream_name,cursor_json,last_full_sync_at,last_success_at
                ) VALUES (
                    %s,%s,
                    CASE WHEN %s=1 THEN CURRENT_TIMESTAMP ELSE NULL END,
                    CURRENT_TIMESTAMP
                )
                ON DUPLICATE KEY UPDATE
                    cursor_json=VALUES(cursor_json),
                    last_full_sync_at=COALESCE(
                        VALUES(last_full_sync_at),last_full_sync_at
                    ),
                    last_success_at=CURRENT_TIMESTAMP
                """,
                (STREAM_NAME, cursor_value.to_json(), int(mode == "full")),
            )

    def verify_postcommit_state(self, cursor_value: SyncCursor) -> None:
        state = self.read_state()
        if state is None or state.cursor != cursor_value:
            raise domain.MigrationError("Post-commit cursor verification failed")
        print("verification_postcommit=ok")

    def run(self) -> None:
        self.connect()
        try:
            self.assert_target()
            self.acquire_lock()
            state = self.read_state()
            mode = self.choose_mode(state)
            previous = state.cursor if state else SyncCursor()
            print(f"sync_mode={mode}")

            with self.prepare_rows(mode, previous) as (rows, captured):
                with self.db.cursor() as cursor:
                    cursor.execute(
                        "SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED"
                    )
                self.db.begin()
                try:
                    helper = domain.JavaMySqlMigration(None, self.args)
                    helper.connection = self.db
                    helper.create_staging_tables()
                    counts = self.load_staging(rows, helper)
                    for label, count in counts.items():
                        print(f"sync_{label}={count}")
                    self.assert_no_unmanaged_conflicts()
                    self.merge()
                    self.verify_staging()
                    self.write_state(captured, mode)
                    self.db.commit()
                except Exception:
                    self.db.rollback()
                    raise

            self.verify_postcommit_state(captured)
            print("sync=committed")
        finally:
            self.close()


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Synchronize Flask SQLite domain rows into Java MySQL"
    )
    parser.add_argument("--cards-db", type=Path, default=domain.DEFAULT_CARDS_DB)
    parser.add_argument("--temp-db", type=Path, default=domain.DEFAULT_TEMP_DB)
    parser.add_argument("--batch-size", type=int, default=500)
    parser.add_argument("--mode", choices=("auto", "full", "incremental"), default="auto")
    parser.add_argument("--full-if-due-hours", type=float, default=24.0)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument(
        "--target-database", default=os.environ.get("NXR_SYNC_TARGET_DATABASE", "")
    )
    parser.add_argument(
        "--confirm-target-database",
        default=os.environ.get("NXR_SYNC_CONFIRM_TARGET_DATABASE", ""),
    )
    parser.add_argument("--mysql-host", default="127.0.0.1")
    parser.add_argument("--mysql-port", type=int, default=3306)
    parser.add_argument(
        "--mysql-user",
        default=os.environ.get(
            "NXR_SYNC_MYSQL_USER", os.environ.get("NXR_DB_USERNAME", "root")
        ),
    )
    parser.add_argument("--mysql-unix-socket", default="")
    parser.add_argument(
        "--mysql-password-env",
        default="NXR_SYNC_MYSQL_PASSWORD",
        help="environment variable containing the password; never printed",
    )
    args = parser.parse_args(argv)
    if args.batch_size < 1:
        parser.error("--batch-size must be at least 1")
    if args.full_if_due_hours <= 0:
        parser.error("--full-if-due-hours must be positive")
    if args.apply and not args.target_database:
        parser.error("--target-database is required with --apply")
    return args


def dry_run(args: argparse.Namespace) -> None:
    with SyncSource(args.cards_db.resolve(), args.temp_db.resolve()) as source:
        source.validate_light(quick_check=True)
        cursor = source.capture_cursor()
        counts = {
            "cards": source.db.execute("SELECT COUNT(*) FROM main.cards").fetchone()[0],
            "temp_cards": source.db.execute(
                "SELECT COUNT(*) FROM tempdb.temp_cards"
            ).fetchone()[0],
            "waitlist": source.db.execute(
                "SELECT COUNT(*) FROM main.waitlist"
            ).fetchone()[0],
            "ai_cache": source.db.execute(
                "SELECT COUNT(*) FROM main.ai_character_cache"
            ).fetchone()[0],
        }
    print("source_validation=ok")
    for label, count in counts.items():
        print(f"source_{label}={count}")
    print(f"source_cursor={cursor.to_json()}")
    print("dry_run=1")


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if not args.apply:
            dry_run(args)
            return 0
        PythonToJavaSync(args).run()
        return 0
    except domain.MigrationError as exc:
        print(f"sync_error={exc}", file=sys.stderr)
        return 1
    except Exception as exc:  # noqa: BLE001 - keep unattended output concise
        print(f"sync_error={type(exc).__name__}:{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
