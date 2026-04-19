#!/usr/bin/env python3
"""
NXR Card Grading - Manual Data Entry System (UPDATED)
Updated with new grading logic and database structure
"""
import os
import sqlite3
import random
import string
import json
import shutil
from datetime import datetime
from pathlib import Path
from flask import send_from_directory, send_file, Flask, render_template, request, redirect, url_for, session, flash, jsonify
from werkzeug.utils import secure_filename
from werkzeug.security import check_password_hash, generate_password_hash
from functools import wraps
from PIL import Image

# Configuration
BASE_DIR = Path(__file__).resolve().parent.parent
ADMIN_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "Data"
DATA_DIR.mkdir(exist_ok=True)
DB_PATH = DATA_DIR / "cards.db"
TEMP_DB_PATH = DATA_DIR / "temp_cards.db"
SITE_STATIC_DIR = BASE_DIR / "nxr_site" / "static"
SITE_STATIC_DIR.mkdir(exist_ok=True)

# Ensure directories exist
UPLOAD_FOLDER = ADMIN_DIR / "uploads"
UPLOAD_FOLDER.mkdir(exist_ok=True)

# Brand options (English)
BRAND_OPTIONS = [
    "Pokemon",
    "One Piece",
    "Monkey",
    "Sports Cards",
    "Yu-Gi-Oh!",
    "Magic: The Gathering",
    "Dragon Ball",
    "Marvel",
    "DC Comics",
    "Disney",
    "Other"
]

BRAND_ALIASES = {
    'pokemon': 'Pokemon',
    'poke': 'Pokemon',
    'pokemon jpn': 'Pokemon',
    '宝可梦': 'Pokemon',
    'one piece': 'One Piece',
    'onepiece': 'One Piece',
    'monkey': 'Monkey',
    'sports cards': 'Sports Cards',
    'sports': 'Sports Cards',
    'yu-gi-oh': 'Yu-Gi-Oh!',
    'yugioh': 'Yu-Gi-Oh!',
    'magic': 'Magic: The Gathering',
    'mtg': 'Magic: The Gathering',
    'dragon ball': 'Dragon Ball',
    'marvel': 'Marvel',
    'dc': 'DC Comics',
    'dc comics': 'DC Comics',
    'disney': 'Disney',
    'other': 'Other',
}

# Language options (codes)
LANGUAGE_OPTIONS = [
    "EN",    # English
    "JP",    # Japanese
    "CT",    # Chinese Traditional
    "CS",    # Chinese Simplified
    "IN",    # Indonesian
    "KO",    # Korean
    "TH",    # Thai
    "Other"
]

app = Flask(__name__,
            template_folder=str(ADMIN_DIR / 'templates'),
            static_folder=str(ADMIN_DIR / 'static'),
            static_url_path='/admin/static')
app.secret_key = os.environ.get('ADMIN_SECRET_KEY', 'nxr-manual-entry-2026-updated')
app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER

DEFAULT_ADMIN_ACCOUNTS = {
    'admin': {'password': 'nxr2026', 'role': 'superadmin'},
    'reviewer1': {'password': 'review123', 'role': 'reviewer'},
    'reviewer2': {'password': 'review456', 'role': 'reviewer'},
}

ADMIN_ROLE_LABELS = {
    'superadmin': 'Super Admin',
    'admin': 'Admin',
    'reviewer': 'Reviewer',
}

MANAGEABLE_ADMIN_ROLES = ('admin', 'reviewer')

LANGUAGE_ALIASES = {
    'en': 'EN',
    'english': 'EN',
    'jp': 'JP',
    'ja': 'JP',
    'japanese': 'JP',
    'ct': 'CT',
    'traditional chinese': 'CT',
    'chinese traditional': 'CT',
    'cs': 'CS',
    'simplified chinese': 'CS',
    'chinese simplified': 'CS',
    'in': 'IN',
    'indonesian': 'IN',
    'ko': 'KO',
    'korean': 'KO',
    'th': 'TH',
    'thai': 'TH',
    'other': 'Other',
}

LANGUAGE_DB_VARIANTS = {
    'EN': ['EN', 'English'],
    'JP': ['JP', 'Japanese'],
    'CT': ['CT', 'Traditional Chinese', 'Chinese Traditional'],
    'CS': ['CS', 'Simplified Chinese', 'Chinese Simplified'],
    'IN': ['IN', 'Indonesian'],
    'KO': ['KO', 'Korean'],
    'TH': ['TH', 'Thai'],
    'Other': ['Other'],
}

PAGE_SIZE_OPTIONS = (10, 25, 50, 100, 200)
TEMP_LIST_DEFAULT_PAGE_SIZE = 25
UPLOAD_LIST_DEFAULT_PAGE_SIZE = 50
ADMIN_USERS_DEFAULT_PAGE_SIZE = 25
EXPORT_HISTORY_DEFAULT_PAGE_SIZE = 10
IMAGE_MAX_DIMENSION = 2200
STANDARD_GRADE_OPTIONS = ['8', '8.5', '9', '9.5', '10', 'Pristine 10']
APPROVAL_SEQUENCE_FALLBACK = 9223372036854775807
CLIENT_PUSHED_UPLOAD_STATUS = 'client_pushed'


