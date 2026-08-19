#!/usr/bin/env python3
"""Migrate NXR SQLite databases into the configured MySQL database.

Default mode is dry-run: it reads the SQLite files and prints row counts.
Use --apply with NXR_DB_BACKEND=mysql and NXR_MYSQL_* environment variables
to create/update schema and import rows into MySQL.
"""

from __future__ import annotations

import argparse
import datetime as dt
import decimal
import hashlib
import json
import math
import os
import sqlite3
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

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
TRUNCATE_CONFIRMATION = "DELETE_TARGET_DATA"


def quote_identifier(name):
    if not isinstance(name, str) or not name or "\x00" in name:
        raise ValueError(f"Invalid SQL identifier: {name!r}")
    return f"`{name.replace('`', '``')}`"


def quote_sqlite_identifier(name):
    if not isinstance(name, str) or not name or "\x00" in name:
        raise ValueError(f"Invalid SQLite identifier: {name!r}")
    escaped = name.replace('"', '""')
    return f'"{escaped}"'


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
    return conn.execute(
        f"SELECT COUNT(*) FROM {quote_identifier(table_name)}"
    ).fetchone()[0]


def table_columns(conn, table_name):
    return [
        row["name"]
        for row in conn.execute(
            f"PRAGMA table_info({quote_sqlite_identifier(table_name)})"
        ).fetchall()
    ]


def table_primary_key_columns(conn, table_name):
    columns = conn.execute(
        f"PRAGMA table_info({quote_sqlite_identifier(table_name)})"
    ).fetchall()
    return [
        row["name"]
        for row in sorted(columns, key=lambda row: row["pk"] or 0)
        if row["pk"]
    ]


def table_dependencies(conn, table_name):
    rows = conn.execute(
        f"PRAGMA foreign_key_list({quote_sqlite_identifier(table_name)})"
    ).fetchall()
    return {row["table"] for row in rows}


def ordered_tables(conn):
    tables = sqlite_tables(conn)
    remaining = set(tables)
    dependencies = {
        table_name: table_dependencies(conn, table_name) & remaining
        for table_name in tables
    }
    ordered = []

    while remaining:
        ready = sorted(
            table_name
            for table_name in remaining
            if not (dependencies[table_name] & remaining)
        )
        if not ready:
            cycle = ", ".join(sorted(remaining))
            raise RuntimeError(f"Foreign-key dependency cycle detected: {cycle}")
        ordered.extend(ready)
        remaining.difference_update(ready)

    return ordered


def migration_table_groups(cards_conn, temp_conn):
    main_tables = ordered_tables(cards_conn)
    temp_tables = ordered_tables(temp_conn)
    overlap = sorted(set(main_tables) & set(temp_tables))
    if overlap:
        raise RuntimeError(
            "Source databases contain overlapping table names: " + ", ".join(overlap)
        )
    return main_tables, temp_tables


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


def source_foreign_key_violations(cards_conn, temp_conn):
    violations = []
    for database_name, conn in (("cards", cards_conn), ("temp", temp_conn)):
        for row in conn.execute("PRAGMA foreign_key_check").fetchall():
            violations.append(
                {
                    "database": database_name,
                    "table": row[0],
                    "rowid": row[1],
                    "parent": row[2],
                    "foreign_key_id": row[3],
                }
            )
    return violations


def print_counts(title, counts):
    print(title)
    for table_name in sorted(counts):
        print(f"  {table_name}: {counts[table_name]}")


def ensure_extra_table_from_sqlite(target_conn, source_conn, table_name):
    from nxr_common import db

    if target_table_exists(target_conn, table_name):
        return

    columns = source_conn.execute(
        f"PRAGMA table_info({quote_sqlite_identifier(table_name)})"
    ).fetchall()
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
            definitions.append(
                f"{quote_identifier(name)} {db.auto_increment_primary_key()}"
            )
            continue

        column_type = db.normalize_column_type(name, source_type)
        if row["notnull"]:
            column_type += " NOT NULL"
        default_value = normalize_default_value(target_conn, source_type, row["dflt_value"])
        if default_value is not None and not column_type.upper().startswith("LONGTEXT"):
            column_type += f" DEFAULT {default_value}"
        definitions.append(f"{quote_identifier(name)} {column_type}")
        if row["pk"]:
            table_level_pk.append(name)

    if table_level_pk:
        definitions.append(
            "PRIMARY KEY ({})".format(
                ", ".join(quote_identifier(name) for name in table_level_pk)
            )
        )

    foreign_keys = {}
    for row in source_conn.execute(
        f"PRAGMA foreign_key_list({quote_sqlite_identifier(table_name)})"
    ).fetchall():
        foreign_keys.setdefault(row["id"], []).append(row)

    for rows in foreign_keys.values():
        rows.sort(key=lambda row: row["seq"])
        source_columns = ", ".join(quote_identifier(row["from"]) for row in rows)
        target_columns = ", ".join(quote_identifier(row["to"]) for row in rows)
        definition = (
            f"FOREIGN KEY ({source_columns}) REFERENCES "
            f"{quote_identifier(rows[0]['table'])} ({target_columns})"
        )
        on_delete = rows[0]["on_delete"]
        on_update = rows[0]["on_update"]
        if on_delete and on_delete.upper() != "NO ACTION":
            definition += f" ON DELETE {on_delete}"
        if on_update and on_update.upper() != "NO ACTION":
            definition += f" ON UPDATE {on_update}"
        definitions.append(definition)

    table_options = (
        " ENGINE=InnoDB"
        if db.is_mysql_connection(target_conn)
        else ""
    )
    target_conn.execute(
        f"CREATE TABLE IF NOT EXISTS {quote_identifier(table_name)} "
        f"({', '.join(definitions)}){table_options}"
    )


