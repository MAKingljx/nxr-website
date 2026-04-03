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
from datetime import datetime
from pathlib import Path
from flask import send_from_directory, send_file, Flask, render_template, request, redirect, url_for, session, flash, jsonify
from werkzeug.utils import secure_filename
from functools import wraps

# Configuration
BASE_DIR = Path(__file__).resolve().parent.parent
ADMIN_DIR = Path(__file__).resolve().parent
DB_PATH = BASE_DIR / "cards.db"
TEMP_DB_PATH = ADMIN_DIR / "temp_cards.db"

# Ensure directories exist
UPLOAD_FOLDER = ADMIN_DIR / "uploads"
UPLOAD_FOLDER.mkdir(exist_ok=True)

# Brand options (English)
BRAND_OPTIONS = [
    "Pokemon",
    "One Piece",
    "Sports Cards",
    "Yu-Gi-Oh!",
    "Magic: The Gathering",
    "Dragon Ball",
    "Marvel",
    "DC Comics",
    "Disney",
    "Other"
]

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
            static_folder=str(ADMIN_DIR / 'static'))
app.secret_key = 'nxr-manual-entry-2026-updated'
app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER

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
        entry_notes TEXT DEFAULT '',
        entry_by TEXT DEFAULT '',
        entry_date TEXT,
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
        ('upload_status', 'TEXT DEFAULT "not_started"'),
        ('upload_started', 'TEXT'),
        ('upload_completed', 'TEXT'),
        ('upload_error', 'TEXT'),
        ('server_response', 'TEXT')
    ]

    for column_name, column_type in upload_columns:
        try:
            cursor.execute(f"SELECT {column_name} FROM temp_cards LIMIT 1")
        except sqlite3.OperationalError:
            print(f"Adding {column_name} column to temp_cards table...")
            cursor.execute(f"ALTER TABLE temp_cards ADD COLUMN {column_name} {column_type}")

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