def load_admin_accounts():
    raw_accounts = os.environ.get('ADMIN_CREDENTIALS_JSON')
    if not raw_accounts:
        return DEFAULT_ADMIN_ACCOUNTS

    try:
        data = json.loads(raw_accounts)
    except json.JSONDecodeError:
        app.logger.warning('Invalid ADMIN_CREDENTIALS_JSON; falling back to default accounts')
        return DEFAULT_ADMIN_ACCOUNTS

    if not isinstance(data, dict):
        app.logger.warning('ADMIN_CREDENTIALS_JSON must be a JSON object; falling back to default accounts')
        return DEFAULT_ADMIN_ACCOUNTS

    accounts = {}
    for username, config in data.items():
        if not isinstance(config, dict) or 'role' not in config:
            continue
        if 'password' not in config and 'password_hash' not in config:
            continue
        accounts[username] = config

    return accounts or DEFAULT_ADMIN_ACCOUNTS


def is_password_hash(value):
    return isinstance(value, str) and value.startswith(('scrypt:', 'pbkdf2:', 'argon2:'))


def verify_admin_password(account, password):
    password_hash = account.get('password_hash')
    if password_hash:
        if is_password_hash(password_hash):
            return check_password_hash(password_hash, password)
        return password == password_hash
    return password == account.get('password', '')


def hash_admin_password(password):
    if is_password_hash(password):
        return password
    return generate_password_hash(password)


def normalize_admin_role(role, default='reviewer'):
    normalized = (role or '').strip().lower()
    if normalized in ADMIN_ROLE_LABELS:
        return normalized
    return default


def is_superadmin_role(role):
    return normalize_admin_role(role) == 'superadmin'


def ensure_admin_users_table(conn):
    conn.execute('''
        CREATE TABLE IF NOT EXISTS admin_users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT NOT NULL,
            password_hash TEXT NOT NULL,
            email TEXT,
            role TEXT DEFAULT 'admin',
            is_active INTEGER DEFAULT 1,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            last_login DATETIME
        )
    ''')
    conn.execute('''
        CREATE UNIQUE INDEX IF NOT EXISTS idx_admin_users_username
        ON admin_users (username COLLATE NOCASE)
    ''')


def upsert_admin_user(conn, username, password, role='admin', email=None, is_active=1):
    now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    password_hash = hash_admin_password(password)
    existing = conn.execute(
        'SELECT id, created_at FROM admin_users WHERE username = ? COLLATE NOCASE',
        (username,),
    ).fetchone()

    if existing:
        conn.execute('''
            UPDATE admin_users
            SET username = ?, password_hash = ?, email = ?, role = ?, is_active = ?
            WHERE id = ?
        ''', (username, password_hash, email, role, is_active, existing['id']))
    else:
        conn.execute('''
            INSERT INTO admin_users (username, password_hash, email, role, is_active, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
        ''', (username, password_hash, email, role, is_active, now))


def migrate_existing_admin_passwords(conn):
    rows = conn.execute('SELECT id, password_hash FROM admin_users').fetchall()
    for row in rows:
        stored_value = row['password_hash'] or ''
        if stored_value and not is_password_hash(stored_value):
            conn.execute(
                'UPDATE admin_users SET password_hash = ? WHERE id = ?',
                (hash_admin_password(stored_value), row['id']),
            )


def initialize_admin_users(conn):
    ensure_admin_users_table(conn)
    migrate_existing_admin_passwords(conn)

    existing_count = conn.execute('SELECT COUNT(*) FROM admin_users').fetchone()[0]
    if existing_count == 0:
        for username, config in load_admin_accounts().items():
            upsert_admin_user(
                conn,
                username=username,
                password=config.get('password_hash') or config.get('password', ''),
                role=config.get('role', 'admin'),
                email=config.get('email'),
            )
    else:
        superadmin_count = conn.execute(
            "SELECT COUNT(*) FROM admin_users WHERE lower(role) = 'superadmin'"
        ).fetchone()[0]
        if superadmin_count == 0:
            conn.execute(
                """
                UPDATE admin_users
                SET role = 'superadmin'
                WHERE username = ? COLLATE NOCASE
                """,
                ('admin',),
            )


def get_admin_account(username):
    with get_main_db_connection() as conn:
        row = conn.execute('''
            SELECT username, password_hash, email, role, is_active
            FROM admin_users
            WHERE username = ? COLLATE NOCASE
            LIMIT 1
        ''', (username,)).fetchone()

    if not row or not row['is_active']:
        return None
    account = dict(row)
    account['role'] = normalize_admin_role(account.get('role'), default='admin')
    return account


def get_admin_account_by_id(user_id):
    with get_main_db_connection() as conn:
        row = conn.execute(
            '''
            SELECT id, username, email, role, is_active, created_at, last_login
            FROM admin_users
            WHERE id = ?
            LIMIT 1
            ''',
            (user_id,),
        ).fetchone()

    if not row:
        return None

    account = dict(row)
    account['role'] = normalize_admin_role(account.get('role'), default='admin')
    account['role_label'] = ADMIN_ROLE_LABELS.get(account['role'], account['role'].title())
    account['is_superadmin'] = is_superadmin_role(account['role'])
    account['is_active'] = bool(account.get('is_active'))
    return account


def count_admin_accounts():
    with get_main_db_connection() as conn:
        return conn.execute('SELECT COUNT(*) FROM admin_users').fetchone()[0]


