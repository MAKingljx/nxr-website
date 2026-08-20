import io
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from flask import session

from nxr_admin import admin_core
from nxr_admin import routes_entries


class CommitThenRaiseConnection:
    def __init__(self, connection, commit_first=True, close_raises=False):
        self.connection = connection
        self.commit_first = commit_first
        self.close_raises = close_raises

    def execute(self, *args, **kwargs):
        return self.connection.execute(*args, **kwargs)

    def cursor(self, *args, **kwargs):
        return self.connection.cursor(*args, **kwargs)

    def commit(self):
        if self.commit_first:
            self.connection.commit()
        raise ConnectionError('commit acknowledgement was lost')

    def rollback(self):
        return self.connection.rollback()

    def close(self):
        self.connection.close()
        if self.close_raises:
            raise OSError('close failed')


class CloseAfterCommitConnection:
    def __init__(self, connection):
        self.connection = connection

    def execute(self, *args, **kwargs):
        return self.connection.execute(*args, **kwargs)

    def cursor(self, *args, **kwargs):
        return self.connection.cursor(*args, **kwargs)

    def commit(self):
        return self.connection.commit()

    def rollback(self):
        return self.connection.rollback()

    def close(self):
        self.connection.close()
        raise OSError('close failed after the connection closed')


class InterruptedFileSave:
    filename = 'broken.jpg'

    def save(self, destination):
        Path(destination).write_bytes(b'partial-image')
        raise OSError('upload stream interrupted')


