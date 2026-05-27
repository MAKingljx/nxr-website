import sys
import tempfile
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from nxr_admin import admin_core


with tempfile.TemporaryDirectory() as tmp:
    tmp_path = Path(tmp)
    admin_core.DB_PATH = tmp_path / 'cards.db'
    admin_core.TEMP_DB_PATH = tmp_path / 'temp_cards.db'

    from nxr_admin import app_updated

    with admin_core.get_main_db_connection() as conn:
        conn.execute('CREATE TABLE IF NOT EXISTS cards (cert_id TEXT)')
        conn.commit()

    admin_core.app.config['TESTING'] = True

    assert 'Wind Breaker' in admin_core.get_brand_options()
    assert 'Other' in admin_core.get_brand_options()
    assert admin_core.normalize_brand('dc comics') == 'DC'
    assert admin_core.normalize_brand('wind breaker') == 'Wind Breaker'

    # Aliases must come from the database, not from the hard-coded seed dict.
    alias_map = admin_core.get_brand_alias_map()
    assert alias_map.get('dc comics') == 'DC'
    assert alias_map.get('mtg') == 'Magic: The Gathering'

    client = admin_core.app.test_client()
    with client.session_transaction() as sess:
        sess['admin_logged_in'] = True
        sess['username'] = 'admin'
        sess['role'] = 'superadmin'

    # System Settings hub should render and link to Brand Settings.
    response = client.get('/admin/settings')
    assert response.status_code == 200, response.status_code
    assert b'System Settings' in response.data
    assert b'Brand Settings' in response.data
    assert b'/admin/settings/brands' in response.data

    response = client.get('/admin/settings/brands')
    assert response.status_code == 200, response.status_code
    assert b'Brand Settings' in response.data
    assert b'Back to System Settings' in response.data

    response = client.post(
        '/admin/settings/brands',
        data={
            'name': 'Local Test Brand',
            'aliases': 'ltb\nlocal-test',
            'sort_order': '998',
            'is_active': '1',
        },
        follow_redirects=True,
    )
    assert response.status_code == 200, response.status_code
    assert 'Local Test Brand' in admin_core.get_brand_options()
    assert admin_core.normalize_brand('ltb') == 'Local Test Brand'
    assert admin_core.normalize_brand('local-test') == 'Local Test Brand'

    response = client.get('/admin/entry/new')
    assert response.status_code == 200, response.status_code
    assert b'Local Test Brand' in response.data

    brand = next(item for item in admin_core.list_brand_settings() if item['name'] == 'Local Test Brand')
    response = client.post(
        f'/admin/settings/brands/{brand["id"]}/edit',
        data={
            'name': 'Local Test Brand Updated',
            'aliases': 'ltbu',
            'sort_order': '997',
            'is_active': '1',
        },
        follow_redirects=True,
    )
    assert response.status_code == 200, response.status_code
    assert admin_core.normalize_brand('ltbu') == 'Local Test Brand Updated'

    response = client.post(f'/admin/settings/brands/{brand["id"]}/delete', follow_redirects=True)
    assert response.status_code == 200, response.status_code
    assert 'Local Test Brand Updated' not in admin_core.get_brand_options(include_inactive=True)

    # Database-as-single-source-of-truth: clearing aliases in DB must drop the
    # alias mapping at runtime (no hard-coded fallback from BRAND_ALIASES).
    dc = next(item for item in admin_core.list_brand_settings() if item['name'] == 'DC')
    response = client.post(
        f'/admin/settings/brands/{dc["id"]}/edit',
        data={'name': 'DC', 'aliases': '', 'sort_order': dc['sort_order'], 'is_active': '1'},
        follow_redirects=True,
    )
    assert response.status_code == 200, response.status_code
    assert admin_core.get_brand_alias_map().get('dc comics') is None, (
        'BRAND_ALIASES leaked into runtime alias map - DB is not the single source of truth'
    )
    assert admin_core.normalize_brand('dc comics') == 'Other'
    assert admin_core.normalize_brand('DC') == 'DC'

print('validate_brand_settings_ok=1')