def list_admin_accounts(limit=None, offset=0):
    with get_main_db_connection() as conn:
        query = '''
            SELECT id, username, email, role, is_active, created_at, last_login
            FROM admin_users
            ORDER BY
                CASE lower(role)
                    WHEN 'superadmin' THEN 0
                    WHEN 'admin' THEN 1
                    ELSE 2
                END,
                username COLLATE NOCASE
        '''
        params = []
        if limit is not None:
            query += ' LIMIT ? OFFSET ?'
            params.extend([limit, offset])
        rows = conn.execute(query, tuple(params)).fetchall()

    accounts = []
    for row in rows:
        account = dict(row)
        account['role'] = normalize_admin_role(account.get('role'), default='admin')
        account['role_label'] = ADMIN_ROLE_LABELS.get(account['role'], account['role'].title())
        account['is_superadmin'] = is_superadmin_role(account['role'])
        account['is_active'] = bool(account.get('is_active'))
        accounts.append(account)
    return accounts


def count_active_superadmins(exclude_user_id=None):
    query = '''
        SELECT COUNT(*)
        FROM admin_users
        WHERE lower(role) = 'superadmin' AND is_active = 1
    '''
    params = []
    if exclude_user_id is not None:
        query += ' AND id != ?'
        params.append(exclude_user_id)

    with get_main_db_connection() as conn:
        return conn.execute(query, tuple(params)).fetchone()[0]


def admin_username_exists(username, exclude_user_id=None):
    query = 'SELECT 1 FROM admin_users WHERE username = ? COLLATE NOCASE'
    params = [username]
    if exclude_user_id is not None:
        query += ' AND id != ?'
        params.append(exclude_user_id)
    query += ' LIMIT 1'

    with get_main_db_connection() as conn:
        row = conn.execute(query, tuple(params)).fetchone()
    return bool(row)


def update_admin_user(conn, user_id, username, role='admin', email=None, is_active=1, password=None):
    params = [username, email, role, is_active]
    query = '''
        UPDATE admin_users
        SET username = ?, email = ?, role = ?, is_active = ?
    '''
    if password:
        query += ', password_hash = ?'
        params.append(hash_admin_password(password))
    query += ' WHERE id = ?'
    params.append(user_id)
    conn.execute(query, tuple(params))


def update_admin_last_login(username):
    with get_main_db_connection() as conn:
        conn.execute(
            'UPDATE admin_users SET last_login = CURRENT_TIMESTAMP WHERE username = ? COLLATE NOCASE',
            (username,),
        )
        conn.commit()


@app.context_processor
def inject_admin_session_context():
    if 'admin_logged_in' not in session:
        return {
            'username': None,
            'role': None,
            'role_label': None,
            'is_superadmin': False,
        }

    role = normalize_admin_role(session.get('role'), default='admin')
    return {
        'username': session.get('username', 'Operator'),
        'role': role,
        'role_label': ADMIN_ROLE_LABELS.get(role, role.title()),
        'is_superadmin': is_superadmin_role(role),
    }


def normalize_language(value):
    raw_value = (value or '').strip()
    if not raw_value:
        return ''

    normalized = LANGUAGE_ALIASES.get(raw_value.lower())
    if normalized:
        return normalized

    upper_value = raw_value.upper()
    if upper_value in LANGUAGE_OPTIONS:
        return upper_value

    return raw_value


def normalize_brand(value):
    raw_value = (value or '').strip()
    if not raw_value:
        return ''

    alias = BRAND_ALIASES.get(raw_value.lower())
    if alias:
        return alias

    for option in BRAND_OPTIONS:
        if raw_value.lower() == option.lower():
            return option

    return 'Other'


def grade_sort_key(value):
    grade = (value or '').strip()
    if not grade:
        return (2, float('inf'), '')
    if grade == 'Pristine 10':
        return (1, 10.1, grade)
    try:
        return (0, float(grade), grade)
    except ValueError:
        return (2, float('inf'), grade.lower())


def normalize_final_grade_text(value):
    raw_value = (value or '').strip()
    if not raw_value:
        return ''

    if raw_value in STANDARD_GRADE_OPTIONS:
        return raw_value

    compact = ''.join(ch for ch in raw_value.lower() if ch.isalnum() or ch == '.')
    numeric_aliases = {
        '8': '8',
        '8.0': '8',
        '8.00': '8',
        '8.5': '8.5',
        '8.50': '8.5',
        '9': '9',
        '9.0': '9',
        '9.00': '9',
        '9.5': '9.5',
        '9.50': '9.5',
        '10': '10',
        '10.0': '10',
        '10.00': '10',
    }
    if compact in numeric_aliases:
        return numeric_aliases[compact]

    if 'pristine10' in compact or 'stine10' in compact:
        return 'Pristine 10'

    return ''


def canonical_grade_sql_expression(column_name='final_grade_text'):
    compact = f"replace(replace(lower(trim({column_name})), ' ', ''), '-', '')"
    return f"""
        CASE
            WHEN trim({column_name}) IN ('8', '8.0', '8.00') THEN '8'
            WHEN trim({column_name}) IN ('8.5', '8.50') THEN '8.5'
            WHEN trim({column_name}) IN ('9', '9.0', '9.00') THEN '9'
            WHEN trim({column_name}) IN ('9.5', '9.50') THEN '9.5'
            WHEN trim({column_name}) IN ('10', '10.0', '10.00') THEN '10'
            WHEN {compact} LIKE '%pristine10%' OR {compact} LIKE '%stine10%' THEN 'Pristine 10'
            ELSE NULL
        END
    """.strip()


