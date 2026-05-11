from nxr_admin.admin_core import *


@app.route('/admin/submitters')
@login_required
def waitlist_submitters():
    page = max(request.args.get('page', 1, type=int), 1)
    page_size = get_page_size_arg(default=UPLOAD_LIST_DEFAULT_PAGE_SIZE)
    email_filter = request.args.get('email', '').strip().lower()
    sort_order = (request.args.get('sort_order', 'desc') or 'desc').strip().lower()
    if sort_order not in ('asc', 'desc'):
        sort_order = 'desc'

    where_parts = []
    params = []
    if email_filter:
        where_parts.append('lower(email) LIKE ?')
        params.append(f'%{email_filter}%')

    where_clause = f"WHERE {' AND '.join(where_parts)}" if where_parts else ''
    order_direction = 'ASC' if sort_order == 'asc' else 'DESC'

    conn = get_main_db_connection()
    try:
        total_submitters = conn.execute('SELECT COUNT(*) FROM waitlist').fetchone()[0]
        today = datetime.now().strftime('%Y-%m-%d')
        today_submitters = conn.execute(
            'SELECT COUNT(*) FROM waitlist WHERE substr(created_at, 1, 10) = ?',
            (today,),
        ).fetchone()[0]
        total_matching = conn.execute(
            f'SELECT COUNT(*) FROM waitlist {where_clause}',
            params,
        ).fetchone()[0]

        total_pages = max((total_matching + page_size - 1) // page_size, 1)
        if page > total_pages:
            page = total_pages
        offset = (page - 1) * page_size

        rows = conn.execute(
            f'''
                SELECT id, email, created_at
                FROM waitlist
                {where_clause}
                ORDER BY datetime(created_at) {order_direction}, id {order_direction}
                LIMIT ? OFFSET ?
            ''',
            [*params, page_size, offset],
        ).fetchall()
    finally:
        conn.close()

    pagination_params = {
        'email': email_filter,
        'sort_order': sort_order,
        'page_size': page_size,
    }

    return render_template(
        'waitlist_submitters.html',
        submitters=[dict(row) for row in rows],
        total_submitters=total_submitters,
        today_submitters=today_submitters,
        total_matching=total_matching,
        email_filter=email_filter,
        sort_order=sort_order,
        page=page,
        page_size=page_size,
        page_size_options=PAGE_SIZE_OPTIONS,
        page_start=((page - 1) * page_size) + 1 if total_matching else 0,
        page_end=min(page * page_size, total_matching),
        pagination=build_pagination(
            page,
            total_pages,
            'waitlist_submitters',
            pagination_params,
        ),
    )


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
