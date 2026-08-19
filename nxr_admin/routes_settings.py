from nxr_admin.admin_core import *


def _build_settings_modules():
    dictionary_groups = list_dictionary_groups(include_inactive=True)
    active_dictionary_count = len([group for group in dictionary_groups if group.get('is_active')])
    return [
        {
            'key': 'dictionaries',
            'title': 'Dictionary Settings',
            'icon': 'fas fa-list-ul',
            'url': url_for('dictionary_settings'),
            'badge': f'{active_dictionary_count} active / {len(dictionary_groups)} total',
            'description': 'Manage brand options, sports types, and other reusable entry dropdowns.',
        },
    ]


@app.route('/admin/settings', methods=['GET'])
@superadmin_required
def system_settings():
    return render_template(
        'system_settings.html',
        modules=_build_settings_modules(),
    )


@app.route('/admin/settings/brands', methods=['GET', 'POST'])
@superadmin_required
def brand_settings():
    brand_group = get_dictionary_group_by_code(BRAND_DICTIONARY_CODE)
    if brand_group:
        return redirect(url_for('dictionary_settings', group_id=brand_group['id']))
    return redirect(url_for('dictionary_settings'))


@app.route('/admin/settings/brands/legacy', methods=['GET', 'POST'])
@superadmin_required
def legacy_brand_settings():
    if request.method == 'POST':
        name = normalize_brand_name(request.form.get('name'))
        aliases = request.form.get('aliases', '')
        sort_order = request.form.get('sort_order', 0, type=int) or 0
        is_active = 1 if request.form.get('is_active') == '1' else 0

        if not name:
            flash('Brand name is required.', 'error')
            return redirect(url_for('legacy_brand_settings'))

        try:
            with get_main_db_connection() as conn:
                initialize_dictionary_tables(conn)
                if brand_setting_name_exists(conn, name):
                    flash(f'Brand "{name}" already exists.', 'warning')
                    return redirect(url_for('legacy_brand_settings'))
                create_brand_setting(conn, name, aliases=aliases, sort_order=sort_order, is_active=is_active)
                conn.commit()
            flash(f'Brand "{name}" created successfully.', 'success')
        except Exception as exc:
            flash(f'Error creating brand: {exc}', 'error')

        return redirect(url_for('legacy_brand_settings'))

    return render_template(
        'brand_settings.html',
        brands=list_brand_settings(include_inactive=True),
        active_brand_count=len(get_brand_options()),
        total_brand_count=len(get_brand_options(include_inactive=True)),
    )


@app.route('/admin/settings/brands/<int:brand_id>/edit', methods=['POST'])
@superadmin_required
def update_brand_setting_route(brand_id):
    existing_brand = get_brand_setting_by_id(brand_id)
    if not existing_brand:
        flash('Brand not found.', 'error')
        return redirect(url_for('dictionary_settings'))

    name = normalize_brand_name(request.form.get('name'))
    if existing_brand['name'].lower() == 'other':
        name = 'Other'
    aliases = request.form.get('aliases', '')
    sort_order = request.form.get('sort_order', existing_brand.get('sort_order') or 0, type=int) or 0
    is_active = 1 if existing_brand['name'].lower() == 'other' else 1 if request.form.get('is_active') == '1' else 0

    if not name:
        flash('Brand name is required.', 'error')
        return redirect(url_for('legacy_brand_settings'))

    try:
        with get_main_db_connection() as conn:
            initialize_dictionary_tables(conn)
            if brand_setting_name_exists(conn, name, exclude_brand_id=brand_id):
                flash(f'Brand "{name}" already exists.', 'warning')
                return redirect(url_for('legacy_brand_settings'))
            update_brand_setting(conn, brand_id, name, aliases=aliases, sort_order=sort_order, is_active=is_active)
            conn.commit()
        flash(f'Brand "{name}" updated successfully.', 'success')
    except Exception as exc:
        flash(f'Error updating brand: {exc}', 'error')

    return redirect(url_for('legacy_brand_settings'))


@app.route('/admin/settings/brands/<int:brand_id>/delete', methods=['POST'])
@superadmin_required
def delete_brand_setting_route(brand_id):
    existing_brand = get_brand_setting_by_id(brand_id)
    if not existing_brand:
        flash('Brand not found.', 'error')
        return redirect(url_for('dictionary_settings'))

    try:
        with get_main_db_connection() as conn:
            initialize_dictionary_tables(conn)
            if not delete_brand_setting(conn, brand_id):
                flash('Other cannot be deleted.', 'warning')
                return redirect(url_for('legacy_brand_settings'))
            conn.commit()
        flash(f'Brand "{existing_brand["name"]}" deleted successfully.', 'success')
    except Exception as exc:
        flash(f'Error deleting brand: {exc}', 'error')

    return redirect(url_for('legacy_brand_settings'))


