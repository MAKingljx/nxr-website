"""Safety boundaries for encrypted backup and disposable native SQL restore."""
import dataclasses
import gzip
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import tarfile
import tempfile
import unittest
from unittest import mock

MODULE_PATH = Path(__file__).resolve().parents[1] / "mysql_r2_backup.py"
spec = importlib.util.spec_from_file_location("mysql_r2_backup", MODULE_PATH)
b = importlib.util.module_from_spec(spec)
import sys
sys.modules[spec.name] = b
spec.loader.exec_module(b)

DUMP = b"""-- MySQL dump
-- CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE='mysql-bin.000046', SOURCE_LOG_POS=184467;
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET NAMES utf8mb4 */;
DROP TABLE IF EXISTS `cards`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
CREATE TABLE `cards` (
  `id` bigint NOT NULL,
  `title` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
LOCK TABLES `cards` WRITE;
/*!40000 ALTER TABLE `cards` DISABLE KEYS */;
INSERT INTO `cards` VALUES (1,'card \\n USE production;');
INSERT INTO `cards` VALUES (2,'second');
/*!40000 ALTER TABLE `cards` ENABLE KEYS */;
UNLOCK TABLES;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
"""


class FakeMySQL:
    def __init__(self, dump=DUMP):
        self.body = dump
        self.restore_fail = False
        self.dump_fail = False
        self.calls = []

    def preflight(self):
        self.calls.append("preflight")
        return {"base_tables": 1, "mysql_version": "8.0.46", "character_set": "utf8mb4", "collation": "utf8mb4_0900_ai_ci"}

    def dump(self, db, path):
        self.calls.append("dump")
        if self.dump_fail:
            raise b.BackupError("dump failed")
        b.write_private(path, self.body)

    def verify_restore(self, dump, original, work, schema):
        self.calls.append("restore")
        if self.restore_fail:
            raise b.BackupError("restore failed")
        return {"verified": True, "table_count": 1, "row_count": 2}


class FakeEncryption:
    def __init__(self):
        self.fail = False
        self.archive = None

    def preflight(self):
        pass

    def encrypt(self, source, target):
        if self.fail:
            raise b.BackupError("encryption failed")
        b.audit_package(source)
        self.archive = source.read_bytes()
        b.write_private(target, b"age-encryption.org/v1\n" + b"encrypted-for-test-" * 32)


class FakeStorage:
    def __init__(self):
        self.objects = {}
        self.deleted = []
        self.uploaded = []
        self.corrupt_download = False
        self.marker_fail = False
        self.upload_fail = False
        self.delete_fail = False

    def preflight(self):
        pass

    def keys(self, prefix):
        return [key for key in self.objects if key.startswith(prefix)]

    def read(self, key):
        return self.objects[key]

    def put(self, key, body):
        if self.marker_fail:
            raise b.BackupError("marker failed")
        self.objects[key] = body

    def upload(self, key, path):
        self.uploaded.append(key)
        self.objects[key] = path.read_bytes()
        if self.upload_fail:
            raise b.BackupError("upload failed")

    def download(self, key, path):
        b.write_private(path, self.objects[key] + (b"corrupt" if self.corrupt_download else b""))

    def delete(self, key):
        self.deleted.append(key)
        if self.delete_fail:
            raise b.BackupError("delete failed")
        self.objects.pop(key, None)


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.cfg = b.Config(source_database="nxr_source", mysql_socket="/tmp/mysql.sock", r2_endpoint="https://" + "a" * 32 + ".r2.cloudflarestorage.com", r2_access_key_id="secret-id", r2_secret_access_key="secret-key", r2_bucket="nxr-private-backups", image_bucket="nxr-public-cards", age_recipient="age1" + "a" * 58, private_bucket_confirmed=True, work_root=self.root / "work", state_root=self.root / "state", allow_isolated_restore=True)
        self.mysql = FakeMySQL()
        self.storage = FakeStorage()
        self.encryption = FakeEncryption()
        self.service = b.BackupService(self.cfg, self.mysql, self.storage, self.encryption)

    def tearDown(self):
        self.tmp.cleanup()

    def dump(self, body=DUMP):
        path = self.root / "dump.sql"
        path.write_bytes(body)
        return path

    def old(self, backup_id):
        encrypted = b"old-encrypted-" * 50
        key = b.owned_key(self.cfg, backup_id, "backup.tar.age")
        import hashlib
        marker = {"tool": b.TOOL, "version": b.VERSION, "status": "VERIFIED", "source_database": self.cfg.source_database, "backup_id": backup_id, "created_at": b.dt.datetime.strptime(backup_id[:16], "%Y%m%dT%H%M%SZ").replace(tzinfo=b.dt.timezone.utc).isoformat(), "object": key, "ciphertext_sha256": hashlib.sha256(encrypted).hexdigest(), "bytes": len(encrypted), "binlog": {"file": "mysql-bin.000001", "position": 4}, "isolated_restore": {"verified": True, "table_count": 1, "row_count": 2}, "ciphertext_roundtrip_verified": True}
        self.storage.objects[key] = encrypted
        self.storage.objects[b.owned_key(self.cfg, backup_id, "verified.json")] = b.json_bytes(marker)
        return marker