# ========== Login ==========
@app.route('/admin/login', methods=['GET', 'POST'])
def admin_login():
    if request.method == 'POST':
        username = request.form.get('username', '').strip()
        password = request.form.get('password', '').strip()

        # Simple account system with 3 accounts
        valid_accounts = {
            'admin': {'password': 'nxr2026', 'role': 'admin'},
            'reviewer1': {'password': 'review123', 'role': 'reviewer'},
            'reviewer2': {'password': 'review456', 'role': 'reviewer'}
        }

        if username in valid_accounts and password == valid_accounts[username]['password']:
            session['admin_logged_in'] = True
            session['username'] = username
            session['role'] = valid_accounts[username]['role']
            flash(f'Login successful! Welcome {username}', 'success')
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

    stats = {
        'total_entries': conn.execute('SELECT COUNT(*) FROM temp_cards').fetchone()[0],
        'pending': conn.execute("SELECT COUNT(*) FROM temp_cards WHERE status = 'pending'").fetchone()[0],
        'approved': conn.execute("SELECT COUNT(*) FROM temp_cards WHERE status = 'approved'").fetchone()[0],
    }

    recent_entries = conn.execute('''
        SELECT id, cert_id, card_name, brand, final_grade_text, status, entry_date
        FROM temp_cards
        ORDER BY entry_date DESC
        LIMIT 5
    ''').fetchall()

    conn.close()

    return render_template('dashboard.html',
                         stats=stats,
                         recent_entries=recent_entries,
                         username=session.get('username', 'Operator'),
                         role=session.get('role', 'reviewer'),
                         brand_options=BRAND_OPTIONS,
                         language_options=LANGUAGE_OPTIONS)

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
        language = request.form.get('language', 'English').strip()

        # Calculate POP (Population) automatically
        # Count existing cards with same identity and same grade
        conn_temp = get_temp_db_connection()
        cursor_temp = conn_temp.cursor()

        # Count in temporary database
        cursor_temp.execute('''
            SELECT COUNT(*) FROM temp_cards
            WHERE card_name = ? AND set_name = ? AND card_number = ? AND language = ?
            AND final_grade_text = ?
        ''', (card_name, set_name, card_number, language, final_grade_text))
        temp_count = cursor_temp.fetchone()[0]

        # Count in main database
        conn_main = get_main_db_connection()
        cursor_main = conn_main.cursor()
        cursor_main.execute('''
            SELECT COUNT(*) FROM cards
            WHERE card_name = ? AND set_name = ? AND card_number = ? AND language = ?
            AND final_grade_text = ?
        ''', (card_name, set_name, card_number, language, final_grade_text))
        main_count = cursor_main.fetchone()[0]

        # Total POP = existing count + 1 (current card)
        total_pop = temp_count + main_count + 1

        conn_temp.close()
        conn_main.close()

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
                flash(f'{field.replace("_", " ").title()} is required', 'error')
                return redirect(url_for('new_entry'))

        # Save to temporary database
        conn = get_temp_db_connection()
        try:
            cursor = conn.cursor()

            # Check if cert_id already exists
            cursor.execute("SELECT COUNT(*) FROM temp_cards WHERE cert_id = ?", (entry_data['cert_id'],))
            if cursor.fetchone()[0] > 0:
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
    card_name_filter = request.args.get('card_name', '').strip()
    final_grade_filter = request.args.get('final_grade', '').strip()
    set_name_filter = request.args.get('set_name', '').strip()
    language_filter = request.args.get('language', '').strip()
    sort_by = request.args.get('sort_by', 'entry_date')
    sort_order = request.args.get('sort_order', 'desc')
    
    # Validate sort parameters
    valid_sort_columns = ['entry_date', 'card_name', 'final_grade', 'set_name', 'language']
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
    
    if card_name_filter:
        conditions.append("card_name LIKE ?")
        params.append(f"%{card_name_filter}%")
    
    if final_grade_filter:
        conditions.append("final_grade_text = ?")
        params.append(final_grade_filter)
    
    if set_name_filter:
        conditions.append("set_name LIKE ?")
        params.append(f"%{set_name_filter}%")
    
    if language_filter:
        conditions.append("language = ?")
        params.append(language_filter)
    
    # Add WHERE clause if there are conditions
    if conditions:
        query += " WHERE " + " AND ".join(conditions)
    
    # Add ORDER BY clause
    query += f" ORDER BY {sort_by} {sort_order.upper()}"
    
    # Execute query
    entries = conn.execute(query, params).fetchall()
    
    # Get available filter options
    # Get unique final grades for filter dropdown
    grade_options = []
    if status_filter == 'approved' or status_filter == 'all':
        grade_result = conn.execute("""
            SELECT DISTINCT final_grade_text 
            FROM temp_cards 
            WHERE final_grade_text IS NOT NULL AND final_grade_text != ''
            ORDER BY final_grade_text
        """).fetchall()
        grade_options = [row[0] for row in grade_result]
    
    # Get unique set names for filter dropdown
    set_options = []
    if status_filter == 'approved' or status_filter == 'all':
        set_result = conn.execute("""
            SELECT DISTINCT set_name 
            FROM temp_cards 
            WHERE set_name IS NOT NULL AND set_name != ''
            ORDER BY set_name
        """).fetchall()
        set_options = [row[0] for row in set_result]
    
    # Get status counts
    status_counts = {
        'all': conn.execute("SELECT COUNT(*) FROM temp_cards").fetchone()[0],
        'pending': conn.execute("SELECT COUNT(*) FROM temp_cards WHERE status = 'pending'").fetchone()[0],
        'approved': conn.execute("SELECT COUNT(*) FROM temp_cards WHERE status = 'approved'").fetchone()[0],
    }

    conn.close()

    return render_template('entry_list.html',
                         entries=entries,
                         status_filter=status_filter,
                         status_counts=status_counts,
                         brand_options=BRAND_OPTIONS,
                         language_options=LANGUAGE_OPTIONS,
                         # Filter values
                         card_name_filter=card_name_filter,
                         final_grade_filter=final_grade_filter,
                         set_name_filter=set_name_filter,
                         language_filter=language_filter,
                         # Filter options
                         grade_options=grade_options,
                         set_options=set_options,
                         # Sort values
                         sort_by=sort_by,
                         sort_order=sort_order)

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

    return render_template('entry_detail.html',
                         entry=entry,
                         brand_options=BRAND_OPTIONS,
                         language_options=LANGUAGE_OPTIONS)