def _redirect_dictionary_settings(group_id=None):
    if group_id:
        return redirect(url_for('dictionary_settings', group_id=group_id))
    return redirect(url_for('dictionary_settings'))


@app.route('/admin/settings/dictionaries', methods=['GET', 'POST'])
@superadmin_required
def dictionary_settings():
    if request.method == 'POST':
        code = request.form.get('code', '')
        name = request.form.get('name', '')
        description = request.form.get('description', '')
        sort_order = request.form.get('sort_order', 0, type=int) or 0
        is_active = 1 if request.form.get('is_active') == '1' else 0

        try:
            with get_main_db_connection() as conn:
                initialize_dictionary_tables(conn)
                normalized_code = normalize_dictionary_code(code)
                if not normalized_code or not normalize_dictionary_value(name):
                    flash('Dictionary code and name are required.', 'error')
                    return redirect(url_for('dictionary_settings'))
                if dictionary_group_code_exists(conn, normalized_code):
                    flash(f'Dictionary code "{normalized_code}" already exists.', 'warning')
                    return redirect(url_for('dictionary_settings'))
                create_dictionary_group(
                    conn,
                    normalized_code,
                    name,
                    description=description,
                    sort_order=sort_order,
                    is_active=is_active,
                )
                conn.commit()
            flash(f'Dictionary "{normalize_dictionary_value(name)}" created successfully.', 'success')
        except Exception as exc:
            flash(f'Error creating dictionary: {exc}', 'error')

        return redirect(url_for('dictionary_settings'))

    groups = list_dictionary_groups(include_inactive=True)
    selected_group_id = request.args.get('group_id', type=int)
    selected_group = None
    if selected_group_id:
        selected_group = next((group for group in groups if group['id'] == selected_group_id), None)
    if not selected_group and groups:
        selected_group = groups[0]
    items = list_dictionary_items(group_id=selected_group['id'], include_inactive=True) if selected_group else []

    return render_template(
        'dictionary_settings.html',
        groups=groups,
        selected_group=selected_group,
        items=items,
        active_group_count=len([group for group in groups if group.get('is_active')]),
        total_group_count=len(groups),
        active_item_count=len([item for item in items if item.get('is_active')]),
        total_item_count=len(items),
        brand_dictionary_code=BRAND_DICTIONARY_CODE,
        protected_dictionary_codes=PROTECTED_DICTIONARY_CODES,
        vintage_classification_dictionary_code=VINTAGE_CLASSIFICATION_DICTIONARY_CODE,
        max_vintage_classifications=MAX_VINTAGE_CLASSIFICATIONS,
    )


@app.route('/admin/settings/dictionaries/<int:group_id>/edit', methods=['POST'])
@superadmin_required
def update_dictionary_group_route(group_id):
    existing_group = get_dictionary_group_by_id(group_id)
    if not existing_group:
        flash('Dictionary not found.', 'error')
        return redirect(url_for('dictionary_settings'))

    code = existing_group['code'] if is_protected_dictionary_code(existing_group['code']) else request.form.get('code', '')
    name = request.form.get('name', '')
    description = request.form.get('description', '')
    sort_order = request.form.get('sort_order', existing_group.get('sort_order') or 0, type=int) or 0
    is_active = 1 if is_protected_dictionary_code(existing_group['code']) else 1 if request.form.get('is_active') == '1' else 0

    try:
        with get_main_db_connection() as conn:
            initialize_dictionary_tables(conn)
            normalized_code = normalize_dictionary_code(code)
            if not normalized_code or not normalize_dictionary_value(name):
                flash('Dictionary code and name are required.', 'error')
                return _redirect_dictionary_settings(group_id)
            if dictionary_group_code_exists(conn, normalized_code, exclude_group_id=group_id):
                flash(f'Dictionary code "{normalized_code}" already exists.', 'warning')
                return _redirect_dictionary_settings(group_id)
            update_dictionary_group(
                conn,
                group_id,
                normalized_code,
                name,
                description=description,
                sort_order=sort_order,
                is_active=is_active,
            )
            conn.commit()
        flash(f'Dictionary "{normalize_dictionary_value(name)}" updated successfully.', 'success')
    except Exception as exc:
        flash(f'Error updating dictionary: {exc}', 'error')

    return _redirect_dictionary_settings(group_id)


