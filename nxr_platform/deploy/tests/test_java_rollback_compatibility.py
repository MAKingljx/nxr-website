"""Exercise rollback ordering without a server, database, or production files."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "hot-deploy-java.sh"


class LegacyRollbackBoundaryTest(unittest.TestCase):
    def rollback(self, schema_parts="5", capable=False, mysql_failure=False):
        with tempfile.TemporaryDirectory(prefix="nxr-rollback-contract-") as directory:
            root = Path(directory)
            target = root / "old"
            target.mkdir()
            if capable:
                (target / "RELEASE_CAPABILITIES").write_text("partner-orders-card-returns-v1\n")
            mysql = root / "mysql-stub"
            mysql.write_text("#!/bin/sh\n" + ("exit 2\n" if mysql_failure else f"printf '%s\\n' '{schema_parts}'\n"))
            mysql.chmod(0o700)
            environment = os.environ.copy()
            environment.update({
                "NXR_HOT_DEPLOY_MYSQL_BIN": str(mysql),
                "NXR_HOT_DEPLOY_DATABASE": "nxr_acceptance_rollback_contract",
                "NXR_JAVA_SLOTS_ROOT": str(root / "slots"),
                "NXR_JAVA_CURRENT_LINK": str(root / "current"),
                "NXR_HOT_DEPLOY_STATE_ROOT": str(root / "state"),
                "NXR_HOT_DEPLOY_SYSTEMCTL_BIN": "systemctl_stub",
                "NXR_ROLLBACK_TEST_TARGET": str(target),
            })
            functions = SCRIPT.read_text().rsplit('\nmain "$@"', 1)[0]
            actions = r'''
restore_active_config() { printf 'RESTORE_TRAFFIC\n'; }
atomic_symlink() { printf 'LINK\n'; }
atomic_write() { printf 'STATE\n'; }
systemctl_stub() { printf 'SERVICE\n'; }
rollback_backend_switch blue "$NXR_ROLLBACK_TEST_TARGET" candidate.service old.service
'''
            return subprocess.run(["bash", "-c", functions + actions], env=environment,
                                  capture_output=True, text=True, timeout=10)

    def test_pre_partner_schema_retains_legacy_rollback(self):
        result = self.rollback(schema_parts="0")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("RESTORE_TRAFFIC", result.stdout)

    def test_new_schema_blocks_legacy_rollback_even_before_first_order(self):
        result = self.rollback()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertIn("target is a legacy binary", result.stderr)

    def test_partial_schema_does_not_restore_old_traffic(self):
        result = self.rollback(schema_parts="2")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertIn("migration is incomplete", result.stderr)

    def test_unavailable_database_does_not_restore_old_traffic(self):
        result = self.rollback(mysql_failure=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertIn("Cannot verify", result.stderr)

    def test_invalid_schema_result_does_not_restore_old_traffic(self):
        result = self.rollback(schema_parts="unexpected")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")

    def test_partner_capable_release_can_recover_without_database_probe(self):
        result = self.rollback(capable=True, mysql_failure=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("RESTORE_TRAFFIC", result.stdout)


if __name__ == "__main__":
    unittest.main()