class ConfigTests(Base):
    def test_reject_image_bucket(self):
        with self.assertRaises(b.BackupError):
            dataclasses.replace(self.cfg, r2_bucket=self.cfg.image_bucket).validate()

    def test_reject_public_endpoint(self):
        for endpoint in ("https://pub-test.r2.dev", "https://images.nxrgrading.com", "http://" + "a" * 32 + ".r2.cloudflarestorage.com", self.cfg.r2_endpoint + "/path", "https://user:password@" + "a" * 32 + ".r2.cloudflarestorage.com"):
            with self.subTest(endpoint=endpoint), self.assertRaises(b.BackupError):
                dataclasses.replace(self.cfg, r2_endpoint=endpoint).validate()

    def test_private_confirmation_required(self):
        with self.assertRaises(b.BackupError):
            dataclasses.replace(self.cfg, private_bucket_confirmed=False).validate()

    def test_recipient_and_retention_guard(self):
        for kwargs in ({"age_recipient": "AGE-SECRET-KEY-123"}, {"age_recipient": ""}, {"retention": 1}, {"retention": 3}):
            with self.subTest(kwargs=kwargs), self.assertRaises(b.BackupError):
                dataclasses.replace(self.cfg, **kwargs).validate()

    def test_schema_identifier_guard(self):
        for name in ("production; DROP DATABASE prod", "mysql", "nxr_backup_verify_abc", "foo.bar", "", "x" * 65):
            with self.subTest(name=name), self.assertRaises(b.BackupError):
                dataclasses.replace(self.cfg, source_database=name).validate()

    def test_namespace_guard(self):
        for prefix in ("cards/", "mysql/other/", "mysql/nxr_source/../", "/mysql/nxr_source/"):
            with self.subTest(prefix=prefix), self.assertRaises(b.BackupError):
                dataclasses.replace(self.cfg, prefix=prefix).validate()

    def test_credentials_not_in_repr(self):
        self.assertNotIn("secret-id", repr(self.cfg))
        self.assertNotIn("secret-key", repr(self.cfg))

    def test_secure_env_file_no_evaluation(self):
        path = self.root / "backup.env"
        b.write_private(path, b"NXR_BACKUP_R2_SECRET_ACCESS_KEY='$(touch bad)'\n")
        self.assertEqual(b.load_env(path)["R2_SECRET_ACCESS_KEY"], "$(touch bad)")
        os.chmod(path, 0o640)
        with self.assertRaises(b.BackupError):
            b.load_env(path)

    def test_unknown_env_and_symlink_rejected(self):
        path = self.root / "bad.env"
        b.write_private(path, b"HOME=/tmp\n")
        with self.assertRaises(b.BackupError):
            b.load_env(path)
        link = self.root / "link"
        link.symlink_to(path)
        with self.assertRaises(b.BackupError):
            b.load_env(link)

    def test_private_directories(self):
        self.assertEqual(stat.S_IMODE(b.private_dir(self.root / "private").stat().st_mode), 0o700)
        public = self.root / "public"
        public.mkdir(mode=0o755)
        with self.assertRaises(b.BackupError):
            b.private_dir(public)
        link = self.root / "linked"
        link.symlink_to(public)
        with self.assertRaises(b.BackupError):
            b.private_dir(link)


