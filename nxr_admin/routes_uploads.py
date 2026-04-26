import re
import uuid
from pathlib import Path

from nxr_admin.admin_core import *


IMAGE_IMPORT_PATTERN = re.compile(
    r'(^|/)(?P<cert_id>\d{10})_(?P<side>[AB])(?:_\d+)?\.(?P<ext>webp|jpg|jpeg|png)$',
    re.IGNORECASE,
)
ALLOWED_IMAGE_IMPORT_EXTENSIONS = {'.webp', '.jpg', '.jpeg', '.png'}


def normalize_import_side(raw_side):
    side = (raw_side or '').strip().upper()
    if side == 'A':
        return 'front'
    if side == 'B':
        return 'back'
    return ''


def parse_import_image_name(filename):
    match = IMAGE_IMPORT_PATTERN.search(filename or '')
    if not match:
        return None
    return {
        'cert_id': match.group('cert_id'),
        'side': normalize_import_side(match.group('side')),
        'extension': f".{match.group('ext').lower()}",
        'filename': filename,
    }


def save_imported_image_file(cert_id, side, extension, file_bytes):
    normalized_extension = (extension or '').lower()
    if normalized_extension not in ALLOWED_IMAGE_IMPORT_EXTENSIONS:
        raise ValueError(f'Unsupported image extension for {cert_id} {side}')

    safe_side = 'front' if side == 'front' else 'back'
    output_name = f'{safe_side}_{cert_id}_{uuid.uuid4().hex[:8]}{normalized_extension}'
    output_path = Path(app.config['UPLOAD_FOLDER']) / output_name
    output_path.write_bytes(file_bytes)
    return output_name


def build_image_import_candidates_from_files(uploaded_files):
    candidates = {}
    duplicate_names = []
    invalid_names = []

    for uploaded_file in uploaded_files:
        raw_name = (uploaded_file.filename or '').strip()
        if not raw_name:
            continue
        parsed = parse_import_image_name(raw_name)
        if not parsed:
            if not Path(raw_name).name.startswith('.'):
                invalid_names.append(raw_name)
            continue

        key = (parsed['cert_id'], parsed['side'])
        if key in candidates:
            duplicate_names.append(raw_name)
            continue

        candidates[key] = {
            'source_name': raw_name,
            'cert_id': parsed['cert_id'],
            'side': parsed['side'],
            'extension': parsed['extension'],
            'file_bytes': uploaded_file.read(),
        }

    return candidates, invalid_names, duplicate_names


def import_image_candidates_to_temp_cards(candidates, invalid_names, duplicate_names, conn):
    cert_ids = sorted({cert_id for cert_id, _ in candidates.keys()})
    if not cert_ids:
        return {
            'matched_entries': 0,
            'saved_files': 0,
            'updated_sides': 0,
            'missing_cert_ids': [],
            'invalid_names': invalid_names,
            'duplicate_names': duplicate_names,
            'updated_entry_ids': [],
        }

    placeholders = ', '.join(['?' for _ in cert_ids])
    rows = conn.execute(
        f'''
            SELECT id, cert_id, front_image, back_image
            FROM temp_cards
            WHERE status = 'approved'
              AND cert_id IN ({placeholders})
        ''',
        cert_ids,
    ).fetchall()
    rows_by_cert_id = {row['cert_id']: row for row in rows}

    matched_entries = 0
    saved_files = 0
    updated_sides = 0
    updated_entry_ids = []
    files_to_delete = []

    for cert_id in cert_ids:
        row = rows_by_cert_id.get(cert_id)
        if not row:
            continue

        matched_entries += 1
        update_data = {}

        for side in ('front', 'back'):
            candidate = candidates.get((cert_id, side))
            if not candidate:
                continue

            saved_name = save_imported_image_file(
                cert_id=cert_id,
                side=side,
                extension=candidate['extension'],
                file_bytes=candidate['file_bytes'],
            )
            saved_files += 1
            updated_sides += 1
            update_data[f'{side}_image'] = saved_name

            existing_name = (row[f'{side}_image'] or '').strip()
            if existing_name and existing_name != saved_name:
                files_to_delete.append(existing_name)

        if not update_data:
            continue

        update_data['updated_at'] = datetime.now().isoformat()
        set_clause = ', '.join([f'{column} = ?' for column in update_data.keys()])
        conn.execute(
            f'UPDATE temp_cards SET {set_clause} WHERE id = ?',
            [*update_data.values(), row['id']],
        )
        updated_entry_ids.append(row['id'])

    for filename in dict.fromkeys(files_to_delete):
        delete_uploaded_file(filename)

    missing_cert_ids = [cert_id for cert_id in cert_ids if cert_id not in rows_by_cert_id]
    return {
        'matched_entries': matched_entries,
        'saved_files': saved_files,
        'updated_sides': updated_sides,
        'missing_cert_ids': missing_cert_ids,
        'invalid_names': invalid_names,
        'duplicate_names': duplicate_names,
        'updated_entry_ids': updated_entry_ids,
    }


