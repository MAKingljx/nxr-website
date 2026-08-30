import hashlib
import sqlite3
import tempfile
import unittest
from pathlib import Path

from scripts import migrate_python_to_java_mysql as migration
from scripts import sync_python_to_java_mysql as sync


CARDS_SCHEMA = """
CREATE TABLE cards (
    cert_id TEXT PRIMARY KEY,
    card_name TEXT,
    grade TEXT,
    year TEXT,
    brand TEXT,
    player TEXT,
    variety TEXT,
    image TEXT,
    pop TEXT,
    back_image TEXT,
    front_image TEXT,
    qr_url TEXT,
    centering REAL,
    edges REAL,
    corners REAL,
    surface REAL,
    language TEXT,
    set_name TEXT,
    card_number TEXT,
    grading_phase TEXT,
    created_at TEXT,
    updated_at TEXT,
    ai_confidence REAL,
    ai_grade REAL,
    has_ai_analysis INTEGER,
    final_grade REAL,
    decision_method TEXT,
    decision_notes TEXT,
    final_grade_text TEXT,
    card_category TEXT,
    movie_name TEXT,
    release_year TEXT,
    production_company TEXT,
    film_type TEXT,
    sports_type TEXT,
    group_name TEXT
);
CREATE TABLE waitlist (
    id INTEGER PRIMARY KEY,
    email TEXT NOT NULL,
    created_at TEXT
);
CREATE TABLE dictionary_groups (
    id INTEGER PRIMARY KEY,
    code TEXT NOT NULL,
    name TEXT NOT NULL
);
CREATE TABLE dictionary_items (
    id INTEGER PRIMARY KEY,
    group_id INTEGER NOT NULL,
    value TEXT NOT NULL,
    aliases TEXT,
    sort_order INTEGER,
    is_active INTEGER,
    created_at TEXT,
    updated_at TEXT,
    FOREIGN KEY (group_id) REFERENCES dictionary_groups(id)
);
CREATE TABLE ai_character_cache (
    cert_id TEXT NOT NULL,
    language TEXT NOT NULL,
    prompt_hash TEXT NOT NULL,
    rendered_html TEXT,
    created_at TEXT,
    PRIMARY KEY (cert_id, language, prompt_hash)
);
"""


TEMP_SCHEMA = """
CREATE TABLE temp_cards (
    id INTEGER PRIMARY KEY,
    cert_id TEXT UNIQUE,
    card_name TEXT,
    year TEXT,
    brand TEXT,
    variety TEXT,
    pop TEXT,
    language TEXT,
    set_name TEXT,
    card_number TEXT,
    centering REAL,
    edges REAL,
    corners REAL,
    surface REAL,
    final_grade REAL,
    final_grade_text TEXT,
    front_image TEXT,
    back_image TEXT,
    entry_notes TEXT,
    entry_by TEXT,
    entry_date TEXT,
    status TEXT,
    created_at TEXT,
    updated_at TEXT,
    upload_status TEXT,
    upload_started TEXT,
    upload_completed TEXT,
    published_front_image TEXT,
    published_back_image TEXT,
    approved_at TEXT,
    approval_sequence INTEGER,
    card_category TEXT,
    movie_name TEXT,
    release_year TEXT,
    production_company TEXT,
    film_type TEXT,
    sports_type TEXT,
    group_name TEXT
);
"""