def get_grade_filter_options(conn, status_filter='all'):
    canonical_grade = canonical_grade_sql_expression('final_grade_text')
    query = f'''
        SELECT DISTINCT canonical_grade
        FROM (
            SELECT {canonical_grade} AS canonical_grade, status
            FROM temp_cards
        ) grade_rows
    '''
    params = []
    conditions = ['canonical_grade IS NOT NULL']
    if status_filter != 'all':
        conditions.append('status = ?')
        params.append(status_filter)
    query += ' WHERE ' + ' AND '.join(conditions)

    dynamic_grades = {row[0] for row in conn.execute(query, params).fetchall() if row[0]}
    return [grade for grade in STANDARD_GRADE_OPTIONS if grade in dynamic_grades]


def build_grade_filter_sql(grade_filter, table_alias=''):
    canonical_grade = canonical_grade_sql_expression(
        f"{table_alias}.final_grade_text" if table_alias else 'final_grade_text'
    )
    return f"{canonical_grade} = ?"


def format_export_date_range(df):
    for column_name in ('approved_at', 'entry_date', 'created_at', 'updated_at'):
        if column_name not in df.columns:
            continue
        values = df[column_name].dropna().astype('string').str.strip()
        values = values[values != '']
        if values.empty:
            continue
        return f"{values.min()[:10]} 至 {values.max()[:10]}"
    return '无数据'


def load_export_history(export_history_path):
    if not export_history_path.exists():
        return []
    try:
        with open(export_history_path, 'r', encoding='utf-8') as file_obj:
            history = json.load(file_obj)
        if isinstance(history, list):
            return history
    except (OSError, json.JSONDecodeError) as exc:
        app.logger.warning('Failed to read export history %s: %s', export_history_path, exc)
    return []


def approval_timestamp_expression(table_alias=''):
    qualifier = f'{table_alias}.' if table_alias else ''
    return f"COALESCE(NULLIF({qualifier}approved_at, ''), {qualifier}updated_at, {qualifier}entry_date, {qualifier}created_at)"


def approval_sequence_expression(table_alias=''):
    qualifier = f'{table_alias}.' if table_alias else ''
    return f"COALESCE({qualifier}approval_sequence, {APPROVAL_SEQUENCE_FALLBACK})"


def build_approved_order_clause(sort_order='desc', table_alias=''):
    qualifier = f'{table_alias}.' if table_alias else ''
    direction = 'ASC' if sort_order == 'asc' else 'DESC'
    return ', '.join([
        f"{approval_timestamp_expression(table_alias)} {direction}",
        f"{approval_sequence_expression(table_alias)} ASC",
        f"{qualifier}id ASC",
    ])


def build_entry_list_order_clause(status_filter, sort_by, sort_order):
    direction = 'ASC' if sort_order == 'asc' else 'DESC'
    if status_filter == 'approved' and sort_by == 'entry_date':
        return build_approved_order_clause(sort_order=sort_order)
    return f"{sort_by} {direction}, id ASC"


def format_display_datetime(value):
    raw_value = (value or '').strip()
    if not raw_value:
        return ''
    return raw_value.replace('T', ' ')[:19]


def get_entry_display_timestamp(entry):
    if (entry.get('status') or '').strip().lower() == 'approved':
        return (
            entry.get('approved_at')
            or entry.get('updated_at')
            or entry.get('entry_date')
            or entry.get('created_at')
            or ''
        )
    return entry.get('entry_date') or entry.get('created_at') or ''


def serialize_temp_entry(entry):
    entry_dict = dict(entry)
    entry_dict['language'] = normalize_language(entry_dict.get('language'))
    entry_dict['display_date'] = format_display_datetime(get_entry_display_timestamp(entry_dict))
    entry_dict['approved_at_display'] = format_display_datetime(entry_dict.get('approved_at') or '')
    return entry_dict


def backfill_approval_metadata(conn):
    rows = conn.execute(f'''
        SELECT id, {approval_timestamp_expression()} AS effective_approved_at
        FROM temp_cards
        WHERE status = 'approved'
        ORDER BY {approval_timestamp_expression()} ASC, id ASC
    ''').fetchall()

    sequence = 1
    for entry_id, effective_approved_at in rows:
        approved_at = effective_approved_at or datetime.now().isoformat()
        conn.execute(
            '''
                UPDATE temp_cards
                SET approved_at = ?, approval_sequence = ?
                WHERE id = ?
            ''',
            (approved_at, sequence, entry_id),
        )
        sequence += 1


def assign_approval_metadata(conn, entry_ids, approved_at=None):
    approved_at = approved_at or datetime.now().isoformat()
    next_sequence = conn.execute(
        "SELECT COALESCE(MAX(approval_sequence), 0) + 1 FROM temp_cards"
    ).fetchone()[0]
    next_sequence = int(next_sequence or 1)

    updated_count = 0
    for entry_id in entry_ids:
        cursor = conn.execute(
            '''
                UPDATE temp_cards
                SET status = 'approved',
                    approved_at = ?,
                    approval_sequence = ?,
                    updated_at = ?
                WHERE id = ? AND status = 'pending'
            ''',
            (approved_at, next_sequence, approved_at, entry_id),
        )
        if cursor.rowcount > 0:
            updated_count += 1
            next_sequence += 1

    return updated_count, approved_at


