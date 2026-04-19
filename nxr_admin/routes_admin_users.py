from nxr_admin.admin_core import *

@app.route('/admin/users', methods=['GET', 'POST'])
@superadmin_required
def admin_users():
    edit_user_id = request.args.get('edit', type=int)
    page = max(request.args.get('page', 1, type=int), 1)
    page_size = get_page_size_arg(default=ADMIN_USERS_DEFAULT_PAGE_SIZE)
    if request.method == 'POST':
        page = max(request.form.get('page', page, type=int), 1)
        page_size = request.form.get('page_size', page_size, type=int)
        if page_size not in PAGE_SIZE_OPTIONS:
            page_size = ADMIN_USERS_DEFAULT_PAGE_SIZE
        username = request.form.get('username', '').strip()
        password = request.form.get('password', '').strip()
        email = request.form.get('email', '').strip() or None
        role = normalize_admin_role(request.form.get('role'), default='admin')
        is_active = 0 if request.form.get('inactive') == '1' else 1

        if not username:
            flash('Username is required.', 'error')
            return redirect(url_for('admin_users', page=page, page_size=page_size))
        if not password:
            flash('Password is required.', 'error')
            return redirect(url_for('admin_users', page=page, page_size=page_size))
        if len(password) < 6:
            flash('Password must be at least 6 characters long.', 'error')
            return redirect(url_for('admin_users', page=page, page_size=page_size))
        if role not in MANAGEABLE_ADMIN_ROLES:
            flash('Only admin or reviewer accounts can be created here.', 'error')
            return redirect(url_for('admin_users', page=page, page_size=page_size))
        if admin_username_exists(username):
            flash(f'Username "{username}" already exists.', 'warning')
            return redirect(url_for('admin_users', page=page, page_size=page_size))

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
        return redirect(url_for('admin_users', page=page, page_size=page_size))

    total_accounts = count_admin_accounts()
    total_pages = max((total_accounts + page_size - 1) // page_size, 1)
    if page > total_pages:
        page = total_pages

    return render_template(
        'admin_users.html',
        admin_accounts=list_admin_accounts(limit=page_size, offset=(page - 1) * page_size),
        editing_account=get_admin_account_by_id(edit_user_id) if edit_user_id else None,
        role_options=[{'value': role, 'label': ADMIN_ROLE_LABELS[role]} for role in MANAGEABLE_ADMIN_ROLES],
        edit_role_options=[{'value': role, 'label': ADMIN_ROLE_LABELS[role]} for role in ('superadmin',) + MANAGEABLE_ADMIN_ROLES],
        total_accounts=total_accounts,
        page_size=page_size,
        page_size_options=PAGE_SIZE_OPTIONS,
        pagination=build_pagination(
            page,
            total_pages,
            'admin_users',
            {'edit': edit_user_id, 'page_size': page_size},
        ),
        page_start=((page - 1) * page_size) + 1 if total_accounts else 0,
        page_end=min(page * page_size, total_accounts),
    )


@app.route('/admin/users/<int:user_id>/edit', methods=['POST'])
@superadmin_required
def update_admin_user_route(user_id):
    page = max(request.form.get('page', 1, type=int), 1)
    page_size = request.form.get('page_size', ADMIN_USERS_DEFAULT_PAGE_SIZE, type=int)
    if page_size not in PAGE_SIZE_OPTIONS:
        page_size = ADMIN_USERS_DEFAULT_PAGE_SIZE
    existing_account = get_admin_account_by_id(user_id)
    if not existing_account:
        flash('Administrator account not found.', 'error')
        return redirect(url_for('admin_users', page=page, page_size=page_size))

    username = request.form.get('username', '').strip()
    password = request.form.get('password', '').strip()
    email = request.form.get('email', '').strip() or None
    role = normalize_admin_role(request.form.get('role'), default=existing_account['role'])
    is_active = 1 if request.form.get('is_active') == '1' else 0

    if not username:
        flash('Username is required.', 'error')
        return redirect(url_for('admin_users', edit=user_id, page=page, page_size=page_size))
    if password and len(password) < 6:
        flash('Password must be at least 6 characters long when updating it.', 'error')
        return redirect(url_for('admin_users', edit=user_id, page=page, page_size=page_size))
    if role not in ADMIN_ROLE_LABELS:
        flash('Invalid administrator role.', 'error')
        return redirect(url_for('admin_users', edit=user_id, page=page, page_size=page_size))
    if admin_username_exists(username, exclude_user_id=user_id):
        flash(f'Username "{username}" already exists.', 'warning')
        return redirect(url_for('admin_users', edit=user_id, page=page, page_size=page_size))

    if existing_account['is_superadmin'] and (role != 'superadmin' or not is_active):
        if count_active_superadmins(exclude_user_id=user_id) == 0:
            flash('You must keep at least one active super admin account.', 'error')
            return redirect(url_for('admin_users', edit=user_id, page=page, page_size=page_size))

    with get_main_db_connection() as conn:
        update_admin_user(
            conn,
            user_id=user_id,
            username=username,
            role=role,
            email=email,
            is_active=is_active,
            password=password or None,
        )
        conn.commit()

    is_current_session_user = session.get('username', '').lower() == existing_account['username'].lower()
    if is_current_session_user:
        session['username'] = username
        session['role'] = role

    flash(f'Administrator account "{username}" updated successfully.', 'success')
    if is_current_session_user and not is_active:
        session.clear()
        flash('Your account was set to inactive. Please login again with an active administrator account.', 'info')
        return redirect(url_for('admin_login'))
    if is_current_session_user and not is_superadmin_role(role):
        return redirect(url_for('dashboard'))
    return redirect(url_for('admin_users', edit=user_id, page=page, page_size=page_size))
