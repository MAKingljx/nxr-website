"""Small database compatibility layer for SQLite and MySQL.

SQLite remains the default backend. MySQL is enabled only when
``NXR_DB_BACKEND=mysql`` is present in the environment.
"""

from __future__ import annotations

import os
import re
import sqlite3
from pathlib import Path

try:  # Optional dependency used only when NXR_DB_BACKEND=mysql.
    import pymysql
except ImportError:  # pragma: no cover - exercised by configuration checks.
    pymysql = None


SQLITE_BACKEND = "sqlite"
MYSQL_BACKEND = "mysql"
SUPPORTED_BACKENDS = {SQLITE_BACKEND, MYSQL_BACKEND}


class DatabaseConfigError(RuntimeError):
    """Raised when the selected database backend is not configured."""


if pymysql is not None:
    DatabaseError = (sqlite3.Error, pymysql.MySQLError)
    IntegrityError = (sqlite3.IntegrityError, pymysql.IntegrityError)
else:
    DatabaseError = (sqlite3.Error,)
    IntegrityError = (sqlite3.IntegrityError,)


def current_backend():
    backend = os.environ.get("NXR_DB_BACKEND", SQLITE_BACKEND).strip().lower()
    if backend not in SUPPORTED_BACKENDS:
        raise DatabaseConfigError(f"Unsupported NXR_DB_BACKEND: {backend}")
    return backend


def is_mysql_backend():
    return current_backend() == MYSQL_BACKEND


def is_mysql_connection(conn):
    return getattr(conn, "backend", SQLITE_BACKEND) == MYSQL_BACKEND


def sqlite_connect(path):
    conn = sqlite3.connect(Path(path))
    conn.row_factory = sqlite3.Row
    return conn


def mysql_connect():
    if pymysql is None:
        raise DatabaseConfigError(
            "NXR_DB_BACKEND=mysql requires PyMySQL. Install it with "
            "`python3 -m pip install PyMySQL`."
        )

    database = os.environ.get("NXR_MYSQL_DATABASE") or os.environ.get("MYSQL_DATABASE")
    if not database:
        raise DatabaseConfigError("NXR_MYSQL_DATABASE is required for MySQL backend")

    port_value = os.environ.get("NXR_MYSQL_PORT") or os.environ.get("MYSQL_PORT") or "3306"
    return CompatConnection(
        pymysql.connect(
            host=os.environ.get("NXR_MYSQL_HOST") or os.environ.get("MYSQL_HOST") or "127.0.0.1",
            port=int(port_value),
            user=os.environ.get("NXR_MYSQL_USER") or os.environ.get("MYSQL_USER") or "nxr",
            password=os.environ.get("NXR_MYSQL_PASSWORD") or os.environ.get("MYSQL_PASSWORD") or "",
            database=database,
            charset=os.environ.get("NXR_MYSQL_CHARSET", "utf8mb4"),
            autocommit=False,
        )
    )


def connect(sqlite_path=None):
    if is_mysql_backend():
        return mysql_connect()
    if sqlite_path is None:
        raise DatabaseConfigError("sqlite_path is required for SQLite backend")
    return sqlite_connect(sqlite_path)


class CompatRow:
    """Row object that supports both index and mapping access."""

    def __init__(self, columns, values):
        self._columns = list(columns)
        self._values = tuple(values)
        self._data = {column: value for column, value in zip(self._columns, self._values)}

    def __getitem__(self, key):
        if isinstance(key, int):
            return self._values[key]
        return self._data[key]

    def __iter__(self):
        return iter(self._values)

    def __len__(self):
        return len(self._values)

    def __contains__(self, key):
        return key in self._data

    def keys(self):
        return self._data.keys()

    def get(self, key, default=None):
        return self._data.get(key, default)

    def items(self):
        return self._data.items()

    def values(self):
        return self._values


class CompatCursor:
    def __init__(self, raw_cursor):
        self.raw_cursor = raw_cursor
        self._columns = []

    @property
    def rowcount(self):
        return self.raw_cursor.rowcount

    @property
    def lastrowid(self):
        return self.raw_cursor.lastrowid

    def execute(self, sql, params=None):
        original_sql = sql
        sql = translate_sql(sql)
        try:
            self.raw_cursor.execute(sql, params or ())
        except DatabaseError as exc:
            if should_ignore_mysql_duplicate_index(original_sql, exc):
                self._columns = []
                return self
            raise
        self._columns = [
            description[0]
            for description in (self.raw_cursor.description or [])
        ]
        return self

    def fetchone(self):
        row = self.raw_cursor.fetchone()
        if row is None:
            return None
        return CompatRow(self._columns, row)

    def fetchall(self):
        return [
            CompatRow(self._columns, row)
            for row in self.raw_cursor.fetchall()
        ]

    def close(self):
        self.raw_cursor.close()


class CompatConnection:
    backend = MYSQL_BACKEND

    def __init__(self, raw_connection):
        self.raw_connection = raw_connection

    def cursor(self):
        return CompatCursor(self.raw_connection.cursor())

    def execute(self, sql, params=None):
        cursor = self.cursor()
        return cursor.execute(sql, params)

    def commit(self):
        self.raw_connection.commit()

    def rollback(self):
        self.raw_connection.rollback()

    def close(self):
        self.raw_connection.close()

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        if exc_type is None:
            self.commit()
        else:
            self.rollback()
        self.close()
        return False