def generated_index_name(table_name, columns, unique):
    prefix = "uq" if unique else "idx"
    readable = f"{prefix}_{table_name}_{'_'.join(columns)}"
    if len(readable) <= 64:
        return readable
    digest = hashlib.sha1(readable.encode("utf-8")).hexdigest()[:10]
    return f"{readable[:53]}_{digest}"


def ensure_extra_indexes_from_sqlite(target_conn, source_conn, table_name):
    indexes = source_conn.execute(
        f"PRAGMA index_list({quote_sqlite_identifier(table_name)})"
    ).fetchall()
    for index in indexes:
        index_name = index["name"]
        index_origin = index["origin"] if "origin" in index.keys() else "c"
        if index_origin == "pk":
            continue
        is_partial = bool(index["partial"]) if "partial" in index.keys() else False
        if is_partial:
            print(f"skipped partial index {index_name} on {table_name}")
            continue
        unique_sql = "UNIQUE " if index["unique"] else ""
        index_rows = source_conn.execute(
            f"PRAGMA index_xinfo({quote_sqlite_identifier(index_name)})"
        ).fetchall()
        key_rows = [
            row
            for row in index_rows
            if "key" not in row.keys() or row["key"]
        ]
        if not key_rows or any(not row["name"] for row in key_rows):
            print(f"skipped expression index {index_name} on {table_name}")
            continue
        columns = [row["name"] for row in key_rows]
        if index_name.startswith("sqlite_autoindex"):
            index_name = generated_index_name(table_name, columns, bool(index["unique"]))
        column_sql = ", ".join(
            f"{quote_identifier(row['name'])}{' DESC' if row['desc'] else ''}"
            for row in key_rows
        )
        try:
            target_conn.execute(
                f"CREATE {unique_sql}INDEX IF NOT EXISTS {quote_identifier(index_name)} "
                f"ON {quote_identifier(table_name)} ({column_sql})"
            )
        except Exception as exc:  # noqa: BLE001
            if "Duplicate" not in str(exc) and "exists" not in str(exc):
                raise


def ensure_schema(cards_conn, temp_conn):
    from nxr_admin import admin_core
    from nxr_site import app as site_app

    admin_core.initialize_databases()
    site_app.initialize_site_database()

    main_tables, temp_tables = migration_table_groups(cards_conn, temp_conn)

    with admin_core.get_main_db_connection() as target_conn:
        for table_name in main_tables:
            ensure_extra_table_from_sqlite(target_conn, cards_conn, table_name)
            ensure_extra_indexes_from_sqlite(target_conn, cards_conn, table_name)
        target_conn.commit()

    with admin_core.get_temp_db_connection() as target_conn:
        for table_name in temp_tables:
            ensure_extra_table_from_sqlite(target_conn, temp_conn, table_name)
            ensure_extra_indexes_from_sqlite(target_conn, temp_conn, table_name)
        target_conn.commit()


def delete_target_rows(conn, table_names):
    for table_name in reversed(table_names):
        conn.execute(f"DELETE FROM {quote_identifier(table_name)}")


def build_upsert_sql(source_conn, table_name):
    from nxr_common import db

    columns = table_columns(source_conn, table_name)
    primary_keys = table_primary_key_columns(source_conn, table_name)
    if not primary_keys:
        raise RuntimeError(
            f"Table {table_name} has no primary key; idempotent migration is unsafe"
        )

    column_sql = ", ".join(quote_identifier(column) for column in columns)
    placeholders = ", ".join(["?" for _ in columns])
    return (
        columns,
        primary_keys,
        f"INSERT INTO {quote_identifier(table_name)} ({column_sql}) "
        f"VALUES ({placeholders}) {db.upsert_clause(primary_keys, columns)}",
    )