# ========== Edit Entry ==========
@app.route('/admin/entries/<int:entry_id>/edit', methods=['GET', 'POST'])
@login_required
def edit_entry(entry_id):
    conn = get_temp_db_connection()

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
        language = request.form.get('language', 'English').strip()

        # Calculate POP (Population) automatically
        # Count existing cards with same identity and same grade
        conn_temp = get_temp_db_connection()
        cursor_temp = conn_temp.cursor()

        # Count in temporary database (excluding current entry)
        cursor_temp.execute('''
            SELECT COUNT(*) FROM temp_cards
            WHERE card_name = ? AND set_name = ? AND card_number = ? AND language = ?
            AND final_grade_text = ? AND id != ?
        ''', (card_name, set_name, card_number, language, final_grade_text, entry_id))
        temp_count = cursor_temp.fetchone()[0]

        # Count in main database
        conn_main = get_main_db_connection()
        cursor_main = conn_main.cursor()
        cursor_main.execute('''
            SELECT COUNT(*) FROM cards
            WHERE card_name = ? AND set_name = ? AND card_number = ? AND language = ?
            AND final_grade_text = ?
        ''', (card_name, set_name, card_number, language, final_grade_text))
        main_count = cursor_main.fetchone()[0]

        # Total POP = existing count + 1 (current card)
        total_pop = temp_count + main_count + 1

        conn_temp.close()
        conn_main.close()

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

        if back_image_filename:
            update_data['back_image'] = back_image_filename

        # Validate required fields
        required_fields = ['card_name', 'brand', 'language', 'set_name', 'card_number']
        for field in required_fields:
            if not update_data[field]:
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

            flash(f"Entry updated successfully. New grade: {final_grade_text}", 'success')
            conn.close()
            return redirect(url_for('entry_detail', entry_id=entry_id))

        except Exception as e:
            conn.rollback()
            flash(f"Error updating entry: {str(e)}", 'error')
            conn.close()
            return redirect(url_for('edit_entry', entry_id=entry_id))

    # GET request - show edit form
    entry = conn.execute("SELECT * FROM temp_cards WHERE id = ?", (entry_id,)).fetchone()
    conn.close()

    if not entry:
        flash('Entry not found', 'error')
        return redirect(url_for('entry_list'))

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
        conn.execute("UPDATE temp_cards SET status = 'approved', updated_at = ? WHERE id = ?",
                    (datetime.now().isoformat(), entry_id))
        conn.commit()
        flash('Entry approved successfully', 'success')
    except Exception as e:
        conn.rollback()
        flash(f'Error approving entry: {str(e)}', 'error')

    conn.close()
    return redirect(url_for('entry_detail', entry_id=entry_id))

# ========== Export to Main Database ==========
@app.route('/admin/export/approved')
@login_required
def export_approved():
    conn_temp = get_temp_db_connection()
    conn_main = get_main_db_connection()

    try:
        approved_entries = conn_temp.execute("SELECT * FROM temp_cards WHERE status = 'approved'").fetchall()

        exported = 0
        for entry in approved_entries:
            # Check if exists in main DB
            cursor = conn_main.cursor()
            cursor.execute("SELECT COUNT(*) FROM cards WHERE cert_id = ?", (entry['cert_id'],))

            if cursor.fetchone()[0] == 0:
                # Prepare data for main DB
                entry_dict = dict(entry)
                # Remove temp DB specific fields
                for field in ['id', 'status', 'entry_notes', 'entry_by', 'entry_date']:
                    entry_dict.pop(field, None)

                # Insert into main DB
                columns = ', '.join(entry_dict.keys())
                placeholders = ', '.join(['?' for _ in entry_dict])
                values = tuple(entry_dict.values())

                conn_main.execute(f"INSERT INTO cards ({columns}) VALUES ({placeholders})", values)
                exported += 1

        conn_main.commit()
        flash(f'Exported {exported} entries to main database', 'success')

    except Exception as e:
        conn_main.rollback()
        flash(f'Error exporting: {str(e)}', 'error')

    finally:
        conn_temp.close()
        conn_main.close()

    return redirect(url_for('entry_list'))

# ========== API: Generate Cert ID ==========
@app.route('/api/generate-cert-id')
@login_required
def api_generate_cert_id():
    """API endpoint to generate a new unique Cert ID"""
    cert_id = generate_cert_id()
    return jsonify({'cert_id': cert_id})

