#!/usr/bin/env python3
"""Merge the production Flask SQLite data into the Java domain schema.

The source databases are always opened read-only. The default command only
performs source validation and prints a migration plan. ``--apply`` requires an
exact target-database confirmation and writes all persistent rows in one MySQL
transaction.

This is intentionally different from ``migrate_sqlite_to_mysql.py``: it maps
the two Flask databases into the Java/RuoYi domain tables instead of copying
the SQLite table layout verbatim.
"""

from __future__ import annotations

import argparse
import datetime as dt
import decimal
import hashlib
import json
import os
import re
import sqlite3
import sys
from collections import Counter
from collections.abc import Iterable, Iterator, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CARDS_DB = PROJECT_ROOT / "Data" / "cards.db"
DEFAULT_TEMP_DB = PROJECT_ROOT / "Data" / "temp_cards.db"

SUPPORTED_TEMP_STATUSES = {"pending", "review", "approved"}
SUPPORTED_CATEGORIES = {
    "trading_card",
    "movie_film",
    "sports_card",
    "celebrity_card",
}
EXCLUDED_FK_VIOLATION_TABLES = {"grading_history", "human_grading_details"}

TARGET_TABLES = (
    "grading_submission",
    "grading_score",
    "submission_media",
    "published_certificate",
    "waitlist_email",
    "brand_settings",
    "ai_character_cache",
    "sys_dict_type",
    "sys_dict_data",
)
SYNC_TABLES = (
    "nxr_python_sync_submission",
    "nxr_python_sync_state",
)
UNTOUCHED_TABLES = (
    "sys_user",
    "customer_account",
    "customer_session",
    "certificate_ownership",
    "certificate_ownership_event",
    "grading_order",
    "grading_order_item",
    "payment_record",
    "payment_callback_event",
    "order_shipment",
    "order_timeline_event",
)

SUBMISSION_COLUMNS = (
    "cert_id",
    "card_name",
    "year_label",
    "brand_name",
    "player_name",
    "variety_name",
    "set_name",
    "card_number",
    "language_code",
    "population_value",
    "status_code",
    "grading_phase_code",
    "card_category_code",
    "movie_name",
    "release_year",
    "production_company",
    "film_type",
    "sports_type",
    "group_name",
    "approval_sequence",
    "entry_notes",
    "approved_at",
    "published_at",
    "created_at",
    "updated_at",
)
SCORE_COLUMNS = (
    "centering_score",
    "edges_score",
    "corners_score",
    "surface_score",
    "final_grade_value",
    "final_grade_label",
    "ai_grade_value",
    "ai_confidence_value",
    "decision_method_code",
    "decision_notes",
)
PUBLICATION_COLUMNS = (
    "is_published",
    "verification_slug",
    "qr_url",
    "published_front_url",
    "published_back_url",
)
MAPPED_SUBMISSION_COLUMNS = SUBMISSION_COLUMNS + SCORE_COLUMNS + PUBLICATION_COLUMNS
STAGING_SUBMISSION_COLUMNS = MAPPED_SUBMISSION_COLUMNS + ("source_fingerprint",)


class MigrationError(RuntimeError):
    """Raised when a safety check or verification fails."""


@dataclass(frozen=True)
class SourceStats:
    cards: int
    temp_cards: int
    overlap: int
    temp_only_approved: int
    temp_only_pending_or_review: int
    submissions: int
    published_certificates: int
    published_media: int
    waitlist: int
    brands: int
    sports_types: int
    ai_cache_rows: int
    excluded_foreign_key_violations: int


@dataclass(frozen=True)
class TargetBaseline:
    counts: dict[str, int]
    waitlist_overlap: int
    brand_overlap: int
    sports_overlap: int
    ai_cache_overlap: int


def clean(value: Any) -> str:
    return " ".join(str(value or "").strip().split())


def optional_text(value: Any) -> str | None:
    normalized = clean(value)
    return normalized or None


def optional_raw_text(value: Any) -> str | None:
    normalized = str(value or "").strip()
    return normalized or None


def normalize_cert_id(value: Any) -> str:
    cert_id = clean(value).upper()
    if not cert_id:
        raise MigrationError("Source row contains an empty certificate id")
    if len(cert_id) > 32:
        raise MigrationError(f"Certificate id exceeds 32 characters: {cert_id!r}")
    return cert_id


def normalize_category(value: Any) -> str:
    normalized = clean(value).lower().replace(" ", "_")
    if not normalized:
        return "trading_card"
    aliases = {
        "card": "trading_card",
        "film": "movie_film",
        "sports": "sports_card",
        "celebrity": "celebrity_card",
    }
    normalized = aliases.get(normalized, normalized)
    if normalized not in SUPPORTED_CATEGORIES:
        raise MigrationError(f"Unsupported card category: {normalized!r}")
    return normalized


def normalize_language(value: Any) -> str:
    language = clean(value).upper() or "EN"
    if len(language) > 16:
        raise MigrationError(f"Language code exceeds 16 characters: {language!r}")
    return language


def normalize_temp_status(value: Any) -> str:
    status = clean(value).lower() or "pending"
    if status not in SUPPORTED_TEMP_STATUSES:
        raise MigrationError(f"Unsupported temporary-card status: {status!r}")
    return status


def to_int(value: Any, *, default: int = 0) -> int:
    if value is None or clean(value) == "":
        return default
    try:
        return int(decimal.Decimal(str(value)))
    except (decimal.InvalidOperation, ValueError) as exc:
        raise MigrationError(f"Expected an integer-compatible value, got {value!r}") from exc


def to_decimal(value: Any, *, default: str = "0") -> decimal.Decimal:
    if value is None or clean(value) == "":
        value = default
    try:
        return decimal.Decimal(str(value)).quantize(decimal.Decimal("0.1"))
    except decimal.InvalidOperation as exc:
        raise MigrationError(f"Expected a decimal-compatible value, got {value!r}") from exc


def to_confidence(value: Any) -> decimal.Decimal | None:
    if value is None or clean(value) == "":
        return None
    try:
        result = decimal.Decimal(str(value))
    except decimal.InvalidOperation as exc:
        raise MigrationError(f"Invalid AI confidence value: {value!r}") from exc
    if decimal.Decimal("0") <= result <= decimal.Decimal("1"):
        result *= 100
    return result.quantize(decimal.Decimal("0.01"))