def get_language_variants(value):
    normalized = normalize_language(value)
    variants = LANGUAGE_DB_VARIANTS.get(normalized, [normalized] if normalized else [])
    # Preserve order but remove duplicates.
    return list(dict.fromkeys([variant for variant in variants if variant]))


def delete_uploaded_file(filename):
    if not filename:
        return

    safe_name = Path(filename).name
    file_path = Path(app.config['UPLOAD_FOLDER']) / safe_name
    try:
        if file_path.exists():
            file_path.unlink()
    except OSError:
        app.logger.warning('Failed to delete uploaded file: %s', file_path)


def resolve_uploaded_file_path(filename):
    safe_name = Path(filename or '').name
    if not safe_name or safe_name != (filename or ''):
        return None
    return Path(app.config['UPLOAD_FOLDER']) / safe_name


def resolve_public_image_path(image_url):
    raw_value = (image_url or '').strip()
    if not raw_value:
        return None
    if raw_value.startswith('/static/'):
        safe_name = Path(raw_value).name
        return SITE_STATIC_DIR / safe_name
    if raw_value.startswith('static/'):
        safe_name = Path(raw_value).name
        return SITE_STATIC_DIR / safe_name
    return None


def build_public_image_name(cert_id, side, source_path):
    cert_part = secure_filename((cert_id or '').strip()) or 'card'
    extension = source_path.suffix.lower() or '.jpg'
    return f"{cert_part}_{side}{extension}"


def cleanup_public_image_variants(cert_id, side, keep_name=None):
    cert_part = secure_filename((cert_id or '').strip()) or 'card'
    for candidate in SITE_STATIC_DIR.glob(f'{cert_part}_{side}.*'):
        if keep_name and candidate.name == keep_name:
            continue
        if candidate.is_file():
            candidate.unlink()


def delete_public_image(image_url):
    public_path = resolve_public_image_path(image_url)
    if public_path is None:
        return
    try:
        if public_path.exists():
            public_path.unlink()
    except OSError:
        app.logger.warning('Failed to delete published image: %s', public_path)


def sync_uploaded_image_to_site(cert_id, side, filename):
    source_path = resolve_uploaded_file_path(filename)
    if source_path is None:
        raise ValueError(f'Invalid {side} image filename')
    if not source_path.is_file():
        raise FileNotFoundError(f'{side.title()} image file not found: {source_path.name}')

    public_name = build_public_image_name(cert_id, side, source_path)
    destination_path = SITE_STATIC_DIR / public_name

    cleanup_public_image_variants(cert_id, side, keep_name=public_name)
    shutil.copy2(source_path, destination_path)

    return f'/static/{public_name}'


def _resize_for_storage(image):
    image.thumbnail((IMAGE_MAX_DIMENSION, IMAGE_MAX_DIMENSION), Image.Resampling.LANCZOS)
    return image


def normalize_language_values(conn, table_name):
    cursor = conn.cursor()
    for legacy_value, normalized_value in (
        ('English', 'EN'),
        ('Japanese', 'JP'),
        ('Traditional Chinese', 'CT'),
        ('Chinese Traditional', 'CT'),
        ('Simplified Chinese', 'CS'),
        ('Chinese Simplified', 'CS'),
        ('Indonesian', 'IN'),
        ('Korean', 'KO'),
        ('Thai', 'TH'),
    ):
        cursor.execute(
            f"UPDATE {table_name} SET language = ? WHERE language = ?",
            (normalized_value, legacy_value),
        )


def initialize_main_database():
    conn = get_main_db_connection()
    cursor = conn.cursor()

    initialize_admin_users(conn)

    has_cards_table = cursor.execute("""
        SELECT 1 FROM sqlite_master
        WHERE type = 'table' AND name = 'cards'
    """).fetchone()
    if not has_cards_table:
        conn.commit()
        conn.close()
        return

    cursor.execute('''
        CREATE INDEX IF NOT EXISTS idx_cards_identity_grade
        ON cards (card_name, set_name, card_number, language, final_grade_text)
    ''')
    cursor.execute('''
        CREATE INDEX IF NOT EXISTS idx_cards_updated_at
        ON cards (updated_at)
    ''')
    cursor.execute('''
        CREATE INDEX IF NOT EXISTS idx_cards_set_name_number
        ON cards (set_name, card_number)
    ''')
    normalize_language_values(conn, 'cards')

    conn.commit()
    conn.close()