class EntryImageTransactionTests(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.upload_directory = self.root / 'uploads'
        self.upload_directory.mkdir()
        self.database_path = self.root / 'temp_cards.db'
        self.original_upload_folder = admin_core.app.config['UPLOAD_FOLDER']
        admin_core.app.config.update(
            TESTING=True,
            UPLOAD_FOLDER=self.upload_directory,
        )

        connection = self._connect()
        connection.execute(
            '''
                CREATE TABLE temp_cards (
                    id INTEGER PRIMARY KEY,
                    cert_id TEXT,
                    card_name TEXT,
                    product_type TEXT,
                    vintage_classification TEXT,
                    merch_description TEXT,
                    card_category TEXT,
                    movie_name TEXT,
                    release_year TEXT,
                    production_company TEXT,
                    film_type TEXT,
                    sports_type TEXT,
                    group_name TEXT,
                    year TEXT,
                    brand TEXT,
                    variety TEXT,
                    pop TEXT,
                    language TEXT,
                    set_name TEXT,
                    card_number TEXT,
                    centering REAL,
                    edges REAL,
                    corners REAL,
                    surface REAL,
                    final_grade REAL,
                    final_grade_text TEXT,
                    front_image TEXT,
                    back_image TEXT,
                    published_front_image TEXT,
                    published_back_image TEXT,
                    entry_notes TEXT,
                    entry_by TEXT,
                    entry_date TEXT,
                    status TEXT,
                    created_at TEXT,
                    updated_at TEXT,
                    upload_status TEXT
                )
            '''
        )
        connection.execute(
            '''
                INSERT INTO temp_cards (
                    id, cert_id, card_name, product_type, card_category,
                    language, set_name, card_number, centering, edges,
                    corners, surface, final_grade, final_grade_text,
                    front_image, back_image, published_front_image,
                    published_back_image, updated_at, upload_status
                ) VALUES (
                    1, '7000000001', 'Original', 'graded_card', 'trading_card',
                    'EN', 'Set', '1', 9, 9, 9, 9, 9, '9',
                    'old-front.jpg', 'old-back.jpg', '', '',
                    'original-update', 'not_started'
                )
            '''
        )
        connection.commit()
        connection.close()
        (self.upload_directory / 'old-front.jpg').write_bytes(b'old-front')
        (self.upload_directory / 'old-back.jpg').write_bytes(b'old-back')

    def tearDown(self):
        admin_core.app.config['UPLOAD_FOLDER'] = self.original_upload_folder
        self.temporary_directory.cleanup()

    def _connect(self):
        connection = sqlite3.connect(self.database_path)
        connection.row_factory = sqlite3.Row
        return connection

    @staticmethod
    def _card_data():
        return {
            'product_type': 'graded_card',
            'vintage_classification': '',
            'merch_description': '',
            'card_category': 'trading_card',
            'card_name': 'Updated',
            'movie_name': '',
            'release_year': '',
            'production_company': '',
            'film_type': '',
            'sports_type': '',
            'group_name': '',
            'year': '2026',
            'brand': 'Pokemon',
            'variety': '',
            'language': 'EN',
            'set_name': 'Set',
            'card_number': '1',
        }

    @staticmethod
    def _grading_data():
        return {
            'centering': 9.0,
            'edges': 9.0,
            'corners': 9.0,
            'surface': 9.0,
            'final_grade': 9.0,
            'final_grade_text': '9',
        }

    def _stage_image(self, _uploaded_file, prefix):
        filename = f'new-{prefix}.jpg'
        (self.upload_directory / filename).write_bytes(b'new')
        return filename

    def _post_edit(self, connection_factory, data=None):
        form_data = {
            'front_image': (io.BytesIO(b'image'), 'front.jpg'),
            'entry_notes': 'updated',
        }
        if data:
            form_data.update(data)

        with (
            patch.object(
                routes_entries,
                'get_temp_db_connection',
                side_effect=connection_factory,
            ),
            patch.object(
                routes_entries,
                'collect_category_form_data',
                return_value=self._card_data(),
            ),
            patch.object(
                routes_entries,
                'validate_product_policy',
                return_value=(True, ''),
            ),
            patch.object(
                routes_entries,
                'collect_grading_data',
                return_value=(self._grading_data(), ''),
            ),
            patch.object(
                routes_entries,
                'calculate_population_for_card_data',
                return_value=(1, 'EN', None, None),
            ),
            patch.object(
                routes_entries,
                'validate_category_required_fields',
                return_value=(True, ''),
            ),
            patch.object(
                routes_entries,
                'save_uploaded_file',
                side_effect=self._stage_image,
            ),
            admin_core.app.test_request_context(
                '/admin/entries/1/edit',
                method='POST',
                data=form_data,
                content_type='multipart/form-data',
            ),
        ):
            response = routes_entries.edit_entry.__wrapped__(1)
            flashes = list(session.get('_flashes', []))
        return response, flashes

    def _post_new(self, connection_factory):
        with (
            patch.object(
                routes_entries,
                'get_temp_db_connection',
                side_effect=connection_factory,
            ),
            patch.object(
                routes_entries,
                'collect_category_form_data',
                return_value=self._card_data(),
            ),
            patch.object(
                routes_entries,
                'validate_product_policy',
                return_value=(True, ''),
            ),
            patch.object(
                routes_entries,
                'collect_grading_data',
                return_value=(self._grading_data(), ''),
            ),
            patch.object(
                routes_entries,
                'calculate_population_for_card_data',
                return_value=(1, 'EN', None, None),
            ),
            patch.object(
                routes_entries,
                'validate_category_required_fields',
                return_value=(True, ''),
            ),
            patch.object(
                routes_entries,
                'certificate_id_exists',
                return_value=False,
            ),
            patch.object(
                routes_entries,
                'save_uploaded_file',
                side_effect=self._stage_image,
            ),
            admin_core.app.test_request_context(
                '/admin/entry/new',
                method='POST',
                data={
                    'cert_id': '7000000003',
                    'front_image': (io.BytesIO(b'image'), 'front.jpg'),
                },
                content_type='multipart/form-data',
            ),
        ):
            response = routes_entries.new_entry.__wrapped__()
            flashes = list(session.get('_flashes', []))
        return response, flashes

    def test_commit_acknowledgement_loss_keeps_committed_new_image(self):
        call_count = 0

        def connection_factory():
            nonlocal call_count
            call_count += 1
            connection = self._connect()
            if call_count == 1:
                return CommitThenRaiseConnection(connection, commit_first=True)
            return connection

        response, flashes = self._post_edit(connection_factory)

        self.assertEqual(response.status_code, 302)
        connection = self._connect()
        row = connection.execute(
            'SELECT front_image FROM temp_cards WHERE id = 1'
        ).fetchone()
        connection.close()
        self.assertEqual(row['front_image'], 'new-front.jpg')
        self.assertTrue((self.upload_directory / 'new-front.jpg').is_file())
        self.assertFalse((self.upload_directory / 'old-front.jpg').exists())
        self.assertTrue(any(category == 'success' for category, _ in flashes))

    def test_new_entry_commit_acknowledgement_loss_keeps_image_and_row(self):
        call_count = 0

        def connection_factory():
            nonlocal call_count
            call_count += 1
            connection = self._connect()
            if call_count == 1:
                return CommitThenRaiseConnection(connection, commit_first=True)
            return connection

        response, flashes = self._post_new(connection_factory)

        self.assertEqual(response.status_code, 302)
        connection = self._connect()
        row = connection.execute(
            '''
                SELECT cert_id, front_image
                FROM temp_cards
                WHERE cert_id = '7000000003'
            '''
        ).fetchone()
        connection.close()
        self.assertIsNotNone(row)
        self.assertEqual(row['front_image'], 'new-front.jpg')
        self.assertTrue((self.upload_directory / 'new-front.jpg').is_file())
        self.assertTrue(any(category == 'success' for category, _ in flashes))

    def test_commit_failure_removes_only_unreferenced_new_image(self):
        call_count = 0

        def connection_factory():
            nonlocal call_count
            call_count += 1
            connection = self._connect()
            if call_count == 1:
                return CommitThenRaiseConnection(connection, commit_first=False)
            return connection

        response, flashes = self._post_edit(connection_factory)

        self.assertEqual(response.status_code, 302)
        connection = self._connect()
        row = connection.execute(
            'SELECT front_image FROM temp_cards WHERE id = 1'
        ).fetchone()
        connection.close()
        self.assertEqual(row['front_image'], 'old-front.jpg')
        self.assertFalse((self.upload_directory / 'new-front.jpg').exists())
        self.assertTrue((self.upload_directory / 'old-front.jpg').is_file())
        self.assertTrue(any(category == 'error' for category, _ in flashes))

    def test_partial_file_save_removes_private_staging_file(self):
        files_before = sorted(path.name for path in self.upload_directory.iterdir())

        with self.assertRaisesRegex(OSError, 'upload stream interrupted'):
            admin_core.save_uploaded_file(InterruptedFileSave(), 'front')

        self.assertEqual(
            sorted(path.name for path in self.upload_directory.iterdir()),
            files_before,
        )

    def test_close_error_after_commit_does_not_reverse_edit_success(self):
        call_count = 0

        def connection_factory():
            nonlocal call_count
            call_count += 1
            connection = self._connect()
            if call_count == 1:
                return CloseAfterCommitConnection(connection)
            return connection

        response, flashes = self._post_edit(connection_factory)

        self.assertEqual(response.status_code, 302)
        connection = self._connect()
        row = connection.execute(
            'SELECT front_image FROM temp_cards WHERE id = 1'
        ).fetchone()
        connection.close()
        self.assertEqual(row['front_image'], 'new-front.jpg')
        self.assertTrue((self.upload_directory / 'new-front.jpg').is_file())
        self.assertFalse((self.upload_directory / 'old-front.jpg').exists())
        self.assertTrue(any(category == 'success' for category, _ in flashes))

    def test_shared_old_queue_image_is_not_deleted_after_success(self):
        connection = self._connect()
        connection.execute(
            '''
                INSERT INTO temp_cards (
                    id, cert_id, card_name, product_type, card_category,
                    front_image, back_image, upload_status
                ) VALUES (
                    2, '7000000002', 'Shared', 'graded_card', 'trading_card',
                    'old-front.jpg', '', 'not_started'
                )
            '''
        )
        connection.commit()
        connection.close()

        response, _ = self._post_edit(self._connect)

        self.assertEqual(response.status_code, 302)
        self.assertTrue((self.upload_directory / 'old-front.jpg').is_file())
        self.assertTrue((self.upload_directory / 'new-front.jpg').is_file())

    def test_clearing_published_reference_never_deletes_public_file_inline(self):
        connection = self._connect()
        connection.execute(
            '''
                UPDATE temp_cards
                SET published_front_image = '/static/shared.jpg'
                WHERE id = 1
            '''
        )
        connection.commit()
        connection.close()

        with patch.object(routes_entries, 'delete_public_image') as delete_public:
            response, _ = self._post_edit(
                self._connect,
                data={
                    'front_image': (io.BytesIO(b''), ''),
                    'delete_front_image': '1',
                },
            )

        self.assertEqual(response.status_code, 302)
        delete_public.assert_not_called()
        connection = self._connect()
        row = connection.execute(
            '''
                SELECT front_image, published_front_image
                FROM temp_cards
                WHERE id = 1
            '''
        ).fetchone()
        connection.close()
        self.assertEqual(row['front_image'], '')
        self.assertEqual(row['published_front_image'], '')


if __name__ == '__main__':
    unittest.main()
