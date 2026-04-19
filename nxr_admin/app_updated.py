#!/usr/bin/env python3
"""
NXR Card Grading - Manual Data Entry System (UPDATED)
Lightweight admin entrypoint that boots shared services and route modules.
"""

import os
import sys
from pathlib import Path


CURRENT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = CURRENT_DIR.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from nxr_admin.admin_core import app, initialize_databases
from nxr_admin import routes_admin_users  # noqa: F401
from nxr_admin import routes_auth  # noqa: F401
from nxr_admin import routes_entries  # noqa: F401
from nxr_admin import routes_exports  # noqa: F401
from nxr_admin import routes_misc  # noqa: F401
from nxr_admin import routes_uploads  # noqa: F401


initialize_databases()


if __name__ == '__main__':
    port = int(os.environ.get('PORT', '8081'))
    debug = os.environ.get('FLASK_DEBUG') == '1'

    print("=" * 60)
    print("NXR Card Grading - Manual Data Entry System (UPDATED)")
    print("=" * 60)
    print(f"Access: http://localhost:{port}/admin")
    print("Login: configured admin accounts")
    print("Route modules: auth, admin users, entries, uploads, exports, misc")
    print("=" * 60)

    app.run(
        host='0.0.0.0',
        port=port,
        debug=debug,
        use_reloader=False,
    )