def calculate_population(card_name, set_name, card_number, language, final_grade_text, exclude_entry_id=None):
    normalized_language = normalize_language(language)
    language_variants = get_language_variants(normalized_language)

    if not all([card_name, set_name, card_number, normalized_language, final_grade_text]):
        return 1, normalized_language, 0, 0

    placeholders = ', '.join(['?' for _ in language_variants])
    temp_query = f'''
        SELECT COUNT(*) FROM temp_cards
        WHERE card_name = ? AND set_name = ? AND card_number = ?
        AND language IN ({placeholders}) AND final_grade_text = ?
    '''
    temp_params = [card_name, set_name, card_number, *language_variants, final_grade_text]
    if exclude_entry_id is not None:
        temp_query += ' AND id != ?'
        temp_params.append(exclude_entry_id)

    conn_temp = get_temp_db_connection()
    temp_count = conn_temp.execute(temp_query, temp_params).fetchone()[0]
    conn_temp.close()

    conn_main = get_main_db_connection()
    main_count = conn_main.execute(f'''
        SELECT COUNT(*) FROM cards
        WHERE card_name = ? AND set_name = ? AND card_number = ?
        AND language IN ({placeholders}) AND final_grade_text = ?
    ''', [card_name, set_name, card_number, *language_variants, final_grade_text]).fetchone()[0]
    conn_main.close()

    return temp_count + main_count + 1, normalized_language, temp_count, main_count


def get_existing_public_images(existing_row):
    if not existing_row:
        return '', ''
    front_image = existing_row['front_image'] or existing_row['image'] or ''
    back_image = existing_row['back_image'] or front_image or ''
    return front_image, back_image


def prepare_main_card_images(entry, existing_row=None, require_complete=False):
    cert_id = entry['cert_id']
    temp_front = entry['front_image'] or ''
    temp_back = entry['back_image'] or ''
    existing_front, existing_back = get_existing_public_images(existing_row)

    if require_complete and (not temp_front or not temp_back):
        raise ValueError('Both front and back images are required before upload')

    front_image = existing_front
    back_image = existing_back

    if temp_front:
        front_image = sync_uploaded_image_to_site(cert_id, 'front', temp_front)
    if temp_back:
        back_image = sync_uploaded_image_to_site(cert_id, 'back', temp_back)

    return front_image, back_image


def build_main_card_payload(entry, front_image='', back_image=''):
    cert_id = entry['cert_id']
    created_at = entry['created_at'] or entry['entry_date'] or datetime.now().isoformat()
    updated_at = entry['updated_at'] or created_at

    return {
        'cert_id': cert_id,
        'card_name': entry['card_name'] or '',
        'grade': entry['final_grade_text'] or '',
        'year': entry['year'] or '',
        'brand': entry['brand'] or '',
        'player': '',
        'variety': entry['variety'] or '',
        'image': front_image,
        'pop': entry['pop'] or '1',
        'back_image': back_image,
        'front_image': front_image,
        'qr_url': f'/card/{cert_id}',
        'centering': entry['centering'] or 0,
        'edges': entry['edges'] or 0,
        'corners': entry['corners'] or 0,
        'surface': entry['surface'] or 0,
        'language': normalize_language(entry['language']),
        'set_name': entry['set_name'] or '',
        'card_number': entry['card_number'] or '',
        'grading_phase': 'human_only',
        'data_version': 1,
        'created_at': created_at,
        'updated_at': updated_at,
        'ai_model_version': '',
        'ai_confidence': 0,
        'ai_grade': None,
        'ai_centering': None,
        'ai_edges': None,
        'ai_corners': None,
        'ai_surface': None,
        'final_grade': entry['final_grade'] or 0,
        'decision_method': 'human_only',
        'decision_notes': entry['entry_notes'] or '',
        'ai_front_analysis': '',
        'ai_back_analysis': '',
        'has_ai_analysis': 0,
        'final_grade_text': entry['final_grade_text'] or '',
    }


def upsert_main_card(entry, conn_main, require_complete=False):
    existing = conn_main.execute(
        '''
            SELECT cert_id, image, front_image, back_image
            FROM cards
            WHERE cert_id = ?
        ''',
        (entry['cert_id'],),
    ).fetchone()

    front_image, back_image = prepare_main_card_images(
        entry,
        existing_row=existing,
        require_complete=require_complete,
    )
    payload = build_main_card_payload(entry, front_image=front_image, back_image=back_image)

    columns = list(payload.keys())
    placeholders = ', '.join(['?' for _ in columns])
    update_clause = ', '.join([
        f"{column} = excluded.{column}" for column in columns if column != 'cert_id'
    ])
    values = [payload[column] for column in columns]

    conn_main.execute(
        f'''
            INSERT INTO cards ({', '.join(columns)})
            VALUES ({placeholders})
            ON CONFLICT(cert_id) DO UPDATE SET {update_clause}
        ''',
        values,
    )

    return {
        'action': 'updated' if existing else 'inserted',
        'front_image': front_image,
        'back_image': back_image,
    }


def build_pagination(page, total_pages, endpoint, params):
    clean_params = {key: value for key, value in params.items() if value not in (None, '', 'all')}

    def make_url(target_page):
        return url_for(endpoint, page=target_page, **clean_params)

    if total_pages <= 1:
        return {
            'page': page,
            'total_pages': total_pages,
            'has_prev': False,
            'has_next': False,
            'prev_url': None,
            'next_url': None,
            'pages': [],
        }

    window_start = max(1, page - 2)
    window_end = min(total_pages, page + 2)

    return {
        'page': page,
        'total_pages': total_pages,
        'has_prev': page > 1,
        'has_next': page < total_pages,
        'prev_url': make_url(page - 1) if page > 1 else None,
        'next_url': make_url(page + 1) if page < total_pages else None,
        'pages': [
            {'number': page_number, 'url': make_url(page_number), 'current': page_number == page}
            for page_number in range(window_start, window_end + 1)
        ],
    }