class DumpTests(Base):
    def test_hash_native_rows_and_coordinates(self):
        snapshot = b.inspect_dump(self.dump())
        self.assertEqual(snapshot["binlog"], {"file": "mysql-bin.000046", "position": 184467})
        self.assertEqual(snapshot["tables"], ["cards"])
        self.assertEqual(snapshot["data_signatures"]["cards"]["rows"], 2)
        self.assertTrue(b.SHA.fullmatch(snapshot["ddl_signatures"]["cards"]))

    def test_master_coordinates_supported(self):
        result = b.inspect_dump(self.dump(DUMP.replace(b"CHANGE REPLICATION SOURCE TO SOURCE_LOG_FILE", b"CHANGE MASTER TO MASTER_LOG_FILE").replace(b"SOURCE_LOG_POS", b"MASTER_LOG_POS")))
        self.assertEqual(result["binlog"]["position"], 184467)

    def test_missing_coordinates_fail(self):
        with self.assertRaises(b.BackupError):
            b.inspect_dump(self.dump(DUMP.replace(DUMP.splitlines()[1] + b"\n", b"")))

    def test_missing_coordinates_redump_allowed(self):
        result = b.inspect_dump(self.dump(DUMP.replace(DUMP.splitlines()[1] + b"\n", b"")), require_coordinates=False)
        self.assertIsNone(result["binlog"])

    def test_duplicate_or_invalid_coords_fail(self):
        for body in (DUMP + DUMP.splitlines()[1] + b"\n", DUMP.replace(b"184467", b"0")):
            with self.subTest(body=body[:10]), self.assertRaises(b.BackupError):
                b.inspect_dump(self.dump(body))

    def test_scope_escape_statements_fail(self):
        for malicious in (b"USE nxr_source;\n", b"CREATE DATABASE production;\n", b"SET GLOBAL sql_mode='';\n", b"SET @x=1; USE nxr_source;\n", b"SET @x=1; DROP DATABASE nxr_source;\n", b"/*!80000 SET GLOBAL sql_mode='' */;\n", b"GRANT ALL ON *.* TO 'x';\n", b"\\! rm -rf /\n", b"SOURCE /tmp/evil;\n", b"LOAD DATA INFILE '/tmp/data' INTO TABLE `cards`;\n", b"DROP TABLE IF EXISTS `nxr_source`.`cards`;\n", b"DROP TABLE IF EXISTS `cards`; USE nxr_source;\n", b"INSERT INTO `nxr_source`.`cards` VALUES (3,'x');\n"):
            with self.subTest(malicious=malicious), self.assertRaises(b.BackupError):
                b.inspect_dump(self.dump(DUMP + malicious))

    def test_unclosed_or_unknown_table_fail(self):
        for body in (DUMP.replace(b") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n", b""), DUMP.replace(b"INSERT INTO `cards`", b"INSERT INTO `unknown`"), DUMP + b"`orphan` int;\n"):
            with self.subTest(body=body[:10]), self.assertRaises(b.BackupError):
                b.inspect_dump(self.dump(body))

    def test_row_and_schema_change_affect_signature(self):
        original = b.inspect_dump(self.dump())
        changed = b.inspect_dump(self.dump(DUMP.replace(b"'second'", b"'different'")))
        self.assertNotEqual(original["data_signatures"], changed["data_signatures"])
        changed = b.inspect_dump(self.dump(DUMP.replace(b"varchar(255)", b"varchar(300)")))
        self.assertNotEqual(original["ddl_signatures"], changed["ddl_signatures"])

    def test_archive_exact_members_only_and_private(self):
        sql = self.dump()
        package = b.create_package(sql, {"safe": True}, self.root)
        with tarfile.open(package) as archive:
            self.assertEqual(set(archive.getnames()), b.MEMBERS)
            self.assertTrue(all(member.mode == 0o600 for member in archive.getmembers()))
            self.assertEqual(gzip.decompress(archive.extractfile("database.sql.gz").read()), DUMP)
        self.assertEqual(stat.S_IMODE(package.stat().st_mode), 0o600)

    def test_archive_rejects_secret_extra_and_link(self):
        for kind in ("extra", "link"):
            package = self.root / (kind + ".tar")
            with tarfile.open(package, "w") as archive:
                for name in b.MEMBERS:
                    info = tarfile.TarInfo(name)
                    info.mode = 0o600
                    archive.addfile(info, io.BytesIO(b""))
                info = tarfile.TarInfo("secret.env")
                if kind == "link":
                    info.type = tarfile.SYMTYPE
                    info.linkname = "/etc/passwd"
                archive.addfile(info)
            with self.assertRaises(b.BackupError):
                b.audit_package(package)