def to_datetime(value: Any) -> dt.datetime | None:
    if value is None or clean(value) == "":
        return None
    if isinstance(value, dt.datetime):
        result = value
    else:
        raw = str(value).strip().replace("Z", "+00:00")
        try:
            result = dt.datetime.fromisoformat(raw)
        except ValueError as exc:
            raise MigrationError(f"Invalid datetime value: {value!r}") from exc
    if result.tzinfo is not None:
        result = result.astimezone(dt.timezone.utc).replace(tzinfo=None)
    # The Java schema uses TIMESTAMP without fractional-second precision.
    return result.replace(microsecond=0)


def truncate(value: Any, limit: int, *, field: str) -> str:
    normalized = clean(value)
    if len(normalized) > limit:
        raise MigrationError(f"{field} exceeds {limit} characters")
    return normalized


def grade_label(row: Mapping[str, Any]) -> str:
    label = clean(row["final_grade_text"] if "final_grade_text" in row.keys() else "")
    if not label and "grade" in row.keys():
        label = clean(row["grade"])
    if not label:
        value = to_decimal(row["final_grade"])
        label = format(value.normalize(), "f")
    return truncate(label, 64, field="final_grade_label")


def submission_fingerprint(row: Mapping[str, Any]) -> str:
    def canonical(value: Any) -> Any:
        if isinstance(value, decimal.Decimal):
            return format(value, "f")
        if isinstance(value, dt.datetime):
            return value.isoformat(timespec="seconds")
        return value

    payload = [
        (column, canonical(row.get(column)))
        for column in MAPPED_SUBMISSION_COLUMNS
    ]
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def finalize_submission(mapped: dict[str, Any]) -> dict[str, Any]:
    mapped["source_fingerprint"] = submission_fingerprint(mapped)
    return mapped


def _base_submission(row: Mapping[str, Any]) -> dict[str, Any]:
    population = max(1, to_int(row["pop"], default=1))
    category = normalize_category(row["card_category"])
    created_at = to_datetime(row["created_at"])
    updated_at = to_datetime(row["updated_at"]) or created_at
    return {
        "cert_id": normalize_cert_id(row["cert_id"]),
        "card_name": truncate(row["card_name"], 255, field="card_name"),
        "year_label": truncate(row["year"], 16, field="year_label") or None,
        "brand_name": truncate(row["brand"], 64, field="brand_name"),
        "player_name": None,
        "variety_name": truncate(row["variety"], 255, field="variety_name") or None,
        "set_name": truncate(row["set_name"], 255, field="set_name"),
        "card_number": truncate(row["card_number"], 64, field="card_number"),
        "language_code": normalize_language(row["language"]),
        "population_value": population,
        "grading_phase_code": "human_only",
        "card_category_code": category,
        "movie_name": truncate(row["movie_name"], 255, field="movie_name") or None,
        "release_year": truncate(row["release_year"], 16, field="release_year") or None,
        "production_company": truncate(
            row["production_company"], 128, field="production_company"
        )
        or None,
        "film_type": truncate(row["film_type"], 128, field="film_type") or None,
        "sports_type": truncate(row["sports_type"], 64, field="sports_type") or None,
        "group_name": truncate(row["group_name"], 128, field="group_name") or None,
        "approval_sequence": None,
        "entry_notes": None,
        "approved_at": None,
        "published_at": None,
        "created_at": created_at or dt.datetime(1970, 1, 1),
        "updated_at": updated_at or created_at or dt.datetime(1970, 1, 1),
        "centering_score": to_decimal(row["centering"]),
        "edges_score": to_decimal(row["edges"]),
        "corners_score": to_decimal(row["corners"]),
        "surface_score": to_decimal(row["surface"]),
        "final_grade_value": to_decimal(row["final_grade"]),
        "final_grade_label": grade_label(row),
        "ai_grade_value": None,
        "ai_confidence_value": None,
        "decision_method_code": "human_only",
        "decision_notes": None,
        "is_published": 0,
        "verification_slug": None,
        "qr_url": None,
        "published_front_url": None,
        "published_back_url": None,
    }


def map_published_row(row: Mapping[str, Any]) -> dict[str, Any]:
    mapped = _base_submission(row)
    cert_id = mapped["cert_id"]
    if "has_ai_analysis" in row.keys():
        has_ai_analysis = bool(to_int(row["has_ai_analysis"]))
    else:
        has_ai_analysis = row["ai_grade"] is not None or to_confidence(
            row["ai_confidence"]
        ) not in (None, decimal.Decimal("0.00"))
    mapped.update(
        {
            "player_name": truncate(row["player"], 128, field="player_name") or None,
            "status_code": "published",
            "grading_phase_code": truncate(
                row["grading_phase"], 32, field="grading_phase_code"
            )
            or "human_only",
            "approval_sequence": to_int(row["temp_approval_sequence"])
            if row["temp_approval_sequence"] is not None
            else None,
            "entry_notes": optional_raw_text(row["temp_entry_notes"]),
            "approved_at": to_datetime(row["temp_approved_at"]),
            "published_at": to_datetime(row["temp_upload_completed"])
            or mapped["updated_at"],
            "ai_grade_value": to_decimal(row["ai_grade"])
            if has_ai_analysis and row["ai_grade"] is not None
            else None,
            "ai_confidence_value": to_confidence(row["ai_confidence"])
            if has_ai_analysis
            else None,
            "decision_method_code": truncate(
                row["decision_method"], 32, field="decision_method_code"
            )
            or "human_only",
            "decision_notes": optional_raw_text(row["decision_notes"]),
            "is_published": 1,
            "verification_slug": cert_id.lower(),
            "qr_url": truncate(row["qr_url"], 255, field="qr_url")
            or f"/card/{cert_id}",
            "published_front_url": truncate(
                row["front_image"] or row["image"], 255, field="front_image"
            )
            or None,
            "published_back_url": truncate(
                row["back_image"], 255, field="back_image"
            )
            or None,
        }
    )
    return finalize_submission(mapped)


def map_temp_only_row(row: Mapping[str, Any]) -> dict[str, Any]:
    mapped = _base_submission(row)
    status = normalize_temp_status(row["status"])
    mapped.update(
        {
            "status_code": status,
            "approval_sequence": to_int(row["approval_sequence"])
            if row["approval_sequence"] is not None
            else None,
            "entry_notes": optional_raw_text(row["entry_notes"]),
            "approved_at": to_datetime(row["approved_at"]),
        }
    )
    return finalize_submission(mapped)


