from nxr_admin.admin_core import *


def collect_category_form_data():
    product_type = normalize_submitted_product_type(
        request.form.get('product_type', DEFAULT_PRODUCT_TYPE)
    )
    category = normalize_card_category(request.form.get('card_category', DEFAULT_CARD_CATEGORY))
    if not product_uses_grading(product_type):
        category = DEFAULT_CARD_CATEGORY
    movie_name = request.form.get('movie_name', '').strip()
    release_year = request.form.get('release_year', '').strip()
    production_company = request.form.get('production_company', '').strip()
    film_type = request.form.get('film_type', '').strip()
    sports_type = normalize_sports_type(request.form.get('sports_type', ''))
    group_name = request.form.get('group_name', '').strip()

    if category == 'movie_film':
        card_name = movie_name
        year = release_year
        variety = film_type
        sports_type = ''
        group_name = ''
        brand = ''
        language = ''
        set_name = ''
        card_number = ''
    else:
        card_name = request.form.get('card_name', '').strip()
        year = request.form.get('year', '').strip()
        variety = request.form.get('variety', '').strip()
        brand = request.form.get('brand', '').strip()
        language = request.form.get('language', '').strip()
        set_name = request.form.get('set_name', '').strip()
        card_number = request.form.get('card_number', '').strip()
        movie_name = ''
        release_year = ''
        production_company = ''
        film_type = ''
        if category != 'sports_card':
            sports_type = ''
        if category != 'celebrity_card':
            group_name = ''

    return {
        'product_type': product_type,
        'vintage_classification': (
            normalize_vintage_classification(request.form.get('vintage_classification', ''))
            if product_type == 'vintage_product'
            else ''
        ),
        'merch_description': (
            request.form.get('merch_description', '').strip()
            if product_type == 'merch_product'
            else ''
        ),
        'card_category': category,
        'card_name': card_name,
        'movie_name': movie_name,
        'release_year': release_year,
        'production_company': production_company,
        'film_type': film_type,
        'sports_type': sports_type,
        'group_name': group_name,
        'year': year,
        'brand': brand,
        'variety': variety,
        'language': language,
        'set_name': set_name,
        'card_number': card_number,
    }


def is_pop_request_complete(card_data, final_grade_text):
    product_type = normalize_product_type(card_data.get('product_type'))
    category = normalize_card_category(card_data.get('card_category'))
    required = ['final_grade_text'] if product_uses_grading(product_type) else []
    if category == 'movie_film':
        required.extend(['movie_name', 'release_year', 'production_company', 'film_type'])
    else:
        required.extend(['card_name', 'set_name', 'card_number', 'language'])
        if category == 'sports_card':
            required.append('sports_type')
        elif category == 'celebrity_card':
            required.append('group_name')
    if product_type == 'vintage_product':
        required.append('vintage_classification')

    values = {**card_data, 'final_grade_text': final_grade_text}
    return all(str(values.get(field) or '').strip() for field in required)


def calculate_population_for_card_data(card_data, final_grade_text, exclude_entry_id=None):
    return calculate_population(
        card_data.get('card_name', ''),
        card_data.get('set_name', ''),
        card_data.get('card_number', ''),
        card_data.get('language', ''),
        final_grade_text,
        exclude_entry_id=exclude_entry_id,
        card_category=card_data.get('card_category', DEFAULT_CARD_CATEGORY),
        movie_name=card_data.get('movie_name', ''),
        release_year=card_data.get('release_year', ''),
        production_company=card_data.get('production_company', ''),
        film_type=card_data.get('film_type', ''),
        sports_type=card_data.get('sports_type', ''),
        group_name=card_data.get('group_name', ''),
        product_type=card_data.get('product_type', DEFAULT_PRODUCT_TYPE),
        vintage_classification=card_data.get('vintage_classification', ''),
    )


def collect_grading_data(product_type):
    if not product_uses_grading(product_type):
        return {
            'centering': 1.0,
            'edges': 1.0,
            'corners': 1.0,
            'surface': 1.0,
            'final_grade': 1.0,
            'final_grade_text': '',
        }, ''

    raw_scores = {
        'centering': request.form.get('centering', '0').strip(),
        'edges': request.form.get('edges', '0').strip(),
        'corners': request.form.get('corners', '0').strip(),
        'surface': request.form.get('surface', '0').strip(),
    }
    is_valid, error_msg = validate_sub_scores(**raw_scores)
    if not is_valid:
        return None, error_msg
    scores = {name: float(value) for name, value in raw_scores.items()}
    final_grade, final_grade_text = calculate_final_grade(**scores)
    return {**scores, 'final_grade': final_grade, 'final_grade_text': final_grade_text}, ''