@app.route('/admin/settings/dictionaries/<int:group_id>/delete', methods=['POST'])
@superadmin_required
def delete_dictionary_group_route(group_id):
    existing_group = get_dictionary_group_by_id(group_id)
    if not existing_group:
        flash('Dictionary not found.', 'error')
        return redirect(url_for('dictionary_settings'))
    if is_protected_dictionary_code(existing_group['code']):
        flash(f'Dictionary "{existing_group["name"]}" is required by the entry workflow and cannot be deleted.', 'warning')
        return _redirect_dictionary_settings(group_id)

    try:
        with get_main_db_connection() as conn:
            initialize_dictionary_tables(conn)
            delete_dictionary_group(conn, group_id)
            conn.commit()
        flash(f'Dictionary "{existing_group["name"]}" deleted successfully.', 'success')
    except Exception as exc:
        flash(f'Error deleting dictionary: {exc}', 'error')

    return redirect(url_for('dictionary_settings'))


@app.route('/admin/settings/dictionaries/<int:group_id>/items', methods=['POST'])
@superadmin_required
def create_dictionary_item_route(group_id):
    existing_group = get_dictionary_group_by_id(group_id)
    if not existing_group:
        flash('Dictionary not found.', 'error')
        return redirect(url_for('dictionary_settings'))

    value = request.form.get('value', '')
    aliases = request.form.get('aliases', '')
    sort_order = request.form.get('sort_order', 0, type=int) or 0
    is_active = 1 if request.form.get('is_active') == '1' else 0

    try:
        with get_main_db_connection() as conn:
            initialize_dictionary_tables(conn)
            item_value = normalize_dictionary_value(value)
            if not item_value:
                flash('Dictionary item value is required.', 'error')
                return _redirect_dictionary_settings(group_id)
            if dictionary_item_value_exists(conn, group_id, item_value):
                flash(f'Item "{item_value}" already exists in this dictionary.', 'warning')
                return _redirect_dictionary_settings(group_id)
            create_dictionary_item(conn, group_id, item_value, aliases=aliases, sort_order=sort_order, is_active=is_active)
            conn.commit()
        flash(f'Item "{normalize_dictionary_value(value)}" created successfully.', 'success')
    except Exception as exc:
        flash(f'Error creating dictionary item: {exc}', 'error')

    return _redirect_dictionary_settings(group_id)


@app.route('/admin/settings/dictionaries/items/<int:item_id>/edit', methods=['POST'])
@superadmin_required
def update_dictionary_item_route(item_id):
    existing_item = get_dictionary_item_by_id(item_id)
    if not existing_item:
        flash('Dictionary item not found.', 'error')
        return redirect(url_for('dictionary_settings'))

    group_id = existing_item['group_id']
    value = request.form.get('value', '')
    aliases = request.form.get('aliases', '')
    sort_order = request.form.get('sort_order', existing_item.get('sort_order') or 0, type=int) or 0
    is_brand_other = (
        normalize_dictionary_code(existing_item.get('group_code')) == BRAND_DICTIONARY_CODE
        and (existing_item.get('value') or '').lower() == 'other'
    )
    is_active = 1 if is_brand_other else 1 if request.form.get('is_active') == '1' else 0

    try:
        with get_main_db_connection() as conn:
            initialize_dictionary_tables(conn)
            item_value = normalize_dictionary_value(value)
            if not item_value:
                flash('Dictionary item value is required.', 'error')
                return _redirect_dictionary_settings(group_id)
            if dictionary_item_value_exists(conn, group_id, item_value, exclude_item_id=item_id):
                flash(f'Item "{item_value}" already exists in this dictionary.', 'warning')
                return _redirect_dictionary_settings(group_id)
            update_dictionary_item(conn, item_id, item_value, aliases=aliases, sort_order=sort_order, is_active=is_active)
            conn.commit()
        flash(f'Item "{normalize_dictionary_value(value)}" updated successfully.', 'success')
    except Exception as exc:
        flash(f'Error updating dictionary item: {exc}', 'error')

    return _redirect_dictionary_settings(group_id)


@app.route('/admin/settings/dictionaries/items/<int:item_id>/delete', methods=['POST'])
@superadmin_required
def delete_dictionary_item_route(item_id):
    existing_item = get_dictionary_item_by_id(item_id)
    if not existing_item:
        flash('Dictionary item not found.', 'error')
        return redirect(url_for('dictionary_settings'))

    group_id = existing_item['group_id']
    if (
        normalize_dictionary_code(existing_item.get('group_code')) == BRAND_DICTIONARY_CODE
        and (existing_item.get('value') or '').lower() == 'other'
    ):
        flash('Brand "Other" cannot be deleted.', 'warning')
        return _redirect_dictionary_settings(group_id)
    try:
        with get_main_db_connection() as conn:
            initialize_dictionary_tables(conn)
            delete_dictionary_item(conn, item_id)
            conn.commit()
        flash(f'Item "{existing_item["value"]}" deleted successfully.', 'success')
    except Exception as exc:
        flash(f'Error deleting dictionary item: {exc}', 'error')

    return _redirect_dictionary_settings(group_id)