class SourceBundle:
    def __init__(self, cards_path: Path, temp_path: Path):
        self.cards_path = cards_path
        self.temp_path = temp_path
        self.conn: sqlite3.Connection | None = None
        self.card_cert_ids_by_key: dict[str, str] | None = None
        self.temp_cert_ids_by_key: dict[str, str] | None = None

    def __enter__(self) -> "SourceBundle":
        if not self.cards_path.is_file():
            raise MigrationError(f"Missing cards database: {self.cards_path}")
        if not self.temp_path.is_file():
            raise MigrationError(f"Missing temp database: {self.temp_path}")
        self.conn = sqlite3.connect(
            f"file:{self.cards_path}?mode=ro", uri=True, timeout=30
        )
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA query_only=ON")
        self.conn.execute(
            "ATTACH DATABASE ? AS tempdb",
            (f"file:{self.temp_path}?mode=ro",),
        )
        return self

    def __exit__(self, exc_type, exc, traceback) -> None:
        if self.conn is not None:
            self.conn.close()
            self.conn = None

    @property
    def db(self) -> sqlite3.Connection:
        if self.conn is None:
            raise RuntimeError("SourceBundle is not open")
        return self.conn

    def validate(self) -> SourceStats:
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
            raise MigrationError("Missing source tables: " + ", ".join(missing))

        for schema in ("main", "tempdb"):
            result = self.db.execute(f"PRAGMA {schema}.integrity_check").fetchone()[0]
            if result != "ok":
                raise MigrationError(f"{schema} SQLite integrity check failed: {result}")

        violations = []
        for schema in ("main", "tempdb"):
            for row in self.db.execute(f"PRAGMA {schema}.foreign_key_check"):
                violations.append((schema, row[0], row[2]))
        unexpected = [
            violation
            for violation in violations
            if violation[1] not in EXCLUDED_FK_VIOLATION_TABLES
        ]
        if unexpected:
            raise MigrationError(
                "Unexpected source foreign-key violations: "
                + ", ".join(
                    f"{schema}.{table}->{parent}"
                    for schema, table, parent in unexpected[:10]
                )
            )

        self.card_cert_ids_by_key = self._cert_id_map("main.cards", "cards")
        self.temp_cert_ids_by_key = self._cert_id_map(
            "tempdb.temp_cards", "temp_cards"
        )

        # Materialize every mapped row during preflight so type, length and
        # status failures happen before a MySQL connection can write anything.
        submission_count = sum(1 for _ in self.iter_submissions())
        stats = self.stats(excluded_fk_violations=len(violations))
        if submission_count != stats.submissions:
            raise MigrationError(
                f"Source union mismatch: mapped={submission_count}, expected={stats.submissions}"
            )
        return stats

    def _cert_id_map(self, table: str, label: str) -> dict[str, str]:
        result: dict[str, str] = {}
        duplicate_keys = set()
        for row in self.db.execute(f"SELECT cert_id FROM {table}"):
            original = clean(row[0])
            key = normalize_cert_id(original)
            if key in result:
                duplicate_keys.add(key)
            else:
                result[key] = original
        if duplicate_keys:
            raise MigrationError(
                f"{label} has {len(duplicate_keys)} case-insensitive certificate duplicates"
            )
        return result

    def _ensure_cert_maps(self) -> None:
        if self.card_cert_ids_by_key is None:
            self.card_cert_ids_by_key = self._cert_id_map("main.cards", "cards")
        if self.temp_cert_ids_by_key is None:
            self.temp_cert_ids_by_key = self._cert_id_map(
                "tempdb.temp_cards", "temp_cards"
            )

    def stats(self, *, excluded_fk_violations: int = 0) -> SourceStats:
        self._ensure_cert_maps()
        assert self.card_cert_ids_by_key is not None
        assert self.temp_cert_ids_by_key is not None
        query = self.db.execute
        cards = query("SELECT COUNT(*) FROM main.cards").fetchone()[0]
        temp_cards = query("SELECT COUNT(*) FROM tempdb.temp_cards").fetchone()[0]
        card_keys = set(self.card_cert_ids_by_key)
        overlap = len(card_keys.intersection(self.temp_cert_ids_by_key))
        status_counts: Counter[str] = Counter()
        for row in query("SELECT cert_id, status FROM tempdb.temp_cards"):
            if normalize_cert_id(row["cert_id"]) not in card_keys:
                status_counts[clean(row["status"]).lower() or "pending"] += 1
        unsupported = sorted(set(status_counts) - SUPPORTED_TEMP_STATUSES)
        if unsupported:
            raise MigrationError(
                "Unsupported temp-only statuses: " + ", ".join(unsupported)
            )
        media = query(
            """
            SELECT
                SUM(CASE WHEN COALESCE(NULLIF(TRIM(front_image), ''), NULLIF(TRIM(image), '')) IS NOT NULL THEN 1 ELSE 0 END)
              + SUM(CASE WHEN TRIM(COALESCE(back_image, '')) <> '' THEN 1 ELSE 0 END)
            FROM main.cards
            """
        ).fetchone()[0] or 0
        ai_cache = query(
            """
            SELECT COUNT(*) FROM (
                SELECT UPPER(TRIM(cert_id)), LOWER(TRIM(language))
                FROM main.ai_character_cache
                GROUP BY UPPER(TRIM(cert_id)), LOWER(TRIM(language))
            ) deduplicated
            """
        ).fetchone()[0]
        brands = query(
            """
            SELECT COUNT(*)
            FROM main.dictionary_items i
            JOIN main.dictionary_groups g ON g.id = i.group_id
            WHERE LOWER(TRIM(g.code)) = 'brand'
            """
        ).fetchone()[0]
        sports = query(
            """
            SELECT COUNT(*)
            FROM main.dictionary_items i
            JOIN main.dictionary_groups g ON g.id = i.group_id
            WHERE LOWER(TRIM(g.code)) = 'sports_type'
            """
        ).fetchone()[0]
        return SourceStats(
            cards=cards,
            temp_cards=temp_cards,
            overlap=overlap,
            temp_only_approved=status_counts.get("approved", 0),
            temp_only_pending_or_review=(
                status_counts.get("pending", 0) + status_counts.get("review", 0)
            ),
            submissions=cards + temp_cards - overlap,
            published_certificates=cards,
            published_media=media,
            waitlist=query("SELECT COUNT(*) FROM main.waitlist").fetchone()[0],
            brands=brands,
            sports_types=sports,
            ai_cache_rows=ai_cache,
            excluded_foreign_key_violations=excluded_fk_violations,
        )

    def iter_submissions(self) -> Iterator[dict[str, Any]]:
        self._ensure_cert_maps()
        assert self.card_cert_ids_by_key is not None
        assert self.temp_cert_ids_by_key is not None
        published_rows = self.db.execute(
            """
            SELECT
                c.*,
                t.cert_id AS temp_joined_cert_id,
                t.entry_notes AS temp_entry_notes,
                t.approved_at AS temp_approved_at,
                t.approval_sequence AS temp_approval_sequence,
                t.upload_completed AS temp_upload_completed
            FROM main.cards c
            LEFT JOIN tempdb.temp_cards t
              ON t.cert_id = c.cert_id
            ORDER BY c.cert_id
            """
        )
        for row in published_rows:
            mapped_row = dict(row)
            if row["temp_joined_cert_id"] is None:
                temp_cert_id = self.temp_cert_ids_by_key.get(
                    normalize_cert_id(row["cert_id"])
                )
                if temp_cert_id is not None:
                    metadata = self.db.execute(
                        """
                        SELECT entry_notes, approved_at, approval_sequence, upload_completed
                        FROM tempdb.temp_cards
                        WHERE cert_id = ?
                        """,
                        (temp_cert_id,),
                    ).fetchone()
                    if metadata is not None:
                        mapped_row.update(
                            {
                                "temp_entry_notes": metadata["entry_notes"],
                                "temp_approved_at": metadata["approved_at"],
                                "temp_approval_sequence": metadata[
                                    "approval_sequence"
                                ],
                                "temp_upload_completed": metadata["upload_completed"],
                            }
                        )
            yield map_published_row(mapped_row)

        temp_only_rows = self.db.execute(
            """
            SELECT t.*
            FROM tempdb.temp_cards t
            ORDER BY t.cert_id
            """
        )
        for row in temp_only_rows:
            if normalize_cert_id(row["cert_id"]) not in self.card_cert_ids_by_key:
                yield map_temp_only_row(row)

    def iter_waitlist(self) -> Iterator[tuple[Any, ...]]:
        for row in self.db.execute(
            "SELECT email, created_at FROM main.waitlist ORDER BY LOWER(TRIM(email))"
        ):
            email = clean(row["email"]).lower()
            if not email:
                raise MigrationError("Waitlist contains an empty email")
            yield (email, to_datetime(row["created_at"]) or dt.datetime(1970, 1, 1))

    def iter_brands(self) -> Iterator[tuple[Any, ...]]:
        for row in self.db.execute(
            """
            SELECT i.value, i.aliases, i.sort_order, i.is_active, i.created_at, i.updated_at
            FROM main.dictionary_items i
            JOIN main.dictionary_groups g ON g.id = i.group_id
            WHERE LOWER(TRIM(g.code)) = 'brand'
            ORDER BY i.sort_order, LOWER(TRIM(i.value))
            """
        ):
            name = truncate(row["value"], 128, field="brand name")
            if not name:
                raise MigrationError("Brand dictionary contains an empty value")
            yield (
                name,
                str(row["aliases"] or "").strip(),
                to_int(row["sort_order"]),
                int(bool(row["is_active"])),
                to_datetime(row["created_at"]) or dt.datetime(1970, 1, 1),
                to_datetime(row["updated_at"])
                or to_datetime(row["created_at"])
                or dt.datetime(1970, 1, 1),
            )

    def iter_sports_types(self) -> Iterator[tuple[Any, ...]]:
        for row in self.db.execute(
            """
            SELECT i.value, i.aliases, i.sort_order, i.is_active
            FROM main.dictionary_items i
            JOIN main.dictionary_groups g ON g.id = i.group_id
            WHERE LOWER(TRIM(g.code)) = 'sports_type'
            ORDER BY i.sort_order, LOWER(TRIM(i.value))
            """
        ):
            value = truncate(row["value"], 100, field="sports type")
            if not value:
                raise MigrationError("Sports dictionary contains an empty value")
            aliases = str(row["aliases"] or "").strip()
            remark = f"Migrated aliases: {aliases}" if aliases else "Migrated from Flask dictionary"
            yield (
                value,
                to_int(row["sort_order"]),
                "0" if row["is_active"] else "1",
                remark[:500],
            )

    def iter_ai_cache(self) -> Iterator[tuple[Any, ...]]:
        rows = self.db.execute(
            """
            WITH ranked AS (
                SELECT a.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY UPPER(TRIM(a.cert_id)), LOWER(TRIM(a.language))
                           ORDER BY datetime(a.created_at) DESC, a.rowid DESC
                       ) AS rank_no
                FROM main.ai_character_cache a
            )
            SELECT
                a.cert_id,
                a.language,
                a.rendered_html,
                a.created_at,
                c.brand,
                c.player,
                c.card_name,
                c.movie_name
            FROM ranked a
            LEFT JOIN main.cards c
              ON c.cert_id = a.cert_id
            WHERE a.rank_no = 1
            ORDER BY UPPER(TRIM(a.cert_id)), LOWER(TRIM(a.language))
            """
        )
        for row in rows:
            cert_id = normalize_cert_id(row["cert_id"])
            language = truncate(row["language"], 16, field="AI cache language").lower()
            character = (
                optional_text(row["player"])
                or optional_text(row["movie_name"])
                or optional_text(row["card_name"])
                or cert_id
            )
            yield (
                cert_id,
                truncate(row["brand"], 128, field="AI cache brand") or "Unknown",
                truncate(character, 255, field="AI cache character"),
                language or "en",
                str(row["rendered_html"] or ""),
                "legacy-python",
                to_datetime(row["created_at"]) or dt.datetime(1970, 1, 1),
            )