def get_page_size_arg(name='page_size', default=TEMP_LIST_DEFAULT_PAGE_SIZE):
    page_size = request.args.get(name, default, type=int)
    if page_size not in PAGE_SIZE_OPTIONS:
        return default
    return page_size


def resolve_export_file_path(filename):
    safe_name = secure_filename(filename or '')
    if not safe_name or safe_name != Path(filename).name or not safe_name.lower().endswith('.xlsx'):
        return None
    return ADMIN_DIR / "exports" / safe_name


def initialize_databases():
    init_temp_database()
    initialize_main_database()

# Initialize temporary database
def init_temp_database():
    conn = sqlite3.connect(TEMP_DB_PATH)
    cursor = conn.cursor()

    # Create temporary cards table with updated structure
    cursor.execute('''
    CREATE TABLE IF NOT EXISTS temp_cards (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        cert_id TEXT UNIQUE,
        card_name TEXT,
        year TEXT,
        brand TEXT,
        variety TEXT,
        pop TEXT,
        language TEXT NOT NULL DEFAULT 'EN',
        set_name TEXT NOT NULL DEFAULT '',
        card_number TEXT NOT NULL DEFAULT '',
        centering REAL NOT NULL DEFAULT 1 CHECK(centering >= 1 AND centering <= 10),
        edges REAL NOT NULL DEFAULT 1 CHECK(edges >= 1 AND edges <= 10),
        corners REAL NOT NULL DEFAULT 1 CHECK(corners >= 1 AND corners <= 10),
        surface REAL NOT NULL DEFAULT 1 CHECK(surface >= 1 AND surface <= 10),
        final_grade REAL NOT NULL DEFAULT 1,
        final_grade_text TEXT NOT NULL DEFAULT '',
        front_image TEXT DEFAULT '',
        back_image TEXT DEFAULT '',
        published_front_image TEXT DEFAULT '',
        published_back_image TEXT DEFAULT '',
        entry_notes TEXT DEFAULT '',
        entry_by TEXT DEFAULT '',
        entry_date TEXT,
        approved_at TEXT,
        approval_sequence INTEGER,
        status TEXT DEFAULT 'pending',
        created_at TEXT,
        updated_at TEXT,
        upload_status TEXT DEFAULT 'not_started',
        upload_started TEXT,
        upload_completed TEXT,
        upload_error TEXT,
        server_response TEXT
    )
    ''')

    # Check if updated_at column exists, add if not
    try:
        cursor.execute("SELECT updated_at FROM temp_cards LIMIT 1")
    except sqlite3.OperationalError:
        # Column doesn't exist, add it
        print("Adding updated_at column to temp_cards table...")
        cursor.execute("ALTER TABLE temp_cards ADD COLUMN updated_at TEXT")

    # Check and add upload-related columns
    upload_columns = [
        ('approved_at', 'TEXT'),
        ('approval_sequence', 'INTEGER'),
        ('upload_status', 'TEXT DEFAULT "not_started"'),
        ('upload_started', 'TEXT'),
        ('upload_completed', 'TEXT'),
        ('upload_error', 'TEXT'),
        ('server_response', 'TEXT'),
        ('published_front_image', "TEXT DEFAULT ''"),
        ('published_back_image', "TEXT DEFAULT ''"),
    ]

    for column_name, column_type in upload_columns:
        try:
            cursor.execute(f"SELECT {column_name} FROM temp_cards LIMIT 1")
        except sqlite3.OperationalError:
            print(f"Adding {column_name} column to temp_cards table...")
            cursor.execute(f"ALTER TABLE temp_cards ADD COLUMN {column_name} {column_type}")

    cursor.execute('''
        CREATE INDEX IF NOT EXISTS idx_temp_cards_status_entry_date
        ON temp_cards (status, entry_date DESC)
    ''')
    cursor.execute(f'''
        CREATE INDEX IF NOT EXISTS idx_temp_cards_status_approved_order
        ON temp_cards (
            status,
            {approval_timestamp_expression()},
            {approval_sequence_expression()}
        )
    ''')
    cursor.execute('''
        CREATE INDEX IF NOT EXISTS idx_temp_cards_identity_grade
        ON temp_cards (card_name, set_name, card_number, language, final_grade_text)
    ''')
    cursor.execute('''
        CREATE INDEX IF NOT EXISTS idx_temp_cards_card_name
        ON temp_cards (card_name)
    ''')
    cursor.execute('''
        CREATE INDEX IF NOT EXISTS idx_temp_cards_set_name
        ON temp_cards (set_name)
    ''')
    cursor.execute('''
        CREATE INDEX IF NOT EXISTS idx_temp_cards_set_name_number
        ON temp_cards (set_name, card_number)
    ''')
    backfill_approval_metadata(conn)
    normalize_language_values(conn, 'temp_cards')

    conn.commit()
    conn.close()
    print("Temporary database initialized with updated structure")

