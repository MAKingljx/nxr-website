from nxr_admin.admin_core import *


def _build_settings_modules():
    active_count = len(get_brand_options())
    total_count = len(get_brand_options(include_inactive=True))
    return [
        {
            'key': 'brands',
            'title': 'Brand Settings',
            'icon': 'fas fa-tags',
            'url': url_for('brand_settings'),
            'badge': f'{active_count} active / {total_count} total',
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
    if request.method == 'POST':
        name = normalize_brand_name(request.form.get('name'))
        aliases = request.form.get('aliases', '')
        sort_order = request.form.get('sort_order', 0, type=int) or 0
        is_active = 1 if request.form.get('is_active') == '1' else 0

        if not name:
            flash('Brand name is required.', 'error')
            return redirect(url_for('brand_settings'))

        try:
            with get_main_db_connection() as conn:
                initialize_brand_settings(conn)
                if brand_setting_name_exists(conn, name):
                    flash(f'Brand "{name}" already exists.', 'warning')
                    return redirect(url_for('brand_settings'))
                create_brand_setting(conn, name, aliases=aliases, sort_order=sort_order, is_active=is_active)
                conn.commit()
            flash(f'Brand "{name}" created successfully.', 'success')
        except Exception as exc:
            flash(f'Error creating brand: {exc}', 'error')

        return redirect(url_for('brand_settings'))

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
        return redirect(url_for('brand_settings'))

    name = normalize_brand_name(request.form.get('name'))
    if existing_brand['name'].lower() == 'other':
        name = 'Other'
    aliases = request.form.get('aliases', '')
    sort_order = request.form.get('sort_order', existing_brand.get('sort_order') or 0, type=int) or 0
    is_active = 1 if existing_brand['name'].lower() == 'other' else 1 if request.form.get('is_active') == '1' else 0

    if not name:
        flash('Brand name is required.', 'error')
        return redirect(url_for('brand_settings'))

    try:
        with get_main_db_connection() as conn:
            initialize_brand_settings(conn)
            if brand_setting_name_exists(conn, name, exclude_brand_id=brand_id):
                flash(f'Brand "{name}" already exists.', 'warning')
                return redirect(url_for('brand_settings'))
            update_brand_setting(conn, brand_id, name, aliases=aliases, sort_order=sort_order, is_active=is_active)
            conn.commit()
        flash(f'Brand "{name}" updated successfully.', 'success')
    except Exception as exc:
        flash(f'Error updating brand: {exc}', 'error')

    return redirect(url_for('brand_settings'))


@app.route('/admin/settings/brands/<int:brand_id>/delete', methods=['POST'])
@superadmin_required
def delete_brand_setting_route(brand_id):
    existing_brand = get_brand_setting_by_id(brand_id)
    if not existing_brand:
        flash('Brand not found.', 'error')
        return redirect(url_for('brand_settings'))

    try:
        with get_main_db_connection() as conn:
            initialize_brand_settings(conn)
            if not delete_brand_setting(conn, brand_id):
                flash('Other cannot be deleted.', 'warning')
                return redirect(url_for('brand_settings'))
            conn.commit()
        flash(f'Brand "{existing_brand["name"]}" deleted successfully.', 'success')
    except Exception as exc:
        flash(f'Error deleting brand: {exc}', 'error')

    return redirect(url_for('brand_settings'))
