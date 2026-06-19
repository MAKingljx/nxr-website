#!/usr/bin/env python3
import argparse
from pathlib import Path
import sys


PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from nxr_admin import admin_core  # noqa: E402


def get_connection():
    return admin_core.get_main_db_connection()


def ensure_table(conn):
    admin_core.ensure_admin_users_table(conn)


def add_or_update_user(username, password, role, email, is_active):
    with get_connection() as conn:
        ensure_table(conn)
        password_hash = admin_core.hash_admin_password(password)
        role = admin_core.normalize_admin_role(role, default='admin')
        existing = conn.execute(
            'SELECT id FROM admin_users WHERE username = ? COLLATE NOCASE',
            (username,),
        ).fetchone()

        if existing:
            conn.execute('''
                UPDATE admin_users
                SET username = ?, password_hash = ?, email = ?, role = ?, is_active = ?
                WHERE id = ?
            ''', (username, password_hash, email, role, is_active, existing['id']))
            action = 'updated'
        else:
            conn.execute('''
                INSERT INTO admin_users (username, password_hash, email, role, is_active)
                VALUES (?, ?, ?, ?, ?)
            ''', (username, password_hash, email, role, is_active))
            action = 'created'

        conn.commit()
    print(f'{action}: {username}')


def list_users():
    with get_connection() as conn:
        ensure_table(conn)
        rows = conn.execute('''
            SELECT username, email, role, is_active, created_at, last_login
            FROM admin_users
            ORDER BY username COLLATE NOCASE
        ''').fetchall()

    for row in rows:
        status = 'active' if row['is_active'] else 'disabled'
        print(
            f"{row['username']}\t{row['role']}\t{status}\t"
            f"{row['email'] or '-'}\tcreated={row['created_at']}\tlast_login={row['last_login'] or '-'}"
        )


def main():
    parser = argparse.ArgumentParser(description='Manage NXR admin users')
    subparsers = parser.add_subparsers(dest='command', required=True)

    add_parser = subparsers.add_parser('upsert', help='Create or update an admin user')
    add_parser.add_argument('--username', required=True)
    add_parser.add_argument('--password', required=True)
    add_parser.add_argument('--role', default='admin')
    add_parser.add_argument('--email', default=None)
    add_parser.add_argument('--inactive', action='store_true')

    subparsers.add_parser('list', help='List admin users')

    args = parser.parse_args()
    if args.command == 'list':
        list_users()
        return

    add_or_update_user(
        username=args.username,
        password=args.password,
        role=args.role,
        email=args.email,
        is_active=0 if args.inactive else 1,
    )


if __name__ == '__main__':
    main()
