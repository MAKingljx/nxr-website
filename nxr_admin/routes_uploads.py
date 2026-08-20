import os
import re
import uuid
from pathlib import Path

from PIL import Image, UnidentifiedImageError

from nxr_admin.admin_core import *


IMAGE_IMPORT_PATTERN = re.compile(
    r'(^|/)(?P<cert_id>\d{10})_(?P<side>[AB])(?:_\d+)?\.(?P<ext>webp|jpg|jpeg|png)$',
    re.IGNORECASE,
)
ALLOWED_IMAGE_IMPORT_EXTENSIONS = {'.webp', '.jpg', '.jpeg', '.png'}
IMAGE_FORMAT_EXTENSIONS = {
    'JPEG': {'.jpg', '.jpeg'},
    'PNG': {'.png'},
    'WEBP': {'.webp'},
}
SAFE_UPLOAD_FAILURE_DETAIL = 'Upload failed during server finalization'


def positive_int_env(name, default):
    raw_value = os.environ.get(name, '').strip()
    try:
        value = int(raw_value)
    except ValueError:
        return default
    return value if value > 0 else default


MAX_IMAGE_IMPORT_FILES = positive_int_env('NXR_IMAGE_IMPORT_MAX_FILES', 12)
MAX_IMAGE_IMPORT_FILE_BYTES = positive_int_env(
    'NXR_IMAGE_IMPORT_MAX_FILE_BYTES',
    24 * 1024 * 1024,
)
MAX_IMAGE_IMPORT_BATCH_BYTES = positive_int_env(
    'NXR_IMAGE_IMPORT_MAX_BATCH_BYTES',
    24 * 1024 * 1024,
)
MAX_IMAGE_IMPORT_PIXELS = positive_int_env(
    'NXR_IMAGE_IMPORT_MAX_PIXELS',
    100_000_000,
)


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


