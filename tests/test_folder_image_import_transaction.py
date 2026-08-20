import io
import inspect
import sqlite3
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from PIL import Image

from nxr_admin import admin_core
from nxr_admin import routes_entries
from nxr_admin import routes_uploads


class CommitFailingConnection:
    """Proxy that reproduces a lock raised only at SQLite commit time."""

    def __init__(self, connection):
        self.connection = connection

    def execute(self, *args, **kwargs):
        return self.connection.execute(*args, **kwargs)

    def cursor(self, *args, **kwargs):
        return self.connection.cursor(*args, **kwargs)

    def commit(self):
        raise sqlite3.OperationalError("database is locked")

    def rollback(self):
        return self.connection.rollback()

    def close(self):
        return self.connection.close()


class CommitThenRaiseConnection(CommitFailingConnection):
    def commit(self):
        self.connection.commit()
        raise ConnectionError("commit response was lost")


class RaiseOnNthCommitConnection(CommitFailingConnection):
    def __init__(self, connection, commit_number, commit_first):
        super().__init__(connection)
        self.commit_number = commit_number
        self.commit_first = commit_first
        self.commit_count = 0

    def commit(self):
        self.commit_count += 1
        if self.commit_count == self.commit_number:
            if self.commit_first:
                self.connection.commit()
            raise ConnectionError("commit response was lost")
        return self.connection.commit()


class TakeoverAfterRollbackConnection(RaiseOnNthCommitConnection):
    def __init__(self, connection, takeover):
        super().__init__(connection, commit_number=2, commit_first=False)
        self.takeover = takeover
        self.takeover_applied = False

    def rollback(self):
        result = self.connection.rollback()
        if self.commit_count >= 2 and not self.takeover_applied:
            self.takeover_applied = True
            self.takeover()
        return result


class RecordingMainConnection:
    def __init__(self):
        self.commit_count = 0
        self.rollback_count = 0
        self.close_count = 0

    def commit(self):
        self.commit_count += 1

    def rollback(self):
        self.rollback_count += 1

    def close(self):
        self.close_count += 1


class RollbackFailingConnection(CommitFailingConnection):
    def rollback(self):
        raise sqlite3.OperationalError("rollback failed")


class CloseFailingConnection(CommitFailingConnection):
    def commit(self):
        return self.connection.commit()

    def close(self):
        self.connection.close()
        raise OSError("close failed")


class InterruptedStream:
    def __init__(self):
        self.read_count = 0

    def seek(self, offset):
        self.read_count = 0

    def read(self, size):
        self.read_count += 1
        if self.read_count == 1:
            return b"partial-image"
        raise OSError("stream interrupted")


class FakeCursor:
    def __init__(self, rows=None, rowcount=0):
        self.rows = rows or []
        self.rowcount = rowcount

    def fetchall(self):
        return self.rows

    def fetchone(self):
        return self.rows[0] if self.rows else None


class RecordingMySQLConnection:
    backend = "mysql"

    def __init__(self, row):
        self.row = row
        self.statements = []
        self.begin_count = 0

    def begin(self):
        self.begin_count += 1

    def execute(self, sql, params=None):
        self.statements.append(sql)
        if "SELECT id, cert_id" in sql:
            return FakeCursor([self.row])
        if "UPDATE temp_cards" in sql:
            return FakeCursor(rowcount=1)
        raise AssertionError(f"Unexpected SQL: {sql}")


class UploadConflictConnection:
    def __init__(self, row):
        self.row = row
        self.statements = []
        self.rollback_count = 0

    def execute(self, sql, params=None):
        self.statements.append(sql)
        if "SELECT *" in sql:
            return FakeCursor([self.row])
        if "SET upload_status = 'uploading'" in sql:
            return FakeCursor(rowcount=0)
        raise AssertionError(f"Unexpected SQL: {sql}")

    def rollback(self):
        self.rollback_count += 1

    def close(self):
        return None