def replace_qmark_placeholders(sql):
    result = []
    in_single = False
    in_double = False
    escaped = False
    for char in sql:
        if char == "\\" and (in_single or in_double):
            escaped = not escaped
            result.append(char)
            continue
        if char == "'" and not in_double and not escaped:
            in_single = not in_single
        elif char == '"' and not in_single and not escaped:
            in_double = not in_double
        if char == "?" and not in_single and not in_double:
            result.append("%s")
        else:
            result.append(char)
        escaped = False
    return "".join(result)


def translate_sql(sql):
    if not is_mysql_backend():
        return sql

    converted = sql
    converted = re.sub(r"\bCOLLATE\s+NOCASE\b", "", converted, flags=re.IGNORECASE)
    converted = re.sub(
        r"\bINSERT\s+OR\s+REPLACE\s+INTO\b",
        "REPLACE INTO",
        converted,
        flags=re.IGNORECASE,
    )
    converted = re.sub(
        r"^\s*BEGIN\s+IMMEDIATE\s*$",
        "START TRANSACTION",
        converted,
        flags=re.IGNORECASE,
    )
    converted = re.sub(
        r"\bCREATE\s+(UNIQUE\s+)?INDEX\s+IF\s+NOT\s+EXISTS\s+",
        lambda match: "CREATE " + (match.group(1) or "") + "INDEX ",
        converted,
        flags=re.IGNORECASE,
    )
    converted = re.sub(
        r"\blast_insert_rowid\s*\(\s*\)",
        "LAST_INSERT_ID()",
        converted,
        flags=re.IGNORECASE,
    )
    return replace_qmark_placeholders(converted)


def should_ignore_mysql_duplicate_index(sql, exc):
    if not is_mysql_backend():
        return False
    if not re.search(r"\bCREATE\s+(UNIQUE\s+)?INDEX\s+IF\s+NOT\s+EXISTS\b", sql, re.IGNORECASE):
        return False
    code = getattr(exc, "args", [None])[0]
    return code in {1061}


def auto_increment_primary_key():
    if is_mysql_backend():
        return "INT AUTO_INCREMENT PRIMARY KEY"
    return "INTEGER PRIMARY KEY AUTOINCREMENT"


def varchar(length=255):
    return f"VARCHAR({length})"


LONG_TEXT_COLUMNS = {
    "action_data",
    "ai_back_analysis",
    "ai_front_analysis",
    "analysis_data",
    "back_analysis_json",
    "content_json",
    "decision_notes",
    "entry_notes",
    "error_message",
    "front_analysis_json",
    "rendered_html",
    "server_response",
}

WIDE_VARCHAR_COLUMNS = {
    "aliases": 1000,
    "description": 500,
    "published_back_image": 1024,
    "published_front_image": 1024,
    "front_image": 1024,
    "back_image": 1024,
    "image": 1024,
    "qr_url": 1024,
}

SHORT_VARCHAR_COLUMNS = {
    "approved_at": 32,
    "card_category": 32,
    "card_number": 128,
    "cert_id": 64,
    "code": 64,
    "created_at": 32,
    "entry_date": 32,
    "film_type": 64,
    "final_grade_text": 32,
    "language": 32,
    "last_login": 32,
    "name": 191,
    "release_year": 32,
    "role": 32,
    "sports_type": 64,
    "status": 32,
    "updated_at": 32,
    "upload_completed": 32,
    "upload_started": 32,
    "upload_status": 32,
    "username": 191,
    "value": 191,
}


def normalize_column_type(column_name, column_type):
    if not is_mysql_backend():
        return column_type

    raw = " ".join(str(column_type).strip().split())
    upper = raw.upper()
    lower_name = column_name.lower()

    if "INTEGER PRIMARY KEY AUTOINCREMENT" in upper:
        return re.sub(
            r"INTEGER\s+PRIMARY\s+KEY\s+AUTOINCREMENT",
            "INT AUTO_INCREMENT PRIMARY KEY",
            raw,
            flags=re.IGNORECASE,
        )

    if upper.startswith("INTEGER"):
        return re.sub(r"^INTEGER\b", "INT", raw, flags=re.IGNORECASE)

    if upper.startswith("REAL"):
        return re.sub(r"^REAL\b", "DOUBLE", raw, flags=re.IGNORECASE)

    if upper.startswith("TEXT"):
        suffix = raw[4:].strip()
        if lower_name in LONG_TEXT_COLUMNS:
            suffix = re.sub(
                r"(^|\s+)DEFAULT\s+('[^']*'|\"[^\"]*\"|[^\s,]+)",
                "",
                suffix,
                flags=re.IGNORECASE,
            )
            return f"LONGTEXT {suffix}".strip()
        length = SHORT_VARCHAR_COLUMNS.get(lower_name, WIDE_VARCHAR_COLUMNS.get(lower_name, 255))
        return f"VARCHAR({length}) {suffix}".strip()

    return raw


def column_definition(column_name, column_type):
    return f"{column_name} {normalize_column_type(column_name, column_type)}"


def table_columns(conn, table_name):
    if is_mysql_connection(conn):
        row = conn.execute("SELECT DATABASE()").fetchone()
        database_name = row[0]
        rows = conn.execute(
            """
            SELECT COLUMN_NAME
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            """,
            (database_name, table_name),
        ).fetchall()
        return {row[0] for row in rows}

    return {
        row[1]
        for row in conn.execute(f"PRAGMA table_info({table_name})").fetchall()
    }


def upsert_clause(primary_key, columns):
    update_columns = [column for column in columns if column != primary_key]
    if is_mysql_backend():
        return "ON DUPLICATE KEY UPDATE " + ", ".join(
            f"{column} = VALUES({column})" for column in update_columns
        )
    return "ON CONFLICT({}) DO UPDATE SET {}".format(
        primary_key,
        ", ".join(f"{column} = excluded.{column}" for column in update_columns),
    )
