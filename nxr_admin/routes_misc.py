from nxr_admin.admin_core import *

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