# ========== API: Calculate Grade ==========
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
@app.route('/api/calculate-pop', methods=['POST'])
@login_required
def api_calculate_pop():
    """API endpoint to calculate POP (Population)"""
    try:
        data = request.get_json()
        card_name = data.get('card_name', '').strip()
        set_name = data.get('set_name', '').strip()
        card_number = data.get('card_number', '').strip()
        language = data.get('language', 'English').strip()
        final_grade_text = data.get('final_grade_text', '').strip()
        current_entry_id = data.get('current_entry_id')

        # Validate required fields
        if not all([card_name, set_name, card_number, language, final_grade_text]):
            return jsonify({'pop': '1', 'message': 'Incomplete data for POP calculation'})

        # Count in temporary database
        conn_temp = get_temp_db_connection()
        cursor_temp = conn_temp.cursor()

        if current_entry_id:
            # For edit: exclude current entry
            cursor_temp.execute('''
                SELECT COUNT(*) FROM temp_cards
                WHERE card_name = ? AND set_name = ? AND card_number = ? AND language = ?
                AND final_grade_text = ? AND id != ?
            ''', (card_name, set_name, card_number, language, final_grade_text, current_entry_id))
        else:
            # For new entry
            cursor_temp.execute('''
                SELECT COUNT(*) FROM temp_cards
                WHERE card_name = ? AND set_name = ? AND card_number = ? AND language = ?
                AND final_grade_text = ?
            ''', (card_name, set_name, card_number, language, final_grade_text))

        temp_count = cursor_temp.fetchone()[0]
        conn_temp.close()

        # Count in main database
        conn_main = get_main_db_connection()
        cursor_main = conn_main.cursor()
        cursor_main.execute('''
            SELECT COUNT(*) FROM cards
            WHERE card_name = ? AND set_name = ? AND card_number = ? AND language = ?
            AND final_grade_text = ?
        ''', (card_name, set_name, card_number, language, final_grade_text))
        main_count = cursor_main.fetchone()[0]
        conn_main.close()

        # Total POP = existing count + 1 (current card)
        total_pop = temp_count + main_count + 1

        return jsonify({
            'pop': str(total_pop),
            'calculation': f'Temporary DB: {temp_count} + Main DB: {main_count} + 1 = {total_pop}',
            'details': {
                'card_identity': f'{card_name} / {set_name} / {card_number} / {language}',
                'grade': final_grade_text,
                'temp_count': temp_count,
                'main_count': main_count
            }
        })

    except Exception as e:
        return jsonify({'error': str(e), 'pop': '1'}), 400

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
    per_page = 50

    conn = get_temp_db_connection()
    cursor = conn.cursor()

    # 获取所有已批准数据（包括图片不完整的）
    offset = (page - 1) * per_page

    cursor.execute('''
        SELECT * FROM temp_cards
        WHERE status = 'approved'
        ORDER BY entry_date DESC
        LIMIT ? OFFSET ?
    ''', (per_page, offset))

    entries = cursor.fetchall()

    # 获取总数
    cursor.execute('''
        SELECT COUNT(*) FROM temp_cards
        WHERE status = 'approved'
    ''')
    total = cursor.fetchone()[0]

    # 获取统计信息
    cursor.execute('''
        SELECT upload_status, COUNT(*)
        FROM temp_cards
        WHERE status = 'approved'
        GROUP BY upload_status
    ''')

    stats = {}
    for status, count in cursor.fetchall():
        stats[status or 'not_started'] = count

    # 图片完整性统计
    cursor.execute('''
        SELECT
            COUNT(*) as total_approved,
            SUM(CASE WHEN front_image IS NOT NULL AND front_image != '' THEN 1 ELSE 0 END) as has_front,
            SUM(CASE WHEN back_image IS NOT NULL AND back_image != '' THEN 1 ELSE 0 END) as has_back,
            SUM(CASE WHEN front_image IS NOT NULL AND front_image != ''
                     AND back_image IS NOT NULL AND back_image != '' THEN 1 ELSE 0 END) as has_both
        FROM temp_cards
        WHERE status = 'approved'
    ''')

    row = cursor.fetchone()
    stats['image_stats'] = {
        'total_approved': row[0],
        'has_front_image': row[1],
        'has_back_image': row[2],
        'ready_for_upload': row[3]
    }

    conn.close()

    total_pages = (total + per_page - 1) // per_page

    return render_template('upload_manager.html',
                         entries=entries,
                         page=page,
                         per_page=per_page,
                         total=total,
                         total_pages=total_pages,
                         stats=stats,
                         brand_options=BRAND_OPTIONS,
                         language_options=LANGUAGE_OPTIONS)

@app.route('/api/upload-stats')
@login_required
def api_upload_stats():
    """API: 获取上传统计信息"""
    conn = get_temp_db_connection()
    cursor = conn.cursor()

    stats = {}

    # 各状态数量
    cursor.execute('''
        SELECT upload_status, COUNT(*)
        FROM temp_cards
        WHERE status = 'approved'
        GROUP BY upload_status
    ''')

    for status, count in cursor.fetchall():
        stats[status or 'not_started'] = count

    # 图片完整性统计
    cursor.execute('''
        SELECT
            COUNT(*) as total_approved,
            SUM(CASE WHEN front_image IS NOT NULL AND front_image != '' THEN 1 ELSE 0 END) as has_front,
            SUM(CASE WHEN back_image IS NOT NULL AND back_image != '' THEN 1 ELSE 0 END) as has_back,
            SUM(CASE WHEN front_image IS NOT NULL AND front_image != ''
                     AND back_image IS NOT NULL AND back_image != '' THEN 1 ELSE 0 END) as has_both
        FROM temp_cards
        WHERE status = 'approved'
    ''')

    row = cursor.fetchone()
    stats['image_stats'] = {
        'total_approved': row[0],
        'has_front_image': row[1],
        'has_back_image': row[2],
        'ready_for_upload': row[3]
    }

    conn.close()

    return jsonify(stats)