def save_imported_image_upload(cert_id, side, extension, uploaded_file):
    normalized_extension = (extension or '').lower()
    if normalized_extension not in ALLOWED_IMAGE_IMPORT_EXTENSIONS:
        raise ValueError(f'Unsupported image extension for {cert_id} {side}')

    safe_side = 'front' if side == 'front' else 'back'
    upload_folder = Path(app.config['UPLOAD_FOLDER'])
    pending_path = upload_folder / f'.image-import-{uuid.uuid4().hex}.part'

    try:
        if hasattr(uploaded_file, 'stream') and hasattr(uploaded_file.stream, 'seek'):
            uploaded_file.stream.seek(0)
    except (OSError, ValueError):
        pass

    bytes_written = 0
    try:
        with pending_path.open('xb') as output_file:
            while True:
                chunk = uploaded_file.stream.read(1024 * 1024)
                if not chunk:
                    break
                bytes_written += len(chunk)
                if bytes_written > MAX_IMAGE_IMPORT_FILE_BYTES:
                    raise ValueError('Image import file exceeds the configured size limit')
                output_file.write(chunk)
            output_file.flush()
            os.fsync(output_file.fileno())

        try:
            with Image.open(pending_path) as image:
                detected_format = (image.format or '').upper()
                width, height = image.size
                if width <= 0 or height <= 0 or width * height > MAX_IMAGE_IMPORT_PIXELS:
                    raise ValueError('Image dimensions exceed the configured limit')
                image.verify()
        except (UnidentifiedImageError, OSError) as exc:
            raise ValueError('Uploaded file is not a valid supported image') from exc

        if normalized_extension not in IMAGE_FORMAT_EXTENSIONS.get(detected_format, set()):
            raise ValueError('Image content does not match its file extension')

        for _ in range(5):
            output_name = f'{safe_side}_{cert_id}_{uuid.uuid4().hex[:8]}{normalized_extension}'
            output_path = upload_folder / output_name
            try:
                # A hard link publishes the fully-written image atomically and
                # refuses to overwrite an existing UUID collision.
                os.link(pending_path, output_path)
                fsync_directory(upload_folder)
                return output_name, bytes_written
            except FileExistsError:
                continue
        raise FileExistsError('Unable to allocate a unique image filename')
    finally:
        try:
            pending_path.unlink(missing_ok=True)
            fsync_directory(upload_folder)
        except OSError:
            app.logger.warning('Failed to remove staged image file: %s', pending_path)


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
            'uploaded_file': uploaded_file,
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
    initial_rows = conn.execute(
        f'''
            SELECT id, cert_id, front_image, back_image
            FROM temp_cards
            WHERE status = 'approved'
              AND COALESCE(upload_status, 'not_started') <> 'uploading'
              AND cert_id IN ({placeholders})
        ''',
        cert_ids,
    ).fetchall()
    initial_rows_by_cert_id = {row['cert_id']: row for row in initial_rows}

    # Copy uploaded bytes before starting the write transaction. This keeps the
    # SQLite writer lock short even when a browser batch contains large images.
    staged_names = {}
    created_files = []
    staged_bytes = 0
    try:
        for cert_id in cert_ids:
            if cert_id not in initial_rows_by_cert_id:
                continue
            for side in ('front', 'back'):
                candidate = candidates.get((cert_id, side))
                if not candidate:
                    continue
                saved_name, saved_bytes = save_imported_image_upload(
                    cert_id=cert_id,
                    side=side,
                    extension=candidate['extension'],
                    uploaded_file=candidate['uploaded_file'],
                )
                staged_names[(cert_id, side)] = saved_name
                created_files.append(saved_name)
                staged_bytes += saved_bytes
                if staged_bytes > MAX_IMAGE_IMPORT_BATCH_BYTES:
                    raise ValueError('Image import batch exceeds the configured size limit')

        if not staged_names:
            return {
                'matched_entries': len(initial_rows_by_cert_id),
                'saved_files': 0,
                'updated_sides': 0,
                'missing_cert_ids': [
                    cert_id for cert_id in cert_ids if cert_id not in initial_rows_by_cert_id
                ],
                'invalid_names': invalid_names,
                'duplicate_names': duplicate_names,
                'updated_entry_ids': [],
                '_created_files': [],
                '_used_files': [],
                '_obsolete_files': [],
            }

        if db.is_mysql_connection(conn):
            conn.begin()
        else:
            # Acquire SQLite's single writer slot before any row changes.
            conn.execute('BEGIN IMMEDIATE')

        # Re-read after acquiring the writer lock. Another request may have
        # changed an image reference while this batch was being copied.
        row_lock_clause = ' FOR UPDATE' if db.is_mysql_connection(conn) else ''
        rows = conn.execute(
            f'''
                SELECT id, cert_id, front_image, back_image
                FROM temp_cards
                WHERE status = 'approved'
                  AND COALESCE(upload_status, 'not_started') <> 'uploading'
                  AND cert_id IN ({placeholders})
                {row_lock_clause}
            ''',
            cert_ids,
        ).fetchall()
        rows_by_cert_id = {row['cert_id']: row for row in rows}

        matched_entries = 0
        updated_entry_ids = []
        used_files = []
        files_to_delete = []

        for cert_id in cert_ids:
            row = rows_by_cert_id.get(cert_id)
            if not row:
                continue

            matched_entries += 1
            update_data = {}
            entry_used_files = []
            entry_obsolete_files = []

            for side in ('front', 'back'):
                saved_name = staged_names.get((cert_id, side))
                if not saved_name:
                    continue

                entry_used_files.append(saved_name)
                update_data[f'{side}_image'] = saved_name

                existing_name = (row[f'{side}_image'] or '').strip()
                if existing_name and existing_name != saved_name:
                    entry_obsolete_files.append(existing_name)

            if not update_data:
                continue

            update_data['updated_at'] = datetime.now().isoformat()
            set_clause = ', '.join([f'{column} = ?' for column in update_data.keys()])
            update_cursor = conn.execute(
                f'''
                    UPDATE temp_cards
                    SET {set_clause}
                    WHERE id = ?
                      AND status = 'approved'
                      AND COALESCE(upload_status, 'not_started') <> 'uploading'
                      AND COALESCE(front_image, '') = ?
                      AND COALESCE(back_image, '') = ?
                ''',
                [
                    *update_data.values(),
                    row['id'],
                    row['front_image'] or '',
                    row['back_image'] or '',
                ],
            )
            if update_cursor.rowcount == 0:
                continue
            used_files.extend(entry_used_files)
            files_to_delete.extend(entry_obsolete_files)
            updated_entry_ids.append(row['id'])
    except Exception:
        # No database commit has happened yet, so every newly-created file is
        # disposable. Existing files remain untouched until after commit.
        for filename in dict.fromkeys(created_files):
            delete_uploaded_file(filename)
        raise

    missing_cert_ids = [cert_id for cert_id in cert_ids if cert_id not in rows_by_cert_id]
    return {
        'matched_entries': matched_entries,
        'saved_files': len(used_files),
        'updated_sides': len(used_files),
        'missing_cert_ids': missing_cert_ids,
        'invalid_names': invalid_names,
        'duplicate_names': duplicate_names,
        'updated_entry_ids': updated_entry_ids,
        # Internal lifecycle lists. The route removes these before returning a
        # response so filenames are not exposed to the browser.
        '_created_files': created_files,
        '_used_files': used_files,
        '_obsolete_files': list(dict.fromkeys(files_to_delete)),
    }