def insert_temp_card(conn, cert_id, status="pending"):
    conn.execute(
        """
        INSERT INTO temp_cards (
            cert_id,card_name,year,brand,variety,pop,language,set_name,card_number,
            centering,edges,corners,surface,final_grade,final_grade_text,
            entry_notes,entry_date,status,created_at,updated_at,upload_status,
            approved_at,approval_sequence,card_category,movie_name,release_year,
            production_company,film_type,sports_type,group_name
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        (
            cert_id,
            "Test Card",
            "2026",
            "Pokemon",
            "Variant",
            "1",
            "EN",
            "Set",
            "001",
            9.5,
            9.0,
            9.0,
            9.5,
            9.3,
            "9.5",
            "Intake note",
            "2026-07-01T09:00:00",
            status,
            "2026-07-01T09:00:00",
            "2026-07-01T10:00:00",
            "not_started",
            "2026-07-01T09:30:00" if status == "approved" else None,
            7 if status == "approved" else None,
            "trading_card",
            "",
            "",
            "",
            "",
            "",
            "",
        ),
    )


class SourceFixture:
    def __init__(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        self.cards_path = root / "cards.db"
        self.temp_path = root / "temp_cards.db"
        cards = sqlite3.connect(self.cards_path)
        temp = sqlite3.connect(self.temp_path)
        cards.executescript(CARDS_SCHEMA)
        temp.executescript(TEMP_SCHEMA)
        cards.execute("INSERT INTO dictionary_groups VALUES (1,'brand','Brand')")
        cards.execute(
            "INSERT INTO dictionary_groups VALUES (2,'sports_type','Sports Type')"
        )
        cards.execute(
            """
            INSERT INTO dictionary_items
                (id,group_id,value,aliases,sort_order,is_active,created_at,updated_at)
            VALUES (1,1,'Pokemon','poke',10,1,'2026-07-01','2026-07-02')
            """
        )
        cards.execute(
            """
            INSERT INTO dictionary_items
                (id,group_id,value,aliases,sort_order,is_active,created_at,updated_at)
            VALUES (2,2,'Basketball','basket ball',10,1,'2026-07-01','2026-07-02')
            """
        )
        cards.commit()
        temp.commit()
        cards.close()
        temp.close()

    def close(self):
        self.directory.cleanup()


class JavaDomainMigrationTests(unittest.TestCase):
    def setUp(self):
        self.fixture = SourceFixture()

    def tearDown(self):
        self.fixture.close()

    def test_merges_published_card_and_temp_record_without_duplication(self):
        cards = sqlite3.connect(self.fixture.cards_path)
        cards.execute(
            """
            INSERT INTO cards VALUES (
                'vra003','Published Card','9.5','2021','Pokemon','Umbreon','Alt',
                '/static/front.webp','3','/static/back.webp','/static/front.webp',
                '/card/VRA003',9.5,9.0,9.0,9.5,'en','Set','001','human_only',
                '2026-07-01T09:00:00','2026-07-01T11:00:00',0.95,9.4,1,9.3,
                'human_only','Decision line 1\nDecision line 2','9.5','trading_card','','','','','',''
            )
            """
        )
        cards.commit()
        cards.close()
        temp = sqlite3.connect(self.fixture.temp_path)
        insert_temp_card(temp, "VRA003", status="approved")
        temp.execute(
            """
            UPDATE temp_cards
            SET upload_completed='2026-07-01T10:30:00', approval_sequence=42,
                entry_notes='Intake line 1\nIntake line 2'
            WHERE cert_id='VRA003'
            """
        )
        temp.commit()
        temp.close()

        with migration.SourceBundle(
            self.fixture.cards_path, self.fixture.temp_path
        ) as source:
            stats = source.validate()
            rows = list(source.iter_submissions())

        self.assertEqual(stats.cards, 1)
        self.assertEqual(stats.overlap, 1)
        self.assertEqual(stats.submissions, 1)
        self.assertEqual(stats.published_media, 2)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["cert_id"], "VRA003")
        self.assertEqual(rows[0]["status_code"], "published")
        self.assertEqual(rows[0]["approval_sequence"], 42)
        self.assertEqual(rows[0]["published_front_url"], "/static/front.webp")
        self.assertEqual(rows[0]["ai_confidence_value"], migration.decimal.Decimal("95.00"))
        self.assertEqual(rows[0]["entry_notes"], "Intake line 1\nIntake line 2")
        self.assertEqual(len(rows[0]["source_fingerprint"]), 64)
        self.assertEqual(
            rows[0]["decision_notes"], "Decision line 1\nDecision line 2"
        )

        cards = sqlite3.connect(self.fixture.cards_path)
        cards.execute(
            "UPDATE cards SET has_ai_analysis=0 WHERE cert_id='vra003'"
        )
        cards.commit()
        cards.close()
        with migration.SourceBundle(
            self.fixture.cards_path, self.fixture.temp_path
        ) as source:
            source.validate()
            without_ai = list(source.iter_submissions())[0]
        self.assertIsNone(without_ai["ai_grade_value"])
        self.assertIsNone(without_ai["ai_confidence_value"])

    def test_temp_only_rows_map_to_java_workflow_states(self):
        temp = sqlite3.connect(self.fixture.temp_path)
        insert_temp_card(temp, "PENDING01", status="pending")
        insert_temp_card(temp, "APPROVED1", status="approved")
        temp.commit()
        temp.close()

        with migration.SourceBundle(
            self.fixture.cards_path, self.fixture.temp_path
        ) as source:
            stats = source.validate()
            rows = {row["cert_id"]: row for row in source.iter_submissions()}

        self.assertEqual(stats.submissions, 2)
        self.assertEqual(stats.temp_only_approved, 1)
        self.assertEqual(stats.temp_only_pending_or_review, 1)
        self.assertEqual(rows["PENDING01"]["status_code"], "pending")
        self.assertIsNone(rows["PENDING01"]["approved_at"])
        self.assertEqual(rows["APPROVED1"]["status_code"], "approved")
        self.assertIsNotNone(rows["APPROVED1"]["approved_at"])

    def test_product_types_map_without_fake_grading_scores(self):
        cards = sqlite3.connect(self.fixture.cards_path)
        cards.execute(
            "ALTER TABLE cards ADD COLUMN product_type TEXT NOT NULL DEFAULT 'graded_card'"
        )
        cards.execute(
            "ALTER TABLE cards ADD COLUMN vintage_classification TEXT DEFAULT ''"
        )
        cards.execute(
            "ALTER TABLE cards ADD COLUMN merch_description TEXT DEFAULT ''"
        )
        cards.commit()
        cards.close()

        temp = sqlite3.connect(self.fixture.temp_path)
        temp.execute(
            "ALTER TABLE temp_cards ADD COLUMN product_type TEXT NOT NULL DEFAULT 'graded_card'"
        )
        temp.execute(
            "ALTER TABLE temp_cards ADD COLUMN vintage_classification TEXT DEFAULT ''"
        )
        temp.execute(
            "ALTER TABLE temp_cards ADD COLUMN merch_description TEXT DEFAULT ''"
        )
        insert_temp_card(temp, "LABEL001", status="approved")
        insert_temp_card(temp, "VINTAGE1", status="approved")
        temp.execute(
            """
            UPDATE temp_cards
            SET product_type='merch_product', merch_description='Limited pin',
                centering=1, edges=1, corners=1, surface=1, final_grade=1,
                final_grade_text=''
            WHERE cert_id='LABEL001'
            """
        )
        temp.execute(
            """
            UPDATE temp_cards
            SET product_type='vintage_product', vintage_classification='Archive A',
                centering=1, edges=1, corners=1, surface=1,
                final_grade=1, final_grade_text=''
            WHERE cert_id='VINTAGE1'
            """
        )
        temp.commit()
        temp.close()

        with migration.SourceBundle(
            self.fixture.cards_path, self.fixture.temp_path
        ) as source:
            stats = source.validate()
            rows = {row["cert_id"]: row for row in source.iter_submissions()}

        self.assertEqual(stats.submissions, 2)
        self.assertEqual(stats.graded_submissions, 0)
        self.assertEqual(rows["LABEL001"]["product_type_code"], "merch_product")
        self.assertEqual(rows["LABEL001"]["merch_description"], "Limited pin")
        self.assertIsNone(rows["LABEL001"]["final_grade_value"])
        self.assertIsNone(rows["LABEL001"]["final_grade_label"])
        self.assertEqual(
            rows["VINTAGE1"]["vintage_classification_code"], "Archive A"
        )
        self.assertIsNone(rows["VINTAGE1"]["centering_score"])

    def test_published_non_graded_product_discards_legacy_ai_grade(self):
        cards = sqlite3.connect(self.fixture.cards_path)
        cards.execute(
            "ALTER TABLE cards ADD COLUMN product_type TEXT NOT NULL DEFAULT 'graded_card'"
        )
        cards.execute(
            "ALTER TABLE cards ADD COLUMN vintage_classification TEXT DEFAULT ''"
        )
        cards.execute(
            "ALTER TABLE cards ADD COLUMN merch_description TEXT DEFAULT ''"
        )
        cards.execute(
            """
            INSERT INTO cards (
                cert_id, card_name, grade, year, brand, variety, pop, language,
                set_name, card_number, grading_phase, created_at, updated_at,
                ai_confidence, ai_grade, has_ai_analysis, final_grade,
                decision_method, final_grade_text, card_category, product_type,
                vintage_classification, merch_description
            ) VALUES (
                'LABELAI1', 'Label AI Record', '', '2026', 'Pokemon', 'Label',
                '1', 'EN', 'Set', '001', 'human_only', '2026-07-01',
                '2026-07-01', 0.97, 9.5, 1, NULL, 'human_only', '',
                'trading_card', 'label_product', '', 'Legacy merch description'
            )
            """
        )
        cards.commit()
        cards.close()

        with migration.SourceBundle(
            self.fixture.cards_path, self.fixture.temp_path
        ) as source:
            source.validate()
            row = list(source.iter_submissions())[0]

        self.assertEqual(row["product_type_code"], "merch_product")
        self.assertEqual(row["merch_description"], "Legacy merch description")
        self.assertIsNone(row["ai_grade_value"])
        self.assertIsNone(row["ai_confidence_value"])

    def test_vintage_product_requires_classification(self):
        temp = sqlite3.connect(self.fixture.temp_path)
        temp.execute(
            "ALTER TABLE temp_cards ADD COLUMN product_type TEXT NOT NULL DEFAULT 'graded_card'"
        )
        temp.execute(
            "ALTER TABLE temp_cards ADD COLUMN vintage_classification TEXT DEFAULT ''"
        )
        insert_temp_card(temp, "VINTAGE2", status="pending")
        temp.execute(
            "UPDATE temp_cards SET product_type='vintage_product' WHERE cert_id='VINTAGE2'"
        )
        temp.commit()
        temp.close()

        with migration.SourceBundle(
            self.fixture.cards_path, self.fixture.temp_path
        ) as source:
            with self.assertRaisesRegex(
                migration.MigrationError, "requires a vintage classification"
            ):
                source.validate()

    def test_ai_cache_uses_latest_cert_language_row(self):
        cards = sqlite3.connect(self.fixture.cards_path)
        cards.execute(
            """
            INSERT INTO ai_character_cache VALUES
                ('CACHE01','en','old','<p>old</p>','2026-07-01'),
                ('CACHE01','en','new','<p>new</p>','2026-07-02')
            """
        )
        cards.commit()
        cards.close()

        with migration.SourceBundle(
            self.fixture.cards_path, self.fixture.temp_path
        ) as source:
            stats = source.validate()
            rows = list(source.iter_ai_cache())

        self.assertEqual(stats.ai_cache_rows, 1)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0][0], "CACHE01")
        self.assertEqual(rows[0][4], "<p>new</p>")

    def test_apply_requires_exact_database_confirmation(self):
        args = migration.parse_args(
            [
                "--apply",
                "--target-database",
                "nxr_clone",
                "--confirm-target-database",
                "different_database",
            ]
        )
        with migration.SourceBundle(
            self.fixture.cards_path, self.fixture.temp_path
        ) as source:
            runner = migration.JavaMySqlMigration(source, args)
            runner.connection = object()
            with self.assertRaisesRegex(
                migration.MigrationError, "must exactly match"
            ):
                runner.assert_target()

    def test_target_column_preflight_includes_product_sync_fields(self):
        available = {
            table: set(columns)
            for table, columns in migration.TARGET_REQUIRED_COLUMNS.items()
        }
        available["grading_submission"].remove("merch_description")

        self.assertEqual(
            migration.missing_target_columns(available),
            ["grading_submission.merch_description"],
        )

    def test_unsupported_temp_status_fails_before_mysql(self):
        temp = sqlite3.connect(self.fixture.temp_path)
        insert_temp_card(temp, "REJECTED1", status="rejected")
        temp.commit()
        temp.close()

        with migration.SourceBundle(
            self.fixture.cards_path, self.fixture.temp_path
        ) as source:
            with self.assertRaisesRegex(
                migration.MigrationError, "Unsupported temporary-card status"
            ):
                source.validate()

    def test_incremental_cursor_detects_insert_and_upload_completion(self):
        with sync.SyncSource(
            self.fixture.cards_path, self.fixture.temp_path
        ) as source:
            source.validate_light()
            empty_cursor = source.capture_cursor()

        temp = sqlite3.connect(self.fixture.temp_path)
        insert_temp_card(temp, "SYNC0001", status="approved")
        temp.commit()
        temp.close()

        with sync.SyncSource(
            self.fixture.cards_path, self.fixture.temp_path
        ) as source:
            inserted = source.changed_cert_ids(empty_cursor)
            inserted_cursor = source.capture_cursor()
        self.assertEqual(inserted, ["SYNC0001"])

        temp = sqlite3.connect(self.fixture.temp_path)
        temp.execute(
            """
            UPDATE temp_cards
            SET upload_completed='2026-07-03T12:00:00'
            WHERE cert_id='SYNC0001'
            """
        )
        temp.commit()
        temp.close()

        with sync.SyncSource(
            self.fixture.cards_path, self.fixture.temp_path
        ) as source:
            completed = source.changed_cert_ids(inserted_cursor)
        self.assertEqual(completed, ["SYNC0001"])

    def test_sync_source_does_not_modify_sqlite_files(self):
        before = {
            path: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in (self.fixture.cards_path, self.fixture.temp_path)
        }
        with sync.SyncSource(
            self.fixture.cards_path, self.fixture.temp_path
        ) as source:
            source.validate_light(quick_check=True)
            source.capture_cursor()
            list(source.iter_brands())
            list(source.iter_sports_types())
        after = {
            path: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in (self.fixture.cards_path, self.fixture.temp_path)
        }
        self.assertEqual(after, before)

    def test_online_backup_snapshot_preserves_source_and_rows(self):
        temp = sqlite3.connect(self.fixture.temp_path)
        insert_temp_card(temp, "SNAP0001", status="approved")
        temp.commit()
        temp.close()
        source_hashes = {
            path: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in (self.fixture.cards_path, self.fixture.temp_path)
        }
        snapshot_root = Path(self.fixture.directory.name) / "snapshots"
        snapshot_root.mkdir()
        cards_snapshot = snapshot_root / "cards.db"
        temp_snapshot = snapshot_root / "temp_cards.db"

        sync.backup_sqlite_readonly(self.fixture.cards_path, cards_snapshot)
        sync.backup_sqlite_readonly(self.fixture.temp_path, temp_snapshot)

        after_hashes = {
            path: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in (self.fixture.cards_path, self.fixture.temp_path)
        }
        self.assertEqual(after_hashes, source_hashes)
        with sync.SyncSource(cards_snapshot, temp_snapshot) as source:
            source.validate_light(quick_check=True)
            rows = list(source.iter_submissions())
        self.assertEqual([row["cert_id"] for row in rows], ["SNAP0001"])

    def test_submission_fingerprint_changes_with_domain_data(self):
        temp = sqlite3.connect(self.fixture.temp_path)
        insert_temp_card(temp, "HASH0001", status="pending")
        temp.commit()
        temp.close()

        with migration.SourceBundle(
            self.fixture.cards_path, self.fixture.temp_path
        ) as source:
            source.validate()
            original = list(source.iter_submissions())[0]

        temp = sqlite3.connect(self.fixture.temp_path)
        temp.execute(
            """
            UPDATE temp_cards
            SET entry_notes='A different memory',updated_at='2026-07-04T10:00:00'
            WHERE cert_id='HASH0001'
            """
        )
        temp.commit()
        temp.close()

        with migration.SourceBundle(
            self.fixture.cards_path, self.fixture.temp_path
        ) as source:
            source.validate()
            updated = list(source.iter_submissions())[0]

        self.assertNotEqual(
            original["source_fingerprint"], updated["source_fingerprint"]
        )


if __name__ == "__main__":
    unittest.main()