@app.route('/api/upload/<int:entry_id>', methods=['POST'])
@login_required
def api_upload_entry(entry_id):
    """API: 上传单条数据到服务器"""
    # 这里需要实现实际的上传逻辑
    # 暂时返回模拟响应

    conn = get_temp_db_connection()
    cursor = conn.cursor()

    # 获取条目信息
    cursor.execute('SELECT cert_id FROM temp_cards WHERE id = ?', (entry_id,))
    entry = cursor.fetchone()

    if not entry:
        conn.close()
        return jsonify({'success': False, 'error': 'Entry not found'})

    cert_id = entry[0]

    # 模拟上传过程
    import time
    time.sleep(1)  # 模拟上传延迟

    # 更新状态
    cursor.execute('''
        UPDATE temp_cards
        SET upload_status = 'uploaded',
            upload_started = ?,
            upload_completed = ?,
            server_response = ?
        WHERE id = ?
    ''', (
        datetime.now().isoformat(),
        datetime.now().isoformat(),
        json.dumps({'simulated': True, 'cert_id': cert_id}),
        entry_id
    ))

    conn.commit()
    conn.close()

    return jsonify({
        'success': True,
        'entry_id': entry_id,
        'cert_id': cert_id,
        'upload_status': 'uploaded',
        'message': 'Upload simulated successfully (server not configured)'
    })

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
    cursor = conn.cursor()

    cursor.execute("""
        SELECT DISTINCT final_grade_text
        FROM temp_cards
        WHERE status = 'approved' AND final_grade_text IS NOT NULL AND final_grade_text != ''
        ORDER BY final_grade_text
    """)

    grades = [row[0] for row in cursor.fetchall()]
    conn.close()

    return grades

def get_grade_stats_from_db():
    """从数据库获取各评分等级的数量统计"""
    conn = get_temp_db_connection()
    cursor = conn.cursor()

    grade_options = get_grade_options_from_db()
    grade_stats = {}

    for grade in grade_options:
        cursor.execute("""
            SELECT COUNT(*) FROM temp_cards
            WHERE status = 'approved' AND final_grade_text = ?
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
    grade_options = get_grade_options_from_db()

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
    import pandas as pd

    grade_filter = request.form.get('grade_filter', '').strip()
    if grade_filter == 'all':
        grade_filter = None

    # 构建查询
    query = "SELECT * FROM temp_cards WHERE status = 'approved'"
    params = []

    if grade_filter:
        query += " AND final_grade_text = ?"
        params.append(grade_filter)

    query += " ORDER BY entry_date DESC"

    try:
        # 执行查询
        conn = get_temp_db_connection()
        df = pd.read_sql_query(query, conn, params=params)
        conn.close()

        if df.empty:
            flash('没有找到匹配的数据', 'warning')
            return redirect(url_for('export_excel_page'))

        # 添加landing page url列
        df['landing_page_url'] = df['cert_id'].apply(lambda x: f"nxrgrading.com/card/{x}")

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
                '数据范围': [f"{df['entry_date'].min()[:10]} 至 {df['entry_date'].max()[:10]}" if len(df) > 0 else "无数据"],
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
        history = []
        if export_history_path.exists():
            with open(export_history_path, 'r', encoding='utf-8') as f:
                history = json.load(f)

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
    exports_dir = ADMIN_DIR / "exports"
    file_path = exports_dir / filename

    if not file_path.exists():
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
    exports_dir = ADMIN_DIR / "exports"
    file_path = exports_dir / filename

    if file_path.exists():
        try:
            file_path.unlink()
            flash(f'文件已删除: {filename}', 'success')
        except Exception as e:
            flash(f'删除文件失败: {str(e)}', 'error')
    else:
        flash('文件不存在', 'error')

    return redirect(url_for('export_excel_page'))


if __name__ == '__main__':
    # Initialize temporary database
    init_temp_database()

    print("=" * 60)
    print("NXR Card Grading - Manual Data Entry System (UPDATED)")
    print("=" * 60)
    print("Access: http://localhost:8081/admin")
    print("Login: admin / nxr2026")
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

    app.run(debug=True, port=8081, host='0.0.0.0', use_reloader=False)