def get_temp_db_connection():
    conn = sqlite3.connect(TEMP_DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def get_main_db_connection():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def save_uploaded_file(file, filename_prefix):
    """保存上传的文件"""
    if not file or file.filename == '':
        return None

    # 确保文件名安全
    original_filename = secure_filename(file.filename)

    # 生成唯一文件名
    import uuid
    file_ext = os.path.splitext(original_filename)[1].lower()
    unique_filename = f"{filename_prefix}_{uuid.uuid4().hex[:8]}{file_ext}"

    # 保存文件
    file_path = os.path.join(app.config['UPLOAD_FOLDER'], unique_filename)
    file.save(file_path)

    return unique_filename


def get_entry_image_flags(entry):
    front_name = (entry['front_image'] or '').strip()
    back_name = (entry['back_image'] or '').strip()
    published_front = (entry['published_front_image'] or '').strip()
    published_back = (entry['published_back_image'] or '').strip()
    front_path = resolve_uploaded_file_path(front_name) if front_name else None
    back_path = resolve_uploaded_file_path(back_name) if back_name else None
    published_front_path = resolve_public_image_path(published_front) if published_front else None
    published_back_path = resolve_public_image_path(published_back) if published_back else None

    has_front = bool(front_path and front_path.is_file())
    has_back = bool(back_path and back_path.is_file())
    has_published_front = bool(published_front_path and published_front_path.is_file())
    has_published_back = bool(published_back_path and published_back_path.is_file())

    return {
        'has_front_image_file': has_front,
        'has_back_image_file': has_back,
        'ready_for_upload': has_front and has_back,
        'has_published_front_image': has_published_front,
        'has_published_back_image': has_published_back,
        'published_complete': has_published_front and has_published_back,
    }


def get_upload_stats(conn):
    approved_entries = conn.execute(
        '''
            SELECT id, front_image, back_image, published_front_image, published_back_image, upload_status
            FROM temp_cards
            WHERE status = 'approved'
        '''
    ).fetchall()

    stats = {}
    image_stats = {
        'total_approved': len(approved_entries),
        'client_pushed': 0,
        'has_front_image': 0,
        'has_back_image': 0,
        'ready_for_upload': 0,
    }

    for entry in approved_entries:
        upload_status = entry['upload_status'] or 'not_started'
        stats[upload_status] = stats.get(upload_status, 0) + 1
        if upload_status == CLIENT_PUSHED_UPLOAD_STATUS:
            image_stats['client_pushed'] += 1

        flags = get_entry_image_flags(entry)
        if flags['has_front_image_file']:
            image_stats['has_front_image'] += 1
        if flags['has_back_image_file']:
            image_stats['has_back_image'] += 1
        if flags['ready_for_upload']:
            image_stats['ready_for_upload'] += 1

    stats['image_stats'] = image_stats
    return stats

# Generate unique 10-digit Cert ID
def generate_cert_id():
    """Generate a unique 10-digit certificate ID"""
    while True:
        # Generate 10 random digits
        cert_id = ''.join(random.choices(string.digits, k=10))

        # Check if it exists in temp database
        conn_temp = get_temp_db_connection()
        cursor_temp = conn_temp.cursor()
        cursor_temp.execute("SELECT COUNT(*) FROM temp_cards WHERE cert_id = ?", (cert_id,))
        exists_temp = cursor_temp.fetchone()[0] > 0
        conn_temp.close()

        # Check if it exists in main database
        conn_main = get_main_db_connection()
        cursor_main = conn_main.cursor()
        cursor_main.execute("SELECT COUNT(*) FROM cards WHERE cert_id = ?", (cert_id,))
        exists_main = cursor_main.fetchone()[0] > 0
        conn_main.close()

        # If not exists in both databases, return the ID
        if not exists_temp and not exists_main:
            return cert_id

# Calculate final grade based on four sub-scores
def calculate_final_grade(centering, edges, corners, surface):
    """Calculate final grade and grade text based on four sub-scores"""
    # Calculate average
    avg = (centering + edges + corners + surface) / 4
    avg = round(avg, 2)

    # Map to grade text
    if avg < 8.5:
        grade_text = "8"
    elif avg < 9.0:
        grade_text = "8.5"
    elif avg < 9.35:
        grade_text = "9"
    elif avg < 9.75:
        grade_text = "9.5"
    elif avg < 10.0:
        grade_text = "10"
    else:  # avg == 10.0
        grade_text = "Pristine 10"

    return avg, grade_text

# Validate sub-scores (1-10)
def validate_sub_scores(centering, edges, corners, surface):
    """Validate that all sub-scores are between 1 and 10"""
    scores = [centering, edges, corners, surface]
    for score in scores:
        try:
            score_float = float(score)
            if score_float < 1 or score_float > 10:
                return False, f"Score must be between 1 and 10, got {score_float}"
        except ValueError:
            return False, f"Invalid score value: {score}"
    return True, ""

# Login decorator
def login_required(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if 'admin_logged_in' not in session:
            flash('Please login first', 'warning')
            return redirect(url_for('admin_login'))
        return f(*args, **kwargs)
    return decorated_function


def superadmin_required(f):
    @wraps(f)
    def decorated_function(*args, **kwargs):
        if 'admin_logged_in' not in session:
            flash('Please login first', 'warning')
            return redirect(url_for('admin_login'))
        if not is_superadmin_role(session.get('role')):
            flash('Only super admin users can manage administrator accounts.', 'error')
            return redirect(url_for('dashboard'))
        return f(*args, **kwargs)
    return decorated_function