class FolderImageImportTransactionTests(unittest.TestCase):
    cert_id = "7000000001"

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.upload_dir = self.root / "uploads"
        self.upload_dir.mkdir()
        self.db_path = self.root / "temp_cards.db"
        self.original_upload_folder = admin_core.app.config["UPLOAD_FOLDER"]
        admin_core.app.config.update(TESTING=True, UPLOAD_FOLDER=self.upload_dir)

        connection = self._connect()
        connection.execute(
            """
            CREATE TABLE temp_cards (
                id INTEGER PRIMARY KEY,
                cert_id TEXT NOT NULL UNIQUE,
                status TEXT NOT NULL,
                upload_status TEXT,
                upload_started TEXT,
                upload_completed TEXT,
                upload_error TEXT,
                server_response TEXT,
                front_image TEXT,
                back_image TEXT,
                published_front_image TEXT,
                published_back_image TEXT,
                updated_at TEXT
            )
            """
        )
        connection.execute(
            """
            INSERT INTO temp_cards (id, cert_id, status, front_image, back_image)
            VALUES (1, ?, 'approved', 'old-front.jpg', '')
            """,
            (self.cert_id,),
        )
        connection.commit()
        connection.close()
        (self.upload_dir / "old-front.jpg").write_bytes(b"old")

    def tearDown(self):
        admin_core.app.config["UPLOAD_FOLDER"] = self.original_upload_folder
        self.temp_dir.cleanup()

    def _connect(self):
        connection = sqlite3.connect(self.db_path)
        connection.row_factory = sqlite3.Row
        return connection

    def _post_import(self):
        return self._post_files([
            (
                self._jpeg_stream(),
                f"folder/{self.cert_id}_A.jpg",
            )
        ])

    def _post_files(self, files):
        client = admin_core.app.test_client()
        with client.session_transaction() as session:
            session["admin_logged_in"] = True
            session["username"] = "test-operator"
        return client.post(
            "/admin/upload/import-images",
            data={"image_files": files},
            headers={"X-Requested-With": "XMLHttpRequest"},
            content_type="multipart/form-data",
        )

    @staticmethod
    def _jpeg_stream():
        image_bytes = io.BytesIO()
        Image.new("RGB", (2, 2), color="white").save(image_bytes, format="JPEG")
        image_bytes.seek(0)
        return image_bytes

    def test_success_commits_new_reference_before_deleting_old_file(self):
        with patch.object(routes_uploads, "get_temp_db_connection", side_effect=self._connect):
            response = self._post_import()

        self.assertEqual(response.status_code, 200)
        connection = self._connect()
        new_name = connection.execute(
            "SELECT front_image FROM temp_cards WHERE cert_id = ?",
            (self.cert_id,),
        ).fetchone()[0]
        connection.close()

        self.assertNotEqual(new_name, "old-front.jpg")
        self.assertTrue((self.upload_dir / new_name).is_file())
        self.assertFalse((self.upload_dir / "old-front.jpg").exists())

    def test_commit_lock_rolls_back_reference_and_removes_only_new_file(self):
        def failing_connection():
            return CommitFailingConnection(self._connect())

        with patch.object(routes_uploads, "get_temp_db_connection", side_effect=failing_connection):
            response = self._post_import()

        self.assertEqual(response.status_code, 500)
        self.assertNotIn("database is locked", response.get_json()["message"])
        self.assertIn("No existing image reference was removed", response.get_json()["message"])
        self.assertTrue(response.get_json()['retryable'])
        self.assertEqual(response.get_json()['retry_after_ms'], 1000)
        connection = self._connect()
        current_name = connection.execute(
            "SELECT front_image FROM temp_cards WHERE cert_id = ?",
            (self.cert_id,),
        ).fetchone()[0]
        connection.close()

        self.assertEqual(current_name, "old-front.jpg")
        self.assertTrue((self.upload_dir / "old-front.jpg").is_file())
        self.assertEqual(
            sorted(path.name for path in self.upload_dir.iterdir()),
            ["old-front.jpg"],
        )

    def test_commit_result_ambiguity_keeps_file_when_database_references_it(self):
        call_count = 0

        def connection_factory():
            nonlocal call_count
            call_count += 1
            connection = self._connect()
            if call_count == 1:
                return CommitThenRaiseConnection(connection)
            return connection

        with patch.object(
            routes_uploads,
            "get_temp_db_connection",
            side_effect=connection_factory,
        ):
            response = self._post_import()

        self.assertEqual(response.status_code, 500)
        self.assertTrue(response.get_json()['retryable'])
        connection = self._connect()
        current_name = connection.execute(
            "SELECT front_image FROM temp_cards WHERE cert_id = ?",
            (self.cert_id,),
        ).fetchone()[0]
        connection.close()
        self.assertNotEqual(current_name, "old-front.jpg")
        self.assertTrue((self.upload_dir / current_name).is_file())
        self.assertTrue((self.upload_dir / "old-front.jpg").is_file())

    def test_rollback_failure_does_not_skip_new_file_cleanup(self):
        call_count = 0

        def connection_factory():
            nonlocal call_count
            call_count += 1
            connection = self._connect()
            if call_count == 1:
                return RollbackFailingConnection(connection)
            return connection

        with patch.object(
            routes_uploads,
            "get_temp_db_connection",
            side_effect=connection_factory,
        ):
            response = self._post_import()

        self.assertEqual(response.status_code, 500)
        self.assertEqual(
            sorted(path.name for path in self.upload_dir.iterdir()),
            ["old-front.jpg"],
        )

    def test_close_failure_after_commit_does_not_turn_success_into_failure(self):
        def connection_factory():
            return CloseFailingConnection(self._connect())

        with patch.object(
            routes_uploads,
            "get_temp_db_connection",
            side_effect=connection_factory,
        ):
            response = self._post_import()

        self.assertEqual(response.status_code, 200)
        connection = self._connect()
        current_name = connection.execute(
            "SELECT front_image FROM temp_cards WHERE cert_id = ?",
            (self.cert_id,),
        ).fetchone()[0]
        connection.close()
        self.assertTrue((self.upload_dir / current_name).is_file())
        self.assertFalse((self.upload_dir / "old-front.jpg").exists())

    def test_partial_stream_failure_leaves_no_part_or_final_file(self):
        with self.assertRaisesRegex(OSError, "stream interrupted"):
            routes_uploads.save_imported_image_upload(
                self.cert_id,
                "front",
                ".jpg",
                SimpleNamespace(stream=InterruptedStream()),
            )

        self.assertEqual(
            sorted(path.name for path in self.upload_dir.iterdir()),
            ["old-front.jpg"],
        )

    def test_old_file_is_retained_when_another_entry_still_references_it(self):
        connection = self._connect()
        connection.execute(
            """
            INSERT INTO temp_cards (
                id, cert_id, status, upload_status, front_image, back_image
            ) VALUES (2, '7000000002', 'approved', 'not_started', 'old-front.jpg', '')
            """
        )
        connection.commit()
        connection.close()

        with patch.object(routes_uploads, "get_temp_db_connection", side_effect=self._connect):
            response = self._post_import()

        self.assertEqual(response.status_code, 200)
        self.assertTrue((self.upload_dir / "old-front.jpg").is_file())

    def test_import_skips_entry_while_upload_is_in_progress(self):
        connection = self._connect()
        connection.execute(
            "UPDATE temp_cards SET upload_status = 'uploading' WHERE cert_id = ?",
            (self.cert_id,),
        )
        connection.commit()
        connection.close()

        with patch.object(routes_uploads, "get_temp_db_connection", side_effect=self._connect):
            response = self._post_import()

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.get_json()["summary"]["saved_files"], 0)
        self.assertEqual(
            sorted(path.name for path in self.upload_dir.iterdir()),
            ["old-front.jpg"],
        )

    def test_two_concurrent_imports_serialize_without_orphaning_images(self):
        with patch.object(routes_uploads, "get_temp_db_connection", side_effect=self._connect):
            with ThreadPoolExecutor(max_workers=2) as pool:
                responses = list(pool.map(lambda _: self._post_import(), range(2)))

        self.assertEqual([response.status_code for response in responses], [200, 200])
        connection = self._connect()
        current_name = connection.execute(
            "SELECT front_image FROM temp_cards WHERE cert_id = ?",
            (self.cert_id,),
        ).fetchone()[0]
        connection.close()
        remaining_files = sorted(path.name for path in self.upload_dir.iterdir())
        self.assertEqual(remaining_files, [current_name])

    def test_server_rejects_more_files_than_one_batch_allows(self):
        files = [
            (self._jpeg_stream(), f"folder/{7000000100 + index}_A.jpg")
            for index in range(routes_uploads.MAX_IMAGE_IMPORT_FILES + 1)
        ]
        with patch.object(routes_uploads, "get_temp_db_connection") as getter:
            response = self._post_files(files)

        self.assertEqual(response.status_code, 413)
        self.assertFalse(response.get_json()['retryable'])
        getter.assert_not_called()

    def test_invalid_image_content_is_rejected_without_leaving_files(self):
        with patch.object(routes_uploads, "get_temp_db_connection", side_effect=self._connect):
            response = self._post_files([
                (
                    io.BytesIO(b"not-an-image"),
                    f"folder/{self.cert_id}_A.jpg",
                )
            ])

        self.assertEqual(response.status_code, 422)
        self.assertFalse(response.get_json()['retryable'])
        self.assertEqual(
            sorted(path.name for path in self.upload_dir.iterdir()),
            ["old-front.jpg"],
        )

    def test_image_content_must_match_filename_extension(self):
        with patch.object(routes_uploads, "get_temp_db_connection", side_effect=self._connect):
            response = self._post_files([
                (
                    self._jpeg_stream(),
                    f"folder/{self.cert_id}_A.png",
                )
            ])

        self.assertEqual(response.status_code, 422)
        self.assertFalse(response.get_json()['retryable'])
        self.assertEqual(
            sorted(path.name for path in self.upload_dir.iterdir()),
            ["old-front.jpg"],
        )

    def test_batch_byte_limit_cleans_every_staged_file(self):
        sample = self._jpeg_stream().getvalue()
        with (
            patch.object(
                routes_uploads,
                'MAX_IMAGE_IMPORT_BATCH_BYTES',
                len(sample) + 1,
            ),
            patch.object(
                routes_uploads,
                'get_temp_db_connection',
                side_effect=self._connect,
            ),
        ):
            response = self._post_files([
                (io.BytesIO(sample), f'folder/{self.cert_id}_A.jpg'),
                (io.BytesIO(sample), f'folder/{self.cert_id}_B.jpg'),
            ])

        self.assertEqual(response.status_code, 422)
        self.assertFalse(response.get_json()['retryable'])
        self.assertEqual(
            sorted(path.name for path in self.upload_dir.iterdir()),
            ['old-front.jpg'],
        )

    def test_pixel_limit_rejects_oversized_dimensions_before_database_write(self):
        with (
            patch.object(routes_uploads, 'MAX_IMAGE_IMPORT_PIXELS', 1),
            patch.object(
                routes_uploads,
                'get_temp_db_connection',
                side_effect=self._connect,
            ),
        ):
            response = self._post_import()

        self.assertEqual(response.status_code, 422)
        self.assertFalse(response.get_json()['retryable'])
        self.assertEqual(
            sorted(path.name for path in self.upload_dir.iterdir()),
            ['old-front.jpg'],
        )

    def test_flask_rejects_oversized_request_before_database_connection(self):
        original_limit = admin_core.app.config['MAX_CONTENT_LENGTH']
        admin_core.app.config['MAX_CONTENT_LENGTH'] = 128
        try:
            with patch.object(routes_uploads, 'get_temp_db_connection') as getter:
                response = self._post_files([
                    (
                        io.BytesIO(b'x' * 1024),
                        f'folder/{self.cert_id}_A.jpg',
                    )
                ])
        finally:
            admin_core.app.config['MAX_CONTENT_LENGTH'] = original_limit

        self.assertEqual(response.status_code, 413)
        getter.assert_not_called()

    def test_mysql_import_rechecks_rows_for_update_and_uses_image_cas(self):
        row = {
            "id": 1,
            "cert_id": self.cert_id,
            "front_image": "old-front.jpg",
            "back_image": "",
        }
        connection = RecordingMySQLConnection(row)
        candidates = {
            (self.cert_id, "front"): {
                "cert_id": self.cert_id,
                "side": "front",
                "extension": ".jpg",
                "uploaded_file": SimpleNamespace(stream=io.BytesIO()),
            }
        }
        with patch.object(
            routes_uploads,
            "save_imported_image_upload",
            return_value=("new-front.jpg", 10),
        ):
            summary = routes_uploads.import_image_candidates_to_temp_cards(
                candidates,
                [],
                [],
                connection,
            )

        select_statements = [
            statement for statement in connection.statements if "SELECT id, cert_id" in statement
        ]
        update_statement = next(
            statement for statement in connection.statements if "UPDATE temp_cards" in statement
        )
        self.assertEqual(connection.begin_count, 1)
        self.assertEqual(len(select_statements), 2)
        self.assertIn("FOR UPDATE", select_statements[1])
        self.assertIn("COALESCE(front_image, '') = ?", update_statement)
        self.assertEqual(summary["saved_files"], 1)

    def test_single_upload_rejects_stale_image_snapshot_before_export(self):
        (self.upload_dir / "front.jpg").write_bytes(b"front")
        (self.upload_dir / "back.jpg").write_bytes(b"back")
        row = {
            "id": 1,
            "cert_id": self.cert_id,
            "status": "approved",
            "upload_status": "not_started",
            "front_image": "front.jpg",
            "back_image": "back.jpg",
            "published_front_image": "",
            "published_back_image": "",
        }
        connection = UploadConflictConnection(row)
        with (
            patch.object(routes_uploads, "get_temp_db_connection", return_value=connection),
            patch.object(routes_uploads, "get_main_db_connection") as main_connection,
            admin_core.app.test_request_context(method="POST"),
        ):
            response, status = routes_uploads.api_upload_entry.__wrapped__(1)

        self.assertEqual(status, 409)
        self.assertFalse(response.get_json()["success"])
        main_connection.assert_not_called()
        self.assertEqual(connection.rollback_count, 1)
        update_statement = next(
            statement for statement in connection.statements if "SET upload_status = 'uploading'" in statement
        )
        self.assertIn("COALESCE(front_image, '') = ?", update_statement)
        self.assertIn("COALESCE(back_image, '') = ?", update_statement)
        self.assertTrue((self.upload_dir / "front.jpg").is_file())
        self.assertTrue((self.upload_dir / "back.jpg").is_file())

    def test_single_upload_recovers_when_completion_commit_was_applied(self):
        connection = self._connect()
        connection.execute(
            '''
                UPDATE temp_cards
                SET front_image = 'front.jpg',
                    back_image = 'back.jpg',
                    upload_status = 'not_started'
                WHERE id = 1
            '''
        )
        connection.commit()
        connection.close()
        (self.upload_dir / 'front.jpg').write_bytes(b'front')
        (self.upload_dir / 'back.jpg').write_bytes(b'back')

        call_count = 0

        def connection_factory():
            nonlocal call_count
            call_count += 1
            connection = self._connect()
            if call_count == 1:
                return RaiseOnNthCommitConnection(
                    connection,
                    commit_number=2,
                    commit_first=True,
                )
            return connection

        main_connection = RecordingMainConnection()
        with (
            patch.object(
                routes_uploads,
                'get_temp_db_connection',
                side_effect=connection_factory,
            ),
            patch.object(
                routes_uploads,
                'get_main_db_connection',
                return_value=main_connection,
            ),
            patch.object(
                routes_uploads,
                'upsert_main_card',
                return_value={
                    'action': 'updated',
                    'front_image': '/static/front.jpg',
                    'back_image': '/static/back.jpg',
                },
            ),
            admin_core.app.test_request_context(method='POST'),
        ):
            response = routes_uploads.api_upload_entry.__wrapped__(1)

        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.get_json()['success'])
        connection = self._connect()
        row = connection.execute(
            '''
                SELECT upload_status, front_image, back_image,
                       published_front_image, published_back_image
                FROM temp_cards
                WHERE id = 1
            '''
        ).fetchone()
        connection.close()
        self.assertEqual(row['upload_status'], 'uploaded')
        self.assertEqual(row['front_image'], '')
        self.assertEqual(row['back_image'], '')
        self.assertEqual(row['published_front_image'], '/static/front.jpg')
        self.assertEqual(row['published_back_image'], '/static/back.jpg')
        self.assertFalse((self.upload_dir / 'front.jpg').exists())
        self.assertFalse((self.upload_dir / 'back.jpg').exists())

    def test_single_upload_marks_only_owned_snapshot_failed_after_commit_error(self):
        connection = self._connect()
        connection.execute(
            '''
                UPDATE temp_cards
                SET front_image = 'front.jpg',
                    back_image = 'back.jpg',
                    upload_status = 'not_started'
                WHERE id = 1
            '''
        )
        connection.commit()
        connection.close()
        (self.upload_dir / 'front.jpg').write_bytes(b'front')
        (self.upload_dir / 'back.jpg').write_bytes(b'back')

        call_count = 0

        def connection_factory():
            nonlocal call_count
            call_count += 1
            connection = self._connect()
            if call_count == 1:
                return RaiseOnNthCommitConnection(
                    connection,
                    commit_number=2,
                    commit_first=False,
                )
            return connection

        main_connection = RecordingMainConnection()
        with (
            patch.object(
                routes_uploads,
                'get_temp_db_connection',
                side_effect=connection_factory,
            ),
            patch.object(
                routes_uploads,
                'get_main_db_connection',
                return_value=main_connection,
            ),
            patch.object(
                routes_uploads,
                'upsert_main_card',
                return_value={
                    'action': 'updated',
                    'front_image': '/static/front.jpg',
                    'back_image': '/static/back.jpg',
                },
            ),
            admin_core.app.test_request_context(method='POST'),
        ):
            response, status = routes_uploads.api_upload_entry.__wrapped__(1)

        self.assertEqual(status, 500)
        self.assertFalse(response.get_json()['success'])
        self.assertNotIn('commit response was lost', response.get_json()['error'])
        connection = self._connect()
        row = connection.execute(
            '''
                SELECT upload_status, front_image, back_image, upload_error
                FROM temp_cards
                WHERE id = 1
            '''
        ).fetchone()
        connection.close()
        self.assertEqual(row['upload_status'], 'failed')
        self.assertEqual(row['front_image'], 'front.jpg')
        self.assertEqual(row['back_image'], 'back.jpg')
        self.assertEqual(
            row['upload_error'],
            routes_uploads.SAFE_UPLOAD_FAILURE_DETAIL,
        )
        self.assertNotIn('commit response was lost', row['upload_error'])
        self.assertTrue((self.upload_dir / 'front.jpg').is_file())
        self.assertTrue((self.upload_dir / 'back.jpg').is_file())

    def test_single_upload_failure_cas_does_not_overwrite_new_owner(self):
        connection = self._connect()
        connection.execute(
            '''
                UPDATE temp_cards
                SET front_image = 'front.jpg',
                    back_image = 'back.jpg',
                    upload_status = 'not_started'
                WHERE id = 1
            '''
        )
        connection.commit()
        connection.close()
        (self.upload_dir / 'front.jpg').write_bytes(b'front')
        (self.upload_dir / 'back.jpg').write_bytes(b'back')

        def apply_takeover():
            takeover_connection = self._connect()
            takeover_connection.execute(
                '''
                    UPDATE temp_cards
                    SET upload_status = 'uploading',
                        upload_started = 'new-owner',
                        front_image = 'new-front.jpg',
                        back_image = 'new-back.jpg',
                        upload_error = NULL
                    WHERE id = 1
                '''
            )
            takeover_connection.commit()
            takeover_connection.close()

        call_count = 0

        def connection_factory():
            nonlocal call_count
            call_count += 1
            connection = self._connect()
            if call_count == 1:
                return TakeoverAfterRollbackConnection(connection, apply_takeover)
            return connection

        main_connection = RecordingMainConnection()
        with (
            patch.object(
                routes_uploads,
                'get_temp_db_connection',
                side_effect=connection_factory,
            ),
            patch.object(
                routes_uploads,
                'get_main_db_connection',
                return_value=main_connection,
            ),
            patch.object(
                routes_uploads,
                'upsert_main_card',
                return_value={
                    'action': 'updated',
                    'front_image': '/static/front.jpg',
                    'back_image': '/static/back.jpg',
                },
            ),
            admin_core.app.test_request_context(method='POST'),
        ):
            response, status = routes_uploads.api_upload_entry.__wrapped__(1)

        self.assertEqual(status, 500)
        self.assertFalse(response.get_json()['success'])
        connection = self._connect()
        row = connection.execute(
            '''
                SELECT upload_status, upload_started,
                       front_image, back_image, upload_error
                FROM temp_cards
                WHERE id = 1
            '''
        ).fetchone()
        connection.close()
        self.assertEqual(row['upload_status'], 'uploading')
        self.assertEqual(row['upload_started'], 'new-owner')
        self.assertEqual(row['front_image'], 'new-front.jpg')
        self.assertEqual(row['back_image'], 'new-back.jpg')
        self.assertIsNone(row['upload_error'])

    def test_edit_route_uses_image_snapshot_cas_and_uploading_guard(self):
        source = inspect.getsource(routes_entries.edit_entry.__wrapped__)
        self.assertIn("changes_queue_images", source)
        self.assertIn("upload_status, 'not_started'", source)
        self.assertIn("COALESCE(front_image, '') = ?", source)
        self.assertIn("COALESCE(back_image, '') = ?", source)
        self.assertIn("update_cursor.rowcount == 0", source)


if __name__ == "__main__":
    unittest.main()
