import os
import sqlite3
import unittest
from unittest import mock

from scripts import migrate_sqlite_to_mysql as migration


def sqlite_memory_connection():
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


class MysqlMigrationTests(unittest.TestCase):
    def test_generated_mysql_table_uses_innodb_and_inherits_database_collation(self):
        source_conn = sqlite_memory_connection()

        class RecordingTarget:
            backend = "mysql"

            def __init__(self):
                self.statements = []

            def execute(self, sql, params=None):
                self.statements.append((sql, params))

        target_conn = RecordingTarget()
        try:
            source_conn.execute(
                "CREATE TABLE sample (id INTEGER PRIMARY KEY, cert_id TEXT NOT NULL)"
            )
            with mock.patch.dict(
                os.environ, {"NXR_DB_BACKEND": "mysql"}, clear=False
            ), mock.patch.object(migration, "target_table_exists", return_value=False):
                migration.ensure_extra_table_from_sqlite(
                    target_conn, source_conn, "sample"
                )

            create_sql = target_conn.statements[-1][0]
            self.assertTrue(create_sql.endswith("ENGINE=InnoDB"))
            self.assertNotIn("DEFAULT CHARSET", create_sql)
        finally:
            source_conn.close()

    def test_group_import_rolls_back_every_table_on_failure(self):
        source_conn = sqlite_memory_connection()
        target_conn = sqlite_memory_connection()
        try:
            for conn in (source_conn, target_conn):
                conn.execute(
                    "CREATE TABLE first_table (id INTEGER PRIMARY KEY, value TEXT NOT NULL)"
                )
            source_conn.execute(
                "CREATE TABLE second_table (id INTEGER PRIMARY KEY, value TEXT NOT NULL)"
            )
            target_conn.execute(
                """
                CREATE TABLE second_table (
                    id INTEGER PRIMARY KEY,
                    value TEXT NOT NULL CHECK (value = 'accepted')
                )
                """
            )
            source_conn.execute(
                "INSERT INTO first_table (id, value) VALUES (1, 'first')"
            )
            source_conn.execute(
                "INSERT INTO second_table (id, value) VALUES (1, 'rejected')"
            )
            source_conn.commit()
            target_conn.commit()

            with self.assertRaises(sqlite3.IntegrityError):
                migration.import_table_groups_transactionally(
                    target_conn,
                    [(source_conn, ["first_table", "second_table"])],
                    batch_size=1,
                    truncate=False,
                    allow_orphan_foreign_keys=False,
                    verification={},
                )

            self.assertEqual(
                target_conn.execute("SELECT COUNT(*) FROM first_table").fetchone()[0],
                0,
            )
            self.assertEqual(
                target_conn.execute("SELECT COUNT(*) FROM second_table").fetchone()[0],
                0,
            )
        finally:
            source_conn.close()
            target_conn.close()

    def test_truncate_import_restores_existing_rows_when_transaction_fails(self):
        source_conn = sqlite_memory_connection()
        target_conn = sqlite_memory_connection()
        try:
            source_conn.execute(
                "CREATE TABLE first_table (id INTEGER PRIMARY KEY, value TEXT NOT NULL)"
            )
            source_conn.execute(
                "CREATE TABLE second_table (id INTEGER PRIMARY KEY, value TEXT NOT NULL)"
            )
            target_conn.execute(
                "CREATE TABLE first_table (id INTEGER PRIMARY KEY, value TEXT NOT NULL)"
            )
            target_conn.execute(
                """
                CREATE TABLE second_table (
                    id INTEGER PRIMARY KEY,
                    value TEXT NOT NULL CHECK (value = 'accepted')
                )
                """
            )
            source_conn.execute(
                "INSERT INTO first_table (id, value) VALUES (1, 'replacement')"
            )
            source_conn.execute(
                "INSERT INTO second_table (id, value) VALUES (1, 'rejected')"
            )
            target_conn.execute(
                "INSERT INTO first_table (id, value) VALUES (99, 'original')"
            )
            target_conn.execute(
                "INSERT INTO second_table (id, value) VALUES (99, 'accepted')"
            )
            source_conn.commit()
            target_conn.commit()

            with self.assertRaises(sqlite3.IntegrityError):
                migration.import_table_groups_transactionally(
                    target_conn,
                    [(source_conn, ["first_table", "second_table"])],
                    batch_size=1,
                    truncate=True,
                    allow_orphan_foreign_keys=False,
                    verification={},
                )

            self.assertEqual(
                [
                    tuple(row)
                    for row in target_conn.execute(
                        "SELECT id, value FROM first_table"
                    ).fetchall()
                ],
                [(99, "original")],
            )
            self.assertEqual(
                [
                    tuple(row)
                    for row in target_conn.execute(
                        "SELECT id, value FROM second_table"
                    ).fetchall()
                ],
                [(99, "accepted")],
            )
        finally:
            source_conn.close()
            target_conn.close()

    def test_orders_tables_by_foreign_key_dependencies(self):
        conn = sqlite_memory_connection()
        try:
            conn.execute("CREATE TABLE site_users (id INTEGER PRIMARY KEY)")
            conn.execute(
                """
                CREATE TABLE card_bindings (
                    id INTEGER PRIMARY KEY,
                    user_id INTEGER NOT NULL,
                    FOREIGN KEY (user_id) REFERENCES site_users(id)
                )
                """
            )
            conn.execute(
                """
                CREATE TABLE transfer_events (
                    id INTEGER PRIMARY KEY,
                    binding_id INTEGER NOT NULL,
                    FOREIGN KEY (binding_id) REFERENCES card_bindings(id)
                )
                """
            )

            ordered = migration.ordered_tables(conn)

            self.assertLess(ordered.index("site_users"), ordered.index("card_bindings"))
            self.assertLess(ordered.index("card_bindings"), ordered.index("transfer_events"))
        finally:
            conn.close()

    def test_discovers_user_extension_tables_dynamically(self):
        cards_conn = sqlite_memory_connection()
        temp_conn = sqlite_memory_connection()
        try:
            cards_conn.execute("CREATE TABLE cards (cert_id TEXT PRIMARY KEY)")
            cards_conn.execute("CREATE TABLE site_users (id INTEGER PRIMARY KEY)")
            cards_conn.execute(
                """
                CREATE TABLE card_bindings (
                    id INTEGER PRIMARY KEY,
                    user_id INTEGER NOT NULL,
                    FOREIGN KEY (user_id) REFERENCES site_users(id)
                )
                """
            )
            cards_conn.execute(
                """
                CREATE TABLE card_transfer_events (
                    id INTEGER PRIMARY KEY,
                    from_user_id INTEGER,
                    to_user_id INTEGER,
                    FOREIGN KEY (from_user_id) REFERENCES site_users(id),
                    FOREIGN KEY (to_user_id) REFERENCES site_users(id)
                )
                """
            )
            temp_conn.execute("CREATE TABLE temp_cards (id INTEGER PRIMARY KEY)")

            main_tables, temp_tables = migration.migration_table_groups(
                cards_conn, temp_conn
            )

            self.assertEqual(
                set(main_tables),
                {"cards", "site_users", "card_bindings", "card_transfer_events"},
            )
            self.assertLess(main_tables.index("site_users"), main_tables.index("card_bindings"))
            self.assertEqual(temp_tables, ["temp_cards"])
        finally:
            cards_conn.close()
            temp_conn.close()

    def test_reports_source_foreign_key_violations_without_deleting_rows(self):
        cards_conn = sqlite_memory_connection()
        temp_conn = sqlite_memory_connection()
        try:
            cards_conn.execute("CREATE TABLE cards (cert_id TEXT PRIMARY KEY)")
            cards_conn.execute(
                """
                CREATE TABLE grading_history (
                    id INTEGER PRIMARY KEY,
                    cert_id TEXT NOT NULL,
                    FOREIGN KEY (cert_id) REFERENCES cards(cert_id)
                )
                """
            )
            cards_conn.commit()
            cards_conn.execute("PRAGMA foreign_keys = OFF")
            cards_conn.execute(
                "INSERT INTO grading_history (id, cert_id) VALUES (1, 'missing')"
            )
            cards_conn.commit()
            temp_conn.execute("CREATE TABLE temp_cards (id INTEGER PRIMARY KEY)")

            violations = migration.source_foreign_key_violations(
                cards_conn, temp_conn
            )

            self.assertEqual(len(violations), 1)
            self.assertEqual(violations[0]["table"], "grading_history")
            self.assertEqual(
                cards_conn.execute("SELECT COUNT(*) FROM grading_history").fetchone()[0],
                1,
            )
        finally:
            cards_conn.close()
            temp_conn.close()

    def test_repeated_sync_is_idempotent_and_updates_rows(self):
        source_conn = sqlite_memory_connection()
        target_conn = sqlite_memory_connection()
        try:
            schema = "CREATE TABLE sample (id INTEGER PRIMARY KEY, value TEXT NOT NULL)"
            source_conn.execute(schema)
            target_conn.execute(schema)
            source_conn.executemany(
                "INSERT INTO sample (id, value) VALUES (?, ?)",
                [(1, "first"), (2, "second"), (3, "third")],
            )
            source_conn.commit()

            with mock.patch.dict(os.environ, {"NXR_DB_BACKEND": "sqlite"}, clear=False):
                self.assertEqual(
                    migration.insert_table_rows(source_conn, target_conn, "sample", 2),
                    3,
                )
                self.assertEqual(
                    migration.insert_table_rows(source_conn, target_conn, "sample", 2),
                    3,
                )
                source_conn.execute(
                    "UPDATE sample SET value = 'updated' WHERE id = 2"
                )
                source_conn.commit()
                migration.insert_table_rows(source_conn, target_conn, "sample", 2)
                verification = migration.verify_table(
                    source_conn, target_conn, "sample", 2
                )

            self.assertEqual(verification["rows"], 3)
            self.assertEqual(
                target_conn.execute(
                    "SELECT value FROM sample WHERE id = 2"
                ).fetchone()[0],
                "updated",
            )
        finally:
            source_conn.close()
            target_conn.close()

    def test_content_signature_is_order_independent_and_detects_changes(self):
        first = sqlite_memory_connection()
        second = sqlite_memory_connection()
        try:
            for conn in (first, second):
                conn.execute("CREATE TABLE sample (id INTEGER PRIMARY KEY, value TEXT)")
            first.executemany(
                "INSERT INTO sample (id, value) VALUES (?, ?)",
                [(1, "one"), (2, "two")],
            )
            second.executemany(
                "INSERT INTO sample (id, value) VALUES (?, ?)",
                [(2, "two"), (1, "one")],
            )

            columns = ["id", "value"]
            self.assertEqual(
                migration.table_content_signature(first, "sample", columns, 1),
                migration.table_content_signature(second, "sample", columns, 1),
            )

            second.execute("UPDATE sample SET value = 'changed' WHERE id = 2")
            self.assertNotEqual(
                migration.table_content_signature(first, "sample", columns, 1),
                migration.table_content_signature(second, "sample", columns, 1),
            )
        finally:
            first.close()
            second.close()

    def test_mysql_upsert_uses_supported_row_alias(self):
        conn = sqlite_memory_connection()
        try:
            conn.execute(
                """
                CREATE TABLE cache (
                    cert_id TEXT NOT NULL,
                    language TEXT NOT NULL,
                    content_json TEXT NOT NULL,
                    PRIMARY KEY (cert_id, language)
                )
                """
            )
            with mock.patch.dict(os.environ, {"NXR_DB_BACKEND": "mysql"}, clear=False):
                columns, primary_keys, sql = migration.build_upsert_sql(conn, "cache")

            self.assertEqual(columns, ["cert_id", "language", "content_json"])
            self.assertEqual(primary_keys, ["cert_id", "language"])
            self.assertIn("AS incoming ON DUPLICATE KEY UPDATE", sql)
            self.assertIn("content_json = incoming.content_json", sql)
        finally:
            conn.close()


if __name__ == "__main__":
    unittest.main()
