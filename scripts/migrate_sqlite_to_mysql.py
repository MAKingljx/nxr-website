#!/usr/bin/env python3
"""Migrate NXR SQLite databases into the configured MySQL database.

Default mode is dry-run: it reads the SQLite files and prints row counts.
Use --apply with NXR_DB_BACKEND=mysql and NXR_MYSQL_* environment variables
to create/update schema and import rows into MySQL.
"""

from __future__ import annotations

import argparse
import os
import sqlite3
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CARDS_DB = PROJECT_ROOT / "Data" / "cards.db"
DEFAULT_TEMP_DB = PROJECT_ROOT / "Data" / "temp_cards.db"

MAIN_TABLES = (
    "cards",
    "admin_users",
    "brand_settings",
    "dictionary_groups",
    "dictionary_items",
    "waitlist",
    "ai_character_cache",
    "ai_grading_details",
    "human_grading_details",
    "image_analysis",
    "grading_history",
)
TEMP_TABLES = ("temp_cards",)


def sqlite_connection(path):
    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    return conn


def table_exists(conn, table_name):
    row = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        (table_name,),
    ).fetchone()
    return row is not None


def sqlite_tables(conn):
    return [
        row["name"]
        for row in conn.execute(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name != 'sqlite_sequence' ORDER BY name"
        ).fetchall()
    ]


def table_count(conn, table_name):
    return conn.execute(f"SELECT COUNT(*) FROM {table_name}").fetchone()[0]


def table_columns(conn, table_name):
    return [row["name"] for row in conn.execute(f"PRAGMA table_info({table_name})").fetchall()]


def target_table_exists(target_conn, table_name):
    from nxr_common import db

    return bool(db.table_columns(target_conn, table_name))


def normalize_default_value(target_conn, source_type, default_value):
    from nxr_common import db

    if default_value is None:
        return None
    if not db.is_mysql_connection(target_conn):
        return default_value

    raw_default = str(default_value).strip()
    if raw_default.upper() in {"CURRENT_TIMESTAMP", "CURRENT_DATE", "CURRENT_TIME"}:
        return raw_default
    if raw_default.startswith("'") and raw_default.endswith("'"):
        return raw_default
    if raw_default.startswith('"') and raw_default.endswith('"'):
        raw_default = raw_default[1:-1].replace("'", "''")
        return f"'{raw_default}'"

    if "TEXT" in str(source_type).upper() or "CHAR" in str(source_type).upper():
        escaped = raw_default.replace("'", "''")
        return f"'{escaped}'"
    return raw_default


def source_counts(cards_conn, temp_conn):
    counts = {}
    for table_name in sqlite_tables(cards_conn):
        counts[table_name] = table_count(cards_conn, table_name)
    for table_name in sqlite_tables(temp_conn):
        counts[table_name] = table_count(temp_conn, table_name)
    return counts


def print_counts(title, counts):
    print(title)
    for table_name in sorted(counts):
        print(f"  {table_name}: {counts[table_name]}")


def ensure_extra_table_from_sqlite(target_conn, source_conn, table_name):
    from nxr_common import db

    if target_table_exists(target_conn, table_name):
        return

    columns = source_conn.execute(f"PRAGMA table_info({table_name})").fetchall()
    if not columns:
        return

    pk_columns = [row["name"] for row in columns if row["pk"]]
    definitions = []
    table_level_pk = []
    for row in columns:
        name = row["name"]
        source_type = row["type"] or "TEXT"
        is_single_integer_pk = (
            len(pk_columns) == 1
            and row["pk"]
            and "INT" in source_type.upper()
        )
        if is_single_integer_pk:
            definitions.append(f"{name} {db.auto_increment_primary_key()}")
            continue

        column_type = db.normalize_column_type(name, source_type)
        if row["notnull"]:
            column_type += " NOT NULL"
        default_value = normalize_default_value(target_conn, source_type, row["dflt_value"])
        if default_value is not None and not column_type.upper().startswith("LONGTEXT"):
            column_type += f" DEFAULT {default_value}"
        definitions.append(f"{name} {column_type}")
        if row["pk"]:
            table_level_pk.append(name)

    if table_level_pk:
        definitions.append(f"PRIMARY KEY ({', '.join(table_level_pk)})")

    target_conn.execute(
        f"CREATE TABLE IF NOT EXISTS {table_name} ({', '.join(definitions)})"
    )


def ensure_extra_indexes_from_sqlite(target_conn, source_conn, table_name):
    indexes = source_conn.execute(f"PRAGMA index_list({table_name})").fetchall()
    for index in indexes:
        index_name = index["name"]
        if index_name.startswith("sqlite_autoindex"):
            continue
        unique_sql = "UNIQUE " if index["unique"] else ""
        columns = [
            row["name"]
            for row in source_conn.execute(f"PRAGMA index_info({index_name})").fetchall()
            if row["name"]
        ]
        if not columns:
            continue
        try:
            target_conn.execute(
                f"CREATE {unique_sql}INDEX {index_name} ON {table_name} ({', '.join(columns)})"
            )
        except Exception as exc:  # noqa: BLE001
            if "Duplicate" not in str(exc) and "exists" not in str(exc):
                raise