def import_uploaded_images_to_temp_cards(uploaded_files, conn):
    candidates, invalid_names, duplicate_names = build_image_import_candidates_from_files(uploaded_files)
    return import_image_candidates_to_temp_cards(candidates, invalid_names, duplicate_names, conn)


def cleanup_failed_import_files(filenames):
    """Resolve an uncertain commit before removing newly-created images.

    If the database cannot be read, files are deliberately retained. An orphan
    is recoverable; deleting a file that a successful commit references is not.
    """
    cleanup_uncertain_queue_files(
        filenames,
        connection_factory=get_temp_db_connection,
    )

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
    card_category_filter = normalize_card_category_filter(request.args.get('card_category', '').strip())
    product_type_filter = normalize_product_type_filter(request.args.get('product_type', '').strip())
    brand_filter = normalize_brand(request.args.get('brand', '').strip())
    language_filter = normalize_language(request.args.get('language', '').strip())
    final_grade_filter = normalize_final_grade_text(request.args.get('final_grade', '').strip())
    upload_status_filter = (request.args.get('upload_status', '') or '').strip().lower()
    image_status_filter = (request.args.get('image_status', '') or '').strip().lower()

    upload_status_options = (
        ('remaining_uploads', 'Remaining Uploads'),
        ('not_started', 'Not Started'),
        ('uploading', 'Waiting for Upload'),
        ('uploaded', 'Uploaded'),
        ('failed', 'Failed'),
        (CLIENT_PUSHED_UPLOAD_STATUS, 'Client Pushed'),
    )
    valid_upload_status_filters = {value for value, _ in upload_status_options}
    if upload_status_filter and upload_status_filter not in valid_upload_status_filters:
        upload_status_filter = ''

    image_status_options = (
        ('ready', 'Ready for Upload'),
        ('waiting', 'Waiting for Upload'),
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

    if card_category_filter:
        query += " AND COALESCE(NULLIF(card_category, ''), 'trading_card') = ?"
        params.append(card_category_filter)

    if product_type_filter:
        query += f" AND {product_type_sql_expression()} = ?"
        params.append(product_type_filter)

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

    if upload_status_filter == 'remaining_uploads':
        query += " AND COALESCE(upload_status, 'not_started') NOT IN (?, ?)"
        params.extend(['uploaded', CLIENT_PUSHED_UPLOAD_STATUS])
    elif upload_status_filter:
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
        if image_status_filter == 'waiting' and not entry_dict['waiting_for_upload']:
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
        'card_category': card_category_filter,
        'product_type': product_type_filter,
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
                         card_category_filter=card_category_filter,
                         product_type_filter=product_type_filter,
                         brand_filter=brand_filter,
                         language_filter=language_filter,
                         final_grade_filter=final_grade_filter,
                         upload_status_filter=upload_status_filter,
                         image_status_filter=image_status_filter,
                         grade_options=grade_options,
                         upload_status_options=upload_status_options,
                         image_status_options=image_status_options,
                         card_category_options=CARD_CATEGORY_OPTIONS,
                         product_type_options=PRODUCT_TYPE_OPTIONS,
                         brand_options=get_brand_options(include_inactive=True),
                         language_options=LANGUAGE_OPTIONS)