class FlowTests(Base):
    def test_verified_cycle_leaves_no_plaintext(self):
        result = self.service.run()
        self.assertEqual(result["status"], "VERIFIED")
        self.assertTrue(result["ciphertext_roundtrip_verified"])
        self.assertEqual(result["retained_verified"], 1)
        self.assertEqual(self.mysql.calls, ["preflight", "dump", "restore"])
        self.assertEqual(list(self.cfg.work_root.iterdir()), [])
        self.assertEqual(stat.S_IMODE((self.cfg.state_root / "last-success.json").stat().st_mode), 0o600)
        self.assertEqual(len(self.storage.objects), 2)

    def test_two_verified_retention_exact_namespace(self):
        one = self.old("20260101T000000Z-" + "1" * 16)
        two = self.old("20260102T000000Z-" + "2" * 16)
        untouched = {"card/front.webp": b"card", self.cfg.expected_prefix + "legacy-backup.sql": b"legacy", self.cfg.expected_prefix + "20260103T000000Z-" + "3" * 16 + "/unowned.bin": b"unknown", "mysql/other/db.sql": b"other"}
        self.storage.objects.update(untouched)
        result = self.service.run()
        self.assertEqual(result["pruned_backups"], [one["backup_id"]])
        self.assertEqual(result["retained_verified"], 2)
        self.assertIn(two["object"], self.storage.objects)
        for key, body in untouched.items():
            self.assertEqual(self.storage.objects[key], body)
        self.assertEqual(set(self.storage.deleted), {one["object"], b.owned_key(self.cfg, one["backup_id"], "verified.json")})

    def test_failures_preserve_old_sets(self):
        old = self.old("20260101T000000Z-" + "1" * 16)
        saved = dict(self.storage.objects)
        for kind in ("dump", "restore", "encryption", "corruption", "marker", "upload"):
            self.mysql.dump_fail = kind == "dump"
            self.mysql.restore_fail = kind == "restore"
            self.encryption.fail = kind == "encryption"
            self.storage.corrupt_download = kind == "corruption"
            self.storage.marker_fail = kind == "marker"
            self.storage.upload_fail = kind == "upload"
            with self.subTest(kind=kind), self.assertRaises(b.BackupError):
                self.service.run()
            self.assertEqual(self.storage.objects, saved)
            self.assertIn(old["object"], self.storage.objects)
            self.assertFalse((self.cfg.state_root / "last-success.json").exists())
            self.assertEqual(list(self.cfg.work_root.iterdir()), [])

    def test_missing_coordinates_no_upload(self):
        self.mysql.body = DUMP.replace(DUMP.splitlines()[1] + b"\n", b"")
        with self.assertRaises(b.BackupError):
            self.service.run()
        self.assertEqual(self.storage.uploaded, [])
        self.assertNotIn("restore", self.mysql.calls)

    def test_authorization_required_no_preflight(self):
        service = b.BackupService(dataclasses.replace(self.cfg, allow_isolated_restore=False), self.mysql, self.storage, self.encryption)
        with self.assertRaises(b.BackupError):
            service.run()
        self.assertEqual(self.mysql.calls, [])

    def test_lock_prevents_concurrent_run(self):
        with self.service.lock(), self.assertRaises(b.BackupError):
            self.service.run()
        self.assertEqual(self.storage.uploaded, [])

    def test_status_does_not_adopt_bad_or_cross_namespace_marker(self):
        record = self.old("20260101T000000Z-" + "1" * 16)
        record["source_database"] = "other"
        self.storage.objects[b.owned_key(self.cfg, record["backup_id"], "verified.json")] = b.json_bytes(record)
        self.assertEqual(self.service.status()["verified_backups"], [])
        self.assertEqual(self.service.status()["invalid_markers"], 1)
        self.assertEqual(self.storage.deleted, [])

    def test_prune_requires_verified_current_id(self):
        self.old("20260101T000000Z-" + "1" * 16)
        with self.assertRaises(b.BackupError):
            self.service.prune("20260102T000000Z-" + "2" * 16)
        self.assertEqual(self.storage.deleted, [])

    def test_resume_after_verified_publication(self):
        one = self.old("20260101T000000Z-" + "1" * 16)
        self.old("20260102T000000Z-" + "2" * 16)
        three = self.old("20260103T000000Z-" + "3" * 16)
        result = self.service.resume(three["backup_id"])
        self.assertEqual(result["retained_verified"], 2)
        self.assertEqual(result["pruned_backups"], [one["backup_id"]])
        self.assertEqual(self.mysql.calls, [])

    def test_resume_does_not_promote_incomplete_upload(self):
        backup_id = "20260103T000000Z-" + "3" * 16
        self.storage.objects[b.owned_key(self.cfg, backup_id, "backup.tar.age")] = b"partial"
        with self.assertRaises(KeyError):
            self.service.resume(backup_id)
        self.assertEqual(self.storage.deleted, [])

    def test_resume_corruption_does_not_prune(self):
        record = self.old("20260103T000000Z-" + "3" * 16)
        self.storage.corrupt_download = True
        with self.assertRaises(b.BackupError):
            self.service.resume(record["backup_id"])
        self.assertEqual(self.storage.deleted, [])

    def test_failed_prune_keeps_new_verified_backup(self):
        self.old("20260101T000000Z-" + "1" * 16)
        self.old("20260102T000000Z-" + "2" * 16)
        self.storage.delete_fail = True
        with self.assertRaises(b.BackupError):
            self.service.run()
        self.assertEqual(len(self.service.status()["verified_backups"]), 3)
        self.assertFalse((self.cfg.state_root / "last-success.json").exists())

    def test_clock_rollback_never_deletes_current_or_prior_valid_sets(self):
        one = self.old("20260101T000000Z-" + "1" * 16)
        self.old("20260102T000000Z-" + "2" * 16)
        self.old("20260103T000000Z-" + "3" * 16)
        with self.assertRaises(b.BackupError):
            self.service.prune(one["backup_id"])
        self.assertEqual(self.storage.deleted, [])

    def test_download_verified_and_collision_guard(self):
        record = self.old("20260103T000000Z-" + "3" * 16)
        destination = self.root / "download.age"
        result = self.service.fetch(record["backup_id"], destination)
        self.assertTrue(result["download_verified"])
        self.assertEqual(stat.S_IMODE(destination.stat().st_mode), 0o600)
        with self.assertRaises(b.BackupError):
            self.service.fetch(record["backup_id"], destination)

    def test_download_corruption_deletes_only_new_destination(self):
        record = self.old("20260103T000000Z-" + "3" * 16)
        self.storage.corrupt_download = True
        destination = self.root / "corrupt.age"
        with self.assertRaises(b.BackupError):
            self.service.fetch(record["backup_id"], destination)
        self.assertFalse(destination.exists())
        self.assertEqual(self.storage.deleted, [])

    def test_key_traversal_and_object_boundary(self):
        for backup_id, filename in (("../cards", "backup.tar.age"), ("20260103T000000Z-" + "3" * 16, "secret.env"), ("20260103T000000Z-" + "3" * 16, "../cards.webp")):
            with self.subTest(backup_id=backup_id), self.assertRaises(b.BackupError):
                b.owned_key(self.cfg, backup_id, filename)