def ensure_schema(cards_conn, temp_conn):
    from nxr_admin import admin_core
    from nxr_site import app as site_app

    admin_core.initialize_databases()
    site_app.initialize_site_database()

    with admin_core.get_main_db_connection() as target_conn:
        for table_name in sqlite_tables(cards_conn):
            ensure_extra_table_from_sqlite(target_conn, cards_conn, table_name)
            ensure_extra_indexes_from_sqlite(target_conn, cards_conn, table_name)
        target_conn.commit()

    with admin_core.get_temp_db_connection() as target_conn:
        for table_name in sqlite_tables(temp_conn):
            ensure_extra_table_from_sqlite(target_conn, temp_conn, table_name)
            ensure_extra_indexes_from_sqlite(target_conn, temp_conn, table_name)
        target_conn.commit()


def delete_target_rows(conn, table_names):
    from nxr_common import db

    if db.is_mysql_connection(conn):
        conn.execute("SET FOREIGN_KEY_CHECKS = 0")
    for table_name in reversed(table_names):
        conn.execute(f"DELETE FROM {table_name}")
    if db.is_mysql_connection(conn):
        conn.execute("SET FOREIGN_KEY_CHECKS = 1")


def insert_table_rows(source_conn, target_conn, table_name, batch_size):
    columns = table_columns(source_conn, table_name)
    if not columns:
        return 0

    column_sql = ", ".join(columns)
    placeholders = ", ".join(["?" for _ in columns])
    insert_sql = f"INSERT INTO {table_name} ({column_sql}) VALUES ({placeholders})"

    rows = source_conn.execute(f"SELECT {column_sql} FROM {table_name}").fetchall()
    inserted = 0
    for start in range(0, len(rows), batch_size):
        batch = rows[start:start + batch_size]
        for row in batch:
            target_conn.execute(insert_sql, [row[column] for column in columns])
            inserted += 1
        target_conn.commit()
    return inserted


def import_rows(cards_conn, temp_conn, batch_size, truncate):
    from nxr_admin import admin_core

    main_tables = [table for table in MAIN_TABLES if table_exists(cards_conn, table)]
    temp_tables = [table for table in TEMP_TABLES if table_exists(temp_conn, table)]

    with admin_core.get_main_db_connection() as target_conn:
        if truncate:
            delete_target_rows(target_conn, main_tables)
        for table_name in main_tables:
            inserted = insert_table_rows(cards_conn, target_conn, table_name, batch_size)
            print(f"imported {table_name}: {inserted}")

    with admin_core.get_temp_db_connection() as target_conn:
        if truncate:
            delete_target_rows(target_conn, temp_tables)
        for table_name in temp_tables:
            inserted = insert_table_rows(temp_conn, target_conn, table_name, batch_size)
            print(f"imported {table_name}: {inserted}")


def target_counts():
    from nxr_admin import admin_core

    counts = {}
    with admin_core.get_main_db_connection() as conn:
        for table_name in MAIN_TABLES:
            try:
                counts[table_name] = conn.execute(f"SELECT COUNT(*) FROM {table_name}").fetchone()[0]
            except Exception:  # noqa: BLE001
                continue
    with admin_core.get_temp_db_connection() as conn:
        for table_name in TEMP_TABLES:
            try:
                counts[table_name] = conn.execute(f"SELECT COUNT(*) FROM {table_name}").fetchone()[0]
            except Exception:  # noqa: BLE001
                continue
    return counts


def parse_args():
    parser = argparse.ArgumentParser(description="Migrate NXR SQLite data to MySQL")
    parser.add_argument("--cards-db", default=str(DEFAULT_CARDS_DB))
    parser.add_argument("--temp-db", default=str(DEFAULT_TEMP_DB))
    parser.add_argument("--batch-size", type=int, default=500)
    parser.add_argument("--apply", action="store_true", help="write to configured MySQL database")
    parser.add_argument("--truncate", action="store_true", help="delete target rows before importing")
    return parser.parse_args()


def main():
    args = parse_args()
    cards_path = Path(args.cards_db)
    temp_path = Path(args.temp_db)
    if not cards_path.exists():
        raise SystemExit(f"Missing cards DB: {cards_path}")
    if not temp_path.exists():
        raise SystemExit(f"Missing temp DB: {temp_path}")

    with sqlite_connection(cards_path) as cards_conn, sqlite_connection(temp_path) as temp_conn:
        counts = source_counts(cards_conn, temp_conn)
        print_counts("source_counts", counts)

        if not args.apply:
            print("dry_run=1")
            print("Set NXR_DB_BACKEND=mysql and pass --apply to import into MySQL.")
            return

        os.environ["NXR_DB_BACKEND"] = "mysql"
        from nxr_common import db

        if db.current_backend() != db.MYSQL_BACKEND:
            raise SystemExit("NXR_DB_BACKEND=mysql is required with --apply")

        ensure_schema(cards_conn, temp_conn)
        import_rows(cards_conn, temp_conn, args.batch_size, args.truncate)
        print_counts("target_counts", target_counts())


if __name__ == "__main__":
    main()
