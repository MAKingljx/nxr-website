import os
import sqlite3
import unittest
from unittest import mock

from nxr_common import db


class MysqlCompatTests(unittest.TestCase):
    def test_explicit_transaction_commits_or_rolls_back(self):
        class FakeMysqlConnection:
            backend = db.MYSQL_BACKEND

            def __init__(self):
                self.events = []

            def begin(self):
                self.events.append("begin")

            def commit(self):
                self.events.append("commit")

            def rollback(self):
                self.events.append("rollback")

        successful = FakeMysqlConnection()
        with db.transaction(successful):
            successful.events.append("write")
        self.assertEqual(successful.events, ["begin", "write", "commit"])

        failed = FakeMysqlConnection()
        with self.assertRaisesRegex(RuntimeError, "failed write"):
            with db.transaction(failed):
                failed.events.append("write")
                raise RuntimeError("failed write")
        self.assertEqual(failed.events, ["begin", "write", "rollback"])

    def test_grade_expression_avoids_driver_percent_placeholders(self):
        from nxr_admin.admin_core import canonical_grade_sql_expression

        expression = canonical_grade_sql_expression("grade")
        self.assertNotIn("%", expression)

        conn = sqlite3.connect(":memory:")
        try:
            row = conn.execute(
                f"SELECT {expression} FROM (SELECT 'Pristine-10' AS grade)"
            ).fetchone()
        finally:
            conn.close()
        self.assertEqual(row[0], "Pristine 10")

    def test_translate_qmark_placeholders_preserves_literals(self):
        sql = "SELECT '?' AS literal, name FROM cards WHERE cert_id = ? AND note = \"?\""

        with mock.patch.dict(os.environ, {"NXR_DB_BACKEND": "mysql"}, clear=False):
            translated = db.translate_sql(sql)

        self.assertEqual(
            translated,
            "SELECT '?' AS literal, name FROM cards WHERE cert_id = %s AND note = \"?\"",
        )

    def test_translate_sqlite_dialect_for_mysql(self):
        with mock.patch.dict(os.environ, {"NXR_DB_BACKEND": "mysql"}, clear=False):
            self.assertEqual(
                db.translate_sql("BEGIN IMMEDIATE"),
                "START TRANSACTION",
            )
            self.assertEqual(
                db.translate_sql(
                    "SELECT last_insert_rowid(), username COLLATE NOCASE FROM admin_users WHERE id = ?"
                ),
                "SELECT LAST_INSERT_ID(), username  FROM admin_users WHERE id = %s",
            )
            self.assertEqual(
                db.translate_sql("INSERT OR REPLACE INTO ai_character_cache (k) VALUES (?)"),
                "REPLACE INTO ai_character_cache (k) VALUES (%s)",
            )
            self.assertEqual(
                db.translate_sql(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_admin_users_username ON admin_users (username)"
                ),
                "CREATE UNIQUE INDEX idx_admin_users_username ON admin_users (username)",
            )

    def test_mysql_column_type_normalization(self):
        with mock.patch.dict(os.environ, {"NXR_DB_BACKEND": "mysql"}, clear=False):
            self.assertEqual(
                db.column_definition("id", "INTEGER PRIMARY KEY AUTOINCREMENT"),
                "id INT AUTO_INCREMENT PRIMARY KEY",
            )
            self.assertEqual(
                db.column_definition("front_image", "TEXT"),
                "front_image VARCHAR(1024)",
            )
            self.assertEqual(
                db.column_definition("cert_id", "TEXT"),
                "cert_id VARCHAR(64)",
            )
            self.assertEqual(
                db.column_definition("final_grade_text", "TEXT DEFAULT ''"),
                "final_grade_text VARCHAR(32) DEFAULT ''",
            )
            self.assertEqual(
                db.column_definition("analysis_data", "TEXT DEFAULT '{}'"),
                "analysis_data LONGTEXT",
            )

    def test_upsert_clause_switches_backend(self):
        with mock.patch.dict(os.environ, {"NXR_DB_BACKEND": "sqlite"}, clear=False):
            self.assertEqual(
                db.upsert_clause("cert_id", ["cert_id", "name", "grade"]),
                "ON CONFLICT(cert_id) DO UPDATE SET name = excluded.name, grade = excluded.grade",
            )

        with mock.patch.dict(os.environ, {"NXR_DB_BACKEND": "mysql"}, clear=False):
            self.assertEqual(
                db.upsert_clause("cert_id", ["cert_id", "name", "grade"]),
                "AS incoming ON DUPLICATE KEY UPDATE name = incoming.name, grade = incoming.grade",
            )

            self.assertEqual(
                db.upsert_clause(
                    ["cert_id", "language"],
                    ["cert_id", "language", "content_json"],
                ),
                "AS incoming ON DUPLICATE KEY UPDATE content_json = incoming.content_json",
            )

    def test_compat_row_keeps_sqlite_row_access_patterns(self):
        row = db.CompatRow(["id", "username"], [7, "admin"])

        self.assertEqual(row[0], 7)
        self.assertEqual(row["username"], "admin")
        self.assertEqual(tuple(row), (7, "admin"))
        self.assertEqual(dict(row), {"id": 7, "username": "admin"})
        self.assertEqual(row.get("missing", "fallback"), "fallback")


if __name__ == "__main__":
    unittest.main()
