from nxr_admin.admin_core import *

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


