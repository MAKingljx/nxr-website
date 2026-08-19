import tempfile
import unittest
from pathlib import Path

from nxr_admin import admin_core
from nxr_admin import routes_entries  # noqa: F401 - registers entry workflow routes.
from nxr_site import app as public_site


class AdminToPublicProductFlowTests(unittest.TestCase):
    """Exercise the admin approval/export path against an isolated public DB."""

    def setUp(self):
        self._temporary_directory = tempfile.TemporaryDirectory()
        root = Path(self._temporary_directory.name)
        data_dir = root / "Data"

        self._original_admin_paths = {
            "DATA_DIR": admin_core.DATA_DIR,
            "DB_PATH": admin_core.DB_PATH,
            "TEMP_DB_PATH": admin_core.TEMP_DB_PATH,
            "SITE_STATIC_DIR": admin_core.SITE_STATIC_DIR,
            "UPLOAD_FOLDER": admin_core.UPLOAD_FOLDER,
        }
        self._original_site_paths = {
            "DATA_DIR": public_site.DATA_DIR,
            "DB_PATH": public_site.DB_PATH,
        }
        self._original_upload_config = admin_core.app.config["UPLOAD_FOLDER"]

        admin_core.DATA_DIR = data_dir
        admin_core.DB_PATH = data_dir / "cards.db"
        admin_core.TEMP_DB_PATH = data_dir / "temp_cards.db"
        admin_core.SITE_STATIC_DIR = root / "site_static"
        admin_core.UPLOAD_FOLDER = root / "uploads"
        public_site.DATA_DIR = data_dir
        public_site.DB_PATH = admin_core.DB_PATH

        for directory in (
            admin_core.DATA_DIR,
            admin_core.SITE_STATIC_DIR,
            admin_core.UPLOAD_FOLDER,
        ):
            directory.mkdir(parents=True, exist_ok=True)
        admin_core.app.config["UPLOAD_FOLDER"] = admin_core.UPLOAD_FOLDER

        admin_core.initialize_databases()
        public_site.initialize_site_database()

    def tearDown(self):
        for name, value in self._original_admin_paths.items():
            setattr(admin_core, name, value)
        for name, value in self._original_site_paths.items():
            setattr(public_site, name, value)
        admin_core.app.config["UPLOAD_FOLDER"] = self._original_upload_config
        self._temporary_directory.cleanup()

    def admin_client(self):
        client = admin_core.app.test_client()
        with client.session_transaction() as session_state:
            session_state["admin_logged_in"] = True
            session_state["username"] = "integration-tester"
            session_state["role"] = "superadmin"
        return client

    def create_entry(self, client, cert_id, product_type, **extra_fields):
        form_data = {
            "cert_id": cert_id,
            "product_type": product_type,
            "card_category": "trading_card",
            "card_name": "Integration Test Item",
            "year": "1999",
            "brand": "Pokemon",
            "variety": "Test Variant",
            "language": "EN",
            "set_name": "Integration Set",
            "card_number": "001",
            "entry_notes": "Isolated integration test entry",
        }
        form_data.update(extra_fields)

        response = client.post("/admin/entry/new", data=form_data)
        self.assertEqual(response.status_code, 302)

        with admin_core.get_temp_db_connection() as conn:
            entry = conn.execute(
                "SELECT * FROM temp_cards WHERE cert_id = ?", (cert_id,)
            ).fetchone()
        self.assertIsNotNone(entry)
        return entry

    def approve_and_export(self, client, entry_id):
        response = client.post(f"/admin/entries/{entry_id}/approve")
        self.assertEqual(response.status_code, 302)

        response = client.get("/admin/export/approved")
        self.assertEqual(response.status_code, 302)

    def test_merch_and_vintage_entries_reach_the_classic_public_layout(self):
        client = self.admin_client()
        merch = self.create_entry(
            client,
            "8234567891",
            "merch_product",
            card_name="NXR Collector Pin",
            merch_description="Limited enamel pin for the 2026 collector series.",
        )
        vintage = self.create_entry(
            client,
            "8234567892",
            "vintage_product",
            card_name="NXR Archive Card",
            vintage_classification="Patina",
        )

        self.assertEqual(merch["status"], "pending")
        self.assertEqual(vintage["status"], "pending")
        self.approve_and_export(client, merch["id"])
        self.approve_and_export(client, vintage["id"])

        with admin_core.get_main_db_connection() as conn:
            rows = {
                row["cert_id"]: row
                for row in conn.execute(
                    "SELECT * FROM cards WHERE cert_id IN (?, ?)",
                    ("8234567891", "8234567892"),
                ).fetchall()
            }

        self.assertEqual(rows["8234567891"]["product_type"], "merch_product")
        self.assertEqual(
            rows["8234567891"]["merch_description"],
            "Limited enamel pin for the 2026 collector series.",
        )
        self.assertIsNone(rows["8234567891"]["final_grade"])
        self.assertEqual(rows["8234567892"]["product_type"], "vintage_product")
        self.assertEqual(rows["8234567892"]["vintage_classification"], "Patina")
        self.assertIsNone(rows["8234567892"]["final_grade"])

        public_client = public_site.app.test_client()
        merch_response = public_client.get("/card/8234567891")
        vintage_response = public_client.get("/card/8234567892")

        self.assertEqual(merch_response.status_code, 200)
        self.assertEqual(vintage_response.status_code, 200)

        merch_html = merch_response.get_data(as_text=True)
        self.assertIn("Merch Product", merch_html)
        self.assertIn("Description", merch_html)
        self.assertIn("Limited enamel pin for the 2026 collector series.", merch_html)
        self.assertNotIn("Final Grade", merch_html)
        self.assertNotIn("Sub-Grades", merch_html)
        self.assertNotIn("Collector Ledger", merch_html)
        self.assertNotIn("Transfer History", merch_html)

        vintage_html = vintage_response.get_data(as_text=True)
        self.assertIn("Vintage Card", vintage_html)
        self.assertIn("Condition Grade", vintage_html)
        self.assertIn("Patina", vintage_html)
        self.assertNotIn("Final Grade", vintage_html)
        self.assertNotIn("Sub-Grades", vintage_html)
        self.assertNotIn('<div class="vintage-badge', vintage_html)
        self.assertNotIn("Collector Ledger", vintage_html)
        self.assertNotIn("Transfer History", vintage_html)


if __name__ == "__main__":
    unittest.main()