@app.route('/admin/upload/import-images', methods=['POST'])
@login_required
def import_images_by_id():
    is_ajax_request = request.headers.get('X-Requested-With') == 'XMLHttpRequest'

    def respond_with_message(
        message,
        category='error',
        status_code=400,
        summary=None,
        retryable=False,
        retry_after_ms=None,
    ):
        if is_ajax_request:
            payload = {
                'success': category != 'error',
                'message': message,
                'retryable': bool(retryable),
            }
            if summary is not None:
                payload['summary'] = summary
            if retryable and retry_after_ms is not None:
                payload['retry_after_ms'] = retry_after_ms
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
    if len(uploaded_files) > MAX_IMAGE_IMPORT_FILES:
        return respond_with_message(
            f'Each image import batch is limited to {MAX_IMAGE_IMPORT_FILES} files.',
            'error',
            413,
        )

    conn = None
    created_files = []
    used_files = []
    obsolete_files = []
    try:
        conn = get_temp_db_connection()
        try:
            summary = import_uploaded_images_to_temp_cards(uploaded_files, conn)
            created_files = summary.pop('_created_files', [])
            used_files = summary.pop('_used_files', [])
            obsolete_files = summary.pop('_obsolete_files', [])
            conn.commit()
        except Exception:
            try:
                conn.rollback()
            except Exception:
                app.logger.exception('Failed to roll back folder image import')
            raise
        else:
            used_file_set = set(used_files)
            for filename in dict.fromkeys(created_files):
                if filename not in used_file_set:
                    delete_uploaded_file(filename)
            delete_queue_files_if_unreferenced(conn, obsolete_files)
        finally:
            if conn is not None:
                try:
                    conn.close()
                except Exception:
                    app.logger.exception('Failed to close folder image import connection')
    except ValueError as exc:
        cleanup_failed_import_files(created_files)
        app.logger.warning('Folder image import rejected: %s', exc)
        return respond_with_message(
            'Folder image import was rejected. Check image size, format, extension, and dimensions.',
            'error',
            422,
            retryable=False,
        )
    except Exception:
        cleanup_failed_import_files(created_files)
        app.logger.exception('Folder image import failed')
        return respond_with_message(
            'Folder image import failed. No existing image reference was removed.',
            'error',
            500,
            retryable=True,
            retry_after_ms=1000,
        )

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
    conn_temp = None
    conn_main = None
    started_at = datetime.now().isoformat()
    claim_update_applied = False
    entry = None
    export_result = None
    response_payload = None
    local_front_image = ''
    local_back_image = ''

    try:
        conn_temp = get_temp_db_connection()
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
            return jsonify({'success': False, 'error': 'Approved entry not found', 'entry_id': entry_id}), 404

        flags = get_entry_image_flags(entry)
        current_upload_status = ((entry['upload_status'] or 'not_started').strip().lower())
        if not flags['can_upload']:
            if current_upload_status == 'uploading':
                error_message = 'Entry is already uploading'
            elif current_upload_status == CLIENT_PUSHED_UPLOAD_STATUS:
                error_message = 'Client pushed entries cannot be uploaded again'
            elif not flags['has_queue_images']:
                error_message = 'Both front and back queue images are required before upload'
            else:
                error_message = 'Entry is not uploadable in its current state'
            return jsonify({'success': False, 'error': error_message, 'entry_id': entry_id}), 400

        update_cursor = conn_temp.execute(
            '''
                UPDATE temp_cards
                SET upload_status = 'uploading',
                    upload_started = ?,
                    upload_error = NULL
                WHERE id = ?
                  AND status = 'approved'
                  AND COALESCE(upload_status, 'not_started') NOT IN (?, ?)
                  AND COALESCE(front_image, '') = ?
                  AND COALESCE(back_image, '') = ?
            ''',
            (
                started_at,
                entry_id,
                'uploading',
                CLIENT_PUSHED_UPLOAD_STATUS,
                entry['front_image'] or '',
                entry['back_image'] or '',
            ),
        )
        if update_cursor.rowcount == 0:
            safe_rollback(conn_temp, 'stale upload claim')
            return jsonify({
                'success': False,
                'error': 'Entry upload state changed before upload could start',
                'entry_id': entry_id,
            }), 409
        claim_update_applied = True
        conn_temp.commit()

        conn_main = get_main_db_connection()
        export_result = upsert_main_card(entry, conn_main, require_complete=True)
        conn_main.commit()

        local_front_image = entry['front_image'] or ''
        local_back_image = entry['back_image'] or ''

        completed_at = datetime.now().isoformat()
        response_payload = {
            'entry_id': entry_id,
            'cert_id': entry['cert_id'],
            'action': export_result['action'],
            'front_image': export_result['front_image'],
            'back_image': export_result['back_image'],
        }
        completion_cursor = conn_temp.execute(
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
                  AND upload_status = 'uploading'
                  AND COALESCE(front_image, '') = ?
                  AND COALESCE(back_image, '') = ?
            ''',
            (
                started_at,
                completed_at,
                export_result['front_image'],
                export_result['back_image'],
                json.dumps(response_payload),
                entry_id,
                local_front_image,
                local_back_image,
            ),
        )
        if completion_cursor.rowcount == 0:
            safe_rollback(conn_temp, 'stale upload completion')
            raise RuntimeError('Entry image state changed before upload could complete')
        conn_temp.commit()
        delete_queue_files_if_unreferenced(
            conn_temp,
            [local_front_image, local_back_image],
        )

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
        app.logger.exception('Single entry upload failed for entry %s', entry_id)
        safe_rollback(conn_main, 'main card upload')
        safe_rollback(conn_temp, 'temporary upload state')
        safe_close(conn_main, 'main card upload connection')
        conn_main = None
        safe_close(conn_temp, 'temporary upload connection')
        conn_temp = None

        completed_at = datetime.now().isoformat()
        verification_conn = None
        try:
            if claim_update_applied and entry is not None:
                verification_conn = get_temp_db_connection()
                state = verification_conn.execute(
                    '''
                        SELECT id, upload_status, upload_started,
                               front_image, back_image,
                               published_front_image, published_back_image
                        FROM temp_cards
                        WHERE id = ?
                    ''',
                    (entry_id,),
                ).fetchone()

                completion_was_committed = bool(
                    state is not None
                    and export_result is not None
                    and response_payload is not None
                    and (state['upload_status'] or '').strip().lower() == 'uploaded'
                    and (state['upload_started'] or '') == started_at
                    and not (state['front_image'] or '')
                    and not (state['back_image'] or '')
                    and (state['published_front_image'] or '') == export_result['front_image']
                    and (state['published_back_image'] or '') == export_result['back_image']
                )
                if completion_was_committed:
                    delete_queue_files_if_unreferenced(
                        verification_conn,
                        [local_front_image, local_back_image],
                    )
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

                failure_cursor = verification_conn.execute(
                    '''
                        UPDATE temp_cards
                        SET upload_status = 'failed',
                            upload_completed = ?,
                            upload_error = ?
                        WHERE id = ?
                          AND upload_status = 'uploading'
                          AND upload_started = ?
                          AND COALESCE(front_image, '') = ?
                          AND COALESCE(back_image, '') = ?
                    ''',
                    (
                        completed_at,
                        SAFE_UPLOAD_FAILURE_DETAIL,
                        entry_id,
                        started_at,
                        entry['front_image'] or '',
                        entry['back_image'] or '',
                    ),
                )
                if failure_cursor.rowcount:
                    verification_conn.commit()
                else:
                    safe_rollback(verification_conn, 'stale upload failure state')
        except Exception:
            safe_rollback(verification_conn, 'upload recovery state')
            app.logger.exception(
                'Could not reconcile upload state for entry %s; state was retained',
                entry_id,
            )
        finally:
            safe_close(verification_conn, 'upload recovery connection')

        return jsonify({
            'success': False,
            'error': 'Upload failed. Refresh the entry before trying again.',
            'entry_id': entry_id,
        }), 500

    finally:
        safe_close(conn_temp, 'temporary upload connection')
        safe_close(conn_main, 'main card upload connection')


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
    data = request.get_json(silent=True) or {}
    entry_ids = data.get('entry_ids', [])

    if not entry_ids:
        return jsonify({'success': False, 'error': 'No entries selected'})

    results = []

    for entry_id in entry_ids:
        # 调用单条上传API
        result = api_upload_entry(entry_id)
        response = result[0] if isinstance(result, tuple) else result
        payload = response.get_json(silent=True) if hasattr(response, 'get_json') else None
        if not payload:
            payload = {'success': False, 'error': 'Unexpected upload response', 'entry_id': entry_id}
        results.append(payload)

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
