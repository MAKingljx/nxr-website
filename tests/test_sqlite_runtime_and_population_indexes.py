import os
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from nxr_common import db


class SQLiteRuntimeConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.database_path = Path(self.temporary_directory.name) / 'runtime.db'

    def tearDown(self):
        self.temporary_directory.cleanup()

    def test_connection_uses_configurable_busy_timeout(self):
        with mock.patch.dict(
            os.environ,
            {
                'NXR_SQLITE_TIMEOUT_SECONDS': '2.5',
                'NXR_SQLITE_BUSY_TIMEOUT_MS': '1234',
            },
            clear=False,
        ):
            with db.sqlite_connect(self.database_path) as conn:
                busy_timeout = conn.execute('PRAGMA busy_timeout').fetchone()[0]

        self.assertEqual(busy_timeout, 1234)

    def test_invalid_timeout_values_fall_back_to_safe_default(self):
        with mock.patch.dict(
            os.environ,
            {
                'NXR_SQLITE_TIMEOUT_SECONDS': 'invalid',
                'NXR_SQLITE_BUSY_TIMEOUT_MS': '-1',
            },
            clear=False,
        ):
            with db.sqlite_connect(self.database_path) as conn:
                busy_timeout = conn.execute('PRAGMA busy_timeout').fetchone()[0]

        self.assertEqual(
            busy_timeout,
            int(db.DEFAULT_SQLITE_TIMEOUT_SECONDS * 1000),
        )

    def test_wal_requires_explicit_configuration_step(self):
        with sqlite3.connect(self.database_path) as raw_conn:
            self.assertEqual(
                raw_conn.execute('PRAGMA journal_mode = DELETE').fetchone()[0],
                'delete',
            )

        with mock.patch.dict(
            os.environ,
            {'NXR_SQLITE_JOURNAL_MODE': 'wal'},
            clear=False,
        ):
            with db.sqlite_connect(self.database_path) as conn:
                self.assertEqual(
                    conn.execute('PRAGMA journal_mode').fetchone()[0],
                    'delete',
                )
                self.assertEqual(db.configure_sqlite_journal_mode(conn), 'wal')

        with sqlite3.connect(self.database_path) as raw_conn:
            self.assertEqual(raw_conn.execute('PRAGMA journal_mode').fetchone()[0], 'wal')

    def test_invalid_journal_mode_is_rejected(self):
        with db.sqlite_connect(self.database_path) as conn:
            with self.assertRaisesRegex(db.DatabaseConfigError, 'delete, wal'):
                db.configure_sqlite_journal_mode(conn, 'off')


class PopulationIndexTests(unittest.TestCase):
    def setUp(self):
        self.backend_patch = mock.patch.dict(
            os.environ,
            {
                'NXR_DB_BACKEND': 'sqlite',
                'NXR_SQLITE_JOURNAL_MODE': '',
            },
            clear=False,
        )
        self.backend_patch.start()
        self.temporary_directory = tempfile.TemporaryDirectory()
        root = Path(self.temporary_directory.name)

        # Import lazily so the module's environment-derived defaults do not
        # write into the project's real Data directory.
        from nxr_admin import admin_core

        self.admin_core = admin_core
        self.original_paths = {
            'DATA_DIR': admin_core.DATA_DIR,
            'DB_PATH': admin_core.DB_PATH,
            'TEMP_DB_PATH': admin_core.TEMP_DB_PATH,
        }
        admin_core.DATA_DIR = root / 'data'
        admin_core.DB_PATH = admin_core.DATA_DIR / 'cards.db'
        admin_core.TEMP_DB_PATH = admin_core.DATA_DIR / 'temp_cards.db'
        admin_core.DATA_DIR.mkdir(parents=True, exist_ok=True)
        admin_core.initialize_main_database()
        admin_core.init_temp_database()

    def tearDown(self):
        for name, value in self.original_paths.items():
            setattr(self.admin_core, name, value)
        self.temporary_directory.cleanup()
        self.backend_patch.stop()

    def test_population_query_uses_expression_index_for_legacy_values(self):
        admin_core = self.admin_core
        with admin_core.get_temp_db_connection() as conn:
            conn.execute(
                '''
                INSERT INTO temp_cards (
                    cert_id, card_name, product_type, card_category,
                    language, set_name, card_number, final_grade_text
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ''',
                (
                    'LEGACY001',
                    'Legacy Card',
                    'graded card',
                    '',
                    'English',
                    'Legacy Set',
                    '001',
                    '9',
                ),
            )
            _, _, where_sql, params = admin_core._build_population_filter(
                card_category='trading_card',
                card_name='legacy card',
                set_name='legacy set',
                card_number='001',
                language='EN',
                final_grade_text='9',
            )
            plan = conn.execute(
                f'EXPLAIN QUERY PLAN SELECT COUNT(*) FROM temp_cards WHERE {where_sql}',
                params,
            ).fetchall()
            count = conn.execute(
                f'SELECT COUNT(*) FROM temp_cards WHERE {where_sql}',
                params,
            ).fetchone()[0]

        plan_text = ' '.join(row[3] for row in plan)
        self.assertEqual(count, 1)
        self.assertIn('idx_temp_cards_pop_identity', plan_text)
        self.assertNotIn('SCAN temp_cards', plan_text)

    def test_population_index_initialization_is_idempotent(self):
        self.admin_core.initialize_main_database()
        self.admin_core.init_temp_database()
        with self.admin_core.get_temp_db_connection() as conn:
            index_names = {
                row[1]
                for row in conn.execute('PRAGMA index_list(temp_cards)').fetchall()
            }

        self.assertTrue({
            'idx_temp_cards_pop_identity',
            'idx_temp_cards_pop_movie',
            'idx_temp_cards_front_image',
            'idx_temp_cards_back_image',
        }.issubset(index_names))

    def test_queue_image_reference_lookups_use_individual_indexes(self):
        with self.admin_core.get_temp_db_connection() as conn:
            front_plan = conn.execute(
                '''
                    EXPLAIN QUERY PLAN
                    SELECT 1 FROM temp_cards WHERE front_image = ? LIMIT 1
                ''',
                ('front.jpg',),
            ).fetchall()
            back_plan = conn.execute(
                '''
                    EXPLAIN QUERY PLAN
                    SELECT 1 FROM temp_cards WHERE back_image = ? LIMIT 1
                ''',
                ('back.jpg',),
            ).fetchall()

        self.assertIn(
            'idx_temp_cards_front_image',
            ' '.join(row[3] for row in front_plan),
        )
        self.assertIn(
            'idx_temp_cards_back_image',
            ' '.join(row[3] for row in back_plan),
        )


if __name__ == '__main__':
    unittest.main()
