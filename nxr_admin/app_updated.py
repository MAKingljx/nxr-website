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
import math
import shutil
from datetime import datetime
from pathlib import Path
from collections import deque
from flask import send_from_directory, send_file, Flask, render_template, request, redirect, url_for, session, flash, jsonify
from werkzeug.utils import secure_filename
from werkzeug.security import check_password_hash, generate_password_hash
from functools import wraps
from PIL import Image, ImageFilter, ImageOps
import numpy as np

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

TEMP_LIST_PAGE_SIZE = 25
AUTO_PROCESS_UPLOADS = os.environ.get('AUTO_PROCESS_UPLOADS', '1') != '0'
IMAGE_ANALYSIS_MAX_DIMENSION = 480
IMAGE_MAX_DIMENSION = 2200
IMAGE_MIN_FOREGROUND_RATIO = 0.02
IMAGE_ROTATION_LIMIT_DEGREES = 35
IMAGE_CROP_PADDING_RATIO = 0.03
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


def list_admin_accounts():
    with get_main_db_connection() as conn:
        rows = conn.execute(
            '''
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
        ).fetchall()

    accounts = []
    for row in rows:
        account = dict(row)
        account['role'] = normalize_admin_role(account.get('role'), default='admin')
        account['role_label'] = ADMIN_ROLE_LABELS.get(account['role'], account['role'].title())
        account['is_superadmin'] = is_superadmin_role(account['role'])
        account['is_active'] = bool(account.get('is_active'))
        accounts.append(account)
    return accounts


def admin_username_exists(username):
    with get_main_db_connection() as conn:
        row = conn.execute(
            'SELECT 1 FROM admin_users WHERE username = ? COLLATE NOCASE LIMIT 1',
            (username,),
        ).fetchone()
    return bool(row)


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


def _sample_border_pixels(rgb_array):
    height, width = rgb_array.shape[:2]
    margin = max(4, int(min(height, width) * 0.06))

    top = rgb_array[:margin, :, :]
    bottom = rgb_array[-margin:, :, :]
    left = rgb_array[:, :margin, :]
    right = rgb_array[:, -margin:, :]
    return np.concatenate([
        top.reshape(-1, 3),
        bottom.reshape(-1, 3),
        left.reshape(-1, 3),
        right.reshape(-1, 3),
    ], axis=0)


def _extract_largest_component(mask):
    height, width = mask.shape
    visited = np.zeros((height, width), dtype=bool)
    best_component = []

    for y in range(height):
        for x in range(width):
            if not mask[y, x] or visited[y, x]:
                continue

            queue = deque([(y, x)])
            visited[y, x] = True
            component = []

            while queue:
                current_y, current_x = queue.popleft()
                component.append((current_y, current_x))

                for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    next_y = current_y + dy
                    next_x = current_x + dx
                    if (
                        0 <= next_y < height
                        and 0 <= next_x < width
                        and mask[next_y, next_x]
                        and not visited[next_y, next_x]
                    ):
                        visited[next_y, next_x] = True
                        queue.append((next_y, next_x))

            if len(component) > len(best_component):
                best_component = component

    if not best_component:
        return mask

    largest_mask = np.zeros_like(mask, dtype=bool)
    ys, xs = zip(*best_component)
    largest_mask[list(ys), list(xs)] = True
    return largest_mask


def _build_foreground_mask(image):
    analysis_image = ImageOps.exif_transpose(image).convert('RGB')
    analysis_image.thumbnail((IMAGE_ANALYSIS_MAX_DIMENSION, IMAGE_ANALYSIS_MAX_DIMENSION), Image.Resampling.LANCZOS)

    rgb_array = np.asarray(analysis_image, dtype=np.int16)
    if rgb_array.size == 0:
        return analysis_image, None

    grayscale_array = np.asarray(analysis_image.convert('L'), dtype=np.int16)
    border_pixels = _sample_border_pixels(rgb_array)
    border_luma = _sample_border_pixels(np.repeat(grayscale_array[:, :, None], 3, axis=2))[:, 0]

    background_rgb = np.median(border_pixels, axis=0)
    background_luma = np.median(border_luma)

    color_distance = np.sqrt(np.sum((rgb_array - background_rgb) ** 2, axis=2))
    luma_distance = np.abs(grayscale_array - background_luma)
    saturation = rgb_array.max(axis=2) - rgb_array.min(axis=2)

    mask = (color_distance > 28) | ((luma_distance > 20) & (saturation > 10))
    mask[:2, :] = False
    mask[-2:, :] = False
    mask[:, :2] = False
    mask[:, -2:] = False

    mask_image = Image.fromarray((mask.astype(np.uint8) * 255), mode='L')
    mask_image = mask_image.filter(ImageFilter.MaxFilter(7)).filter(ImageFilter.MinFilter(7)).filter(ImageFilter.SMOOTH)
    mask = np.asarray(mask_image) > 127

    if mask.mean() < IMAGE_MIN_FOREGROUND_RATIO:
        return analysis_image, None

    mask = _extract_largest_component(mask)
    if mask.mean() < IMAGE_MIN_FOREGROUND_RATIO:
        return analysis_image, None

    return analysis_image, mask


def _estimate_rotation_from_mask(mask):
    ys, xs = np.nonzero(mask)
    if len(xs) < 100:
        return 0.0

    coordinates = np.column_stack((xs, ys)).astype(np.float64)
    centered = coordinates - coordinates.mean(axis=0)
    covariance = np.cov(centered, rowvar=False)
    eigenvalues, eigenvectors = np.linalg.eigh(covariance)
    principal_axis = eigenvectors[:, np.argmax(eigenvalues)]

    angle = math.degrees(math.atan2(principal_axis[1], principal_axis[0]))
    rotation = 90.0 - angle

    while rotation <= -90:
        rotation += 180
    while rotation > 90:
        rotation -= 180

    if abs(rotation) > IMAGE_ROTATION_LIMIT_DEGREES:
        return 0.0
    if abs(rotation) < 0.8:
        return 0.0
    return rotation


def _compute_crop_box(image, mask):
    ys, xs = np.nonzero(mask)
    if len(xs) < 100:
        return None

    analysis_width, analysis_height = image.size
    left = xs.min()
    right = xs.max()
    top = ys.min()
    bottom = ys.max()

    pad_x = int((right - left + 1) * IMAGE_CROP_PADDING_RATIO)
    pad_y = int((bottom - top + 1) * IMAGE_CROP_PADDING_RATIO)

    left = max(0, left - pad_x)
    top = max(0, top - pad_y)
    right = min(analysis_width, right + pad_x + 1)
    bottom = min(analysis_height, bottom + pad_y + 1)

    if right - left < analysis_width * 0.2 or bottom - top < analysis_height * 0.2:
        return None

    return (left, top, right, bottom)


def _resize_for_storage(image):
    image.thumbnail((IMAGE_MAX_DIMENSION, IMAGE_MAX_DIMENSION), Image.Resampling.LANCZOS)
    return image


def auto_crop_and_straighten_image(file_path):
    source_path = Path(file_path)
    image = Image.open(source_path)
    image = ImageOps.exif_transpose(image).convert('RGB')

    analysis_image, initial_mask = _build_foreground_mask(image)
    if initial_mask is None:
        processed = _resize_for_storage(image)
        processed.save(source_path, quality=92, optimize=True)
        return {'processed': False, 'rotated': False, 'cropped': False}

    rotation = _estimate_rotation_from_mask(initial_mask)
    background_fill = tuple(int(value) for value in np.median(_sample_border_pixels(np.asarray(analysis_image, dtype=np.int16)), axis=0))

    rotated = image
    rotated_flag = False
    if rotation:
        rotated = image.rotate(rotation, resample=Image.Resampling.BICUBIC, expand=True, fillcolor=background_fill)
        rotated_flag = True

    rotated_analysis, rotated_mask = _build_foreground_mask(rotated)
    crop_box = None
    if rotated_mask is not None:
        small_crop_box = _compute_crop_box(rotated_analysis, rotated_mask)
        if small_crop_box:
            scale_x = rotated.width / rotated_analysis.width
            scale_y = rotated.height / rotated_analysis.height
            crop_box = (
                int(small_crop_box[0] * scale_x),
                int(small_crop_box[1] * scale_y),
                int(small_crop_box[2] * scale_x),
                int(small_crop_box[3] * scale_y),
            )

    cropped_flag = False
    processed = rotated
    if crop_box:
        cropped = rotated.crop(crop_box)
        if cropped.width > rotated.width * 0.25 and cropped.height > rotated.height * 0.25:
            processed = cropped
            cropped_flag = True

    processed = _resize_for_storage(processed)
    processed.save(source_path, quality=92, optimize=True)

    return {'processed': rotated_flag or cropped_flag, 'rotated': rotated_flag, 'cropped': cropped_flag}


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

    if AUTO_PROCESS_UPLOADS:
        try:
            result = auto_crop_and_straighten_image(file_path)
            app.logger.info('Processed upload %s: %s', unique_filename, result)
        except Exception as exc:
            app.logger.warning('Auto-processing failed for %s: %s', unique_filename, exc)

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

# ========== Login ==========
@app.route('/admin/login', methods=['GET', 'POST'])
def admin_login():
    if request.method == 'POST':
        username = request.form.get('username', '').strip()
        password = request.form.get('password', '').strip()

        account = get_admin_account(username)
        if account and verify_admin_password(account, password):
            session['admin_logged_in'] = True
            session['username'] = account['username']
            session['role'] = account.get('role', 'admin')
            update_admin_last_login(account['username'])
            flash(f"Login successful! Welcome {account['username']}", 'success')
            return redirect(url_for('dashboard'))
        else:
            flash('Invalid username or password', 'error')

    return render_template('login.html')

@app.route('/admin/logout')
def admin_logout():
    session.clear()
    flash('Logged out successfully', 'info')
    return redirect(url_for('admin_login'))

# ========== Dashboard ==========
@app.route('/admin')
def admin_index():
    if 'admin_logged_in' in session:
        return redirect(url_for('dashboard'))
    return redirect(url_for('admin_login'))

@app.route('/admin/dashboard')
@login_required
def dashboard():
    conn = get_temp_db_connection()
    today = datetime.now().strftime('%Y-%m-%d')

    stats = {
        'total_entries': conn.execute('SELECT COUNT(*) FROM temp_cards').fetchone()[0],
        'pending': conn.execute("SELECT COUNT(*) FROM temp_cards WHERE status = 'pending'").fetchone()[0],
        'approved': conn.execute("SELECT COUNT(*) FROM temp_cards WHERE status = 'approved'").fetchone()[0],
        'today_entries': conn.execute(
            "SELECT COUNT(*) FROM temp_cards WHERE substr(entry_date, 1, 10) = ?",
            (today,),
        ).fetchone()[0],
    }

    recent_entries = conn.execute('''
        SELECT id, cert_id, card_name, brand, set_name, language, final_grade_text, status, entry_date
        FROM temp_cards
        ORDER BY entry_date DESC
        LIMIT 5
    ''').fetchall()

    conn.close()

    return render_template('dashboard.html',
                         stats=stats,
                         recent_entries=[
                             {**dict(entry), 'language': normalize_language(entry['language'])}
                             for entry in recent_entries
                         ],
                         username=session.get('username', 'Operator'),
                         role=session.get('role', 'reviewer'),
                         brand_options=BRAND_OPTIONS,
                         language_options=LANGUAGE_OPTIONS)


@app.route('/admin/users', methods=['GET', 'POST'])
@superadmin_required
def admin_users():
    if request.method == 'POST':
        username = request.form.get('username', '').strip()
        password = request.form.get('password', '').strip()
        email = request.form.get('email', '').strip() or None
        role = normalize_admin_role(request.form.get('role'), default='admin')
        is_active = 0 if request.form.get('inactive') == '1' else 1

        if not username:
            flash('Username is required.', 'error')
            return redirect(url_for('admin_users'))
        if not password:
            flash('Password is required.', 'error')
            return redirect(url_for('admin_users'))
        if len(password) < 6:
            flash('Password must be at least 6 characters long.', 'error')
            return redirect(url_for('admin_users'))
        if role not in MANAGEABLE_ADMIN_ROLES:
            flash('Only admin or reviewer accounts can be created here.', 'error')
            return redirect(url_for('admin_users'))
        if admin_username_exists(username):
            flash(f'Username "{username}" already exists.', 'warning')
            return redirect(url_for('admin_users'))

        with get_main_db_connection() as conn:
            upsert_admin_user(
                conn,
                username=username,
                password=password,
                role=role,
                email=email,
                is_active=is_active,
            )
            conn.commit()

        flash(f'Administrator account "{username}" created successfully.', 'success')
        return redirect(url_for('admin_users'))

    return render_template(
        'admin_users.html',
        admin_accounts=list_admin_accounts(),
        role_options=[{'value': role, 'label': ADMIN_ROLE_LABELS[role]} for role in MANAGEABLE_ADMIN_ROLES],
    )

# ========== New Entry ==========
@app.route('/admin/entry/new', methods=['GET', 'POST'])
@login_required
def new_entry():
    if request.method == 'POST':
        # Collect form data
        centering = request.form.get('centering', '0').strip()
        edges = request.form.get('edges', '0').strip()
        corners = request.form.get('corners', '0').strip()
        surface = request.form.get('surface', '0').strip()

        # Validate sub-scores
        is_valid, error_msg = validate_sub_scores(centering, edges, corners, surface)
        if not is_valid:
            flash(f'Invalid scores: {error_msg}', 'error')
            return redirect(url_for('new_entry'))

        # Calculate final grade
        centering_float = float(centering)
        edges_float = float(edges)
        corners_float = float(corners)
        surface_float = float(surface)

        final_grade, final_grade_text = calculate_final_grade(
            centering_float, edges_float, corners_float, surface_float
        )

        # Get card identity for POP calculation
        card_name = request.form.get('card_name', '').strip()
        set_name = request.form.get('set_name', '').strip()
        card_number = request.form.get('card_number', '').strip()
        total_pop, language, _, _ = calculate_population(
            card_name, set_name, card_number, request.form.get('language', ''), final_grade_text
        )

        # Handle file uploads
        front_image_filename = None
        back_image_filename = None

        if 'front_image' in request.files:
            front_image_file = request.files['front_image']
            front_image_filename = save_uploaded_file(front_image_file, 'front')

        if 'back_image' in request.files:
            back_image_file = request.files['back_image']
            back_image_filename = save_uploaded_file(back_image_file, 'back')

        # Prepare entry data
        entry_data = {
            'cert_id': request.form.get('cert_id', '').strip(),
            'card_name': card_name,
            'year': request.form.get('year', '').strip(),
            'brand': request.form.get('brand', '').strip(),
            'variety': request.form.get('variety', '').strip(),
            'pop': str(total_pop),  # Auto-calculated POP
            'language': language,
            'set_name': set_name,
            'card_number': card_number,
            'centering': centering_float,
            'edges': edges_float,
            'corners': corners_float,
            'surface': surface_float,
            'final_grade': final_grade,
            'final_grade_text': final_grade_text,
            'front_image': front_image_filename or '',
            'back_image': back_image_filename or '',
            'published_front_image': '',
            'published_back_image': '',
            'entry_notes': request.form.get('entry_notes', '').strip(),
            'entry_by': session.get('username', ''),
            'entry_date': datetime.now().isoformat(),
            'status': 'pending',
            'created_at': datetime.now().isoformat(),
            'updated_at': datetime.now().isoformat()
        }

        # Validate required fields
        required_fields = ['cert_id', 'card_name', 'brand', 'language', 'set_name', 'card_number']
        for field in required_fields:
            if not entry_data[field]:
                delete_uploaded_file(front_image_filename)
                delete_uploaded_file(back_image_filename)
                flash(f'{field.replace("_", " ").title()} is required', 'error')
                return redirect(url_for('new_entry'))

        # Save to temporary database
        conn = get_temp_db_connection()
        try:
            cursor = conn.cursor()

            # Check if cert_id already exists
            cursor.execute("SELECT COUNT(*) FROM temp_cards WHERE cert_id = ?", (entry_data['cert_id'],))
            if cursor.fetchone()[0] > 0:
                delete_uploaded_file(front_image_filename)
                delete_uploaded_file(back_image_filename)
                flash(f"Certificate ID {entry_data['cert_id']} already exists", 'error')
                conn.close()
                return redirect(url_for('new_entry'))

            # Insert into temporary database
            columns = ', '.join(entry_data.keys())
            placeholders = ', '.join(['?' for _ in entry_data])
            values = tuple(entry_data.values())

            cursor.execute(f"INSERT INTO temp_cards ({columns}) VALUES ({placeholders})", values)
            conn.commit()

            flash(f"Card {entry_data['cert_id']} entered successfully! Grade: {final_grade_text}", 'success')
            conn.close()
            return redirect(url_for('entry_list'))

        except Exception as e:
            conn.rollback()
            delete_uploaded_file(front_image_filename)
            delete_uploaded_file(back_image_filename)
            flash(f"Error saving entry: {str(e)}", 'error')
            conn.close()
            return redirect(url_for('new_entry'))

    # GET request - show empty form with auto-generated Cert ID
    auto_cert_id = generate_cert_id()

    return render_template('entry_form_updated.html',
                         title="New Card Entry",
                         action=url_for('new_entry'),
                         card=None,
                         auto_cert_id=auto_cert_id,
                         brand_options=BRAND_OPTIONS,
                         language_options=LANGUAGE_OPTIONS)

# ========== Entry List ==========
@app.route('/admin/entries')
@login_required
def entry_list():
    # Get filter parameters
    status_filter = request.args.get('status', 'all')
    cert_id_filter = request.args.get('cert_id', '').strip()
    card_name_filter = request.args.get('card_name', '').strip()
    final_grade_filter = request.args.get('final_grade', '').strip()
    set_name_filter = request.args.get('set_name', '').strip()
    brand_filter = request.args.get('brand', '').strip()
    language_filter = normalize_language(request.args.get('language', '').strip())
    entered_by_filter = request.args.get('entered_by', '').strip()
    sort_by = request.args.get('sort_by', 'entry_date')
    sort_order = request.args.get('sort_order', 'desc')
    page = max(request.args.get('page', 1, type=int), 1)
    
    # Validate sort parameters
    valid_sort_columns = ['entry_date', 'card_name', 'final_grade', 'set_name', 'language', 'cert_id', 'brand']
    if sort_by not in valid_sort_columns:
        sort_by = 'entry_date'
    
    if sort_order not in ['asc', 'desc']:
        sort_order = 'desc'

    conn = get_temp_db_connection()

    # Build query with filters
    query = "SELECT * FROM temp_cards"
    params = []
    conditions = []

    if status_filter != 'all':
        conditions.append("status = ?")
        params.append(status_filter)
    
    if cert_id_filter:
        if cert_id_filter.isdigit() and len(cert_id_filter) == 10:
            conditions.append("cert_id = ?")
            params.append(cert_id_filter)
        else:
            conditions.append("cert_id LIKE ?")
            params.append(f"%{cert_id_filter}%")

    if card_name_filter:
        conditions.append("card_name LIKE ?")
        params.append(f"%{card_name_filter}%")
    
    if final_grade_filter:
        conditions.append("final_grade_text = ?")
        params.append(final_grade_filter)
    
    if set_name_filter:
        conditions.append("set_name LIKE ?")
        params.append(f"%{set_name_filter}%")

    if brand_filter:
        conditions.append("brand LIKE ?")
        params.append(f"%{brand_filter}%")
    
    if language_filter:
        language_variants = get_language_variants(language_filter)
        placeholders = ', '.join(['?' for _ in language_variants])
        conditions.append(f"language IN ({placeholders})")
        params.extend(language_variants)

    if entered_by_filter:
        conditions.append("entry_by LIKE ?")
        params.append(f"%{entered_by_filter}%")
    
    where_clause = f" WHERE {' AND '.join(conditions)}" if conditions else ""
    total_matching = conn.execute(f"SELECT COUNT(*) FROM temp_cards{where_clause}", params).fetchone()[0]
    total_pages = max((total_matching + TEMP_LIST_PAGE_SIZE - 1) // TEMP_LIST_PAGE_SIZE, 1)
    if page > total_pages:
        page = total_pages

    offset = (page - 1) * TEMP_LIST_PAGE_SIZE

    # Add ORDER BY clause
    query += where_clause
    query += f" ORDER BY {build_entry_list_order_clause(status_filter, sort_by, sort_order)} LIMIT ? OFFSET ?"

    # Execute query
    entries = conn.execute(query, [*params, TEMP_LIST_PAGE_SIZE, offset]).fetchall()
    
    # Get available filter options
    grade_options = get_grade_filter_options(conn, status_filter=status_filter)
    
    set_options = []
    if status_filter == 'approved' or status_filter == 'all':
        set_result = conn.execute("""
            SELECT DISTINCT set_name 
            FROM temp_cards 
            WHERE set_name IS NOT NULL AND set_name != ''
            ORDER BY set_name
        """).fetchall()
        set_options = [row[0] for row in set_result]

    entered_by_options = [
        row[0]
        for row in conn.execute("""
            SELECT DISTINCT entry_by
            FROM temp_cards
            WHERE entry_by IS NOT NULL AND entry_by != ''
            ORDER BY entry_by
        """).fetchall()
    ]
    
    # Get status counts
    status_counts = {
        'all': conn.execute("SELECT COUNT(*) FROM temp_cards").fetchone()[0],
        'pending': conn.execute("SELECT COUNT(*) FROM temp_cards WHERE status = 'pending'").fetchone()[0],
        'approved': conn.execute("SELECT COUNT(*) FROM temp_cards WHERE status = 'approved'").fetchone()[0],
    }

    conn.close()

    pagination = build_pagination(page, total_pages, 'entry_list', {
        'status': status_filter,
        'cert_id': cert_id_filter,
        'card_name': card_name_filter,
        'final_grade': final_grade_filter,
        'set_name': set_name_filter,
        'brand': brand_filter,
        'language': language_filter,
        'entered_by': entered_by_filter,
        'sort_by': sort_by,
        'sort_order': sort_order,
    })

    page_start = ((page - 1) * TEMP_LIST_PAGE_SIZE) + 1 if total_matching else 0
    page_end = min(page * TEMP_LIST_PAGE_SIZE, total_matching)

    return render_template('entry_list.html',
                         entries=[serialize_temp_entry(entry) for entry in entries],
                         status_filter=status_filter,
                         status_counts=status_counts,
                         brand_options=BRAND_OPTIONS,
                         language_options=LANGUAGE_OPTIONS,
                         # Filter values
                         cert_id_filter=cert_id_filter,
                         card_name_filter=card_name_filter,
                         final_grade_filter=final_grade_filter,
                         set_name_filter=set_name_filter,
                         brand_filter=brand_filter,
                         language_filter=language_filter,
                         entered_by_filter=entered_by_filter,
                         # Filter options
                         grade_options=grade_options,
                         set_options=set_options,
                         brand_options_for_filter=BRAND_OPTIONS,
                         entered_by_options=entered_by_options,
                         # Sort values
                         sort_by=sort_by,
                         sort_order=sort_order,
                         total_matching=total_matching,
                         pagination=pagination,
                         page_start=page_start,
                         page_end=page_end)

# ========== Entry Detail ==========
@app.route('/admin/entries/<int:entry_id>')
@login_required
def entry_detail(entry_id):
    conn = get_temp_db_connection()
    entry = conn.execute("SELECT * FROM temp_cards WHERE id = ?", (entry_id,)).fetchone()
    conn.close()

    if not entry:
        flash('Entry not found', 'error')
        return redirect(url_for('entry_list'))

    entry = serialize_temp_entry(entry)
    entry['entry_date_display'] = format_display_datetime(entry.get('entry_date') or '')
    entry['created_at_display'] = format_display_datetime(entry.get('created_at') or '')
    entry['updated_at_display'] = format_display_datetime(entry.get('updated_at') or '')

    return render_template('entry_detail.html',
                         entry=entry,
                         brand_options=BRAND_OPTIONS,
                         language_options=LANGUAGE_OPTIONS)

# ========== Edit Entry ==========
@app.route('/admin/entries/<int:entry_id>/edit', methods=['GET', 'POST'])
@login_required
def edit_entry(entry_id):
    conn = get_temp_db_connection()
    existing_entry = conn.execute("SELECT * FROM temp_cards WHERE id = ?", (entry_id,)).fetchone()

    if not existing_entry:
        conn.close()
        flash('Entry not found', 'error')
        return redirect(url_for('entry_list'))

    if request.method == 'POST':
        # Get and validate sub-scores
        centering = request.form.get('centering', '0').strip()
        edges = request.form.get('edges', '0').strip()
        corners = request.form.get('corners', '0').strip()
        surface = request.form.get('surface', '0').strip()

        is_valid, error_msg = validate_sub_scores(centering, edges, corners, surface)
        if not is_valid:
            flash(f'Invalid scores: {error_msg}', 'error')
            conn.close()
            return redirect(url_for('edit_entry', entry_id=entry_id))

        # Calculate final grade
        centering_float = float(centering)
        edges_float = float(edges)
        corners_float = float(corners)
        surface_float = float(surface)

        final_grade, final_grade_text = calculate_final_grade(
            centering_float, edges_float, corners_float, surface_float
        )

        # Get card identity for POP calculation
        card_name = request.form.get('card_name', '').strip()
        set_name = request.form.get('set_name', '').strip()
        card_number = request.form.get('card_number', '').strip()
        total_pop, language, _, _ = calculate_population(
            card_name,
            set_name,
            card_number,
            request.form.get('language', ''),
            final_grade_text,
            exclude_entry_id=entry_id,
        )

        # Handle file uploads
        front_image_filename = None
        back_image_filename = None

        if 'front_image' in request.files:
            front_image_file = request.files['front_image']
            if front_image_file and front_image_file.filename != '':
                front_image_filename = save_uploaded_file(front_image_file, 'front')

        if 'back_image' in request.files:
            back_image_file = request.files['back_image']
            if back_image_file and back_image_file.filename != '':
                back_image_filename = save_uploaded_file(back_image_file, 'back')

        delete_front_image = request.form.get('delete_front_image') == '1'
        delete_back_image = request.form.get('delete_back_image') == '1'
        files_to_delete = []
        published_images_to_delete = []

        # Update entry
        update_data = {
            'card_name': card_name,
            'year': request.form.get('year', '').strip(),
            'brand': request.form.get('brand', '').strip(),
            'variety': request.form.get('variety', '').strip(),
            'pop': str(total_pop),  # Auto-calculated POP
            'language': language,
            'set_name': set_name,
            'card_number': card_number,
            'centering': centering_float,
            'edges': edges_float,
            'corners': corners_float,
            'surface': surface_float,
            'final_grade': final_grade,
            'final_grade_text': final_grade_text,
            'entry_notes': request.form.get('entry_notes', '').strip(),
            'updated_at': datetime.now().isoformat(),
        }

        # Add image filenames if new files were uploaded
        if front_image_filename:
            update_data['front_image'] = front_image_filename
            if existing_entry['front_image']:
                files_to_delete.append(existing_entry['front_image'])
        elif delete_front_image:
            update_data['front_image'] = ''
            update_data['published_front_image'] = ''
            if existing_entry['front_image']:
                files_to_delete.append(existing_entry['front_image'])
            if existing_entry['published_front_image']:
                published_images_to_delete.append(existing_entry['published_front_image'])

        if back_image_filename:
            update_data['back_image'] = back_image_filename
            if existing_entry['back_image']:
                files_to_delete.append(existing_entry['back_image'])
        elif delete_back_image:
            update_data['back_image'] = ''
            update_data['published_back_image'] = ''
            if existing_entry['back_image']:
                files_to_delete.append(existing_entry['back_image'])
            if existing_entry['published_back_image']:
                published_images_to_delete.append(existing_entry['published_back_image'])

        # Validate required fields
        required_fields = ['card_name', 'brand', 'language', 'set_name', 'card_number']
        for field in required_fields:
            if not update_data[field]:
                delete_uploaded_file(front_image_filename)
                delete_uploaded_file(back_image_filename)
                flash(f'{field.replace("_", " ").title()} is required', 'error')
                conn.close()
                return redirect(url_for('edit_entry', entry_id=entry_id))

        try:
            # Build update query
            set_clause = ', '.join([f"{key} = ?" for key in update_data.keys()])
            values = list(update_data.values())
            values.append(entry_id)

            conn.execute(f"UPDATE temp_cards SET {set_clause} WHERE id = ?", values)
            conn.commit()
            conn.close()

            for filename in dict.fromkeys(files_to_delete):
                delete_uploaded_file(filename)
            for image_url in dict.fromkeys(published_images_to_delete):
                delete_public_image(image_url)

            flash(f"Entry updated successfully. New grade: {final_grade_text}", 'success')
            return redirect(url_for('entry_detail', entry_id=entry_id))

        except Exception as e:
            conn.rollback()
            delete_uploaded_file(front_image_filename)
            delete_uploaded_file(back_image_filename)
            flash(f"Error updating entry: {str(e)}", 'error')
            conn.close()
            return redirect(url_for('edit_entry', entry_id=entry_id))

    # GET request - show edit form
    conn.close()
    entry = {**dict(existing_entry), 'language': normalize_language(existing_entry['language'])}

    return render_template('entry_form_updated.html',
                         title="Edit Card Entry",
                         action=url_for('edit_entry', entry_id=entry_id),
                         card=entry,
                         brand_options=BRAND_OPTIONS,
                         language_options=LANGUAGE_OPTIONS)

# ========== Approve Entry ==========
@app.route('/admin/entries/<int:entry_id>/approve', methods=['POST'])
@login_required
def approve_entry(entry_id):
    conn = get_temp_db_connection()

    try:
        conn.execute('BEGIN IMMEDIATE')
        updated_count, _ = assign_approval_metadata(conn, [entry_id])
        conn.commit()
        if updated_count:
            flash('Entry approved successfully', 'success')
        else:
            flash('Entry is already approved or was not found', 'warning')
    except Exception as e:
        conn.rollback()
        flash(f'Error approving entry: {str(e)}', 'error')

    conn.close()
    return redirect(url_for('entry_detail', entry_id=entry_id))


@app.route('/admin/entries/batch-approve', methods=['POST'])
@login_required
def batch_approve_entries():
    data = request.get_json(silent=True) or {}
    raw_entry_ids = data.get('entry_ids', [])

    if not isinstance(raw_entry_ids, list) or not raw_entry_ids:
        return jsonify({'success': False, 'message': 'No entries selected'}), 400

    entry_ids = []
    for value in raw_entry_ids:
        try:
            entry_ids.append(int(value))
        except (TypeError, ValueError):
            return jsonify({'success': False, 'message': f'Invalid entry id: {value}'}), 400

    # Preserve request order while removing duplicates.
    entry_ids = list(dict.fromkeys(entry_ids))

    conn = get_temp_db_connection()

    try:
        conn.execute('BEGIN IMMEDIATE')
        updated_count, approved_at = assign_approval_metadata(conn, entry_ids)
        conn.commit()
        return jsonify({
            'success': True,
            'message': f'Successfully approved {updated_count} entries',
            'count': updated_count,
            'approved_at': approved_at,
        })
    except Exception as exc:
        conn.rollback()
        return jsonify({'success': False, 'message': f'Error approving entries: {exc}'}), 500
    finally:
        conn.close()

# ========== Export to Main Database ==========
@app.route('/admin/export/approved')
@login_required
def export_approved():
    conn_temp = get_temp_db_connection()
    conn_main = get_main_db_connection()

    try:
        approved_entries = conn_temp.execute(
            f"SELECT * FROM temp_cards WHERE status = 'approved' ORDER BY {build_approved_order_clause()}"
        ).fetchall()

        inserted = 0
        updated = 0
        for entry in approved_entries:
            result = upsert_main_card(entry, conn_main, require_complete=False)
            if result['action'] == 'updated':
                updated += 1
            else:
                inserted += 1

        conn_main.commit()
        flash(f'Export completed. Inserted {inserted} and updated {updated} entries in main database', 'success')

    except Exception as e:
        conn_main.rollback()
        flash(f'Error exporting: {str(e)}', 'error')

    finally:
        conn_temp.close()
        conn_main.close()

    return redirect(url_for('entry_list'))

# ========== API: Generate Cert ID ==========
@app.route('/admin/api/generate-cert-id')
@app.route('/api/generate-cert-id')
@login_required
def api_generate_cert_id():
    """API endpoint to generate a new unique Cert ID"""
    cert_id = generate_cert_id()
    return jsonify({'cert_id': cert_id})

# ========== API: Calculate Grade ==========
@app.route('/admin/api/calculate-grade', methods=['POST'])
@app.route('/api/calculate-grade', methods=['POST'])
@login_required
def api_calculate_grade():
    """API endpoint to calculate final grade from sub-scores"""
    try:
        data = request.get_json()
        centering = float(data.get('centering', 0))
        edges = float(data.get('edges', 0))
        corners = float(data.get('corners', 0))
        surface = float(data.get('surface', 0))

        # Validate scores
        scores = [centering, edges, corners, surface]
        for score in scores:
            if score < 1 or score > 10:
                return jsonify({'error': f'Score must be between 1 and 10, got {score}'}), 400

        # Calculate final grade
        final_grade, final_grade_text = calculate_final_grade(centering, edges, corners, surface)

        return jsonify({
            'final_grade': final_grade,
            'final_grade_text': final_grade_text,
            'calculation': f'({centering} + {edges} + {corners} + {surface}) / 4 = {final_grade}'
        })

    except Exception as e:
        return jsonify({'error': str(e)}), 400

# ========== API: Calculate POP ==========
@app.route('/admin/api/calculate-pop', methods=['POST'])
@app.route('/api/calculate-pop', methods=['POST'])
@login_required
def api_calculate_pop():
    """API endpoint to calculate POP (Population)"""
    try:
        data = request.get_json()
        card_name = data.get('card_name', '').strip()
        set_name = data.get('set_name', '').strip()
        card_number = data.get('card_number', '').strip()
        language = data.get('language', '').strip()
        final_grade_text = data.get('final_grade_text', '').strip()
        current_entry_id = data.get('current_entry_id')

        # Validate required fields
        if not all([card_name, set_name, card_number, language, final_grade_text]):
            return jsonify({'pop': '1', 'message': 'Incomplete data for POP calculation'})

        exclude_entry_id = int(current_entry_id) if current_entry_id not in (None, '', 'null') else None
        total_pop, normalized_language, temp_count, main_count = calculate_population(
            card_name,
            set_name,
            card_number,
            language,
            final_grade_text,
            exclude_entry_id=exclude_entry_id,
        )

        return jsonify({
            'pop': str(total_pop),
            'calculation': f'Temporary DB: {temp_count} + Main DB: {main_count} + 1 = {total_pop}',
            'details': {
                'card_identity': f'{card_name} / {set_name} / {card_number} / {normalized_language}',
                'grade': final_grade_text,
                'temp_count': temp_count,
                'main_count': main_count
            }
        })

    except Exception as e:
        return jsonify({'error': str(e), 'pop': '1'}), 400


@app.route('/admin/api/match-card', methods=['POST'])
@app.route('/api/match-card', methods=['POST'])
@login_required
def api_match_card():
    """Auto-fill card metadata from existing temp or main records."""
    try:
        data = request.get_json() or {}
        set_name = data.get('set_name', '').strip()
        card_number = data.get('card_number', '').strip()

        if not set_name or not card_number:
            return jsonify({'error': 'Set name and card number are required'}), 400

        lookup_sql = '''
            SELECT card_name, brand, year, variety, language
            FROM {table_name}
            WHERE set_name = ? COLLATE NOCASE
              AND card_number = ? COLLATE NOCASE
            {order_clause}
            LIMIT 1
        '''

        lookups = (
            (
                get_temp_db_connection,
                'temp_cards',
                '''
                    ORDER BY
                        CASE WHEN status = 'approved' THEN 0 ELSE 1 END,
                        COALESCE(updated_at, entry_date, created_at) DESC,
                        id DESC
                ''',
                'temp_cards',
            ),
            (
                get_main_db_connection,
                'cards',
                '''
                    ORDER BY
                        COALESCE(updated_at, created_at) DESC,
                        cert_id DESC
                ''',
                'cards',
            ),
        )

        for connection_factory, table_name, order_clause, source in lookups:
            with connection_factory() as conn:
                row = conn.execute(
                    lookup_sql.format(table_name=table_name, order_clause=order_clause),
                    (set_name, card_number),
                ).fetchone()
            if not row:
                continue

            return jsonify({
                'found': True,
                'card_name': row['card_name'] or '',
                'brand': normalize_brand(row['brand']),
                'year': row['year'] or '',
                'variety': row['variety'] or '',
                'language': normalize_language(row['language']),
                'source': source,
            })

        return jsonify({'found': False, 'message': 'No matching card found in database'})

    except Exception as exc:
        app.logger.error('Card matching error: %s', exc)
        return jsonify({'error': f'Database error: {exc}'}), 500

@app.route('/favicon.ico')
def favicon():
    return send_from_directory(ADMIN_DIR / 'static' / 'images', 'nxr-logo-circle.png', mimetype='image/png')

# ========== 404 Error Handler ==========
@app.errorhandler(404)
def page_not_found(e):
    # If accessing root, redirect to admin
    if request.path == '/':
        return redirect(url_for('admin_index'))

    # If accessing with trailing slash, redirect to without slash
    if request.path.endswith('/') and request.path != '/':
        # Remove trailing slash and try again
        new_path = request.path.rstrip('/')
        return redirect(new_path)

    return "Page not found. Please check the URL.", 404

# ========== Upload Manager ==========
@app.route('/admin/upload')
@login_required
def upload_manager():
    """上传管理页面"""
    page = request.args.get('page', 1, type=int)
    show_client_pushed = request.args.get('show_client_pushed', '0') == '1'
    per_page = 50

    conn = get_temp_db_connection()
    cursor = conn.cursor()

    # 获取所有已批准数据（包括图片不完整的）
    offset = (page - 1) * per_page

    query = '''
        SELECT * FROM temp_cards
        WHERE status = 'approved'
    '''
    params = []
    if not show_client_pushed:
        query += " AND COALESCE(upload_status, 'not_started') != ?"
        params.append(CLIENT_PUSHED_UPLOAD_STATUS)

    query += '''
        ORDER BY
            COALESCE(NULLIF(approved_at, ''), updated_at, entry_date, created_at) DESC,
            COALESCE(approval_sequence, 9223372036854775807) ASC,
            id ASC
        LIMIT ? OFFSET ?
    '''
    params.extend([per_page, offset])

    cursor.execute(query, params)

    raw_entries = cursor.fetchall()
    entries = []
    for entry in raw_entries:
        entry_dict = serialize_temp_entry(entry)
        entry_dict.update(get_entry_image_flags(entry))
        entries.append(entry_dict)

    # 获取总数
    total_query = '''
        SELECT COUNT(*) FROM temp_cards
        WHERE status = 'approved'
    '''
    total_params = []
    if not show_client_pushed:
        total_query += " AND COALESCE(upload_status, 'not_started') != ?"
        total_params.append(CLIENT_PUSHED_UPLOAD_STATUS)
    cursor.execute(total_query, total_params)
    total = cursor.fetchone()[0]

    stats = get_upload_stats(conn)

    conn.close()

    total_pages = (total + per_page - 1) // per_page

    return render_template('upload_manager.html',
                         entries=entries,
                         page=page,
                         per_page=per_page,
                         total=total,
                         total_pages=total_pages,
                         show_client_pushed=show_client_pushed,
                         stats=stats,
                         brand_options=BRAND_OPTIONS,
                         language_options=LANGUAGE_OPTIONS)

@app.route('/admin/api/upload-stats')
@app.route('/api/upload-stats')
@login_required
def api_upload_stats():
    """API: 获取上传统计信息"""
    conn = get_temp_db_connection()
    stats = get_upload_stats(conn)
    conn.close()

    return jsonify(stats)

@app.route('/admin/api/upload/<int:entry_id>', methods=['POST'])
@app.route('/api/upload/<int:entry_id>', methods=['POST'])
@login_required
def api_upload_entry(entry_id):
    """API: 上传单条数据到主数据库并同步图片到主站静态目录"""
    conn_temp = get_temp_db_connection()
    conn_main = get_main_db_connection()
    started_at = datetime.now().isoformat()

    try:
        conn_temp.execute(
            '''
                UPDATE temp_cards
                SET upload_status = 'uploading',
                    upload_started = ?,
                    upload_error = NULL
                WHERE id = ?
            ''',
            (started_at, entry_id),
        )
        conn_temp.commit()

        entry = conn_temp.execute(
            '''
                SELECT *
                FROM temp_cards
                WHERE id = ?
                  AND status = 'approved'
            ''',
            (entry_id,),
        ).fetchone()

        if not entry:
            raise ValueError('Approved entry not found')

        export_result = upsert_main_card(entry, conn_main, require_complete=True)
        conn_main.commit()

        local_front_image = entry['front_image'] or ''
        local_back_image = entry['back_image'] or ''
        delete_uploaded_file(local_front_image)
        delete_uploaded_file(local_back_image)

        completed_at = datetime.now().isoformat()
        response_payload = {
            'entry_id': entry_id,
            'cert_id': entry['cert_id'],
            'action': export_result['action'],
            'front_image': export_result['front_image'],
            'back_image': export_result['back_image'],
        }
        conn_temp.execute(
            '''
                UPDATE temp_cards
                SET upload_status = 'uploaded',
                    upload_started = ?,
                    upload_completed = ?,
                    front_image = '',
                    back_image = '',
                    published_front_image = ?,
                    published_back_image = ?,
                    upload_error = NULL,
                    server_response = ?
                WHERE id = ?
            ''',
            (
                started_at,
                completed_at,
                export_result['front_image'],
                export_result['back_image'],
                json.dumps(response_payload),
                entry_id,
            ),
        )
        conn_temp.commit()

        return jsonify({
            'success': True,
            'entry_id': entry_id,
            'cert_id': entry['cert_id'],
            'upload_status': 'uploaded',
            'action': export_result['action'],
            'front_image': export_result['front_image'],
            'back_image': export_result['back_image'],
            'message': f"Upload completed ({export_result['action']})",
        })

    except Exception as exc:
        conn_main.rollback()
        completed_at = datetime.now().isoformat()
        error_message = str(exc)
        conn_temp.execute(
            '''
                UPDATE temp_cards
                SET upload_status = 'failed',
                    upload_started = COALESCE(upload_started, ?),
                    upload_completed = ?,
                    upload_error = ?
                WHERE id = ?
            ''',
            (started_at, completed_at, error_message, entry_id),
        )
        conn_temp.commit()
        return jsonify({'success': False, 'error': error_message, 'entry_id': entry_id}), 400

    finally:
        conn_temp.close()
        conn_main.close()


@app.route('/admin/api/upload/<int:entry_id>/client-pushed', methods=['POST'])
@app.route('/api/upload/<int:entry_id>/client-pushed', methods=['POST'])
@login_required
def api_mark_client_pushed(entry_id):
    """API: 标记条目已推送给客户端"""
    conn_temp = get_temp_db_connection()
    try:
        entry = conn_temp.execute(
            '''
                SELECT id, cert_id, status, upload_status, upload_completed
                FROM temp_cards
                WHERE id = ?
            ''',
            (entry_id,),
        ).fetchone()
        if not entry:
            return jsonify({'success': False, 'error': 'Entry not found'}), 404
        if (entry['status'] or '').strip().lower() != 'approved':
            return jsonify({'success': False, 'error': 'Only approved entries can be marked'}), 400
        if (entry['upload_status'] or '').strip().lower() != 'uploaded':
            return jsonify({'success': False, 'error': 'Only uploaded entries can be marked as client pushed'}), 400

        completed_at = entry['upload_completed'] or datetime.now().isoformat()
        conn_temp.execute(
            '''
                UPDATE temp_cards
                SET upload_status = ?,
                    upload_completed = ?
                WHERE id = ?
            ''',
            (CLIENT_PUSHED_UPLOAD_STATUS, completed_at, entry_id),
        )
        conn_temp.commit()

        return jsonify({
            'success': True,
            'entry_id': entry_id,
            'cert_id': entry['cert_id'],
            'upload_status': CLIENT_PUSHED_UPLOAD_STATUS,
            'message': 'Marked as pushed to client',
        })
    finally:
        conn_temp.close()

@app.route('/admin/api/batch-upload', methods=['POST'])
@app.route('/api/batch-upload', methods=['POST'])
@login_required
def api_batch_upload():
    """API: 批量上传数据"""
    data = request.get_json()
    entry_ids = data.get('entry_ids', [])

    if not entry_ids:
        return jsonify({'success': False, 'error': 'No entries selected'})

    results = []

    for entry_id in entry_ids:
        # 调用单条上传API
        result = api_upload_entry(entry_id)
        results.append(result.get_json())

    # 统计结果
    success_count = sum(1 for r in results if r.get('success'))
    failed_count = len(results) - success_count

    return jsonify({
        'success': True,
        'total': len(results),
        'success_count': success_count,
        'failed_count': failed_count,
        'results': results
    })

# ========== Main Application ==========

# ========== Serve Uploaded Files ==========
@app.route('/admin/uploads/<filename>')
def uploaded_file(filename):
    """提供上传的文件"""
    return send_from_directory(app.config['UPLOAD_FOLDER'], filename)

# ========== Excel Export Functions ==========
def get_grade_options_from_db():
    """从数据库获取所有可用的final grade选项"""
    conn = get_temp_db_connection()
    grades = get_grade_filter_options(conn, status_filter='approved')
    conn.close()

    return grades

def get_grade_stats_from_db():
    """从数据库获取各评分等级的数量统计"""
    conn = get_temp_db_connection()
    cursor = conn.cursor()

    grade_options = get_grade_filter_options(conn, status_filter='approved')
    grade_stats = {}

    for grade in grade_options:
        cursor.execute(f"""
            SELECT COUNT(*) FROM temp_cards
            WHERE status = 'approved' AND {build_grade_filter_sql(grade)}
        """, (grade,))
        grade_stats[grade] = cursor.fetchone()[0]

    cursor.execute("SELECT COUNT(*) FROM temp_cards WHERE status = 'approved'")
    total_approved = cursor.fetchone()[0]

    conn.close()

    return total_approved, grade_stats

# ========== Excel Export Page ==========
@app.route('/admin/export/excel')
@login_required
def export_excel_page():
    """Excel导出页面"""
    total_approved, grade_stats = get_grade_stats_from_db()
    grade_options = [grade for grade in STANDARD_GRADE_OPTIONS if grade_stats.get(grade, 0) > 0]

    return render_template('export_excel.html',
                         grade_options=grade_options,
                         total_approved=total_approved,
                         grade_stats=grade_stats,
                         brand_options=BRAND_OPTIONS,
                         language_options=LANGUAGE_OPTIONS)

# ========== Generate Excel File ==========
@app.route('/admin/export/generate-excel', methods=['POST'])
@login_required
def generate_excel():
    """生成Excel文件"""
    try:
        import pandas as pd

        grade_filter = normalize_final_grade_text(request.form.get('grade_filter', '').strip())
        if request.form.get('grade_filter', '').strip() == 'all':
            grade_filter = None

        # 构建查询
        query = "SELECT * FROM temp_cards WHERE status = 'approved'"
        params = []

        if grade_filter:
            query += f" AND {build_grade_filter_sql(grade_filter)}"
            params.append(grade_filter)

        query += f" ORDER BY {build_approved_order_clause()}"

        # 执行查询
        conn = get_temp_db_connection()
        df = pd.read_sql_query(query, conn, params=params)
        conn.close()

        if df.empty:
            flash('没有找到匹配的数据', 'warning')
            return redirect(url_for('export_excel_page'))

        # 添加landing page url列
        df['landing_page_url'] = df['cert_id'].apply(lambda x: f"nxrgrading.com/card/{str(x).strip()}")
        if 'final_grade_text' in df.columns:
            df['final_grade_text'] = df['final_grade_text'].apply(normalize_final_grade_text).replace('', pd.NA).fillna(df['final_grade_text'])

        # 重新排列列顺序，将landing_page_url放在最后
        columns = [col for col in df.columns if col != 'landing_page_url']
        columns.append('landing_page_url')
        df = df[columns]

        # 生成输出文件名
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        grade_suffix = f"_{grade_filter}" if grade_filter else "_all"
        exports_dir = ADMIN_DIR / "exports"
        exports_dir.mkdir(exist_ok=True)

        output_filename = f"approved_cards{grade_suffix}_{timestamp}.xlsx"
        output_path = exports_dir / output_filename

        # 导出到Excel
        with pd.ExcelWriter(output_path, engine='openpyxl') as writer:
            # 主数据表
            df.to_excel(writer, sheet_name='Approved Cards', index=False)

            # 添加汇总表
            summary_data = {
                '导出时间': [datetime.now().strftime("%Y-%m-%d %H:%M:%S")],
                '总记录数': [len(df)],
                '筛选条件': [f"final_grade_text = {grade_filter}" if grade_filter else "全部"],
                '数据范围': [format_export_date_range(df)],
                '包含字段数': [len(df.columns)],
                '文件名称': [output_filename]
            }

            summary_df = pd.DataFrame(summary_data)
            summary_df.to_excel(writer, sheet_name='导出汇总', index=False)

            # 添加评分统计表
            if 'final_grade_text' in df.columns:
                grade_stats = df['final_grade_text'].value_counts().reset_index()
                grade_stats.columns = ['评分等级', '数量']
                grade_stats['占比'] = (grade_stats['数量'] / len(df) * 100).round(1).astype(str) + '%'
                grade_stats.to_excel(writer, sheet_name='评分统计', index=False)

        # 记录导出历史
        export_history_path = exports_dir / "export_history.json"
        history = load_export_history(export_history_path)

        history.append({
            'filename': output_filename,
            'grade_filter': grade_filter,
            'record_count': len(df),
            'export_time': datetime.now().isoformat(),
            'file_size': os.path.getsize(output_path)
        })

        # 只保留最近50条记录
        if len(history) > 50:
            history = history[-50:]

        with open(export_history_path, 'w', encoding='utf-8') as f:
            json.dump(history, f, ensure_ascii=False, indent=2)

        flash(f'Excel文件生成成功: {output_filename} ({len(df)} 条记录)', 'success')
        return redirect(url_for('export_excel_page'))

    except Exception as e:
        flash(f'生成Excel文件失败: {str(e)}', 'error')
        return redirect(url_for('export_excel_page'))

# ========== Download Excel File ==========
@app.route('/admin/export/download/<filename>')
@login_required
def download_excel(filename):
    """下载Excel文件"""
    file_path = resolve_export_file_path(filename)

    if not file_path or not file_path.exists():
        flash('文件不存在', 'error')
        return redirect(url_for('export_excel_page'))

    return send_file(file_path, as_attachment=True)

# ========== List Export Files ==========
@app.route('/admin/export/list')
@login_required
def list_exports():
    """列出所有导出文件"""
    exports_dir = ADMIN_DIR / "exports"

    if not exports_dir.exists():
        return jsonify({'exports': []})

    # 获取所有Excel文件
    excel_files = list(exports_dir.glob("*.xlsx"))

    # 按修改时间排序
    excel_files.sort(key=lambda x: x.stat().st_mtime, reverse=True)

    exports = []
    for file in excel_files:
        file_stat = file.stat()
        exports.append({
            'name': file.name,
            'size': file_stat.st_size,
            'modified': datetime.fromtimestamp(file_stat.st_mtime).strftime("%Y-%m-%d %H:%M:%S"),
            'url': url_for('download_excel', filename=file.name)
        })

    return jsonify({'exports': exports})

# ========== Delete Export File ==========
@app.route('/admin/export/delete/<filename>', methods=['POST'])
@login_required
def delete_export(filename):
    """删除导出文件"""
    file_path = resolve_export_file_path(filename)

    if file_path and file_path.exists():
        try:
            file_path.unlink()
            if request.headers.get('X-Requested-With') == 'XMLHttpRequest':
                return jsonify({'success': True, 'filename': file_path.name})
            flash(f'文件已删除: {file_path.name}', 'success')
        except Exception as e:
            if request.headers.get('X-Requested-With') == 'XMLHttpRequest':
                return jsonify({'success': False, 'error': str(e)}), 500
            flash(f'删除文件失败: {str(e)}', 'error')
    else:
        if request.headers.get('X-Requested-With') == 'XMLHttpRequest':
            return jsonify({'success': False, 'error': 'File not found'}), 404
        flash('文件不存在', 'error')

    return redirect(url_for('export_excel_page'))


initialize_databases()


if __name__ == '__main__':
    port = int(os.environ.get('PORT', '8081'))
    debug = os.environ.get('FLASK_DEBUG') == '1'

    print("=" * 60)
    print("NXR Card Grading - Manual Data Entry System (UPDATED)")
    print("=" * 60)
    print(f"Access: http://localhost:{port}/admin")
    print("Login: configured admin accounts")
    print("=" * 60)
    print("Updated Features:")
    print("  - NEW: Four sub-scores (centering, edges, corners, surface)")
    print("  - NEW: Automatic final grade calculation")
    print("  - NEW: Grade mapping: 8, 8.5, 9, 9.5, 10, Pristine 10")
    print("  - REMOVED: grade field, player field")
    print("  - REQUIRED: card_number, set_name, language")
    print("  - Auto-generated 10-digit Cert ID")
    print("  - Brand dropdown with English options")
    print("  - English-only interface")
    print("  - Temporary storage (temp_cards table)")
    print("  - Review workflow (pending/approved)")
    print("  - Export to main database")
    print("=" * 60)

    app.run(
        host='0.0.0.0',
        port=port,
        debug=debug,
        use_reloader=False,
    )
