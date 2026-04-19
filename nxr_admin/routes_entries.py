from nxr_admin.admin_core import *

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
    page_size = get_page_size_arg(default=TEMP_LIST_DEFAULT_PAGE_SIZE)
    
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
    total_pages = max((total_matching + page_size - 1) // page_size, 1)
    if page > total_pages:
        page = total_pages

    offset = (page - 1) * page_size

    # Add ORDER BY clause
    query += where_clause
    query += f" ORDER BY {build_entry_list_order_clause(status_filter, sort_by, sort_order)} LIMIT ? OFFSET ?"

    # Execute query
    entries = conn.execute(query, [*params, page_size, offset]).fetchall()
    
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
        'page_size': page_size,
    })

    page_start = ((page - 1) * page_size) + 1 if total_matching else 0
    page_end = min(page * page_size, total_matching)

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
                         page_size=page_size,
                         page_size_options=PAGE_SIZE_OPTIONS,
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