def import_uploaded_images_to_temp_cards(uploaded_files, conn):
    candidates, invalid_names, duplicate_names = build_image_import_candidates_from_files(uploaded_files)
    return import_image_candidates_to_temp_cards(candidates, invalid_names, duplicate_names, conn)

# ========== Upload Manager ==========
@app.route('/admin/upload')
@login_required
def upload_manager():
    """上传管理页面"""
    page = max(request.args.get('page', 1, type=int), 1)
    show_client_pushed = request.args.get('show_client_pushed', '0') == '1'
    page_size = get_page_size_arg(default=UPLOAD_LIST_DEFAULT_PAGE_SIZE)
    cert_id_filter = request.args.get('cert_id', '').strip()
    card_name_filter = request.args.get('card_name', '').strip()
    brand_filter = normalize_brand(request.args.get('brand', '').strip())
    language_filter = normalize_language(request.args.get('language', '').strip())
    final_grade_filter = normalize_final_grade_text(request.args.get('final_grade', '').strip())
    upload_status_filter = (request.args.get('upload_status', '') or '').strip().lower()
    image_status_filter = (request.args.get('image_status', '') or '').strip().lower()

    upload_status_options = (
        ('not_started', 'Not Started'),
        ('uploading', 'Uploading'),
        ('uploaded', 'Uploaded'),
        ('failed', 'Failed'),
        (CLIENT_PUSHED_UPLOAD_STATUS, 'Client Pushed'),
    )
    valid_upload_status_filters = {value for value, _ in upload_status_options}
    if upload_status_filter and upload_status_filter not in valid_upload_status_filters:
        upload_status_filter = ''

    image_status_options = (
        ('ready', 'Ready for Upload'),
        ('published', 'Published Complete'),
        ('missing_any', 'Missing Any Image'),
        ('missing_front', 'Missing Front Image'),
        ('missing_back', 'Missing Back Image'),
    )
    valid_image_status_filters = {value for value, _ in image_status_options}
    if image_status_filter and image_status_filter not in valid_image_status_filters:
        image_status_filter = ''

    conn = get_temp_db_connection()
    stats = get_upload_stats(conn)

    query = '''
        SELECT * FROM temp_cards
        WHERE status = 'approved'
    '''
    params = []

    if not show_client_pushed:
        query += " AND COALESCE(upload_status, 'not_started') != ?"
        params.append(CLIENT_PUSHED_UPLOAD_STATUS)

    if cert_id_filter:
        if cert_id_filter.isdigit() and len(cert_id_filter) == 10:
            query += " AND cert_id = ?"
            params.append(cert_id_filter)
        else:
            query += " AND cert_id LIKE ?"
            params.append(f"%{cert_id_filter}%")

    if card_name_filter:
        query += " AND card_name LIKE ?"
        params.append(f"%{card_name_filter}%")

    if brand_filter:
        query += " AND brand = ?"
        params.append(brand_filter)

    if language_filter:
        language_variants = get_language_variants(language_filter)
        placeholders = ', '.join(['?' for _ in language_variants])
        query += f" AND language IN ({placeholders})"
        params.extend(language_variants)

    if final_grade_filter:
        query += f" AND {build_grade_filter_sql(final_grade_filter)}"
        params.append(final_grade_filter)

    if upload_status_filter:
        query += " AND COALESCE(upload_status, 'not_started') = ?"
        params.append(upload_status_filter)

    query += '''
        ORDER BY
            COALESCE(NULLIF(approved_at, ''), updated_at, entry_date, created_at) DESC,
            COALESCE(approval_sequence, 9223372036854775807) ASC,
            id ASC
    '''

    grade_options = get_grade_filter_options(conn, status_filter='approved')
    raw_entries = conn.execute(query, params).fetchall()
    filtered_entries = []
    for entry in raw_entries:
        entry_dict = serialize_temp_entry(entry)
        entry_dict.update(get_entry_image_flags(entry))
        has_any_front = entry_dict['has_front_image_file'] or entry_dict['has_published_front_image']
        has_any_back = entry_dict['has_back_image_file'] or entry_dict['has_published_back_image']
        if image_status_filter == 'ready' and not entry_dict['ready_for_upload']:
            continue
        if image_status_filter == 'published' and not entry_dict['published_complete']:
            continue
        if image_status_filter == 'missing_any' and has_any_front and has_any_back:
            continue
        if image_status_filter == 'missing_front' and has_any_front:
            continue
        if image_status_filter == 'missing_back' and has_any_back:
            continue
        filtered_entries.append(entry_dict)

    total = len(filtered_entries)
    total_pages = max((total + page_size - 1) // page_size, 1)
    if page > total_pages:
        page = total_pages

    offset = (page - 1) * page_size
    entries = filtered_entries[offset:offset + page_size]

    conn.close()

    pagination_params = {
        'show_client_pushed': 1 if show_client_pushed else 0,
        'cert_id': cert_id_filter,
        'card_name': card_name_filter,
        'brand': brand_filter,
        'language': language_filter,
        'final_grade': final_grade_filter,
        'upload_status': upload_status_filter,
        'image_status': image_status_filter,
        'page_size': page_size,
    }

    return render_template('upload_manager.html',
                         entries=entries,
                         page=page,
                         per_page=page_size,
                         total=total,
                         total_pages=total_pages,
                         show_client_pushed=show_client_pushed,
                         pagination=build_pagination(
                             page,
                             total_pages,
                             'upload_manager',
                             pagination_params,
                         ),
                         page_size=page_size,
                         page_size_options=PAGE_SIZE_OPTIONS,
                         page_start=((page - 1) * page_size) + 1 if total else 0,
                         page_end=min(page * page_size, total),
                         stats=stats,
                         cert_id_filter=cert_id_filter,
                         card_name_filter=card_name_filter,
                         brand_filter=brand_filter,
                         language_filter=language_filter,
                         final_grade_filter=final_grade_filter,
                         upload_status_filter=upload_status_filter,
                         image_status_filter=image_status_filter,
                         grade_options=grade_options,
                         upload_status_options=upload_status_options,
                         image_status_options=image_status_options,
                         brand_options=BRAND_OPTIONS,
                         language_options=LANGUAGE_OPTIONS)


@app.route('/admin/upload/import-images', methods=['POST'])
@login_required
def import_images_by_id():
    is_ajax_request = request.headers.get('X-Requested-With') == 'XMLHttpRequest'

    def respond_with_message(message, category='error', status_code=400, summary=None):
        if is_ajax_request:
            payload = {
                'success': category != 'error',
                'message': message,
            }
            if summary is not None:
                payload['summary'] = summary
            return jsonify(payload), status_code
        flash(message, category)
        return redirect(url_for('upload_manager'))

    uploaded_files = [
        file_obj
        for file_obj in request.files.getlist('image_files')
        if file_obj and (file_obj.filename or '').strip()
    ]
    if not uploaded_files:
        return respond_with_message('Please choose an image folder first.', 'warning', 400)

    try:
        conn = get_temp_db_connection()
        try:
            summary = import_uploaded_images_to_temp_cards(uploaded_files, conn)
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()
    except Exception as exc:
        app.logger.error('Folder image import failed: %s', exc)
        return respond_with_message(f'Folder image import failed: {exc}', 'error', 500)

    message_parts = [
        f"Imported {summary['saved_files']} image files",
        f"updated {len(summary['updated_entry_ids'])} approved entries",
    ]
    if summary['missing_cert_ids']:
        message_parts.append(f"{len(summary['missing_cert_ids'])} cert IDs had no approved exact match and were skipped")
    if summary['duplicate_names']:
        message_parts.append(f"{len(summary['duplicate_names'])} duplicate files ignored")
    if summary['invalid_names']:
        message_parts.append(f"{len(summary['invalid_names'])} invalid filenames skipped")

    message = '. '.join(message_parts) + '.'
    category = 'success' if summary['updated_entry_ids'] else 'warning'
    return respond_with_message(
        message,
        category,
        200,
        summary=summary,
    )

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
