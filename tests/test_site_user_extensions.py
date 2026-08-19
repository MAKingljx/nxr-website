import os
import re
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from flask import Flask, render_template_string

from nxr_common import db
from nxr_site import collector


PROJECT_ROOT = Path(__file__).resolve().parents[1]
CSRF_PATTERN = re.compile(rb'name="csrf_token" value="([^"]+)"')


class SiteUserExtensionFlowTests(unittest.TestCase):
    def setUp(self):
        self.backend_patch = mock.patch.dict(os.environ, {"NXR_DB_BACKEND": "sqlite"}, clear=False)
        self.backend_patch.start()
        self.temp_dir = tempfile.TemporaryDirectory()
        self.database_path = Path(self.temp_dir.name) / "collector-flow.db"

        def get_connection():
            return db.sqlite_connect(self.database_path)

        self.get_connection = get_connection
        with get_connection() as conn:
            conn.execute(
                """
                CREATE TABLE cards (
                    cert_id TEXT PRIMARY KEY,
                    card_name TEXT,
                    brand TEXT,
                    grade TEXT,
                    final_grade_text TEXT,
                    front_image TEXT,
                    image TEXT
                )
                """
            )
            conn.execute(
                """
                INSERT INTO cards
                    (cert_id, card_name, brand, grade, final_grade_text, front_image, image)
                VALUES
                    ('VRA002', 'Pikachu', 'Pokemon', '10', 'Gem Mint', '/static/pikachu.jpg', '')
                """
            )
            collector.initialize_database(conn)
            conn.commit()

        app = Flask(
            __name__,
            template_folder=str(PROJECT_ROOT / "nxr_site" / "templates"),
            static_folder=str(PROJECT_ROOT / "nxr_site" / "static"),
        )
        app.config.update(SECRET_KEY="collector-test-secret", TESTING=True)

        def get_card(cert_id):
            if (cert_id or "").strip().lower() != "vra002":
                return None
            return {
                "cert_id": "VRA002",
                "card_name": "Pikachu",
                "brand": "Pokemon",
                "grade": "10",
                "front_image": "/static/pikachu.jpg",
            }

        service = collector.CollectorService(get_connection, get_card, lambda value: value or "")

        @app.route("/verify", endpoint="verify")
        def verify_page():
            return "Public verification"

        @app.route("/card/<cert_id>", endpoint="card_page")
        def card_page(cert_id):
            card = get_card(cert_id)
            if not card:
                return "Card not found", 404
            context = service.get_card_collectors(card["cert_id"])
            return render_template_string(
                """
                {% include "components/collector_ledger.html" %}
                {% include "components/collector_timeline.html" %}
                """,
                card=card,
                binding=context["binding"],
                timeline=context["timeline"],
            )

        collector.register_routes(app, service)
        self.app = app

    def tearDown(self):
        self.temp_dir.cleanup()
        self.backend_patch.stop()

    def csrf_token(self, response):
        match = CSRF_PATTERN.search(response.data)
        self.assertIsNotNone(match, response.data.decode("utf-8", errors="replace"))
        return match.group(1).decode()

    def session_csrf_token(self, client):
        with client.session_transaction() as session_state:
            token = session_state.get(collector.CSRF_SESSION_KEY)
        self.assertIsNotNone(token)
        return token

    def register(self, client, email, display_name):
        form_page = client.get("/account/register")
        response = client.post(
            "/account/register",
            data={
                "csrf_token": self.csrf_token(form_page),
                "email": email,
                "display_name": display_name,
                "password": "collector-pass-123",
            },
            follow_redirects=True,
        )
        self.assertEqual(response.status_code, 200)
        self.assertIn(b"My Cards", response.data)
        return response

    def test_anonymous_verification_and_complete_ownership_flow(self):
        anonymous = self.app.test_client()
        card_page = anonymous.get("/card/vra002")
        self.assertEqual(card_page.status_code, 200)
        self.assertIn(b"Public card verification remains available without an account", card_page.data)

        alice = self.app.test_client()
        alice_cards = self.register(alice, "ALICE@example.test", "Alice Collector")

        logout_response = alice.post(
            "/account/logout",
            data={"csrf_token": self.session_csrf_token(alice)},
            follow_redirects=True,
        )
        self.assertEqual(logout_response.status_code, 200)
        self.assertIn(b"Public verification", logout_response.data)

        login_page = alice.get("/account/login")
        invalid_login = alice.post(
            "/account/login",
            data={
                "csrf_token": self.csrf_token(login_page),
                "email": "alice@example.test",
                "password": "incorrect-password",
            },
        )
        self.assertEqual(invalid_login.status_code, 200)
        self.assertIn(b"Email or password is incorrect", invalid_login.data)

        login_page = alice.get("/account/login")
        alice_cards = alice.post(
            "/account/login",
            data={
                "csrf_token": self.csrf_token(login_page),
                "email": "alice@example.test",
                "password": "collector-pass-123",
            },
            follow_redirects=True,
        )
        self.assertIn(b"My Cards", alice_cards.data)

        alice_card_page = alice.get("/card/VRA002")
        claim = alice.post(
            "/card/VRA002/claim",
            data={
                "csrf_token": self.csrf_token(alice_card_page),
                "visibility": "public",
                "note": "First collector",
            },
            follow_redirects=True,
        )
        self.assertEqual(claim.status_code, 200)
        self.assertIn(b"This card is currently in your collection", claim.data)
        self.assertIn(b"Alice Collector", claim.data)

        bob = self.app.test_client()
        bob_cards = self.register(bob, "bob@example.test", "Bob Collector")
        rejected_claim = bob.post(
            "/card/VRA002/claim",
            data={
                "csrf_token": self.session_csrf_token(bob),
                "visibility": "public",
            },
            follow_redirects=True,
        )
        self.assertIn(b"This card is already bound to another collector", rejected_claim.data)

        alice_card_page = alice.get("/card/VRA002")
        transfer = alice.post(
            "/card/VRA002/transfer",
            data={
                "csrf_token": self.csrf_token(alice_card_page),
                "recipient_email": "BOB@example.test",
                "visibility": "public",
                "message": "Passed to Bob",
            },
            follow_redirects=True,
        )
        self.assertEqual(transfer.status_code, 200)
        self.assertIn(b"Bound to Bob Collector", transfer.data)
        self.assertIn(b"Passed to Bob", transfer.data)

        self.assertNotIn(b"VRA002", alice.get("/account/cards").data)
        self.assertIn(b"VRA002", bob.get("/account/cards").data)

        public_history = anonymous.get("/card/VRA002")
        self.assertIn(b"Alice Collector", public_history.data)
        self.assertIn(b"Bob Collector", public_history.data)
        self.assertNotIn(b"Rating", public_history.data)
        self.assertNotIn(b"Card Memories", public_history.data)

        self.assertEqual(anonymous.post("/card/VRA002/comments").status_code, 404)
        self.assertEqual(anonymous.post("/card/VRA002/memories").status_code, 404)

        with self.get_connection() as conn:
            users = conn.execute(
                "SELECT email, password_hash FROM site_users ORDER BY id"
            ).fetchall()
            active_binding = conn.execute(
                """
                SELECT b.cert_id, u.email
                FROM card_bindings b
                JOIN site_users u ON u.id = b.user_id
                WHERE b.status = 'active'
                """
            ).fetchone()
            events = conn.execute(
                "SELECT event_type FROM card_transfer_events ORDER BY id"
            ).fetchall()
            comment_table = conn.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'card_comments'"
            ).fetchone()

        self.assertEqual([row["email"] for row in users], ["alice@example.test", "bob@example.test"])
        self.assertTrue(all(row["password_hash"] != "collector-pass-123" for row in users))
        self.assertEqual(dict(active_binding), {"cert_id": "VRA002", "email": "bob@example.test"})
        self.assertEqual([row["event_type"] for row in events], ["bound", "transferred"])
        self.assertIsNone(comment_table)

    def test_csrf_and_login_protection(self):
        client = self.app.test_client()
        protected = client.get("/account/cards")
        self.assertEqual(protected.status_code, 302)
        self.assertIn("/account/login", protected.headers["Location"])

        register_without_token = client.post(
            "/account/register",
            data={
                "email": "missing-token@example.test",
                "display_name": "Missing Token",
                "password": "collector-pass-123",
            },
        )
        self.assertEqual(register_without_token.status_code, 200)
        self.assertIn(b"form expired", register_without_token.data)

        with self.get_connection() as conn:
            user_count = conn.execute("SELECT COUNT(*) FROM site_users").fetchone()[0]
        self.assertEqual(user_count, 0)


if __name__ == "__main__":
    unittest.main()