def insert_table_rows(source_conn, target_conn, table_name, batch_size):
    columns, primary_keys, insert_sql = build_upsert_sql(source_conn, table_name)
    column_sql = ", ".join(quote_identifier(column) for column in columns)
    order_sql = ", ".join(quote_identifier(column) for column in primary_keys)
    source_cursor = source_conn.execute(
        f"SELECT {column_sql} FROM {quote_identifier(table_name)} ORDER BY {order_sql}"
    )
    target_cursor = target_conn.cursor()
    synced = 0

    while True:
        rows = source_cursor.fetchmany(batch_size)
        if not rows:
            break
        params = [tuple(row[column] for column in columns) for row in rows]
        target_cursor.executemany(insert_sql, params)
        synced += len(rows)

    source_cursor.close()
    target_cursor.close()
    return synced


def normalize_digest_value(value):
    if isinstance(value, dt.datetime):
        return value.isoformat(sep=" ")
    if isinstance(value, (dt.date, dt.time)):
        return value.isoformat()
    if isinstance(value, decimal.Decimal):
        return format(value, "f")
    if isinstance(value, float):
        if math.isnan(value) or math.isinf(value):
            return repr(value)
        return format(value, ".15g")
    if isinstance(value, (bytes, bytearray, memoryview)):
        return bytes(value).hex()
    return value


def table_content_signature(conn, table_name, columns, batch_size):
    column_sql = ", ".join(quote_identifier(column) for column in columns)
    cursor = conn.execute(
        f"SELECT {column_sql} FROM {quote_identifier(table_name)}"
    )
    xor_digest = 0
    sum_digest = 0
    row_count = 0
    modulus = 1 << 256

    while True:
        rows = cursor.fetchmany(batch_size)
        if not rows:
            break
        for row in rows:
            payload = json.dumps(
                [normalize_digest_value(value) for value in row],
                ensure_ascii=False,
                separators=(",", ":"),
            ).encode("utf-8")
            digest_value = int.from_bytes(hashlib.sha256(payload).digest(), "big")
            xor_digest ^= digest_value
            sum_digest = (sum_digest + digest_value) % modulus
            row_count += 1

    cursor.close()
    return f"{row_count}:{xor_digest:064x}:{sum_digest:064x}"


def verify_table(source_conn, target_conn, table_name, batch_size):
    columns = table_columns(source_conn, table_name)
    source_count = table_count(source_conn, table_name)
    target_count = target_conn.execute(
        f"SELECT COUNT(*) FROM {quote_identifier(table_name)}"
    ).fetchone()[0]
    if source_count != target_count:
        raise RuntimeError(
            f"Count mismatch for {table_name}: source={source_count}, target={target_count}"
        )

    source_signature = table_content_signature(
        source_conn, table_name, columns, batch_size
    )
    target_signature = table_content_signature(
        target_conn, table_name, columns, batch_size
    )
    if source_signature != target_signature:
        raise RuntimeError(f"Content mismatch for {table_name}")

    return {
        "rows": source_count,
        "signature": source_signature,
    }


def assert_transactional_tables(target_conn, table_names):
    from nxr_common import db

    if not db.is_mysql_connection(target_conn):
        return

    database_name = target_conn.execute("SELECT DATABASE()").fetchone()[0]
    placeholders = ", ".join("?" for _ in table_names)
    rows = target_conn.execute(
        f"""
        SELECT TABLE_NAME, ENGINE
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = ? AND TABLE_NAME IN ({placeholders})
        """,
        (database_name, *table_names),
    ).fetchall()
    engines = {row[0]: (row[1] or "").upper() for row in rows}
    missing = sorted(set(table_names) - set(engines))
    non_transactional = sorted(
        table_name
        for table_name, engine in engines.items()
        if engine != "INNODB"
    )
    if missing or non_transactional:
        details = []
        if missing:
            details.append("missing=" + ",".join(missing))
        if non_transactional:
            details.append(
                "non_innodb="
                + ",".join(
                    f"{table_name}:{engines[table_name]}"
                    for table_name in non_transactional
                )
            )
        raise RuntimeError(
            "MySQL migration requires transactional InnoDB tables ("
            + "; ".join(details)
            + ")"
        )


def import_table_groups_transactionally(
    target_conn,
    source_groups,
    batch_size,
    truncate,
    allow_orphan_foreign_keys,
    verification,
):
    from nxr_common import db

    table_names = [
        table_name
        for _, group_table_names in source_groups
        for table_name in group_table_names
    ]
    assert_transactional_tables(target_conn, table_names)

    disable_foreign_keys = db.is_mysql_connection(target_conn) and (
        truncate or allow_orphan_foreign_keys
    )
    if disable_foreign_keys:
        target_conn.execute("SET FOREIGN_KEY_CHECKS = 0")

    try:
        with db.transaction(target_conn):
            if truncate:
                delete_target_rows(target_conn, table_names)
            for source_conn, group_table_names in source_groups:
                for table_name in group_table_names:
                    synced = insert_table_rows(
                        source_conn, target_conn, table_name, batch_size
                    )
                    verification[table_name] = verify_table(
                        source_conn, target_conn, table_name, batch_size
                    )
                    print(f"synced {table_name}: {synced}; verified=1")
    finally:
        if disable_foreign_keys:
            target_conn.execute("SET FOREIGN_KEY_CHECKS = 1")
            target_conn.commit()