# ========== New Entry ==========
@app.route('/admin/entry/new', methods=['GET', 'POST'])
@login_required
def new_entry():
    if request.method == 'POST':
        try:
            card_data = collect_category_form_data()
        except ValueError as error:
            flash(str(error), 'error')
            return redirect(url_for('new_entry'))
        is_valid_product, product_error = validate_product_policy(card_data)
        if not is_valid_product:
            flash(product_error, 'error')
            return redirect(url_for('new_entry'))
        grading_data, error_msg = collect_grading_data(card_data['product_type'])
        if grading_data is None:
            flash(f'Invalid scores: {error_msg}', 'error')
            return redirect(url_for('new_entry'))
        total_pop, language, _, _ = calculate_population_for_card_data(
            card_data,
            grading_data['final_grade_text'],
        )

        # Handle file uploads
        front_image_filename = None
        back_image_filename = None
        try:
            if 'front_image' in request.files:
                front_image_file = request.files['front_image']
                front_image_filename = save_uploaded_file(front_image_file, 'front')

            if 'back_image' in request.files:
                back_image_file = request.files['back_image']
                back_image_filename = save_uploaded_file(back_image_file, 'back')
        except Exception:
            app.logger.exception('Failed to stage images for a new entry')
            cleanup_uncertain_queue_files(
                [front_image_filename, back_image_filename],
                connection_factory=get_temp_db_connection,
            )
            flash('Images could not be saved. No entry was created.', 'error')
            return redirect(url_for('new_entry'))

        # Prepare entry data
        entry_data = {
            'cert_id': request.form.get('cert_id', '').strip(),
            **card_data,
            'pop': str(total_pop),  # Auto-calculated POP
            'language': language,
            **grading_data,
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

        if not is_canonical_cert_id(entry_data['cert_id']):
            delete_uploaded_file(front_image_filename)
            delete_uploaded_file(back_image_filename)
            flash('Certificate ID must be exactly 10 digits and cannot start with zero', 'error')
            return redirect(url_for('new_entry'))

        # Validate required fields
        is_valid_entry, missing_label = validate_category_required_fields(entry_data, include_cert_id=True)
        if not is_valid_entry:
            delete_uploaded_file(front_image_filename)
            delete_uploaded_file(back_image_filename)
            flash(f'{missing_label} is required', 'error')
            return redirect(url_for('new_entry'))
        if certificate_id_exists(entry_data['cert_id']):
            delete_uploaded_file(front_image_filename)
            delete_uploaded_file(back_image_filename)
            flash(f"Certificate ID {entry_data['cert_id']} already exists", 'error')
            return redirect(url_for('new_entry'))

        # Save to temporary database
        conn = None
        try:
            conn = get_temp_db_connection()
            cursor = conn.cursor()

            # Insert into temporary database
            columns = ', '.join(entry_data.keys())
            placeholders = ', '.join(['?' for _ in entry_data])
            values = tuple(entry_data.values())

            cursor.execute(f"INSERT INTO temp_cards ({columns}) VALUES ({placeholders})", values)
            conn.commit()

            result_label = grading_data['final_grade_text'] or get_product_type_label(card_data['product_type'])
            flash(f"Card {entry_data['cert_id']} entered successfully! {result_label}", 'success')
            safe_close(conn, 'new entry connection')
            conn = None
            return redirect(url_for('entry_list'))

        except Exception:
            app.logger.exception('Failed to create entry %s', entry_data['cert_id'])
            safe_rollback(conn, 'new entry')
            safe_close(conn, 'new entry connection')
            conn = None

            verification_conn = None
            commit_was_applied = False
            try:
                verification_conn = get_temp_db_connection()
                committed_row = verification_conn.execute(
                    '''
                        SELECT cert_id, created_at, front_image, back_image
                        FROM temp_cards
                        WHERE cert_id = ?
                    ''',
                    (entry_data['cert_id'],),
                ).fetchone()
                commit_was_applied = bool(
                    committed_row is not None
                    and (committed_row['created_at'] or '') == entry_data['created_at']
                    and (committed_row['front_image'] or '') == entry_data['front_image']
                    and (committed_row['back_image'] or '') == entry_data['back_image']
                )
                if not commit_was_applied:
                    delete_queue_files_if_unreferenced(
                        verification_conn,
                        [front_image_filename, back_image_filename],
                    )
            except Exception:
                app.logger.exception(
                    'Could not reconcile new entry %s; files were retained',
                    entry_data['cert_id'],
                )
            finally:
                safe_close(verification_conn, 'new entry verification connection')

            if commit_was_applied:
                result_label = (
                    grading_data['final_grade_text']
                    or get_product_type_label(card_data['product_type'])
                )
                flash(
                    f"Card {entry_data['cert_id']} entered successfully! {result_label}",
                    'success',
                )
                return redirect(url_for('entry_list'))

            flash('Entry could not be saved. Please reload before trying again.', 'error')
            return redirect(url_for('new_entry'))

    # GET request - show empty form with auto-generated Cert ID
    auto_cert_id = generate_cert_id()

    return render_template('entry_form_updated.html',
                         title="New Card Entry",
                         action=url_for('new_entry'),
                         card=None,
                         auto_cert_id=auto_cert_id,
                         card_category_options=CARD_CATEGORY_OPTIONS,
                         product_type_options=PRODUCT_TYPE_OPTIONS,
                         vintage_classification_options=get_vintage_classification_options(),
                         sports_type_options=get_sports_type_options(),
                         brand_options=get_brand_options(),
                         language_options=LANGUAGE_OPTIONS)

# ========== Entry List ==========
@app.route('/admin/entries')
@login_required
def entry_list():
    # Get filter parameters
    status_filter = request.args.get('status', 'all')
    cert_id_filter = request.args.get('cert_id', '').strip()
    card_name_filter = request.args.get('card_name', '').strip()
    card_category_filter = normalize_card_category_filter(request.args.get('card_category', '').strip())
    product_type_filter = normalize_product_type_filter(request.args.get('product_type', '').strip())
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
    valid_sort_columns = ['entry_date', 'card_name', 'product_type', 'card_category', 'final_grade', 'set_name', 'language', 'cert_id', 'brand']
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

    if card_category_filter:
        conditions.append("COALESCE(NULLIF(card_category, ''), 'trading_card') = ?")
        params.append(card_category_filter)

    if product_type_filter:
        conditions.append(f"{product_type_sql_expression()} = ?")
        params.append(product_type_filter)
    
    if final_grade_filter:
        conditions.append(f"{product_type_sql_expression()} = 'graded_card' AND final_grade_text = ?")
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
        'card_category': card_category_filter,
        'product_type': product_type_filter,
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
                         brand_options=get_brand_options(),
                         language_options=LANGUAGE_OPTIONS,
                         # Filter values
                         cert_id_filter=cert_id_filter,
                         card_name_filter=card_name_filter,
                         card_category_filter=card_category_filter,
                         product_type_filter=product_type_filter,
                         final_grade_filter=final_grade_filter,
                         set_name_filter=set_name_filter,
                         brand_filter=brand_filter,
                         language_filter=language_filter,
                         entered_by_filter=entered_by_filter,
                         # Filter options
                         grade_options=grade_options,
                         card_category_options=CARD_CATEGORY_OPTIONS,
                         product_type_options=PRODUCT_TYPE_OPTIONS,
                         set_options=set_options,
                         brand_options_for_filter=get_brand_options(include_inactive=True),
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
                         product_type_options=PRODUCT_TYPE_OPTIONS,
                         vintage_classification_options=get_vintage_classification_options(entry.get('vintage_classification')),
                         brand_options=get_brand_options_with_current(entry.get('brand')),
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
        try:
            card_data = collect_category_form_data()
        except ValueError as error:
            flash(str(error), 'error')
            conn.close()
            return redirect(url_for('edit_entry', entry_id=entry_id))
        is_valid_product, product_error = validate_product_policy(card_data)
        if not is_valid_product:
            flash(product_error, 'error')
            conn.close()
            return redirect(url_for('edit_entry', entry_id=entry_id))
        grading_data, error_msg = collect_grading_data(card_data['product_type'])
        if grading_data is None:
            flash(f'Invalid scores: {error_msg}', 'error')
            conn.close()
            return redirect(url_for('edit_entry', entry_id=entry_id))
        total_pop, language, _, _ = calculate_population_for_card_data(
            card_data,
            grading_data['final_grade_text'],
            exclude_entry_id=entry_id,
        )

        # Handle file uploads
        front_image_filename = None
        back_image_filename = None
        try:
            if 'front_image' in request.files:
                front_image_file = request.files['front_image']
                if front_image_file and front_image_file.filename != '':
                    front_image_filename = save_uploaded_file(front_image_file, 'front')

            if 'back_image' in request.files:
                back_image_file = request.files['back_image']
                if back_image_file and back_image_file.filename != '':
                    back_image_filename = save_uploaded_file(back_image_file, 'back')
        except Exception:
            app.logger.exception('Failed to stage replacement images for entry %s', entry_id)
            safe_close(conn, 'entry edit connection')
            cleanup_uncertain_queue_files([
                front_image_filename,
                back_image_filename,
            ], connection_factory=get_temp_db_connection)
            flash('Images could not be saved. No entry changes were applied.', 'error')
            return redirect(url_for('edit_entry', entry_id=entry_id))

        delete_front_image = request.form.get('delete_front_image') == '1'
        delete_back_image = request.form.get('delete_back_image') == '1'
        changes_queue_images = bool(
            front_image_filename
            or back_image_filename
            or delete_front_image
            or delete_back_image
        )
        files_to_delete = []

        if (
            changes_queue_images
            and (existing_entry['upload_status'] or '').strip().lower() == 'uploading'
        ):
            delete_uploaded_file(front_image_filename)
            delete_uploaded_file(back_image_filename)
            flash('Images cannot be changed while this entry is uploading.', 'error')
            conn.close()
            return redirect(url_for('edit_entry', entry_id=entry_id))

        # Update entry
        update_data = {
            **card_data,
            'pop': str(total_pop),  # Auto-calculated POP
            'language': language,
            **grading_data,
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
            # The published file can still be referenced by the main cards
            # database. Clear only this queue record; orphan cleanup needs a
            # separate cross-database verification pass.

        if back_image_filename:
            update_data['back_image'] = back_image_filename
            if existing_entry['back_image']:
                files_to_delete.append(existing_entry['back_image'])
        elif delete_back_image:
            update_data['back_image'] = ''
            update_data['published_back_image'] = ''
            if existing_entry['back_image']:
                files_to_delete.append(existing_entry['back_image'])
            # Never delete a public file from an entry edit while the main site
            # may still reference it.

        # Validate required fields
        is_valid_entry, missing_label = validate_category_required_fields(update_data)
        if not is_valid_entry:
            delete_uploaded_file(front_image_filename)
            delete_uploaded_file(back_image_filename)
            flash(f'{missing_label} is required', 'error')
            conn.close()
            return redirect(url_for('edit_entry', entry_id=entry_id))
        # Build update query
        set_clause = ', '.join([f"{key} = ?" for key in update_data.keys()])
        values = list(update_data.values())
        where_clause = 'id = ?'
        values.append(entry_id)
        if changes_queue_images:
            where_clause += '''
                AND COALESCE(upload_status, 'not_started') <> 'uploading'
                AND COALESCE(front_image, '') = ?
                AND COALESCE(back_image, '') = ?
            '''
            values.extend([
                existing_entry['front_image'] or '',
                existing_entry['back_image'] or '',
            ])

        expected_images = {
            'front_image': update_data.get(
                'front_image',
                existing_entry['front_image'] or '',
            ),
            'back_image': update_data.get(
                'back_image',
                existing_entry['back_image'] or '',
            ),
            'published_front_image': update_data.get(
                'published_front_image',
                existing_entry['published_front_image'] or '',
            ),
            'published_back_image': update_data.get(
                'published_back_image',
                existing_entry['published_back_image'] or '',
            ),
        }

        try:
            update_cursor = conn.execute(
                f"UPDATE temp_cards SET {set_clause} WHERE {where_clause}",
                values,
            )
            if changes_queue_images and update_cursor.rowcount == 0:
                safe_rollback(conn, 'stale entry image edit')
                safe_close(conn, 'entry edit connection')
                conn = None
                cleanup_uncertain_queue_files([
                    front_image_filename,
                    back_image_filename,
                ], connection_factory=get_temp_db_connection)
                flash(
                    'Entry images changed in another request. Reload the form and try again.',
                    'error',
                )
                return redirect(url_for('edit_entry', entry_id=entry_id))
            conn.commit()
        except Exception:
            app.logger.exception('Entry update failed for entry %s', entry_id)
            safe_rollback(conn, 'entry edit')
            safe_close(conn, 'entry edit connection')
            conn = None

            verification_conn = None
            commit_was_applied = False
            try:
                verification_conn = get_temp_db_connection()
                committed_row = verification_conn.execute(
                    '''
                        SELECT updated_at, front_image, back_image,
                               published_front_image, published_back_image
                        FROM temp_cards
                        WHERE id = ?
                    ''',
                    (entry_id,),
                ).fetchone()
                commit_was_applied = bool(
                    committed_row is not None
                    and (committed_row['updated_at'] or '') == update_data['updated_at']
                    and all(
                        (committed_row[column_name] or '') == expected_value
                        for column_name, expected_value in expected_images.items()
                    )
                )
                if commit_was_applied:
                    delete_queue_files_if_unreferenced(
                        verification_conn,
                        files_to_delete,
                    )
                else:
                    delete_queue_files_if_unreferenced(
                        verification_conn,
                        [front_image_filename, back_image_filename],
                    )
            except Exception:
                app.logger.exception(
                    'Could not reconcile entry edit for entry %s; files were retained',
                    entry_id,
                )
            finally:
                safe_close(verification_conn, 'entry edit verification connection')

            if commit_was_applied:
                result_label = (
                    grading_data['final_grade_text']
                    or get_product_type_label(card_data['product_type'])
                )
                flash(f"Entry updated successfully. {result_label}", 'success')
                return redirect(url_for('entry_detail', entry_id=entry_id))

            flash('Entry update failed. Reload the entry before trying again.', 'error')
            return redirect(url_for('edit_entry', entry_id=entry_id))

        delete_queue_files_if_unreferenced(conn, files_to_delete)
        safe_close(conn, 'entry edit connection')
        conn = None

        result_label = grading_data['final_grade_text'] or get_product_type_label(card_data['product_type'])
        flash(f"Entry updated successfully. {result_label}", 'success')
        return redirect(url_for('entry_detail', entry_id=entry_id))

    # GET request - show edit form
    conn.close()
    entry = serialize_temp_entry(existing_entry)

    return render_template('entry_form_updated.html',
                         title="Edit Card Entry",
                         action=url_for('edit_entry', entry_id=entry_id),
                         card=entry,
                         card_category_options=CARD_CATEGORY_OPTIONS,
                         product_type_options=PRODUCT_TYPE_OPTIONS,
                         vintage_classification_options=get_vintage_classification_options(entry.get('vintage_classification')),
                         sports_type_options=get_sports_type_options(entry.get('sports_type')),
                         brand_options=get_brand_options_with_current(entry.get('brand')),
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

# ========== Legacy Export Redirect ==========
@app.route('/admin/export/approved')
@login_required
def export_approved():
    # This legacy GET route used to replay every approved row and republish its
    # images. Keep old bookmarks safe while directing operators to the guarded
    # upload workflow, which handles one selected entry at a time.
    flash('Select approved entries in Upload to Server before publishing.', 'warning')
    return redirect(url_for('upload_manager'))

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
        data = request.get_json() or {}
        product_type = normalize_submitted_product_type(
            data.get('product_type', DEFAULT_PRODUCT_TYPE)
        )
        if not product_uses_grading(product_type):
            return jsonify({'error': 'This product type does not accept grading scores'}), 400
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
        data = request.get_json() or {}
        card_data = {
            'product_type': normalize_submitted_product_type(
                data.get('product_type', DEFAULT_PRODUCT_TYPE)
            ),
            'card_category': normalize_card_category(data.get('card_category', DEFAULT_CARD_CATEGORY)),
            'card_name': data.get('card_name', '').strip(),
            'set_name': data.get('set_name', '').strip(),
            'card_number': data.get('card_number', '').strip(),
            'language': data.get('language', '').strip(),
            'movie_name': data.get('movie_name', '').strip(),
            'release_year': data.get('release_year', '').strip(),
            'production_company': data.get('production_company', '').strip(),
            'film_type': data.get('film_type', '').strip(),
            'sports_type': normalize_sports_type(data.get('sports_type', '')),
            'group_name': data.get('group_name', '').strip(),
            'vintage_classification': normalize_vintage_classification(data.get('vintage_classification', '')),
        }
        if not product_uses_grading(card_data['product_type']):
            card_data['card_category'] = DEFAULT_CARD_CATEGORY
        final_grade_text = data.get('final_grade_text', '').strip()
        current_entry_id = data.get('current_entry_id')

        # Validate required fields
        if not is_pop_request_complete(card_data, final_grade_text):
            return jsonify({'pop': '1', 'message': 'Incomplete data for POP calculation'})

        exclude_entry_id = int(current_entry_id) if current_entry_id not in (None, '', 'null') else None
        total_pop, normalized_language, temp_count, main_count = calculate_population_for_card_data(
            card_data,
            final_grade_text,
            exclude_entry_id=exclude_entry_id,
        )
        category = normalize_card_category(card_data.get('card_category'))
        if category == 'movie_film':
            identity = ' / '.join([
                card_data.get('movie_name', ''),
                card_data.get('release_year', ''),
                card_data.get('production_company', ''),
                card_data.get('film_type', ''),
            ])
        else:
            identity_parts = [
                card_data.get('card_name', ''),
                card_data.get('set_name', ''),
                card_data.get('card_number', ''),
                normalized_language,
            ]
            if category == 'sports_card':
                identity_parts.append(card_data.get('sports_type', ''))
            elif category == 'celebrity_card':
                identity_parts.append(card_data.get('group_name', ''))
            identity = ' / '.join(identity_parts)

        return jsonify({
            'pop': str(total_pop),
            'calculation': f'Temporary DB: {temp_count} + Main DB: {main_count} + 1 = {total_pop}',
            'details': {
                'card_category': category,
                'product_type': card_data['product_type'],
                'card_identity': identity,
                'grade': final_grade_text if product_uses_grading(card_data['product_type']) else '',
                'vintage_classification': card_data['vintage_classification'],
                'temp_count': temp_count,
                'main_count': main_count
            }
        })

    except ValueError as exc:
        return jsonify({'error': str(exc), 'pop': '1'}), 400
    except Exception:
        app.logger.exception('POP calculation failed')
        return jsonify({
            'error': 'POP calculation is temporarily unavailable. Please try again.',
            'pop': '1',
        }), 503


@app.route('/admin/api/match-card', methods=['POST'])
@app.route('/api/match-card', methods=['POST'])
@login_required
def api_match_card():
    """Auto-fill card metadata from existing temp or main records."""
    try:
        data = request.get_json() or {}
        product_type = normalize_submitted_product_type(
            data.get('product_type', DEFAULT_PRODUCT_TYPE)
        )
        card_category = normalize_card_category(data.get('card_category', DEFAULT_CARD_CATEGORY))
        if not product_uses_grading(product_type):
            card_category = DEFAULT_CARD_CATEGORY
        set_name = data.get('set_name', '').strip()
        card_number = data.get('card_number', '').strip()

        if card_category == 'movie_film':
            return jsonify({'found': False, 'message': 'Movie Film entries are matched by movie details, not set number.'})

        if not set_name or not card_number:
            return jsonify({'error': 'Set name and card number are required'}), 400

        lookup_sql = f'''
            SELECT card_name, brand, year, variety, language, sports_type, group_name,
                   merch_description
            FROM {{table_name}}
            WHERE {product_type_sql_expression()} = ?
              AND COALESCE(NULLIF(card_category, ''), 'trading_card') = ?
              AND set_name = ? COLLATE NOCASE
              AND card_number = ? COLLATE NOCASE
            {{order_clause}}
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
                    (product_type, card_category, set_name, card_number),
                ).fetchone()
            if not row:
                continue

            return jsonify({
                'found': True,
                'product_type': product_type,
                'card_name': row['card_name'] or '',
                'brand': normalize_brand(row['brand']),
                'year': row['year'] or '',
                'variety': row['variety'] or '',
                'language': normalize_language(row['language']),
                'sports_type': row['sports_type'] or '',
                'group_name': row['group_name'] or '',
                'merch_description': (
                    (row['merch_description'] or '').strip()
                    if product_type == 'merch_product'
                    else ''
                ),
                'source': source,
            })

        return jsonify({'found': False, 'message': 'No matching card found in database'})

    except ValueError as exc:
        return jsonify({'error': str(exc)}), 400
    except Exception:
        app.logger.exception('Card matching failed')
        return jsonify({
            'error': 'Card matching is temporarily unavailable. Please try again.',
        }), 503
