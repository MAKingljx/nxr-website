import importlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import requests

from nxr_common import db
from nxr_site import java_cards


class JavaCardQrBridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scratch = tempfile.TemporaryDirectory()
        cls.database = Path(cls.scratch.name) / "cards.db"
        cls.environment = mock.patch.dict(os.environ, {
            "NXR_DB_BACKEND": "sqlite",
            "NXR_DATA_DIR": cls.scratch.name,
            "NXR_DB_PATH": str(cls.database),
            "NXR_JAVA_PUBLIC_API_BASE": "http://127.0.0.1:18080",
        })
        cls.environment.start()
        cls.site = importlib.import_module("nxr_site.app")
        cls.database_patch = mock.patch.object(cls.site, "DB_PATH", cls.database)
        cls.database_patch.start()
        cls.site.initialize_site_database()
        with db.connect(cls.database) as conn:
            conn.execute("""CREATE TABLE cards (
                cert_id TEXT PRIMARY KEY, card_name TEXT, brand TEXT, grade TEXT,
                front_image TEXT, back_image TEXT,
                centering REAL DEFAULT 9, edges REAL DEFAULT 9,
                corners REAL DEFAULT 9, surface REAL DEFAULT 9
            )""")
            conn.executemany("INSERT INTO cards (cert_id,card_name,brand,grade,front_image,back_image) VALUES (?, 'Test Card', 'NXR', '9', 'front.webp', 'back.webp')",
                             [("VRA002",), ("vra003",)])
            conn.commit()
        cls.site.app.config["TESTING"] = True

    @classmethod
    def tearDownClass(cls):
        cls.database_patch.stop()
        cls.environment.stop()
        cls.scratch.cleanup()

    def setUp(self):
        self.client = self.site.app.test_client()
        self.sessions = mock.patch.object(java_cards.requests, "Session")
        self.session_factory = self.sessions.start()
        self.addCleanup(self.sessions.stop)
        self.session = self.session_factory.return_value.__enter__.return_value
        self.response = self.session.get.return_value.__enter__.return_value
        self.response.status_code = 200
        self.reply({"certId": "9960490811"})

    def reply(self, value):
        self.response.iter_content.return_value = [json.dumps(value).encode()]

    def test_existing_case_insensitive_cards_keep_legacy_images_without_java(self):
        for cert in ("VRA002", "vra002", "vra003", "VRA003"):
            with self.subTest(cert=cert):
                result = self.client.get("/card/" + cert)
                self.assertEqual(result.status_code, 200)
                self.assertIn(b"/static/front.webp", result.data)
                self.assertIn(b"/static/back.webp", result.data)
                self.assertIsNone(result.headers.get("Location"))
        self.session_factory.assert_not_called()

    def test_all_four_java_only_certificates_redirect_from_printed_qr(self):
        for cert in ("9960490811", "9873255669", "9953145933", "9891800481"):
            with self.subTest(cert=cert):
                self.reply({"certId": cert, "qrUrl": "https://untrusted.invalid/"})
                result = self.client.get("/card/" + cert)
                self.assertEqual(result.status_code, 302)
                self.assertEqual(result.headers["Location"], "/java-stage/card/" + cert)
                self.assertEqual(result.headers["Cache-Control"], "no-store")

    def test_verify_form_uses_same_fallback_and_preserves_legacy_flow(self):
        self.assertEqual(self.client.get("/verify").status_code, 200)
        self.assertEqual(self.client.post("/verify", data={"cert_id": "VRA002"}).headers["Location"], "/card/VRA002")
        self.session_factory.assert_not_called()
        result = self.client.post("/verify", data={"cert_id": " 9960490811 "})
        self.assertEqual(result.status_code, 302)
        self.assertEqual(result.headers["Location"], "/java-stage/card/9960490811")

    def test_unpublished_or_missing_java_certificate_remains_not_found(self):
        self.response.status_code = 404
        result = self.client.get("/card/UNKNOWN123")
        self.assertEqual(result.status_code, 404)
        self.assertIn(b"Card Not Found", result.data)
        form = self.client.post("/verify", data={"cert_id": "UNKNOWN123"})
        self.assertIn(b"Certificate ID not found", form.data)

    def test_timeouts_return_retryable_error_on_both_entrypoints(self):
        self.session.get.side_effect = requests.Timeout()
        for method, path, kwargs in [("get", "/card/9960490811", {}),
                                     ("post", "/verify", {"data": {"cert_id": "9960490811"}})]:
            with self.subTest(path=path):
                result = getattr(self.client, method)(path, **kwargs)
                self.assertEqual(result.status_code, 503)
                self.assertEqual(result.headers["Retry-After"], "10")
                self.assertEqual(result.headers["Cache-Control"], "no-store")
                self.assertIn(b"temporarily unavailable", result.data)
                self.assertIn(b' value="9960490811"', result.data)
                self.assertNotIn(b"Card Not Found", result.data)

    def test_upstream_redirect_auth_errors_and_server_errors_are_not_missing_cards(self):
        for status in (301, 302, 401, 403, 429, 500, 503):
            with self.subTest(status=status):
                self.response.status_code = status
                self.assertEqual(self.client.get("/card/9960490811").status_code, 503)

    def test_invalid_or_mismatched_upstream_payload_cannot_redirect(self):
        for payload in ({}, [], {"certId": "OTHER123"}, {"certId": 9960490811},
                        {"certId": "//evil.invalid"}):
            with self.subTest(payload=payload):
                self.reply(payload)
                self.assertEqual(self.client.get("/card/9960490811").status_code, 503)
        for body in (b"<html>Bad gateway</html>", b"a" * (java_cards.MAX_RESPONSE_BYTES + 1)):
            self.response.iter_content.return_value = [body]
            self.assertEqual(self.client.get("/card/9960490811").status_code, 503)

    def test_lookup_is_read_only_bounded_and_ignores_untrusted_redirect_url(self):
        self.reply({"certId": "JavaOnly001", "qrUrl": "https://untrusted.invalid"})
        self.assertEqual(java_cards.published_card_redirect("javaonly001"), "/java-stage/card/JavaOnly001")
        self.assertFalse(self.session.trust_env)
        self.session.get.assert_called_once_with(
            "http://127.0.0.1:18080/api/public/cards/javaonly001", timeout=(1, 3),
            allow_redirects=False, stream=True, headers={"Accept": "application/json"},
        )

    def test_invalid_input_and_disabled_configuration_never_contact_java(self):
        for cert in ("", "../foo", "https://bad.example", "a" * 65):
            self.assertIsNone(java_cards.published_card_redirect(cert))
        with mock.patch.dict(os.environ, {"NXR_JAVA_PUBLIC_API_BASE": ""}):
            self.assertIsNone(java_cards.published_card_redirect("9960490811"))
        self.session_factory.assert_not_called()


if __name__ == "__main__":
    unittest.main()