def batches(rows: Iterable[Sequence[Any]], batch_size: int) -> Iterator[list[Sequence[Any]]]:
    batch: list[Sequence[Any]] = []
    for row in rows:
        batch.append(row)
        if len(batch) >= batch_size:
            yield batch
            batch = []
    if batch:
        yield batch


class JavaMySqlMigration:
    def __init__(self, source: SourceBundle, args: argparse.Namespace):
        self.source = source
        self.args = args
        self.connection = None
        self.baseline: TargetBaseline | None = None

    def connect(self):
        try:
            import pymysql
            from pymysql.cursors import DictCursor
        except ImportError as exc:
            raise MigrationError(
                "PyMySQL is required for --apply; install requirements-mysql.txt in an isolated environment"
            ) from exc

        password = os.environ.get(self.args.mysql_password_env, "")
        options: dict[str, Any] = {
            "user": self.args.mysql_user,
            "password": password,
            "database": self.args.target_database,
            "charset": "utf8mb4",
            "autocommit": False,
            "cursorclass": DictCursor,
            "connect_timeout": 10,
            "read_timeout": 120,
            "write_timeout": 120,
        }
        if self.args.mysql_unix_socket:
            options["unix_socket"] = self.args.mysql_unix_socket
        else:
            options["host"] = self.args.mysql_host
            options["port"] = self.args.mysql_port
        self.connection = pymysql.connect(**options)
        return self.connection

    @property
    def db(self):
        if self.connection is None:
            raise RuntimeError("MySQL connection is not open")
        return self.connection

    def close(self) -> None:
        if self.connection is not None:
            self.connection.close()
            self.connection = None

    def assert_target(self) -> None:
        if self.args.confirm_target_database != self.args.target_database:
            raise MigrationError(
                "--confirm-target-database must exactly match --target-database"
            )
        if not re.fullmatch(r"[A-Za-z0-9_]+", self.args.target_database):
            raise MigrationError("Target database name contains unsupported characters")
        with self.db.cursor() as cursor:
            cursor.execute(
                "SELECT DATABASE() AS database_name, @@foreign_key_checks AS foreign_keys"
            )
            runtime = cursor.fetchone()
            if runtime["database_name"] != self.args.target_database:
                raise MigrationError(
                    f"Connected to {runtime['database_name']!r}, expected {self.args.target_database!r}"
                )
            if int(runtime["foreign_keys"]) != 1:
                raise MigrationError("FOREIGN_KEY_CHECKS must remain enabled")

            required_tables = TARGET_TABLES + UNTOUCHED_TABLES + SYNC_TABLES
            placeholders = ",".join(["%s"] * len(required_tables))
            cursor.execute(
                f"""
                SELECT TABLE_NAME, ENGINE
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME IN ({placeholders})
                """,
                required_tables,
            )
            engines = {row["TABLE_NAME"]: (row["ENGINE"] or "").upper() for row in cursor}
            missing = sorted(set(required_tables) - set(engines))
            non_innodb = sorted(
                name for name, engine in engines.items() if engine != "INNODB"
            )
            if missing or non_innodb:
                details = []
                if missing:
                    details.append("missing=" + ",".join(missing))
                if non_innodb:
                    details.append("non_innodb=" + ",".join(non_innodb))
                raise MigrationError("Unsafe target schema: " + "; ".join(details))

            cursor.execute(
                "SELECT COUNT(*) AS count FROM sys_dict_type WHERE dict_type='nxr_sports_type'"
            )
            if cursor.fetchone()["count"] != 1:
                raise MigrationError("Expected exactly one nxr_sports_type dictionary type")

    def create_staging_tables(self) -> None:
        def column_collation(table: str, column: str) -> str:
            with self.db.cursor() as cursor:
                cursor.execute(
                    """
                    SELECT COLLATION_NAME AS collation_name
                    FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=%s AND COLUMN_NAME=%s
                    """,
                    (table, column),
                )
                row = cursor.fetchone()
            collation = row["collation_name"] if row else None
            if not collation or not re.fullmatch(r"[A-Za-z0-9_]+", collation):
                raise MigrationError(
                    f"Cannot determine a safe collation for {table}.{column}"
                )
            return collation

        submission_collation = column_collation("grading_submission", "cert_id")
        waitlist_collation = column_collation("waitlist_email", "email")
        brand_collation = column_collation("brand_settings", "name")
        sports_collation = column_collation("sys_dict_data", "dict_value")
        ai_collation = column_collation("ai_character_cache", "cert_id")
        statements = (
            f"""
            CREATE TEMPORARY TABLE tmp_nxr_submission (
                cert_id VARCHAR(32) PRIMARY KEY,
                card_name VARCHAR(255) NOT NULL,
                year_label VARCHAR(16) NULL,
                brand_name VARCHAR(64) NOT NULL,
                player_name VARCHAR(128) NULL,
                variety_name VARCHAR(255) NULL,
                set_name VARCHAR(255) NOT NULL,
                card_number VARCHAR(64) NOT NULL,
                language_code VARCHAR(16) NOT NULL,
                population_value INT NOT NULL,
                status_code VARCHAR(32) NOT NULL,
                grading_phase_code VARCHAR(32) NOT NULL,
                card_category_code VARCHAR(32) NOT NULL,
                movie_name VARCHAR(255) NULL,
                release_year VARCHAR(16) NULL,
                production_company VARCHAR(128) NULL,
                film_type VARCHAR(128) NULL,
                sports_type VARCHAR(64) NULL,
                group_name VARCHAR(128) NULL,
                approval_sequence BIGINT NULL,
                entry_notes TEXT NULL,
                approved_at DATETIME(6) NULL,
                published_at DATETIME(6) NULL,
                created_at DATETIME(6) NOT NULL,
                updated_at DATETIME(6) NOT NULL,
                centering_score DECIMAL(4,1) NOT NULL,
                edges_score DECIMAL(4,1) NOT NULL,
                corners_score DECIMAL(4,1) NOT NULL,
                surface_score DECIMAL(4,1) NOT NULL,
                final_grade_value DECIMAL(4,1) NOT NULL,
                final_grade_label VARCHAR(64) NOT NULL,
                ai_grade_value DECIMAL(4,1) NULL,
                ai_confidence_value DECIMAL(5,2) NULL,
                decision_method_code VARCHAR(32) NOT NULL,
                decision_notes TEXT NULL,
                is_published TINYINT NOT NULL,
                verification_slug VARCHAR(64) NULL,
                qr_url VARCHAR(255) NULL,
                published_front_url VARCHAR(255) NULL,
                published_back_url VARCHAR(255) NULL,
                source_fingerprint CHAR(64) NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE={submission_collation}
            """,
            f"""
            CREATE TEMPORARY TABLE tmp_nxr_waitlist (
                email VARCHAR(255) PRIMARY KEY,
                created_at DATETIME(6) NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE={waitlist_collation}
            """,
            f"""
            CREATE TEMPORARY TABLE tmp_nxr_brand (
                name VARCHAR(128) PRIMARY KEY,
                aliases TEXT NOT NULL,
                sort_order INT NOT NULL,
                is_active TINYINT NOT NULL,
                created_at DATETIME(6) NOT NULL,
                updated_at DATETIME(6) NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE={brand_collation}
            """,
            f"""
            CREATE TEMPORARY TABLE tmp_nxr_sports (
                value VARCHAR(100) PRIMARY KEY,
                sort_order INT NOT NULL,
                status CHAR(1) NOT NULL,
                remark VARCHAR(500) NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE={sports_collation}
            """,
            f"""
            CREATE TEMPORARY TABLE tmp_nxr_ai_cache (
                cert_id VARCHAR(64) NOT NULL,
                brand_name VARCHAR(128) NOT NULL,
                character_name VARCHAR(255) NOT NULL,
                language_code VARCHAR(16) NOT NULL,
                content_html MEDIUMTEXT NOT NULL,
                provider_code VARCHAR(32) NOT NULL,
                created_at DATETIME(6) NOT NULL,
                PRIMARY KEY (cert_id, language_code)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE={ai_collation}
            """,
        )
        with self.db.cursor() as cursor:
            for statement in statements:
                cursor.execute(statement)

    def _load(self, sql: str, rows: Iterable[Sequence[Any]], label: str) -> int:
        loaded = 0
        with self.db.cursor() as cursor:
            for batch in batches(rows, self.args.batch_size):
                cursor.executemany(sql, batch)
                loaded += len(batch)
        print(f"staged_{label}={loaded}")
        return loaded

    def load_staging(self, stats: SourceStats) -> None:
        submission_placeholders = ",".join(["%s"] * len(STAGING_SUBMISSION_COLUMNS))
        loaded_submissions = self._load(
            f"INSERT INTO tmp_nxr_submission ({','.join(STAGING_SUBMISSION_COLUMNS)}) "
            f"VALUES ({submission_placeholders})",
            (
                tuple(row[column] for column in STAGING_SUBMISSION_COLUMNS)
                for row in self.source.iter_submissions()
            ),
            "submissions",
        )
        if loaded_submissions != stats.submissions:
            raise MigrationError("Staged submission count changed after source preflight")

        loaded_waitlist = self._load(
            "INSERT INTO tmp_nxr_waitlist (email,created_at) VALUES (%s,%s)",
            self.source.iter_waitlist(),
            "waitlist",
        )
        loaded_brands = self._load(
            """
            INSERT INTO tmp_nxr_brand
                (name,aliases,sort_order,is_active,created_at,updated_at)
            VALUES (%s,%s,%s,%s,%s,%s)
            """,
            self.source.iter_brands(),
            "brands",
        )
        loaded_sports = self._load(
            "INSERT INTO tmp_nxr_sports (value,sort_order,status,remark) VALUES (%s,%s,%s,%s)",
            self.source.iter_sports_types(),
            "sports_types",
        )
        loaded_ai = self._load(
            """
            INSERT INTO tmp_nxr_ai_cache
                (cert_id,brand_name,character_name,language_code,content_html,provider_code,created_at)
            VALUES (%s,%s,%s,%s,%s,%s,%s)
            """,
            self.source.iter_ai_cache(),
            "ai_cache",
        )
        expected = {
            "waitlist": (loaded_waitlist, stats.waitlist),
            "brands": (loaded_brands, stats.brands),
            "sports": (loaded_sports, stats.sports_types),
            "ai_cache": (loaded_ai, stats.ai_cache_rows),
        }
        mismatches = [
            f"{name}:staged={actual},expected={wanted}"
            for name, (actual, wanted) in expected.items()
            if actual != wanted
        ]
        if mismatches:
            raise MigrationError("Staging counts changed: " + "; ".join(mismatches))

    def capture_baseline(self) -> TargetBaseline:
        counts = {}
        with self.db.cursor() as cursor:
            for table in TARGET_TABLES + UNTOUCHED_TABLES:
                cursor.execute(f"SELECT COUNT(*) AS count FROM `{table}`")
                counts[table] = cursor.fetchone()["count"]
            cursor.execute(
                """
                SELECT COUNT(*) AS count
                FROM tmp_nxr_submission t
                JOIN grading_submission s ON s.cert_id = t.cert_id
                """
            )
            cert_conflicts = cursor.fetchone()["count"]
            if cert_conflicts:
                raise MigrationError(
                    f"Target already contains {cert_conflicts} source certificate ids; refusing a non-clean merge"
                )
            overlap_queries = {
                "waitlist": """
                    SELECT COUNT(*) AS count FROM tmp_nxr_waitlist t
                    JOIN waitlist_email w ON w.email = t.email
                """,
                "brand": """
                    SELECT COUNT(*) AS count FROM tmp_nxr_brand t
                    JOIN brand_settings b ON b.name = t.name
                """,
                "sports": """
                    SELECT COUNT(*) AS count FROM tmp_nxr_sports t
                    JOIN sys_dict_data d
                      ON d.dict_type='nxr_sports_type' AND d.dict_value=t.value
                """,
                "ai_cache": """
                    SELECT COUNT(*) AS count FROM tmp_nxr_ai_cache t
                    JOIN ai_character_cache a
                      ON a.cert_id=t.cert_id AND a.language_code=t.language_code
                """,
            }
            overlaps = {}
            for name, sql in overlap_queries.items():
                cursor.execute(sql)
                overlaps[name] = cursor.fetchone()["count"]
        return TargetBaseline(
            counts=counts,
            waitlist_overlap=overlaps["waitlist"],
            brand_overlap=overlaps["brand"],
            sports_overlap=overlaps["sports"],
            ai_cache_overlap=overlaps["ai_cache"],
        )

    def merge(self) -> None:
        statements = (
            """
            INSERT INTO grading_submission (
                cert_id,card_name,year_label,brand_name,player_name,variety_name,
                set_name,card_number,language_code,population_value,status_code,
                grading_phase_code,card_category_code,movie_name,release_year,
                production_company,film_type,sports_type,group_name,approval_sequence,
                entry_notes,entry_by_user_id,approved_by_user_id,approved_at,published_at,
                created_at,updated_at
            )
            SELECT
                cert_id,card_name,year_label,brand_name,player_name,variety_name,
                set_name,card_number,language_code,population_value,status_code,
                grading_phase_code,card_category_code,movie_name,release_year,
                production_company,film_type,sports_type,group_name,approval_sequence,
                entry_notes,NULL,NULL,approved_at,published_at,created_at,updated_at
            FROM tmp_nxr_submission
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
            """,
            """
            INSERT INTO submission_media (
                submission_id,cert_id,media_side_code,media_stage_code,
                storage_provider_code,storage_bucket,storage_key,public_url,
                sort_order,is_active,created_at,updated_at
            )
            SELECT
                s.id,t.cert_id,'front','published','legacy-python','python-public',
                CONCAT(t.cert_id,'/front'),t.published_front_url,1,1,t.published_at,t.updated_at
            FROM tmp_nxr_submission t
            JOIN grading_submission s ON s.cert_id=t.cert_id
            WHERE t.is_published=1 AND t.published_front_url IS NOT NULL
            """,
            """
            INSERT INTO submission_media (
                submission_id,cert_id,media_side_code,media_stage_code,
                storage_provider_code,storage_bucket,storage_key,public_url,
                sort_order,is_active,created_at,updated_at
            )
            SELECT
                s.id,t.cert_id,'back','published','legacy-python','python-public',
                CONCAT(t.cert_id,'/back'),t.published_back_url,1,1,t.published_at,t.updated_at
            FROM tmp_nxr_submission t
            JOIN grading_submission s ON s.cert_id=t.cert_id
            WHERE t.is_published=1 AND t.published_back_url IS NOT NULL
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
            LEFT JOIN submission_media back_media
              ON back_media.submission_id=s.id
             AND back_media.media_stage_code='published'
             AND back_media.media_side_code='back'
             AND back_media.sort_order=1
            WHERE t.is_published=1
            """,
            """
            INSERT IGNORE INTO waitlist_email (email,source_code,status_code,created_at)
            SELECT email,'legacy_python','pending',created_at FROM tmp_nxr_waitlist
            """,
            """
            INSERT INTO brand_settings (name,aliases,sort_order,is_active,created_at,updated_at)
            SELECT name,aliases,sort_order,is_active,created_at,updated_at FROM tmp_nxr_brand
            ON DUPLICATE KEY UPDATE
                aliases=VALUES(aliases),
                sort_order=VALUES(sort_order),
                is_active=VALUES(is_active),
                updated_at=VALUES(updated_at)
            """,
            """
            UPDATE sys_dict_data d
            JOIN tmp_nxr_sports t
              ON d.dict_type='nxr_sports_type' AND d.dict_value=t.value
            SET d.dict_label=t.value,
                d.dict_sort=t.sort_order,
                d.status=t.status,
                d.remark=t.remark,
                d.update_by='migration',
                d.update_time=CURRENT_TIMESTAMP
            """,
            """
            INSERT INTO sys_dict_data (
                dict_sort,dict_label,dict_value,dict_type,css_class,list_class,
                is_default,status,create_by,create_time,remark
            )
            SELECT
                t.sort_order,t.value,t.value,'nxr_sports_type','','','N',t.status,
                'migration',CURRENT_TIMESTAMP,t.remark
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
                brand_name=VALUES(brand_name),
                character_name=VALUES(character_name),
                content_html=VALUES(content_html),
                provider_code=VALUES(provider_code),
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
                submission_id=VALUES(submission_id),
                source_fingerprint=VALUES(source_fingerprint),
                source_updated_at=VALUES(source_updated_at),
                last_synced_at=CURRENT_TIMESTAMP
            """,
        )
        with self.db.cursor() as cursor:
            for index, statement in enumerate(statements, start=1):
                cursor.execute(statement)
                print(f"merge_step_{index}_affected={cursor.rowcount}")

    def expected_counts(self, stats: SourceStats) -> dict[str, int]:
        if self.baseline is None:
            raise RuntimeError("Target baseline has not been captured")
        baseline = self.baseline
        expected = dict(baseline.counts)
        expected["grading_submission"] += stats.submissions
        expected["grading_score"] += stats.submissions
        expected["submission_media"] += stats.published_media
        expected["published_certificate"] += stats.published_certificates
        expected["waitlist_email"] += stats.waitlist - baseline.waitlist_overlap
        expected["brand_settings"] += stats.brands - baseline.brand_overlap
        expected["ai_character_cache"] += stats.ai_cache_rows - baseline.ai_cache_overlap
        expected["sys_dict_data"] += stats.sports_types - baseline.sports_overlap
        return expected

    def verify(self, stats: SourceStats, *, phase: str) -> None:
        expected = self.expected_counts(stats)
        failures = []
        with self.db.cursor() as cursor:
            for table, wanted in expected.items():
                cursor.execute(f"SELECT COUNT(*) AS count FROM `{table}`")
                actual = cursor.fetchone()["count"]
                if actual != wanted:
                    failures.append(f"{table}:actual={actual},expected={wanted}")

            mismatch_queries = {
                "submission": """
                    SELECT COUNT(*) AS count
                    FROM tmp_nxr_submission t
                    LEFT JOIN grading_submission s ON s.cert_id=t.cert_id
                    WHERE s.id IS NULL
                       OR NOT (
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
                        AND s.created_at <=> t.created_at
                        AND s.updated_at <=> t.updated_at
                       )
                """,
                "score": """
                    SELECT COUNT(*) AS count
                    FROM tmp_nxr_submission t
                    JOIN grading_submission s ON s.cert_id=t.cert_id
                    LEFT JOIN grading_score g ON g.submission_id=s.id
                    WHERE g.submission_id IS NULL
                       OR NOT (
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
                """,
                "publication": """
                    SELECT COUNT(*) AS count
                    FROM tmp_nxr_submission t
                    JOIN grading_submission s ON s.cert_id=t.cert_id
                    LEFT JOIN published_certificate p ON p.submission_id=s.id
                    WHERE t.is_published=1
                      AND (
                           p.id IS NULL
                        OR NOT (
                               p.cert_id <=> t.cert_id
                           AND p.verification_slug <=> t.verification_slug
                           AND p.qr_url <=> t.qr_url
                           AND p.published_at <=> t.published_at
                        )
                      )
                """,
                "front_media": """
                    SELECT COUNT(*) AS count
                    FROM tmp_nxr_submission t
                    JOIN grading_submission s ON s.cert_id=t.cert_id
                    LEFT JOIN submission_media m
                      ON m.submission_id=s.id
                     AND m.media_stage_code='published'
                     AND m.media_side_code='front'
                     AND m.sort_order=1
                    WHERE t.published_front_url IS NOT NULL
                      AND (m.id IS NULL OR NOT (m.public_url <=> t.published_front_url))
                """,
                "back_media": """
                    SELECT COUNT(*) AS count
                    FROM tmp_nxr_submission t
                    JOIN grading_submission s ON s.cert_id=t.cert_id
                    LEFT JOIN submission_media m
                      ON m.submission_id=s.id
                     AND m.media_stage_code='published'
                     AND m.media_side_code='back'
                     AND m.sort_order=1
                    WHERE t.published_back_url IS NOT NULL
                      AND (m.id IS NULL OR NOT (m.public_url <=> t.published_back_url))
                """,
                "waitlist": """
                    SELECT COUNT(*) AS count FROM tmp_nxr_waitlist t
                    LEFT JOIN waitlist_email w ON w.email=t.email
                    WHERE w.id IS NULL
                """,
                "brand": """
                    SELECT COUNT(*) AS count FROM tmp_nxr_brand t
                    LEFT JOIN brand_settings b ON b.name=t.name
                    WHERE b.id IS NULL OR NOT (
                           b.aliases <=> t.aliases
                       AND b.sort_order <=> t.sort_order
                       AND b.is_active <=> t.is_active
                    )
                """,
                "sports": """
                    SELECT COUNT(*) AS count FROM tmp_nxr_sports t
                    LEFT JOIN sys_dict_data d
                      ON d.dict_type='nxr_sports_type' AND d.dict_value=t.value
                    WHERE d.dict_code IS NULL OR NOT (
                           d.dict_label <=> t.value
                       AND d.dict_sort <=> t.sort_order
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
                "sync_submission": """
                    SELECT COUNT(*) AS count
                    FROM tmp_nxr_submission t
                    JOIN grading_submission s ON s.cert_id=t.cert_id
                    LEFT JOIN nxr_python_sync_submission m ON m.cert_id=t.cert_id
                    WHERE m.cert_id IS NULL
                       OR m.submission_id<>s.id
                       OR m.source_fingerprint<>t.source_fingerprint
                """,
                "orphan_score": """
                    SELECT COUNT(*) AS count FROM grading_score g
                    LEFT JOIN grading_submission s ON s.id=g.submission_id
                    WHERE s.id IS NULL
                """,
                "orphan_media": """
                    SELECT COUNT(*) AS count FROM submission_media m
                    LEFT JOIN grading_submission s ON s.id=m.submission_id
                    WHERE s.id IS NULL
                """,
                "orphan_publication": """
                    SELECT COUNT(*) AS count FROM published_certificate p
                    LEFT JOIN grading_submission s ON s.id=p.submission_id
                    WHERE s.id IS NULL
                """,
                "orphan_sync_submission": """
                    SELECT COUNT(*) AS count
                    FROM nxr_python_sync_submission m
                    LEFT JOIN grading_submission s ON s.id=m.submission_id
                    WHERE s.id IS NULL OR s.cert_id<>m.cert_id
                """,
            }
            for name, sql in mismatch_queries.items():
                cursor.execute(sql)
                mismatch_count = cursor.fetchone()["count"]
                if mismatch_count:
                    failures.append(f"{name}_mismatches={mismatch_count}")

            if self.baseline is None:
                raise RuntimeError("Target baseline has not been captured")
            for table in UNTOUCHED_TABLES:
                cursor.execute(f"SELECT COUNT(*) AS count FROM `{table}`")
                actual = cursor.fetchone()["count"]
                wanted = self.baseline.counts[table]
                if actual != wanted:
                    failures.append(
                        f"untouched_{table}:actual={actual},expected={wanted}"
                    )

        if failures:
            raise MigrationError(
                f"{phase} verification failed: " + "; ".join(failures)
            )
        print(f"verification_{phase}=ok")

    def run(self, stats: SourceStats) -> None:
        self.connect()
        try:
            self.assert_target()
            with self.db.cursor() as cursor:
                cursor.execute(
                    "SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED"
                )
            self.db.begin()
            try:
                self.create_staging_tables()
                self.load_staging(stats)
                self.baseline = self.capture_baseline()
                self.merge()
                self.verify(stats, phase="precommit")
                self.db.commit()
            except Exception:
                self.db.rollback()
                raise

            # The target is a disposable clone during rehearsal and remains
            # disconnected from Java until this post-commit check succeeds.
            self.verify(stats, phase="postcommit")
        finally:
            self.close()


def print_plan(stats: SourceStats) -> None:
    print("source_validation=ok")
    for field in stats.__dataclass_fields__:
        print(f"source_{field}={getattr(stats, field)}")
    print("mapping_cards=grading_submission+grading_score+published_certificate+submission_media")
    print("mapping_temp_only=grading_submission+grading_score")
    print("mapping_dictionary_brand=brand_settings")
    print("mapping_dictionary_sports_type=sys_dict_data:nxr_sports_type")
    print("mapping_waitlist=waitlist_email")
    print("mapping_ai_character_cache=ai_character_cache:latest_per_cert_language")
    print("skipped_tables=admin_users,brand_settings_legacy,grading_history,human_grading_details,ai_grading_details,image_analysis")


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Merge Flask SQLite data into the Java/RuoYi MySQL domain schema"
    )
    parser.add_argument("--cards-db", type=Path, default=DEFAULT_CARDS_DB)
    parser.add_argument("--temp-db", type=Path, default=DEFAULT_TEMP_DB)
    parser.add_argument("--batch-size", type=int, default=500)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--target-database", default="")
    parser.add_argument("--confirm-target-database", default="")
    parser.add_argument("--mysql-host", default="127.0.0.1")
    parser.add_argument("--mysql-port", type=int, default=3306)
    parser.add_argument("--mysql-user", default="root")
    parser.add_argument("--mysql-unix-socket", default="")
    parser.add_argument(
        "--mysql-password-env",
        default="NXR_MIGRATION_MYSQL_PASSWORD",
        help="environment variable containing the password; the password is never printed",
    )
    args = parser.parse_args(argv)
    if args.batch_size < 1:
        parser.error("--batch-size must be at least 1")
    if args.apply and not args.target_database:
        parser.error("--target-database is required with --apply")
    return args


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        with SourceBundle(args.cards_db.resolve(), args.temp_db.resolve()) as source:
            stats = source.validate()
            print_plan(stats)
            if not args.apply:
                print("dry_run=1")
                return 0
            migration = JavaMySqlMigration(source, args)
            migration.run(stats)
            print("migration=committed")
            return 0
    except MigrationError as exc:
        print(f"migration_error={exc}", file=sys.stderr)
        return 1
    except Exception as exc:  # noqa: BLE001 - keep production output credential-safe
        print(f"migration_error={type(exc).__name__}:{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
