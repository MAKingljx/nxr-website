import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from nxr_admin import admin_core
from nxr_admin import routes_entries  # noqa: F401 - registers entry workflow routes.
from nxr_admin import routes_uploads  # noqa: F401 - registers guarded upload routes.
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

    def attach_queue_images(self, entry_id, cert_id):
        front_name = f"front_{cert_id}.webp"
        back_name = f"back_{cert_id}.webp"
        (admin_core.UPLOAD_FOLDER / front_name).write_bytes(b"front-image")
        (admin_core.UPLOAD_FOLDER / back_name).write_bytes(b"back-image")
        with admin_core.get_temp_db_connection() as conn:
            conn.execute(
                "UPDATE temp_cards SET front_image = ?, back_image = ? WHERE id = ?",
                (front_name, back_name, entry_id),
            )
            conn.commit()
        return front_name, back_name

    def approve_and_upload(self, client, entry_id, cert_id):
        response = client.post(f"/admin/entries/{entry_id}/approve")
        self.assertEqual(response.status_code, 302)

        self.attach_queue_images(entry_id, cert_id)
        with patch.dict(os.environ, {"NXR_STORAGE_DRIVER": "local"}):
            response = client.post(f"/admin/api/upload/{entry_id}")
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.get_json()["success"])

    def test_code_only_restart_skips_bootstrap_but_default_start_keeps_initialization(self):
        import runpy
        from nxr_admin import app_updated

        for flag, expected_calls in (("1", 0), ("", 1)):
            with self.subTest(flag=flag), patch.dict(os.environ, {"NXR_SKIP_DB_INIT": flag}):
                with patch.object(admin_core, "initialize_databases") as initialize:
                    loaded = runpy.run_path(app_updated.__file__, run_name="isolated_admin_startup")
                self.assertIs(loaded["app"], admin_core.app)
                self.assertEqual(initialize.call_count, expected_calls)

    def test_french_language_can_be_selected_saved_edited_filtered_and_published(self):
        client = self.admin_client()
        form = client.get("/admin/entry/new")
        self.assertEqual(form.status_code, 200)
        self.assertIn(b'<option value="FR"', form.data)

        entry = self.create_entry(
            client, "8234567893", "merch_product", language="French",
            merch_description="French-language collectible fixture",
        )
        self.assertEqual(entry["language"], "FR")

        # Imported legacy spellings must remain editable and discoverable without a data migration.
        with admin_core.get_temp_db_connection() as conn:
            conn.execute("UPDATE temp_cards SET language = ? WHERE id = ?", ("Français", entry["id"]))
            conn.commit()
        filtered = client.get("/admin/entries?language=FR")
        self.assertIn(b"8234567893", filtered.data)
        edit = client.get(f"/admin/entries/{entry['id']}/edit")
        self.assertEqual(edit.status_code, 200)
        self.assertRegex(edit.get_data(as_text=True), r'<option value="FR"\s+selected')

        response = client.post(f"/admin/entries/{entry['id']}/edit", data={
            "cert_id": entry["cert_id"], "product_type": "merch_product",
            "card_category": "trading_card", "card_name": entry["card_name"],
            "year": "1999", "brand": "Pokemon", "variety": "Test Variant",
            "language": "fr", "set_name": "Integration Set", "card_number": "001",
            "merch_description": "French-language collectible fixture",
        })
        self.assertEqual(response.status_code, 302)
        self.approve_and_upload(client, entry["id"], entry["cert_id"])
        with admin_core.get_main_db_connection() as conn:
            saved = conn.execute("SELECT language FROM cards WHERE cert_id = ?", (entry["cert_id"],)).fetchone()
        self.assertEqual(saved["language"], "FR")
        self.assertEqual(public_site.get_card(entry["cert_id"])["language_label"], "French")

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
            vintage_classification="Nova",
        )

        self.assertEqual(merch["status"], "pending")
        self.assertEqual(vintage["status"], "pending")
        self.approve_and_upload(client, merch["id"], merch["cert_id"])
        self.approve_and_upload(client, vintage["id"], vintage["cert_id"])

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
        self.assertEqual(rows["8234567892"]["vintage_classification"], "Nova")
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
        self.assertIn("Nova", vintage_html)
        self.assertNotIn("Final Grade", vintage_html)
        self.assertNotIn("Sub-Grades", vintage_html)
        self.assertIn('<div class="vintage-badge', vintage_html)
        self.assertIn("Vintage Card Classifications", vintage_html)
        self.assertIn("Pristine", vintage_html)
        self.assertIn("Nova", vintage_html)
        self.assertIn("Legacy", vintage_html)
        self.assertIn("Helix", vintage_html)
        self.assertNotIn("Collector Ledger", vintage_html)
        self.assertNotIn("Transfer History", vintage_html)

    def test_legacy_export_redirect_does_not_replay_approved_entries(self):
        client = self.admin_client()
        entry = self.create_entry(client, "8234567893", "merch_product")
        response = client.post(f"/admin/entries/{entry['id']}/approve")
        self.assertEqual(response.status_code, 302)
        front_name, back_name = self.attach_queue_images(entry["id"], entry["cert_id"])

        response = client.get("/admin/export/approved")

        self.assertEqual(response.status_code, 302)
        self.assertTrue(response.headers["Location"].endswith("/admin/upload"))
        with admin_core.get_main_db_connection() as conn:
            main_count = conn.execute(
                "SELECT COUNT(*) FROM cards WHERE cert_id = ?", (entry["cert_id"],)
            ).fetchone()[0]
        self.assertEqual(main_count, 0)
        self.assertTrue((admin_core.UPLOAD_FOLDER / front_name).is_file())
        self.assertTrue((admin_core.UPLOAD_FOLDER / back_name).is_file())
        self.assertEqual(list(admin_core.SITE_STATIC_DIR.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