def import_rows(
    cards_conn,
    temp_conn,
    batch_size,
    truncate,
    allow_orphan_foreign_keys=False,
):
    from nxr_common import db
    from nxr_admin import admin_core

    main_tables, temp_tables = migration_table_groups(cards_conn, temp_conn)
    verification = {}

    if db.is_mysql_backend():
        with admin_core.get_main_db_connection() as target_conn:
            import_table_groups_transactionally(
                target_conn,
                [(cards_conn, main_tables), (temp_conn, temp_tables)],
                batch_size,
                truncate,
                allow_orphan_foreign_keys,
                verification,
            )
    else:
        with admin_core.get_main_db_connection() as target_conn:
            import_table_groups_transactionally(
                target_conn,
                [(cards_conn, main_tables)],
                batch_size,
                truncate,
                False,
                verification,
            )
        with admin_core.get_temp_db_connection() as target_conn:
            import_table_groups_transactionally(
                target_conn,
                [(temp_conn, temp_tables)],
                batch_size,
                truncate,
                False,
                verification,
            )

    return verification


def target_counts(main_tables, temp_tables):
    from nxr_admin import admin_core

    counts = {}
    with admin_core.get_main_db_connection() as conn:
        for table_name in main_tables:
            try:
                counts[table_name] = conn.execute(
                    f"SELECT COUNT(*) FROM {quote_identifier(table_name)}"
                ).fetchone()[0]
            except Exception:  # noqa: BLE001
                continue
    with admin_core.get_temp_db_connection() as conn:
        for table_name in temp_tables:
            try:
                counts[table_name] = conn.execute(
                    f"SELECT COUNT(*) FROM {quote_identifier(table_name)}"
                ).fetchone()[0]
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
    parser.add_argument(
        "--confirm-truncate",
        default="",
        help=f"required with --truncate; must equal {TRUNCATE_CONFIRMATION}",
    )
    parser.add_argument(
        "--allow-orphan-foreign-keys",
        action="store_true",
        help="preserve source rows that reference missing parents",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    if args.batch_size < 1:
        raise SystemExit("--batch-size must be at least 1")
    if args.truncate and args.confirm_truncate != TRUNCATE_CONFIRMATION:
        raise SystemExit(
            f"--truncate requires --confirm-truncate {TRUNCATE_CONFIRMATION}"
        )

    cards_path = Path(args.cards_db)
    temp_path = Path(args.temp_db)
    if not cards_path.exists():
        raise SystemExit(f"Missing cards DB: {cards_path}")
    if not temp_path.exists():
        raise SystemExit(f"Missing temp DB: {temp_path}")

    with sqlite_connection(cards_path) as cards_conn, sqlite_connection(temp_path) as temp_conn:
        main_tables, temp_tables = migration_table_groups(cards_conn, temp_conn)
        counts = source_counts(cards_conn, temp_conn)
        foreign_key_violations = source_foreign_key_violations(cards_conn, temp_conn)
        print_counts("source_counts", counts)
        print("main_tables=" + ",".join(main_tables))
        print("temp_tables=" + ",".join(temp_tables))
        print(f"source_foreign_key_violations={len(foreign_key_violations)}")

        if not args.apply:
            print("dry_run=1")
            print("Set NXR_DB_BACKEND=mysql and pass --apply to import into MySQL.")
            return

        if foreign_key_violations and not args.allow_orphan_foreign_keys:
            affected_tables = sorted(
                {violation["table"] for violation in foreign_key_violations}
            )
            raise SystemExit(
                "Source contains foreign-key violations in {}. Re-run only after review "
                "with --allow-orphan-foreign-keys to preserve them.".format(
                    ", ".join(affected_tables)
                )
            )

        os.environ["NXR_DB_BACKEND"] = "mysql"
        from nxr_common import db

        if db.current_backend() != db.MYSQL_BACKEND:
            raise SystemExit("NXR_DB_BACKEND=mysql is required with --apply")

        ensure_schema(cards_conn, temp_conn)
        verification = import_rows(
            cards_conn,
            temp_conn,
            args.batch_size,
            args.truncate,
            allow_orphan_foreign_keys=args.allow_orphan_foreign_keys,
        )
        print_counts("target_counts", target_counts(main_tables, temp_tables))
        print(f"verified_tables={len(verification)}")
        print("verification=ok")


if __name__ == "__main__":
    main()
