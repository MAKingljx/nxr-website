from nxr_admin.admin_core import *

SCORE_EXPORT_COLUMNS = (
    'centering',
    'edges',
    'corners',
    'surface',
    'final_grade',
    'ai_grade',
    'ai_centering',
    'ai_edges',
    'ai_corners',
    'ai_surface',
)


def normalize_score_columns_for_export(df, pd_module):
    for column_name in SCORE_EXPORT_COLUMNS:
        if column_name not in df.columns:
            continue
        numeric_values = pd_module.to_numeric(df[column_name], errors='coerce')
        df[column_name] = numeric_values.round(1).where(numeric_values.notna(), df[column_name])
    return df


def apply_score_number_format(worksheet):
    from openpyxl.utils import get_column_letter

    header_map = {
        cell.value: cell.column
        for cell in worksheet[1]
        if cell.value
    }
    for column_name in SCORE_EXPORT_COLUMNS:
        column_index = header_map.get(column_name)
        if not column_index:
            continue
        column_letter = get_column_letter(column_index)
        for cell in worksheet[column_letter][1:]:
            if isinstance(cell.value, (int, float)):
                cell.number_format = '0.0'


def get_grade_options_from_db():
    """从数据库获取所有可用的final grade选项"""
    conn = get_temp_db_connection()
    grades = get_grade_filter_options(conn, status_filter='approved')
    conn.close()

    return grades

def get_grade_stats_from_db():
    """从数据库获取各评分等级的数量统计"""
    conn = get_temp_db_connection()
    cursor = conn.cursor()

    grade_options = get_grade_filter_options(conn, status_filter='approved')
    grade_stats = {}

    for grade in grade_options:
        cursor.execute(f"""
            SELECT COUNT(*) FROM temp_cards
            WHERE status = 'approved' AND {build_grade_filter_sql(grade)}
        """, (grade,))
        grade_stats[grade] = cursor.fetchone()[0]

    cursor.execute("SELECT COUNT(*) FROM temp_cards WHERE status = 'approved'")
    total_approved = cursor.fetchone()[0]

    conn.close()

    return total_approved, grade_stats

# ========== Excel Export Page ==========
@app.route('/admin/export/excel')
@login_required
def export_excel_page():
    """Excel导出页面"""
    total_approved, grade_stats = get_grade_stats_from_db()
    grade_options = [grade for grade in STANDARD_GRADE_OPTIONS if grade_stats.get(grade, 0) > 0]

    return render_template('export_excel.html',
                         grade_options=grade_options,
                         total_approved=total_approved,
                         grade_stats=grade_stats,
                         export_history_default_page_size=EXPORT_HISTORY_DEFAULT_PAGE_SIZE,
                         page_size_options=PAGE_SIZE_OPTIONS,
                         brand_options=BRAND_OPTIONS,
                         language_options=LANGUAGE_OPTIONS)

# ========== Generate Excel File ==========
@app.route('/admin/export/generate-excel', methods=['POST'])
@login_required
def generate_excel():
    """生成Excel文件"""
    try:
        import pandas as pd

        grade_filter = normalize_final_grade_text(request.form.get('grade_filter', '').strip())
        if request.form.get('grade_filter', '').strip() == 'all':
            grade_filter = None

        # 构建查询
        query = "SELECT * FROM temp_cards WHERE status = 'approved'"
        params = []

        if grade_filter:
            query += f" AND {build_grade_filter_sql(grade_filter)}"
            params.append(grade_filter)

        query += f" ORDER BY {build_approved_order_clause()}"

        # 执行查询
        conn = get_temp_db_connection()
        df = pd.read_sql_query(query, conn, params=params)
        conn.close()

        if df.empty:
            flash('没有找到匹配的数据', 'warning')
            return redirect(url_for('export_excel_page'))

        # 添加landing page url列
        df['landing_page_url'] = df['cert_id'].apply(lambda x: f"nxrgrading.com/card/{str(x).strip()}")
        if 'final_grade_text' in df.columns:
            df['final_grade_text'] = df['final_grade_text'].apply(normalize_final_grade_text).replace('', pd.NA).fillna(df['final_grade_text'])
        df = normalize_score_columns_for_export(df, pd)

        # 重新排列列顺序，将landing_page_url放在最后
        columns = [col for col in df.columns if col != 'landing_page_url']
        columns.append('landing_page_url')
        df = df[columns]

        # 生成输出文件名
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        grade_suffix = f"_{grade_filter}" if grade_filter else "_all"
        exports_dir = ADMIN_DIR / "exports"
        exports_dir.mkdir(exist_ok=True)

        output_filename = f"approved_cards{grade_suffix}_{timestamp}.xlsx"
        output_path = exports_dir / output_filename

        # 导出到Excel
        with pd.ExcelWriter(output_path, engine='openpyxl') as writer:
            # 主数据表
            df.to_excel(writer, sheet_name='Approved Cards', index=False)
            apply_score_number_format(writer.sheets['Approved Cards'])

            # 添加汇总表
            summary_data = {
                '导出时间': [datetime.now().strftime("%Y-%m-%d %H:%M:%S")],
                '总记录数': [len(df)],
                '筛选条件': [f"final_grade_text = {grade_filter}" if grade_filter else "全部"],
                '数据范围': [format_export_date_range(df)],
                '包含字段数': [len(df.columns)],
                '文件名称': [output_filename]
            }

            summary_df = pd.DataFrame(summary_data)
            summary_df.to_excel(writer, sheet_name='导出汇总', index=False)

            # 添加评分统计表
            if 'final_grade_text' in df.columns:
                grade_stats = df['final_grade_text'].value_counts().reset_index()
                grade_stats.columns = ['评分等级', '数量']
                grade_stats['占比'] = (grade_stats['数量'] / len(df) * 100).round(1).astype(str) + '%'
                grade_stats.to_excel(writer, sheet_name='评分统计', index=False)

        # 记录导出历史
        export_history_path = exports_dir / "export_history.json"
        history = load_export_history(export_history_path)

        history.append({
            'filename': output_filename,
            'grade_filter': grade_filter,
            'record_count': len(df),
            'export_time': datetime.now().isoformat(),
            'file_size': os.path.getsize(output_path)
        })

        # 只保留最近50条记录
        if len(history) > 50:
            history = history[-50:]

        with open(export_history_path, 'w', encoding='utf-8') as f:
            json.dump(history, f, ensure_ascii=False, indent=2)

        flash(f'Excel文件生成成功: {output_filename} ({len(df)} 条记录)', 'success')
        return redirect(url_for('export_excel_page'))

    except Exception as e:
        flash(f'生成Excel文件失败: {str(e)}', 'error')
        return redirect(url_for('export_excel_page'))

