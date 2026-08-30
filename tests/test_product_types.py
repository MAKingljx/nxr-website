import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

os.environ.setdefault('NXR_DB_BACKEND', 'sqlite')
_IMPORT_TEMPORARY_DIRECTORY = tempfile.TemporaryDirectory()
_IMPORT_ROOT = Path(_IMPORT_TEMPORARY_DIRECTORY.name)
os.environ['NXR_DATA_DIR'] = str(_IMPORT_ROOT / 'data')
os.environ['NXR_DB_PATH'] = str(_IMPORT_ROOT / 'data' / 'cards.db')
os.environ['NXR_TEMP_DB_PATH'] = str(_IMPORT_ROOT / 'data' / 'temp_cards.db')
os.environ['NXR_SITE_STATIC_DIR'] = str(_IMPORT_ROOT / 'site_static')
os.environ['NXR_ADMIN_UPLOAD_FOLDER'] = str(_IMPORT_ROOT / 'uploads')

from nxr_admin import admin_core
from nxr_admin import routes_admin_users
from nxr_admin import routes_auth
from nxr_admin import routes_entries
from nxr_admin import routes_exports
from nxr_admin import routes_misc
from nxr_admin import routes_settings
from nxr_admin import routes_uploads


class ProductTypeTests(unittest.TestCase):
    def setUp(self):
        self._original_paths = {
            'DATA_DIR': admin_core.DATA_DIR,
            'DB_PATH': admin_core.DB_PATH,
            'TEMP_DB_PATH': admin_core.TEMP_DB_PATH,
            'SITE_STATIC_DIR': admin_core.SITE_STATIC_DIR,
            'UPLOAD_FOLDER': admin_core.UPLOAD_FOLDER,
        }
        self._original_upload_config = admin_core.app.config['UPLOAD_FOLDER']
        self._temporary_directory = tempfile.TemporaryDirectory()
        root = Path(self._temporary_directory.name)

        admin_core.DATA_DIR = root / 'data'
        admin_core.DB_PATH = admin_core.DATA_DIR / 'cards.db'
        admin_core.TEMP_DB_PATH = admin_core.DATA_DIR / 'temp_cards.db'
        admin_core.SITE_STATIC_DIR = root / 'site_static'
        admin_core.UPLOAD_FOLDER = root / 'uploads'
        for directory in (admin_core.DATA_DIR, admin_core.SITE_STATIC_DIR, admin_core.UPLOAD_FOLDER):
            directory.mkdir(parents=True, exist_ok=True)
        admin_core.app.config['UPLOAD_FOLDER'] = admin_core.UPLOAD_FOLDER

        admin_core.initialize_main_database()
        admin_core.init_temp_database()

    def tearDown(self):
        for name, value in self._original_paths.items():
            setattr(admin_core, name, value)
        admin_core.app.config['UPLOAD_FOLDER'] = self._original_upload_config
        self._temporary_directory.cleanup()

    def _entry(self, product_type, vintage_classification='', card_category='trading_card'):
        return {
            'cert_id': '1234567891',
            'card_name': 'Shared Identity',
            'product_type': product_type,
            'vintage_classification': vintage_classification,
            'card_category': card_category,
            'year': '1999',
            'brand': 'Pokemon',
            'variety': 'Foil',
            'pop': '1',
            'centering': 1.0,
            'edges': 1.0,
            'corners': 1.0,
            'surface': 1.0,
            'final_grade': 1.0,
            'final_grade_text': '',
            'language': 'EN',
            'set_name': 'Set A',
            'card_number': '001',
            'entry_notes': '',
            'entry_date': '2026-08-13T10:00:00',
            'created_at': '2026-08-13T10:00:00',
            'updated_at': '2026-08-13T10:00:00',
        }

    def _insert_temp_entry(
        self,
        cert_id,
        product_type,
        classification='',
        grade_text='',
        final_grade=1.0,
        merch_description='',
    ):
        with admin_core.get_temp_db_connection() as conn:
            conn.execute(
                '''
                    INSERT INTO temp_cards (
                        cert_id, card_name, product_type, vintage_classification, merch_description,
                        card_category, brand, variety, language, set_name, card_number,
                        centering, edges, corners, surface, final_grade, final_grade_text,
                        status, entry_date, created_at, updated_at
                    )
                    VALUES (?, 'Shared Identity', ?, ?, ?, 'trading_card', 'Pokemon', 'Foil',
                            'EN', 'Set A', '001', 1, 1, 1, 1, ?, ?, 'approved',
                            '2026-08-13T10:00:00', '2026-08-13T10:00:00', '2026-08-13T10:00:00')
                ''',
                (
                    cert_id,
                    product_type,
                    classification,
                    merch_description,
                    final_grade,
                    grade_text,
                ),
            )
            conn.commit()

    def test_schema_defaults_preserve_historical_graded_behavior(self):
        with admin_core.get_temp_db_connection() as conn:
            columns = admin_core.db.table_columns(conn, 'temp_cards')
            conn.execute(
                '''
                    INSERT INTO temp_cards (cert_id, card_name, brand, language, set_name, card_number)
                    VALUES ('1234567892', 'Legacy', 'Pokemon', 'EN', 'Set A', '002')
                ''',
            )
            row = conn.execute(
                'SELECT product_type, vintage_classification FROM temp_cards WHERE cert_id = ?',
                ('1234567892',),
            ).fetchone()

        with admin_core.get_main_db_connection() as conn:
            main_columns = admin_core.db.table_columns(conn, 'cards')

        self.assertIn('product_type', columns)
        self.assertIn('vintage_classification', columns)
        self.assertIn('product_type', main_columns)
        self.assertIn('vintage_classification', main_columns)
        self.assertEqual(row['product_type'], 'graded_card')
        self.assertEqual(row['vintage_classification'], '')

    def test_unscored_products_hide_internal_scores_and_preserve_variety(self):
        raw_entry = self._entry('label_product', card_category='sports_card')
        serialized = admin_core.serialize_temp_entry(raw_entry)
        payload = admin_core.build_main_card_payload(raw_entry)

        self.assertFalse(serialized['uses_grading'])
        self.assertIsNone(serialized['centering'])
        self.assertIsNone(serialized['final_grade'])
        self.assertEqual(serialized['final_grade_text'], '')
        self.assertEqual(payload['product_type'], 'merch_product')
        self.assertEqual(payload['card_category'], 'trading_card')
        self.assertEqual(payload['variety'], 'Foil')
        self.assertIsNone(payload['centering'])
        self.assertIsNone(payload['final_grade'])
        self.assertEqual(payload['grade'], '')
        self.assertEqual(payload['final_grade_text'], '')

    def test_form_policy_forces_tcg_and_ignores_unscored_score_input(self):
        with admin_core.app.test_request_context(
            '/admin/entry/new',
            method='POST',
            data={
                'product_type': 'label_product',
                'card_category': 'sports_card',
                'card_name': 'Label',
                'brand': 'Pokemon',
                'variety': 'Foil',
                'language': 'EN',
                'set_name': 'Set A',
                'card_number': '003',
                'centering': '10',
                'edges': '10',
                'corners': '10',
                'surface': '10',
            },
        ):
            card_data = routes_entries.collect_category_form_data()
            grading_data, error = routes_entries.collect_grading_data(card_data['product_type'])

        self.assertEqual(card_data['card_category'], 'trading_card')
        self.assertEqual(card_data['variety'], 'Foil')
        self.assertEqual(error, '')
        self.assertEqual(grading_data['centering'], 1.0)
        self.assertEqual(grading_data['final_grade_text'], '')

    def test_submitted_product_type_rejects_unknown_values(self):
        with admin_core.app.test_request_context(
            '/admin/entry/new',
            method='POST',
            data={'product_type': 'unknown_product'},
        ):
            with self.assertRaisesRegex(ValueError, 'Unsupported product type'):
                routes_entries.collect_category_form_data()

        client = admin_core.app.test_client()
        with client.session_transaction() as session_state:
            session_state['admin_logged_in'] = True
            session_state['username'] = 'tester'
            session_state['role'] = 'superadmin'

        response = client.post(
            '/admin/api/calculate-pop',
            json={'product_type': 'unknown_product'},
        )

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.get_json()['error'], 'Unsupported product type')

    def test_vintage_dictionary_has_default_protected_options_and_is_limited_to_four_items(self):
        self.assertEqual(
            admin_core.get_vintage_classification_options(),
            ['Pristine', 'Nova', 'Legacy', 'Helix'],
        )
        self.assertEqual(
            admin_core.validate_product_policy(
                {'product_type': 'vintage_product', 'vintage_classification': 'Nova'}
            ),
            (True, ''),
        )
        valid, error = admin_core.validate_product_policy(
            {'product_type': 'vintage_product', 'vintage_classification': ''}
        )
        self.assertFalse(valid)
        self.assertIn('required', error.lower())

        with admin_core.get_main_db_connection() as conn:
            group = conn.execute(
                'SELECT id FROM dictionary_groups WHERE code = ?',
                (admin_core.VINTAGE_CLASSIFICATION_DICTIONARY_CODE,),
            ).fetchone()
            with self.assertRaisesRegex(ValueError, 'at most four'):
                admin_core.create_dictionary_item(conn, group['id'], 'Class E')

        self.assertIn(
            admin_core.VINTAGE_CLASSIFICATION_DICTIONARY_CODE,
            admin_core.PROTECTED_DICTIONARY_CODES,
        )
        self.assertEqual(
            admin_core.get_vintage_classification_options(),
            ['Pristine', 'Nova', 'Legacy', 'Helix'],
        )
        self.assertEqual(
            admin_core.validate_product_policy(
                {'product_type': 'vintage_product', 'vintage_classification': 'Helix'}
            ),
            (True, ''),
        )

    def test_population_isolated_by_product_grade_and_vintage_classification(self):
        self._insert_temp_entry('1234567893', 'graded_card', grade_text='9', final_grade=9.0)
        self._insert_temp_entry('1234567894', 'graded_card', grade_text='9.5', final_grade=9.5)
        self._insert_temp_entry('1234567895', 'label_product')
        self._insert_temp_entry('1234567896', 'vintage_product', classification='Class A')
        self._insert_temp_entry('1234567897', 'vintage_product', classification='Class B')

        graded = admin_core.calculate_population(
            'Shared Identity', 'Set A', '001', 'EN', '9', product_type='graded_card'
        )
        label = admin_core.calculate_population(
            'Shared Identity', 'Set A', '001', 'EN', '', product_type='label_product'
        )
        vintage = admin_core.calculate_population(
            'Shared Identity', 'Set A', '001', 'EN', '',
            product_type='vintage_product', vintage_classification='Class A',
        )

        self.assertEqual((graded[0], graded[2], graded[3]), (2, 1, 0))
        self.assertEqual((label[0], label[2], label[3]), (2, 1, 0))
        self.assertEqual((vintage[0], vintage[2], vintage[3]), (2, 1, 0))

    def test_match_card_and_grade_api_respect_product_type(self):
        self._insert_temp_entry('1234567898', 'graded_card', grade_text='9', final_grade=9.0)
        self._insert_temp_entry(
            '1234567899',
            'label_product',
            merch_description='Limited enamel pin with gold-tone finish.',
        )
        with admin_core.get_temp_db_connection() as conn:
            conn.execute(
                "UPDATE temp_cards SET card_name = 'Graded Match' WHERE cert_id = '1234567898'"
            )
            conn.execute(
                "UPDATE temp_cards SET card_name = 'Label Match' WHERE cert_id = '1234567899'"
            )
            conn.commit()

        client = admin_core.app.test_client()
        with client.session_transaction() as session_state:
            session_state['admin_logged_in'] = True
            session_state['username'] = 'tester'
            session_state['role'] = 'superadmin'

        match_response = client.post(
            '/admin/api/match-card',
            json={
                'product_type': 'label_product',
                'card_category': 'sports_card',
                'set_name': 'Set A',
                'card_number': '001',
            },
        )
        grade_response = client.post(
            '/admin/api/calculate-grade',
            json={
                'product_type': 'label_product',
                'centering': 10,
                'edges': 10,
                'corners': 10,
                'surface': 10,
            },
        )
        with admin_core.get_main_db_connection() as conn:
            conn.execute(
                '''
                    INSERT INTO cards (
                        cert_id, card_name, product_type, merch_description,
                        card_category, brand, year, variety, language,
                        set_name, card_number, created_at, updated_at
                    )
                    VALUES (
                        '1234567900', 'Main Merch Match', 'merch_product',
                        'Description loaded from the published card.',
                        'trading_card', 'Pokemon', '2025', 'Pin', 'EN',
                        'Main Set', '002',
                        '2026-08-13T09:00:00', '2026-08-13T09:00:00'
                    )
                ''',
            )
            conn.commit()
        main_match_response = client.post(
            '/admin/api/match-card',
            json={
                'product_type': 'merch_product',
                'card_category': 'trading_card',
                'set_name': 'Main Set',
                'card_number': '002',
            },
        )

        self.assertEqual(match_response.status_code, 200)
        self.assertEqual(match_response.get_json()['card_name'], 'Label Match')
        self.assertEqual(
            match_response.get_json()['merch_description'],
            'Limited enamel pin with gold-tone finish.',
        )
        self.assertEqual(main_match_response.status_code, 200)
        self.assertEqual(main_match_response.get_json()['source'], 'cards')
        self.assertEqual(
            main_match_response.get_json()['merch_description'],
            'Description loaded from the published card.',
        )
        self.assertEqual(grade_response.status_code, 400)
        self.assertIn('does not accept grading', grade_response.get_json()['error'])

    def test_export_filters_are_ordered_and_scope_each_product_type(self):
        self._insert_temp_entry(
            '3234567891',
            'graded_card',
            grade_text='9',
            final_grade=9.0,
        )
        self._insert_temp_entry(
            '3234567892',
            'merch_product',
            merch_description='Collector pin',
        )
        self._insert_temp_entry(
            '3234567893',
            'vintage_product',
            classification='Pristine',
        )

        client = admin_core.app.test_client()
        with client.session_transaction() as session_state:
            session_state['admin_logged_in'] = True
            session_state['username'] = 'tester'
            session_state['role'] = 'superadmin'

        page = client.get('/admin/export/excel')
        self.assertEqual(page.status_code, 200)
        rendered = page.get_data(as_text=True)
        grade_position = rendered.index('value="grade:9"')
        merch_position = rendered.index('value="merch_product"')
        vintage_positions = [
            rendered.index(f'value="vintage_product:{classification}"')
            for classification in ('Pristine', 'Nova', 'Legacy', 'Helix')
        ]
        self.assertLess(grade_position, merch_position)
        self.assertLess(merch_position, vintage_positions[0])
        self.assertEqual(vintage_positions, sorted(vintage_positions))

        expected_filters = {
            'grade:9': ('3234567891', '9'),
            'merch_product': ('3234567892', 'Merch Product'),
            'vintage_product:Pristine': ('3234567893', 'Vintage Card - Pristine'),
        }
        for export_filter, (cert_id, export_label) in expected_filters.items():
            with self.subTest(export_filter=export_filter):
                response = client.post(
                    '/admin/export/preview',
                    data={'export_filter': export_filter},
                )
                payload = response.get_json()
                self.assertEqual(response.status_code, 200)
                self.assertTrue(payload['can_export'])
                self.assertEqual(payload['total_count'], 1)
                self.assertEqual(payload['rows'][0]['cert_id'], cert_id)
                self.assertEqual(payload['rows'][0]['export_label'], export_label)

        invalid_response = client.post(
            '/admin/export/preview',
            data={'export_filter': 'vintage_product:Unknown'},
        )
        self.assertEqual(invalid_response.status_code, 400)

    def test_export_masks_all_unscored_score_columns(self):
        try:
            import pandas as pd
        except ImportError:
            self.skipTest('pandas is not installed')

        frame = pd.DataFrame([
            {
                'product_type': 'graded_card',
                'centering': 9.0,
                'final_grade': 9.0,
                'final_grade_text': '9',
            },
            {
                'product_type': 'label_product',
                'centering': 1.0,
                'final_grade': 1.0,
                'final_grade_text': '',
            },
        ])

        result = routes_exports.normalize_score_columns_for_export(frame, pd)

        self.assertEqual(result.loc[0, 'final_grade'], 9.0)
        self.assertTrue(pd.isna(result.loc[1, 'centering']))
        self.assertTrue(pd.isna(result.loc[1, 'final_grade']))
        self.assertTrue(pd.isna(result.loc[1, 'final_grade_text']))

    def test_generate_excel_exports_merch_and_vintage_rows(self):
        try:
            import pandas as pd
        except ImportError:
            self.skipTest('pandas is not installed')

        self._insert_temp_entry(
            '4234567891',
            'merch_product',
            merch_description='Numbered collector pin',
        )
        self._insert_temp_entry(
            '4234567892',
            'vintage_product',
            classification='Nova',
        )

        client = admin_core.app.test_client()
        with client.session_transaction() as session_state:
            session_state['admin_logged_in'] = True
            session_state['username'] = 'tester'
            session_state['role'] = 'superadmin'

        export_cases = {
            'merch_product': {
                'cert_id': '4234567891',
                'product_type': 'merch_product',
                'merch_description': 'Numbered collector pin',
            },
            'vintage_product:Nova': {
                'cert_id': '4234567892',
                'product_type': 'vintage_product',
                'vintage_classification': 'Nova',
            },
        }
        with tempfile.TemporaryDirectory() as export_root:
            export_admin_dir = Path(export_root)
            with mock.patch.object(routes_exports, 'ADMIN_DIR', export_admin_dir):
                for export_filter, expected in export_cases.items():
                    with self.subTest(export_filter=export_filter):
                        existing_files = set((export_admin_dir / 'exports').glob('*.xlsx'))
                        response = client.post(
                            '/admin/export/generate-excel',
                            data={'export_filter': export_filter},
                        )
                        generated_files = set((export_admin_dir / 'exports').glob('*.xlsx'))
                        new_files = generated_files - existing_files

                        self.assertEqual(response.status_code, 302)
                        self.assertEqual(len(new_files), 1)
                        frame = pd.read_excel(
                            new_files.pop(),
                            sheet_name='Approved Cards',
                            converters={'cert_id': str},
                        )
                        self.assertEqual(len(frame), 1)
                        for column_name, expected_value in expected.items():
                            self.assertEqual(frame.loc[0, column_name], expected_value)

    def test_changed_templates_parse(self):
        template_names = (
            'entry_form_updated.html',
            'entry_detail.html',
            'entry_list.html',
            'dashboard.html',
            'upload_manager.html',
            'dictionary_settings.html',
            'export_excel.html',
        )
        for template_name in template_names:
            with self.subTest(template=template_name):
                admin_core.app.jinja_env.get_template(template_name)

    def test_admin_views_render_unscored_product_metadata(self):
        self._insert_temp_entry('2234567891', 'label_product')
        self._insert_temp_entry('2234567892', 'vintage_product', classification='Class A')
        with admin_core.get_temp_db_connection() as conn:
            label_entry_id = conn.execute(
                'SELECT id FROM temp_cards WHERE cert_id = ?',
                ('2234567891',),
            ).fetchone()['id']

        client = admin_core.app.test_client()
        with client.session_transaction() as session_state:
            session_state['admin_logged_in'] = True
            session_state['username'] = 'tester'
            session_state['role'] = 'superadmin'

        responses = {
            'dashboard': client.get('/admin/dashboard'),
            'list': client.get('/admin/entries?product_type=label_product'),
            'detail': client.get(f'/admin/entries/{label_entry_id}'),
            'upload': client.get('/admin/upload?product_type=vintage_product'),
        }

        for name, response in responses.items():
            with self.subTest(view=name):
                self.assertEqual(response.status_code, 200)
        self.assertIn(b'Merch Product', responses['dashboard'].data)
        self.assertIn(b'Merch Product', responses['list'].data)
        self.assertNotIn(b'<h6 class="mb-0"><i class="fas fa-star me-2"></i>Grading Details</h6>', responses['detail'].data)
        self.assertIn(b'Vintage Card', responses['upload'].data)
        self.assertIn(b'Class A', responses['upload'].data)


if __name__ == '__main__':
    unittest.main()
