"""Real MySQL sync contracts; opt in against localhost with synthetic databases.

NXR_RUN_MYSQL_SYNC_TESTS=1 python -m unittest tests.test_java_mysql_sync_publication
Only newly created nxr_sync_publication_test_<random> databases are written or
removed. No application, production database, storage provider or network image
request participates. Ordinary unit discovery skips these integration tests.
"""
import contextlib
import io
import hashlib
import os
import re
import sqlite3
import threading
import unittest
import uuid
from pathlib import Path

from scripts import migrate_python_to_java_mysql as domain
from scripts import sync_python_to_java_mysql as sync
from tests.test_java_mysql_domain_migration import SourceFixture, insert_temp_card

ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(os.environ.get('NXR_RUN_MYSQL_SYNC_TESTS') == '1', 'explicit local MySQL opt-in required')
class MySqlPublicationSyncTests(unittest.TestCase):
    def setUp(self):
        import pymysql
        self.options = dict(host='127.0.0.1', port=int(os.environ.get('NXR_TEST_MYSQL_PORT', '3306')),
                            user=os.environ.get('NXR_TEST_MYSQL_USER', 'root'),
                            password=os.environ.get('NXR_TEST_MYSQL_PASSWORD', ''),
                            charset='utf8mb4', autocommit=True,
                            cursorclass=pymysql.cursors.DictCursor)
        self.database = 'nxr_sync_publication_test_' + uuid.uuid4().hex[:16]
        self.admin = pymysql.connect(**self.options)
        self.addCleanup(self.admin.close)
        with self.admin.cursor() as cur:
            # No IF NOT EXISTS: a collision must fail, never reuse another DB.
            cur.execute(f'CREATE DATABASE `{self.database}` CHARACTER SET utf8mb4')
        self.addCleanup(self.drop_owned_database)
        self.db = self.connect()
        self.addCleanup(self.db.close)
        self.fixture = SourceFixture()
        self.addCleanup(self.fixture.close)
        with self.db.cursor() as cur:
            for filename in ('01_nxr_business_schema.sql', '08_nxr_python_sync.sql'):
                sql = (ROOT / 'nxr_platform/nxr-sql/ruoyi' / filename).read_text()
                sql = re.sub(r'^--.*$', '', sql, flags=re.M)
                for statement in sql.split(';'):
                    if statement.strip():
                        cur.execute(statement)
            ruoyi = (ROOT / 'nxr_platform/nxr-backend-ruoyi/sql/ry_20260417.sql').read_text()
            for table in ('sys_dict_type', 'sys_dict_data'):
                cur.execute(re.search(r'create table ' + table + r'\s*\(.*?;', ruoyi, re.S).group())
            cur.execute("INSERT INTO sys_dict_type(dict_type) VALUES ('nxr_sports_type')")
            for table in domain.UNTOUCHED_TABLES:
                cur.execute(f'CREATE TABLE `{table}` (id INT PRIMARY KEY, marker VARCHAR(64)) ENGINE=InnoDB')
                cur.execute(f"INSERT INTO `{table}` VALUES(1,'untouched synthetic sentinel')")
        self.args = sync.parse_args([
            '--cards-db', str(self.fixture.cards_path), '--temp-db', str(self.fixture.temp_path),
            '--target-database', self.database, '--confirm-target-database', self.database,
            '--mysql-host', '127.0.0.1', '--mysql-port', str(self.options['port']),
            '--mysql-user', self.options['user'], '--mysql-password-env', 'NXR_TEST_MYSQL_PASSWORD',
            '--mode', 'full', '--apply',
        ])
        self.source_temp('SYNC0001')
        self.run_sync()
        self.sid = self.scalar("SELECT id FROM grading_submission WHERE cert_id='SYNC0001'")

    def connect(self):
        import pymysql
        return pymysql.connect(database=self.database, **self.options)

    def drop_owned_database(self):
        self.assertRegex(self.database, r'^nxr_sync_publication_test_[a-f0-9]{16}$')
        with self.admin.cursor() as cur:
            cur.execute(f'DROP DATABASE `{self.database}`')
            cur.execute('SELECT COUNT(*) AS n FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=%s', (self.database,))
            self.assertEqual(cur.fetchone()['n'], 0)

    def scalar(self, sql, params=()):
        with self.db.cursor() as cur:
            cur.execute(sql, params)
            return next(iter(cur.fetchone().values()))

    def query(self, sql, params=(), connection=None):
        with (connection or self.db).cursor() as cur:
            cur.execute(sql, params)
            return cur.fetchall()

    def execute(self, sql, params=(), connection=None):
        with (connection or self.db).cursor() as cur:
            cur.execute(sql, params)
            return cur.lastrowid

    def source_temp(self, cert, status='approved'):
        with contextlib.closing(sqlite3.connect(self.fixture.temp_path)) as conn, conn:
            insert_temp_card(conn, cert, status)
            conn.execute("UPDATE temp_cards SET front_image=?, back_image=? WHERE cert_id=?",
                         (cert + '_front.jpg', cert + '_back.jpg', cert))

    def source_update(self, cert='SYNC0001', name='Python updated title'):
        with contextlib.closing(sqlite3.connect(self.fixture.temp_path)) as conn, conn:
            conn.execute("UPDATE temp_cards SET card_name=?,updated_at='2026-10-18T12:34:56' WHERE cert_id=?", (name, cert))

    def source_publish(self, cert='SYNC0001'):
        with contextlib.closing(sqlite3.connect(self.fixture.cards_path)) as conn, conn:
            conn.execute("""INSERT INTO cards(cert_id,card_name,grade,brand,set_name,card_number,
                         centering,edges,corners,surface,language,front_image,back_image,qr_url,
                         created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                         (cert, 'Python published title', '9.5', 'Pokemon', 'Set', '001', 9.5, 9, 9, 9.5,
                          'EN', '/static/python-front.webp', '/static/python-back.webp', '/python/card/' + cert,
                          '2026-07-01T09:00:00', '2026-10-18T13:00:00'))
        with contextlib.closing(sqlite3.connect(self.fixture.temp_path)) as conn, conn:
            conn.execute("UPDATE temp_cards SET upload_status='uploaded',upload_completed='2026-10-18T13:00:00' WHERE cert_id=?", (cert,))

    def run_sync(self, cls=None):
        sources = [self.fixture.cards_path, self.fixture.temp_path]
        before = [hashlib.sha256(p.read_bytes()).hexdigest() for p in sources]
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                (cls or sync.PythonToJavaSync)(self.args).run()
        finally:
            self.assertEqual([hashlib.sha256(p.read_bytes()).hexdigest() for p in sources], before)
            self.assertEqual(self.scalar('SELECT IS_FREE_LOCK(%s)', (sync.LOCK_NAME,)), 1)
        for table in domain.UNTOUCHED_TABLES:
            self.assertEqual(self.query(f'SELECT * FROM `{table}`'), [{'id': 1, 'marker': 'untouched synthetic sentinel'}])

    def media_pair(self, sid=None, cert='SYNC0001', provider='r2', connection=None):
        sid = sid or self.sid
        ids = []
        for side in ('front', 'back'):
            ids.append(self.execute('''INSERT INTO submission_media
                (submission_id,cert_id,media_side_code,media_stage_code,storage_provider_code,
                 storage_bucket,storage_key,public_url) VALUES (%s,%s,%s,'published',%s,'synthetic',%s,%s)''',
                (sid, cert, side, provider, cert + '/' + side, 'https://synthetic.invalid/' + cert + '/' + side), connection))
        return ids

    def java_publish(self, provider='r2', connection=None):
        db = connection or self.db
        front, back = self.media_pair(provider=provider, connection=db)
        self.execute('''INSERT INTO published_certificate(submission_id,cert_id,verification_slug,qr_url,
            published_at,published_front_media_id,published_back_media_id,published_media_snapshot_json)
            VALUES (%s,'SYNC0001','sync0001','https://synthetic.invalid/card/SYNC0001',
                    '2026-10-18 08:00:00',%s,%s,'{"origin":"synthetic-java"}')''', (self.sid, front, back), db)
        self.execute("UPDATE grading_submission SET status_code='published',published_at='2026-10-18 08:00:00' WHERE id=%s", (self.sid,), db)
        self.execute('''UPDATE submission_upload_state SET status_code='uploaded',claim_token=%s,
            claimed_front_media_id=%s,claimed_back_media_id=%s,started_at='2026-10-18 07:59:50',
            completed_at='2026-10-18 08:00:00',error_message=NULL,
            response_payload_json='{"statusCode":"published","origin":"synthetic-java"}',
            triggered_by_user_id=777 WHERE submission_id=%s''', (str(uuid.uuid4()), front, back, self.sid), db)
        return front, back

    def publication_snapshot(self, *, include_staged=False):
        return {table: self.query(f'SELECT * FROM {table} WHERE submission_id=%s'
                                 + (" AND media_stage_code='published'" if table == 'submission_media' and not include_staged else '')
                                 + ' ORDER BY 1', (self.sid,))
                for table in ('published_certificate', 'submission_upload_state', 'submission_media')}

    def target_snapshot(self):
        return {table: self.query(f'SELECT * FROM `{table}` ORDER BY 1')
                for table in domain.TARGET_TABLES + domain.SYNC_TABLES + domain.UNTOUCHED_TABLES}

    def test_write_transaction_is_read_committed_and_retains_connection_lock(self):
        self.source_update()
        observed = {}
        class CheckIsolation(sync.PythonToJavaSync):
            def verify_staging(self):
                with self.db.cursor() as cursor:
                    cursor.execute("SELECT trx_isolation_level FROM information_schema.innodb_trx WHERE trx_mysql_thread_id=CONNECTION_ID()")
                    observed.update(cursor.fetchone())
                    cursor.execute('SELECT IS_USED_LOCK(%s)=CONNECTION_ID() AS owns_lock', (sync.LOCK_NAME,))
                    observed.update(cursor.fetchone())
                super().verify_staging()
        self.run_sync(CheckIsolation)
        self.assertEqual(observed, {'trx_isolation_level': 'READ COMMITTED', 'owns_lock': 1})

    def test_java_success_survives_source_update_and_repeat_full_sync(self):
        self.java_publish()
        before = self.publication_snapshot()
        self.source_update()
        self.run_sync()
        self.assertEqual(self.scalar('SELECT status_code FROM grading_submission WHERE id=%s', (self.sid,)), 'published')
        self.assertEqual(str(self.scalar('SELECT published_at FROM grading_submission WHERE id=%s', (self.sid,))), '2026-10-18 08:00:00')
        self.assertEqual(self.scalar('SELECT card_name FROM grading_submission WHERE id=%s', (self.sid,)), 'Python updated title')
        self.assertEqual(self.publication_snapshot(), before)
        managed = self.query('SELECT * FROM nxr_python_sync_submission')
        self.run_sync()
        self.assertEqual(self.publication_snapshot(), before)
        self.assertEqual(self.query('SELECT * FROM nxr_python_sync_submission'), managed)
        self.assertEqual(self.scalar("SELECT status_code FROM nxr_python_match_projection WHERE cert_id='SYNC0001'"), 'approved')

    def test_incremental_sync_preserves_java_success_and_advances_source_cursor(self):
        self.java_publish()
        before = self.publication_snapshot()
        old_cursor = self.scalar('SELECT cursor_json FROM nxr_python_sync_state')
        self.source_update()
        self.args.mode = 'incremental'
        self.run_sync()
        self.assertEqual(self.publication_snapshot(), before)
        self.assertNotEqual(self.scalar('SELECT cursor_json FROM nxr_python_sync_state'), old_cursor)
        self.assertEqual(self.scalar('SELECT status_code FROM grading_submission WHERE id=%s', (self.sid,)), 'published')
        self.assertEqual(self.scalar('SELECT card_name FROM grading_submission WHERE id=%s', (self.sid,)), 'Python updated title')

    def test_python_publication_cannot_replace_java_certificate_result(self):
        self.java_publish()
        before = self.publication_snapshot()
        self.source_publish()
        self.run_sync()
        self.assertEqual(self.publication_snapshot(), before)
        self.assertEqual(self.scalar('SELECT card_name FROM grading_submission WHERE id=%s', (self.sid,)), 'Python published title')

    def test_normal_python_creation_update_and_publication_still_work(self):
        self.source_update()
        self.source_temp('SYNC0002', 'pending')
        self.run_sync()
        self.assertEqual(self.scalar("SELECT status_code FROM grading_submission WHERE cert_id='SYNC0002'"), 'pending')
        self.assertEqual(self.scalar("SELECT card_name FROM grading_submission WHERE cert_id='SYNC0001'"), 'Python updated title')
        self.source_publish()
        self.run_sync()
        self.assertEqual(self.scalar("SELECT status_code FROM grading_submission WHERE cert_id='SYNC0001'"), 'published')
        self.assertEqual(self.scalar('SELECT status_code FROM submission_upload_state WHERE submission_id=%s', (self.sid,)), 'uploaded')
        self.assertEqual(self.scalar('SELECT COUNT(*) FROM published_certificate'), 1)

    def test_status_flags_without_publication_proof_are_not_protected(self):
        self.execute("UPDATE grading_submission SET status_code='published' WHERE id=%s", (self.sid,))
        self.execute("UPDATE submission_upload_state SET status_code='uploaded' WHERE submission_id=%s", (self.sid,))
        self.source_update()
        self.run_sync()
        self.assertEqual(self.scalar('SELECT status_code FROM grading_submission WHERE id=%s', (self.sid,)), 'approved')
        self.assertEqual(self.scalar('SELECT status_code FROM submission_upload_state WHERE submission_id=%s', (self.sid,)), 'not_started')

    def test_missing_inactive_or_legacy_pair_is_not_java_publication_proof(self):
        # Re-run with each invalid source in its own test-owned transaction state.
        front, back = self.java_publish()
        for broken in ('missing', 'inactive', 'legacy', 'blank_url', 'wrong_side'):
            with self.subTest(broken=broken):
                self.execute("UPDATE grading_submission SET status_code='published' WHERE id=%s", (self.sid,))
                self.execute("UPDATE submission_upload_state SET status_code='uploaded' WHERE submission_id=%s", (self.sid,))
                self.execute("UPDATE submission_media SET is_active=1,storage_provider_code='r2',public_url='https://synthetic.invalid/back',media_side_code='back' WHERE id=%s", (back,))
                self.execute('UPDATE published_certificate SET published_back_media_id=%s WHERE submission_id=%s', (None if broken == 'missing' else back, self.sid))
                if broken == 'inactive': self.execute('UPDATE submission_media SET is_active=0 WHERE id=%s', (back,))
                if broken == 'legacy': self.execute("UPDATE submission_media SET storage_provider_code='legacy-python' WHERE id=%s", (back,))
                if broken == 'blank_url': self.execute("UPDATE submission_media SET public_url=' ' WHERE id=%s", (back,))
                if broken == 'wrong_side': self.execute("UPDATE submission_media SET media_side_code='other' WHERE id=%s", (back,))
                self.source_update(name='Changed ' + broken)
                self.run_sync()
                self.assertEqual(self.scalar('SELECT status_code FROM grading_submission WHERE id=%s', (self.sid,)), 'approved')
                self.assertEqual(self.scalar('SELECT status_code FROM submission_upload_state WHERE submission_id=%s', (self.sid,)), 'not_started')

    def test_already_drifted_rows_are_not_automatically_repaired(self):
        self.java_publish()
        self.execute("UPDATE grading_submission SET status_code='approved',published_at=NULL WHERE id=%s", (self.sid,))
        self.execute("UPDATE submission_upload_state SET status_code='not_started',completed_at=NULL,claim_token=NULL WHERE submission_id=%s", (self.sid,))
        before = self.query('SELECT * FROM published_certificate')
        self.source_update()
        self.run_sync()
        self.assertEqual(self.scalar('SELECT status_code FROM grading_submission WHERE id=%s', (self.sid,)), 'approved')
        self.assertEqual(self.scalar('SELECT status_code FROM submission_upload_state WHERE submission_id=%s', (self.sid,)), 'not_started')
        self.assertEqual(self.query('SELECT * FROM published_certificate'), before)

    def test_verification_detects_corruption_and_rolls_back_cursor_and_all_writes(self):
        self.java_publish()
        before = self.publication_snapshot()
        state = self.query('SELECT * FROM nxr_python_sync_state')
        rows = self.query('SELECT * FROM grading_submission')
        managed = self.query('SELECT * FROM nxr_python_sync_submission')
        self.source_update()
        sid = self.sid
        class CorruptAfterMerge(sync.PythonToJavaSync):
            def verify_staging(self):
                with self.db.cursor() as cursor:
                    cursor.execute("UPDATE submission_upload_state SET response_payload_json='corrupted' WHERE submission_id=%s", (sid,))
                super().verify_staging()
        with self.assertRaisesRegex(domain.MigrationError, 'preserved_java_publication_mismatches=1'):
            self.run_sync(CorruptAfterMerge)
        self.assertEqual(self.publication_snapshot(), before)
        self.assertEqual(self.query('SELECT * FROM nxr_python_sync_state'), state)
        self.assertEqual(self.query('SELECT * FROM grading_submission'), rows)
        self.assertEqual(self.query('SELECT * FROM nxr_python_sync_submission'), managed)

    def test_active_network_claim_and_staged_media_survive_sync(self):
        claim = str(uuid.uuid4())
        self.execute('''UPDATE submission_upload_state SET status_code='uploading',claim_token=%s,
            started_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP,triggered_by_user_id=777,
            response_payload_json='{"network":"in-flight"}' WHERE submission_id=%s''', (claim, self.sid))
        before = self.publication_snapshot(include_staged=True)
        self.source_update()
        with contextlib.closing(sqlite3.connect(self.fixture.temp_path)) as conn, conn:
            conn.execute("UPDATE temp_cards SET front_image='source-new.jpg' WHERE cert_id='SYNC0001'")
        self.run_sync()
        self.assertEqual(self.publication_snapshot(include_staged=True), before)

    def test_missing_upload_state_fails_closed_without_target_or_cursor_changes(self):
        self.java_publish()
        self.execute('DELETE FROM submission_upload_state WHERE submission_id=%s', (self.sid,))
        before = self.target_snapshot()
        self.source_update()
        with self.assertRaisesRegex(domain.MigrationError, 'missing_upload_state=1'):
            self.run_sync()
        self.assertEqual(self.target_snapshot(), before)

    def test_missing_upload_row_fails_before_publication_snapshot(self):
        self.execute('DELETE FROM submission_upload_state WHERE submission_id=%s', (self.sid,))
        self.source_update()
        before = self.target_snapshot()
        snapshot_completed = []
        class StopBeforeConcurrentClaim(sync.PythonToJavaSync):
            def preserve_java_publications(self):
                super().preserve_java_publications()
                snapshot_completed.append(True)
        with self.assertRaisesRegex(domain.MigrationError, 'missing_upload_state=1'):
            self.run_sync(StopBeforeConcurrentClaim)
        self.assertEqual(snapshot_completed, [])
        self.assertEqual(self.target_snapshot(), before)

    def test_java_upload_inserted_and_completed_before_lock_is_preserved(self):
        self.execute('DELETE FROM submission_upload_state WHERE submission_id=%s', (self.sid,))
        self.source_update()
        outer = self
        completed_publication = {}
        class PublishBeforeLock(sync.PythonToJavaSync):
            def preserve_java_publications(self):
                # A separate Java connection restores its state row and commits
                # publication before sync requests that row lock.
                outer.db.begin()
                try:
                    outer.execute('INSERT INTO submission_upload_state(submission_id) VALUES(%s)', (outer.sid,))
                    outer.java_publish()
                    outer.db.commit()
                except Exception:
                    outer.db.rollback()
                    raise
                completed_publication.update(outer.publication_snapshot())
                super().preserve_java_publications()
        self.run_sync(PublishBeforeLock)
        self.assertEqual(self.publication_snapshot(), completed_publication)
        self.assertEqual(self.scalar('SELECT status_code FROM grading_submission WHERE id=%s', (self.sid,)), 'published')
        self.assertEqual(self.scalar('SELECT card_name FROM grading_submission WHERE id=%s', (self.sid,)), 'Python updated title')

    def test_concurrent_java_completion_is_seen_after_upload_lock(self):
        self.source_update()
        publisher = self.connect()
        self.addCleanup(publisher.close)
        publisher.begin()
        self.query('SELECT submission_id FROM submission_upload_state WHERE submission_id=%s FOR UPDATE', (self.sid,), publisher)
        self.java_publish(connection=publisher)
        entered = threading.Event()
        finished = threading.Event()
        errors = []
        class WaitForPublisher(sync.PythonToJavaSync):
            def preserve_java_publications(self):
                entered.set()
                super().preserve_java_publications()
        def run():
            try: WaitForPublisher(self.args).run()
            except Exception as exc: errors.append(exc)
            finally: finished.set()
        thread = threading.Thread(target=run)
        thread.start()
        try:
            self.assertTrue(entered.wait(10))
            self.assertFalse(finished.wait(.2), 'sync must wait for the Java upload lock')
            publisher.commit()
            self.assertTrue(finished.wait(10))
        finally:
            publisher.rollback()
            thread.join(10)
        self.assertEqual(errors, [])
        self.assertEqual(self.scalar('SELECT status_code FROM grading_submission WHERE id=%s', (self.sid,)), 'published')
        self.assertEqual(self.scalar('SELECT status_code FROM submission_upload_state WHERE submission_id=%s', (self.sid,)), 'uploaded')
        self.assertIn('synthetic-java', self.scalar('SELECT response_payload_json FROM submission_upload_state WHERE submission_id=%s', (self.sid,)))

    def test_unchanged_rows_stay_unlocked_while_changed_upload_is_serialized(self):
        self.source_temp('SYNC0002')
        self.run_sync()
        unchanged_id = self.scalar("SELECT id FROM grading_submission WHERE cert_id='SYNC0002'")
        self.source_update()
        frozen, release, finished = threading.Event(), threading.Event(), threading.Event()
        errors = []
        class FreezeAfterLock(sync.PythonToJavaSync):
            def preserve_java_publications(self):
                super().preserve_java_publications()
                frozen.set()
                if not release.wait(10): raise RuntimeError('test release timed out')
        def run():
            try: FreezeAfterLock(self.args).run()
            except Exception as exc: errors.append(exc)
            finally: finished.set()
        thread = threading.Thread(target=run)
        thread.start()
        try:
            self.assertTrue(frozen.wait(10))
            probe = self.connect()
            try:
                probe.begin()
                self.execute('SET SESSION innodb_lock_wait_timeout=1', connection=probe)
                self.query('SELECT submission_id FROM submission_upload_state WHERE submission_id=%s FOR UPDATE', (unchanged_id,), probe)
                import pymysql
                with self.assertRaises(pymysql.err.OperationalError) as caught:
                    self.query('SELECT submission_id FROM submission_upload_state WHERE submission_id=%s FOR UPDATE', (self.sid,), probe)
                self.assertEqual(caught.exception.args[0], 1205)
            finally:
                probe.rollback()
                probe.close()
        finally:
            release.set()
            thread.join(10)
        self.assertTrue(finished.is_set())
        self.assertEqual(errors, [])


if __name__ == '__main__':
    unittest.main()
