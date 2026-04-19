from nxr_admin.admin_core import *

# ========== Upload Manager ==========
@app.route('/admin/upload')
@login_required
def upload_manager():
    """上传管理页面"""
    page = max(request.args.get('page', 1, type=int), 1)
    show_client_pushed = request.args.get('show_client_pushed', '0') == '1'
    page_size = get_page_size_arg(default=UPLOAD_LIST_DEFAULT_PAGE_SIZE)

    conn = get_temp_db_connection()
    cursor = conn.cursor()

    # 获取所有已批准数据（包括图片不完整的）
    offset = (page - 1) * page_size

    query = '''
        SELECT * FROM temp_cards
        WHERE status = 'approved'
    '''
    params = []
    if not show_client_pushed:
        query += " AND COALESCE(upload_status, 'not_started') != ?"
        params.append(CLIENT_PUSHED_UPLOAD_STATUS)

    query += '''
        ORDER BY
            COALESCE(NULLIF(approved_at, ''), updated_at, entry_date, created_at) DESC,
            COALESCE(approval_sequence, 9223372036854775807) ASC,
            id ASC
        LIMIT ? OFFSET ?
    '''
    params.extend([page_size, offset])

    cursor.execute(query, params)

    raw_entries = cursor.fetchall()
    entries = []
    for entry in raw_entries:
        entry_dict = serialize_temp_entry(entry)
        entry_dict.update(get_entry_image_flags(entry))
        entries.append(entry_dict)

    # 获取总数
    total_query = '''
        SELECT COUNT(*) FROM temp_cards
        WHERE status = 'approved'
    '''
    total_params = []
    if not show_client_pushed:
        total_query += " AND COALESCE(upload_status, 'not_started') != ?"
        total_params.append(CLIENT_PUSHED_UPLOAD_STATUS)
    cursor.execute(total_query, total_params)
    total = cursor.fetchone()[0]

    stats = get_upload_stats(conn)

    total_pages = max((total + page_size - 1) // page_size, 1)
    if page > total_pages:
        page = total_pages
        offset = (page - 1) * page_size
        params[-2:] = [page_size, offset]
        cursor.execute(query, params)
        raw_entries = cursor.fetchall()
        entries = []
        for entry in raw_entries:
            entry_dict = serialize_temp_entry(entry)
            entry_dict.update(get_entry_image_flags(entry))
            entries.append(entry_dict)

    conn.close()

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
                             {'show_client_pushed': 1 if show_client_pushed else 0, 'page_size': page_size},
                         ),
                         page_size=page_size,
                         page_size_options=PAGE_SIZE_OPTIONS,
                         page_start=((page - 1) * page_size) + 1 if total else 0,
                         page_end=min(page * page_size, total),
                         stats=stats,
                         brand_options=BRAND_OPTIONS,
                         language_options=LANGUAGE_OPTIONS)

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
