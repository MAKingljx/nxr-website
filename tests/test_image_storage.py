import base64
import json
import os
import tempfile
import unittest
from pathlib import Path

from nxr_admin import admin_core
from nxr_admin import image_storage


class ImageStorageTests(unittest.TestCase):
    def setUp(self):
        self._env_snapshot = {
            key: os.environ.get(key)
            for key in (
                "NXR_STORAGE_DRIVER",
                "R2_ENDPOINT",
                "R2_BUCKET",
                "R2_PUBLIC_BASE_URL",
                "R2_ACCESS_KEY_ID",
                "R2_SECRET_ACCESS_KEY",
                "R2_REGION",
                "R2_OBJECT_PREFIX",
                "R2_ACCOUNT_ID",
                "R2_USE_UPLOAD_ONLY_CREDENTIALS",
                "R2_TEMP_CREDENTIAL_TTL_SECONDS",
            )
        }
        self._upload_folder = admin_core.app.config["UPLOAD_FOLDER"]
        self._site_static_dir = admin_core.SITE_STATIC_DIR
        self._r2_uploader = admin_core.upload_public_image_to_r2

    def tearDown(self):
        for key, value in self._env_snapshot.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        admin_core.app.config["UPLOAD_FOLDER"] = self._upload_folder
        admin_core.SITE_STATIC_DIR = self._site_static_dir
        admin_core.upload_public_image_to_r2 = self._r2_uploader

    def test_local_driver_keeps_existing_static_copy_behavior(self):
        os.environ.pop("NXR_STORAGE_DRIVER", None)
        with tempfile.TemporaryDirectory() as tmp_dir:
            upload_dir = Path(tmp_dir) / "uploads"
            static_dir = Path(tmp_dir) / "static"
            upload_dir.mkdir()
            static_dir.mkdir()
            source_path = upload_dir / "front_abcd1234.jpg"
            source_path.write_bytes(b"local-image")
            admin_core.app.config["UPLOAD_FOLDER"] = upload_dir
            admin_core.SITE_STATIC_DIR = static_dir

            result = admin_core.sync_uploaded_image_to_site(
                "TEST CERT 001",
                "front",
                source_path.name,
            )

            self.assertEqual(result, "/static/TEST_CERT_001_front.jpg")
            self.assertEqual((static_dir / "TEST_CERT_001_front.jpg").read_bytes(), b"local-image")

    def test_r2_driver_uploads_new_public_image_without_static_copy(self):
        os.environ["NXR_STORAGE_DRIVER"] = "r2"
        with tempfile.TemporaryDirectory() as tmp_dir:
            upload_dir = Path(tmp_dir) / "uploads"
            static_dir = Path(tmp_dir) / "static"
            upload_dir.mkdir()
            static_dir.mkdir()
            source_path = upload_dir / "front_abcd1234.jpg"
            source_path.write_bytes(b"r2-image")
            admin_core.app.config["UPLOAD_FOLDER"] = upload_dir
            admin_core.SITE_STATIC_DIR = static_dir
            calls = []

            def fake_upload(path, cert_id, side):
                calls.append((Path(path), cert_id, side))
                return "https://pub.example/cards/TEST_CERT_001/front.jpg"

            admin_core.upload_public_image_to_r2 = fake_upload

            result = admin_core.sync_uploaded_image_to_site(
                "TEST CERT 001",
                "front",
                source_path.name,
            )

            self.assertEqual(result, "https://pub.example/cards/TEST_CERT_001/front.jpg")
            self.assertEqual(calls, [(source_path, "TEST CERT 001", "front")])
            self.assertEqual(list(static_dir.iterdir()), [])

    def test_remote_published_images_count_as_existing_references(self):
        entry = {
            "front_image": "",
            "back_image": "",
            "published_front_image": "https://pub.example/cards/front.jpg",
            "published_back_image": "https://pub.example/cards/back.jpg",
            "upload_status": "uploaded",
        }

        flags = admin_core.get_entry_image_flags(entry)

        self.assertTrue(flags["has_published_front_image"])
        self.assertTrue(flags["has_published_back_image"])
        self.assertTrue(flags["published_complete"])

    def test_r2_upload_builds_signed_put_request_and_public_url(self):
        env = {
            "R2_ENDPOINT": "https://account.r2.cloudflarestorage.com",
            "R2_BUCKET": "nxr-card-images-prod",
            "R2_PUBLIC_BASE_URL": "https://pub.example",
            "R2_ACCESS_KEY_ID": "access-key",
            "R2_SECRET_ACCESS_KEY": "secret-key",
            "R2_REGION": "auto",
            "R2_OBJECT_PREFIX": "cards",
        }

        class FakeResponse:
            status = 200

            def getcode(self):
                return self.status

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return False

        captured = {}

        def fake_opener(request, timeout):
            captured["url"] = request.full_url
            captured["method"] = request.get_method()
            captured["headers"] = dict(request.header_items())
            captured["timeout"] = timeout
            captured["body"] = request.data
            return FakeResponse()

        with tempfile.TemporaryDirectory() as tmp_dir:
            source_path = Path(tmp_dir) / "front abcd.jpg"
            source_path.write_bytes(b"remote-image")

            public_url = image_storage.upload_public_image_to_r2(
                source_path,
                "TEST CERT 001",
                "front",
                environ=env,
                opener=fake_opener,
            )

        self.assertEqual(
            public_url,
            "https://pub.example/cards/TEST-CERT-001/TEST-CERT-001_front_front-abcd.jpg",
        )
        self.assertEqual(captured["method"], "PUT")
        self.assertEqual(captured["timeout"], 30)
        self.assertEqual(captured["body"], b"remote-image")
        self.assertIn("/nxr-card-images-prod/cards/TEST-CERT-001/", captured["url"])
        self.assertIn("Authorization", captured["headers"])

    def test_upload_only_credentials_scope_signed_request_to_put_object(self):
        env = {
            "R2_ACCOUNT_ID": "account-id",
            "R2_ENDPOINT": "https://account.r2.cloudflarestorage.com",
            "R2_BUCKET": "nxr-card-images-prod",
            "R2_PUBLIC_BASE_URL": "https://pub.example",
            "R2_ACCESS_KEY_ID": "access-key",
            "R2_SECRET_ACCESS_KEY": "secret-key",
            "R2_REGION": "auto",
            "R2_OBJECT_PREFIX": "cards",
            "R2_USE_UPLOAD_ONLY_CREDENTIALS": "1",
            "R2_TEMP_CREDENTIAL_TTL_SECONDS": "900",
        }

        class FakeResponse:
            status = 200

            def getcode(self):
                return self.status

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return False

        captured = {}

        def fake_opener(request, timeout):
            headers = {key.lower(): value for key, value in request.header_items()}
            captured["headers"] = headers
            captured["body"] = request.data
            return FakeResponse()

        with tempfile.TemporaryDirectory() as tmp_dir:
            source_path = Path(tmp_dir) / "front.png"
            source_path.write_bytes(b"remote-image")
            public_url = image_storage.upload_public_image_to_r2(
                source_path,
                "TEST CERT 002",
                "front",
                environ=env,
                opener=fake_opener,
            )

        self.assertEqual(
            public_url,
            "https://pub.example/cards/TEST-CERT-002/TEST-CERT-002_front_front.png",
        )
        self.assertEqual(captured["body"], b"remote-image")
        self.assertIn("X-amz-security-token".lower(), captured["headers"])
        token = captured["headers"]["X-amz-security-token".lower()]
        decoded_token = base64.b64decode(token).decode("utf-8")
        self.assertTrue(decoded_token.startswith("jwt/"))
        payload_segment = decoded_token[4:].split(".")[1]
        payload_segment += "=" * (-len(payload_segment) % 4)
        payload = json.loads(base64.urlsafe_b64decode(payload_segment.encode("ascii")))
        self.assertEqual(payload["actions"], ["PutObject"])
        self.assertEqual(payload["paths"]["objectPaths"], ["cards/TEST-CERT-002/TEST-CERT-002_front_front.png"])
        self.assertNotIn("scope", payload)


if __name__ == "__main__":
    unittest.main()