# ========== Download Excel File ==========
@app.route('/admin/export/download/<filename>')
@login_required
def download_excel(filename):
    """下载Excel文件"""
    file_path = resolve_export_file_path(filename)

    if not file_path or not file_path.exists():
        flash('文件不存在', 'error')
        return redirect(url_for('export_excel_page'))

    return send_file(file_path, as_attachment=True)

# ========== List Export Files ==========
@app.route('/admin/export/list')
@login_required
def list_exports():
    """列出所有导出文件"""
    exports_dir = ADMIN_DIR / "exports"
    page = max(request.args.get('page', 1, type=int), 1)
    page_size = get_page_size_arg(default=EXPORT_HISTORY_DEFAULT_PAGE_SIZE)

    if not exports_dir.exists():
        return jsonify({
            'exports': [],
            'page': page,
            'page_size': page_size,
            'page_size_options': list(PAGE_SIZE_OPTIONS),
            'total': 0,
            'total_pages': 1,
            'has_prev': False,
            'has_next': False,
        })

    # 获取所有Excel文件
    excel_files = list(exports_dir.glob("*.xlsx"))

    # 按修改时间排序
    excel_files.sort(key=lambda x: x.stat().st_mtime, reverse=True)
    total = len(excel_files)
    total_pages = max((total + page_size - 1) // page_size, 1)
    if page > total_pages:
        page = total_pages
    start = (page - 1) * page_size
    end = start + page_size

    exports = []
    for file in excel_files[start:end]:
        file_stat = file.stat()
        exports.append({
            'name': file.name,
            'size': file_stat.st_size,
            'modified': datetime.fromtimestamp(file_stat.st_mtime).strftime("%Y-%m-%d %H:%M:%S"),
            'url': url_for('download_excel', filename=file.name)
        })

    return jsonify({
        'exports': exports,
        'page': page,
        'page_size': page_size,
        'page_size_options': list(PAGE_SIZE_OPTIONS),
        'total': total,
        'total_pages': total_pages,
        'has_prev': page > 1,
        'has_next': page < total_pages,
    })

# ========== Delete Export File ==========
@app.route('/admin/export/delete/<filename>', methods=['POST'])
@login_required
def delete_export(filename):
    """删除导出文件"""
    file_path = resolve_export_file_path(filename)

    if file_path and file_path.exists():
        try:
            file_path.unlink()
            if request.headers.get('X-Requested-With') == 'XMLHttpRequest':
                return jsonify({'success': True, 'filename': file_path.name})
            flash(f'文件已删除: {file_path.name}', 'success')
        except Exception as e:
            if request.headers.get('X-Requested-With') == 'XMLHttpRequest':
                return jsonify({'success': False, 'error': str(e)}), 500
            flash(f'删除文件失败: {str(e)}', 'error')
    else:
        if request.headers.get('X-Requested-With') == 'XMLHttpRequest':
            return jsonify({'success': False, 'error': 'File not found'}), 404
        flash('文件不存在', 'error')

    return redirect(url_for('export_excel_page'))