class NativeTests(Base):
    def native(self, fail_at=None, changed=False):
        cfg = self.cfg
        calls = []
        class Native(b.NativeMySQL):
            def query(self, sql):
                calls.append(sql)
                if fail_at and sql.startswith(fail_at):
                    raise b.BackupError("injected native failure")
                if sql.startswith("CHECK TABLE"):
                    return [["scratch.cards", "check", "status", "OK"]]
                return []
            def execute(self, args, **kwargs):
                calls.append(args)
                if fail_at == "IMPORT":
                    raise b.BackupError("injected importer failure")
                return b""
            def dump(self, database, path, *, coordinates=True):
                self_database = cfg.source_database
                if database == self_database:
                    raise AssertionError("redump must target scratch")
                body = DUMP.replace(DUMP.splitlines()[1] + b"\n", b"")
                if changed:
                    body = body.replace(b"'second'", b"'changed'")
                b.write_private(path, body)
        return Native(cfg), calls

    def test_importer_granted_only_scratch_schema(self):
        native, calls = self.native()
        dump = self.dump()
        snapshot = b.inspect_dump(dump)
        result = native.verify_restore(dump, snapshot, self.root, {"character_set": "utf8mb4", "collation": "utf8mb4_0900_ai_ci"})
        self.assertTrue(result["verified"])
        sql = [call for call in calls if isinstance(call, str)]
        grants = [call for call in sql if call.startswith("GRANT")]
        self.assertEqual(len(grants), 1)
        self.assertIn("ON `nxr_backup_verify_", grants[0])
        self.assertNotIn("*.*", grants[0])
        self.assertNotIn("SUPER", grants[0])
        self.assertNotIn(self.cfg.source_database, "\n".join(sql))
        imports = [call for call in calls if isinstance(call, list)]
        self.assertEqual(len(imports), 1)
        self.assertTrue(any(arg.startswith("--user=nxr_bv_") for arg in imports[0]))
        self.assertTrue(imports[0][-1].startswith("nxr_backup_verify_"))
        self.assertTrue(any(arg.startswith("--defaults-file=") for arg in imports[0]))
        self.assertFalse(any(arg.startswith("-p") for arg in imports[0]))
        self.assertFalse((self.root / "importer.cnf").exists())

    def test_failure_cleanup_never_drops_unowned_names(self):
        for failure in ("CREATE DATABASE", "CREATE USER", "GRANT", "IMPORT"):
            native, calls = self.native(fail_at=failure)
            dump = self.dump()
            with self.subTest(failure=failure), self.assertRaises(b.BackupError):
                native.verify_restore(dump, b.inspect_dump(dump), self.root, {"character_set": "utf8mb4", "collation": "utf8mb4_0900_ai_ci"})
            drops = [call for call in calls if isinstance(call, str) and call.startswith("DROP")]
            if failure == "CREATE DATABASE":
                self.assertEqual(drops, [])
            elif failure == "CREATE USER":
                self.assertEqual(len(drops), 1)
                self.assertTrue(drops[0].startswith("DROP DATABASE `nxr_backup_verify_"))
            else:
                self.assertEqual(len(drops), 2)
            self.assertNotIn(self.cfg.source_database, "\n".join(drops))
            self.assertFalse((self.root / "importer.cnf").exists())

    def test_mismatched_restore_data_fails_and_cleans_up(self):
        native, calls = self.native(changed=True)
        dump = self.dump()
        with self.assertRaises(b.BackupError):
            native.verify_restore(dump, b.inspect_dump(dump), self.root, {"character_set": "utf8mb4", "collation": "utf8mb4_0900_ai_ci"})
        self.assertEqual(len([call for call in calls if isinstance(call, str) and call.startswith("DROP")]), 2)

    def test_changed_dump_before_import_fails_without_sql(self):
        native, calls = self.native()
        dump = self.dump()
        snapshot = b.inspect_dump(dump)
        dump.write_bytes(DUMP.replace(b"'second'", b"'changed'"))
        with self.assertRaises(b.BackupError):
            native.verify_restore(dump, snapshot, self.root, {"character_set": "utf8mb4", "collation": "utf8mb4_0900_ai_ci"})
        self.assertEqual(calls, [])

    def test_injected_schema_escape_never_reaches_importer(self):
        native, calls = self.native()
        dump = self.dump(DUMP + b"USE nxr_source;\n")
        with self.assertRaises(b.BackupError):
            native.verify_restore(dump, {}, self.root, {"character_set": "utf8mb4", "collation": "utf8mb4_0900_ai_ci"})
        self.assertEqual(calls, [])

    def test_schema_setting_injection_never_reaches_admin_client(self):
        native, calls = self.native()
        dump = self.dump()
        with self.assertRaises(b.BackupError):
            native.verify_restore(dump, b.inspect_dump(dump), self.root, {"character_set": "utf8mb4; DROP DATABASE nxr_source", "collation": "utf8mb4_0900_ai_ci"})
        self.assertEqual(calls, [])

    def test_native_dump_uses_safe_options_without_database_creation(self):
        native = b.NativeMySQL(self.cfg)
        calls = []
        def execute(args, **kwargs):
            calls.append(args)
            kwargs["output"].write(DUMP)
            return b""
        with mock.patch.object(native, "execute", side_effect=execute):
            native.dump(self.cfg.source_database, self.root / "snapshot.sql")
            native.dump("nxr_backup_verify_" + "1" * 24, self.root / "restored.sql", coordinates=False)
        self.assertIn("--source-data=2", calls[0])
        self.assertNotIn("--source-data=2", calls[1])
        for args in calls:
            for option in ("--single-transaction", "--set-gtid-purged=OFF", "--no-tablespaces", "--hex-blob", "--order-by-primary", "--skip-extended-insert"):
                self.assertIn(option, args)
            self.assertNotIn("--databases", args)
            self.assertNotIn("--all-databases", args)
        self.assertEqual(stat.S_IMODE((self.root / "snapshot.sql").stat().st_mode), 0o600)

    def test_preflight_rejects_nontransactional_or_stored_objects(self):
        native = b.NativeMySQL(self.cfg)
        with mock.patch.object(native, "query", return_value=[["cards", "MyISAM"]]), self.assertRaises(b.BackupError):
            native.preflight()
        with mock.patch.object(native, "query", side_effect=[[["cards", "InnoDB"]], [["0", "1", "0", "0"]]]), self.assertRaises(b.BackupError):
            native.preflight()

    def test_native_client_credentials_never_in_arguments(self):
        credentials = self.root / "client.cnf"
        b.write_private(credentials, b"[client]\npassword=private-password\n")
        native = b.NativeMySQL(dataclasses.replace(self.cfg, mysql_defaults_file=credentials))
        args = native.client("mysql")
        self.assertNotIn("private-password", repr(args))
        self.assertIn("--protocol=SOCKET", args)
        self.assertIn("--no-login-paths", args)
        self.assertEqual(args[1], "--defaults-file=" + str(credentials))


class CryptoTests(Base):
    def test_age_missing_fails_closed(self):
        encryptor = b.AgeEncryptor(dataclasses.replace(self.cfg, age_bin="/not-installed-age"))
        with self.assertRaises(b.BackupError):
            encryptor.preflight()

    def test_age_invalid_header_is_rejected(self):
        source = self.root / "source.tar"
        source.write_bytes(b"source")
        target = self.root / "encrypted.age"
        def subprocess_run(args, **kwargs):
            kwargs["stdout"].write(b"invalid-header" * 20)
            return mock.Mock(returncode=0)
        with mock.patch.object(b.subprocess, "run", side_effect=subprocess_run), self.assertRaises(b.BackupError):
            b.AgeEncryptor(self.cfg).encrypt(source, target)
        self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o600)

    def test_age_failure_never_returns_verified(self):
        source = self.root / "source.tar"
        source.write_bytes(b"source")
        with mock.patch.object(b.subprocess, "run", return_value=mock.Mock(returncode=1)), self.assertRaises(b.BackupError):
            b.AgeEncryptor(self.cfg).encrypt(source, self.root / "encrypted.age")


if __name__ == "__main__":
    unittest.main()
