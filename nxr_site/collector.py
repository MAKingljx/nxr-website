"""Collector accounts, card bindings, and ownership transfer history."""

from __future__ import annotations

import hmac
import re
import secrets
from functools import wraps

from flask import flash, redirect, render_template, request, session, url_for
from werkzeug.security import check_password_hash, generate_password_hash

from nxr_common import db


EMAIL_PATTERN = re.compile(r"^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$", re.IGNORECASE)
CSRF_SESSION_KEY = "_collector_csrf_token"
VALID_VISIBILITIES = {"public", "anonymous", "private"}


def initialize_database(conn):
    conn.execute(
        f"""
        CREATE TABLE IF NOT EXISTS site_users (
            id {db.auto_increment_primary_key()},
            {db.column_definition('email', 'TEXT UNIQUE NOT NULL')},
            {db.column_definition('password_hash', 'TEXT NOT NULL')},
            {db.column_definition('display_name', 'TEXT NOT NULL')},
            {db.column_definition('bio', "TEXT NOT NULL DEFAULT ''")},
            {db.column_definition('avatar_url', "TEXT NOT NULL DEFAULT ''")},
            {db.column_definition('is_active', 'INTEGER NOT NULL DEFAULT 1')},
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            last_login DATETIME
        )
        """
    )
    conn.execute(
        f"""
        CREATE TABLE IF NOT EXISTS card_bindings (
            id {db.auto_increment_primary_key()},
            {db.column_definition('cert_id', 'TEXT NOT NULL')},
            {db.column_definition('user_id', 'INTEGER NOT NULL')},
            {db.column_definition('status', "TEXT NOT NULL DEFAULT 'active'")},
            {db.column_definition('visibility', "TEXT NOT NULL DEFAULT 'public'")},
            {db.column_definition('note', "TEXT NOT NULL DEFAULT ''")},
            bound_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            released_at DATETIME,
            FOREIGN KEY (user_id) REFERENCES site_users(id)
        )
        """
    )
    conn.execute(
        f"""
        CREATE TABLE IF NOT EXISTS card_transfer_events (
            id {db.auto_increment_primary_key()},
            {db.column_definition('cert_id', 'TEXT NOT NULL')},
            {db.column_definition('from_user_id', 'INTEGER')},
            {db.column_definition('to_user_id', 'INTEGER')},
            {db.column_definition('event_type', 'TEXT NOT NULL')},
            {db.column_definition('visibility', "TEXT NOT NULL DEFAULT 'public'")},
            {db.column_definition('message', "TEXT NOT NULL DEFAULT ''")},
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (from_user_id) REFERENCES site_users(id),
            FOREIGN KEY (to_user_id) REFERENCES site_users(id)
        )
        """
    )
    if "visibility" not in db.table_columns(conn, "card_transfer_events"):
        conn.execute(
            f"""
            ALTER TABLE card_transfer_events
            ADD COLUMN {db.column_definition('visibility', "TEXT NOT NULL DEFAULT 'public'")}
            """
        )
    conn.execute("CREATE INDEX IF NOT EXISTS idx_card_bindings_cert_status ON card_bindings (cert_id, status)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_card_bindings_user_status ON card_bindings (user_id, status)")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_card_transfer_events_cert ON card_transfer_events (cert_id, created_at)")


def normalize_email_address(email):
    return (email or "").strip().lower()


def is_valid_email(email):
    return bool(EMAIL_PATTERN.fullmatch(email or ""))


def normalize_display_name(value):
    return " ".join((value or "").strip().split())[:80]


def normalize_visibility(value):
    visibility = (value or "public").strip().lower()
    return visibility if visibility in VALID_VISIBILITIES else "public"


def get_csrf_token():
    token = session.get(CSRF_SESSION_KEY)
    if not token:
        token = secrets.token_urlsafe(32)
        session[CSRF_SESSION_KEY] = token
    return token


def is_valid_csrf_token(token):
    session_token = session.get(CSRF_SESSION_KEY)
    return bool(token and session_token and hmac.compare_digest(str(token), str(session_token)))


def csrf_error_response(redirect_url):
    flash("Your form expired. Please try again.", "error")
    return redirect(redirect_url)


def public_owner_label(display_name, email, visibility="public"):
    if visibility == "anonymous":
        return "Private collector"
    if visibility == "private":
        return "Not publicly shown"
    return display_name or email or "Collector"


class CollectorService:
    def __init__(self, get_db_connection, get_card, normalize_asset_path):
        self.get_db_connection = get_db_connection
        self.get_card = get_card
        self.normalize_asset_path = normalize_asset_path

    def get_user_by_id(self, user_id):
        if not user_id:
            return None
        with self.get_db_connection() as conn:
            row = conn.execute(
                """
                SELECT id, email, display_name, bio, avatar_url, is_active, created_at, last_login
                FROM site_users
                WHERE id = ? AND is_active = 1
                """,
                (user_id,),
            ).fetchone()
        return dict(row) if row else None

    def get_user_by_email(self, email):
        normalized_email = normalize_email_address(email)
        if not normalized_email:
            return None
        with self.get_db_connection() as conn:
            row = conn.execute(
                """
                SELECT id, email, password_hash, display_name, bio, avatar_url, is_active, created_at, last_login
                FROM site_users
                WHERE email = ? AND is_active = 1
                """,
                (normalized_email,),
            ).fetchone()
        return dict(row) if row else None

    def create_user(self, email, password, display_name):
        normalized_email = normalize_email_address(email)
        clean_name = normalize_display_name(display_name) or normalized_email.split("@", 1)[0]
        with self.get_db_connection() as conn:
            cursor = conn.execute(
                """
                INSERT INTO site_users (email, password_hash, display_name)
                VALUES (?, ?, ?)
                """,
                (normalized_email, generate_password_hash(password), clean_name),
            )
            user_id = cursor.lastrowid
            conn.commit()
        return self.get_user_by_id(user_id)

    def current_user(self):
        return self.get_user_by_id(session.get("site_user_id"))

    def require_login(self, view_func):
        @wraps(view_func)
        def wrapper(*args, **kwargs):
            if not self.current_user():
                session["site_next_url"] = request.full_path if request.query_string else request.path
                flash("Please sign in to use collector features.", "info")
                return redirect(url_for("site_login"))
            return view_func(*args, **kwargs)

        return wrapper

    def get_active_card_binding(self, cert_id):
        with self.get_db_connection() as conn:
            row = conn.execute(
                """
                SELECT b.*, u.display_name, u.email
                FROM card_bindings b
                JOIN site_users u ON u.id = b.user_id
                WHERE b.cert_id = ? COLLATE NOCASE AND b.status = 'active'
                ORDER BY b.id DESC
                LIMIT 1
                """,
                ((cert_id or "").strip(),),
            ).fetchone()
        if not row:
            return None
        binding = dict(row)
        binding["owner_label"] = public_owner_label(
            binding.get("display_name"),
            binding.get("email"),
            binding.get("visibility"),
        )
        return binding

    def get_card_timeline(self, cert_id):
        with self.get_db_connection() as conn:
            rows = conn.execute(
                """
                SELECT e.*, fu.display_name AS from_name, fu.email AS from_email,
                       tu.display_name AS to_name, tu.email AS to_email
                FROM card_transfer_events e
                LEFT JOIN site_users fu ON fu.id = e.from_user_id
                LEFT JOIN site_users tu ON tu.id = e.to_user_id
                WHERE e.cert_id = ? COLLATE NOCASE
                ORDER BY e.created_at DESC, e.id DESC
                LIMIT 30
                """,
                ((cert_id or "").strip(),),
            ).fetchall()
        timeline = []
        for row in rows:
            event = dict(row)
            event["from_label"] = public_owner_label(
                event.get("from_name"),
                event.get("from_email"),
                event.get("visibility"),
            )
            event["to_label"] = public_owner_label(
                event.get("to_name"),
                event.get("to_email"),
                event.get("visibility"),
            )
            timeline.append(event)
        return timeline

    def get_user_card_rows(self, user_id):
        with self.get_db_connection() as conn:
            rows = conn.execute(
                """
                SELECT b.*, c.card_name, c.brand, c.grade, c.final_grade_text, c.front_image, c.image
                FROM card_bindings b
                LEFT JOIN cards c ON c.cert_id = b.cert_id COLLATE NOCASE
                WHERE b.user_id = ? AND b.status = 'active'
                ORDER BY b.bound_at DESC, b.id DESC
                """,
                (user_id,),
            ).fetchall()
        cards = []
        for row in rows:
            item = dict(row)
            item["front_image"] = self.normalize_asset_path(item.get("front_image") or item.get("image"))
            cards.append(item)
        return cards

    def get_card_collectors(self, cert_id):
        return {
            "binding": self.get_active_card_binding(cert_id),
            "timeline": self.get_card_timeline(cert_id),
        }

    def claim_card(self, card, user, visibility, note):
        with self.get_db_connection() as conn:
            conn.execute(
                """
                INSERT INTO card_bindings (cert_id, user_id, visibility, note)
                VALUES (?, ?, ?, ?)
                """,
                (card["cert_id"], user["id"], visibility, note),
            )
            conn.execute(
                """
                INSERT INTO card_transfer_events
                    (cert_id, from_user_id, to_user_id, event_type, visibility, message)
                VALUES (?, NULL, ?, 'bound', ?, ?)
                """,
                (card["cert_id"], user["id"], visibility, note),
            )
            conn.commit()

    def transfer_card(self, card, binding, user, recipient, visibility, message):
        with self.get_db_connection() as conn:
            conn.execute(
                """
                UPDATE card_bindings
                SET status = 'transferred', released_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'active'
                """,
                (binding["id"],),
            )
            conn.execute(
                """
                INSERT INTO card_bindings (cert_id, user_id, visibility, note)
                VALUES (?, ?, ?, ?)
                """,
                (card["cert_id"], recipient["id"], visibility, message),
            )
            conn.execute(
                """
                INSERT INTO card_transfer_events
                    (cert_id, from_user_id, to_user_id, event_type, visibility, message)
                VALUES (?, ?, ?, 'transferred', ?, ?)
                """,
                (card["cert_id"], user["id"], recipient["id"], visibility, message),
            )
            conn.commit()


def register_routes(app, service):
    @app.context_processor
    def inject_site_user_context():
        return {
            "current_site_user": service.current_user(),
            "collector_csrf_token": get_csrf_token,
        }

    @app.route("/account")
    def account_home():
        return redirect(url_for("account_cards" if service.current_user() else "site_login"))

    @app.route("/account/register", methods=["GET", "POST"])
    def site_register():
        if service.current_user():
            return redirect(url_for("account_cards"))

        form = {
            "email": normalize_email_address(request.form.get("email", "")),
            "display_name": normalize_display_name(request.form.get("display_name", "")),
        }
        error = ""
        if request.method == "POST":
            if not is_valid_csrf_token(request.form.get("csrf_token")):
                error = "Your form expired. Please refresh and try again."
            else:
                password = request.form.get("password", "")
                if not is_valid_email(form["email"]):
                    error = "Please enter a valid email address."
                elif len(password) < 8:
                    error = "Password must be at least 8 characters."
                elif service.get_user_by_email(form["email"]):
                    error = "An account already exists for this email."
                else:
                    try:
                        user = service.create_user(form["email"], password, form["display_name"])
                    except db.IntegrityError:
                        user = None
                        error = "An account already exists for this email."
                    if user:
                        session["site_user_id"] = user["id"]
                        flash("Welcome to your collector account.", "success")
                        return redirect(session.pop("site_next_url", None) or url_for("account_cards"))

        return render_template("account_register.html", error=error, form=form)

    @app.route("/account/login", methods=["GET", "POST"])
    def site_login():
        if service.current_user():
            return redirect(url_for("account_cards"))

        form = {"email": normalize_email_address(request.form.get("email", ""))}
        error = ""
        if request.method == "POST":
            if not is_valid_csrf_token(request.form.get("csrf_token")):
                error = "Your form expired. Please refresh and try again."
            else:
                user = service.get_user_by_email(form["email"])
                password = request.form.get("password", "")
                if not user or not check_password_hash(user.get("password_hash", ""), password):
                    error = "Email or password is incorrect."
                else:
                    session["site_user_id"] = user["id"]
                    with service.get_db_connection() as conn:
                        conn.execute("UPDATE site_users SET last_login = CURRENT_TIMESTAMP WHERE id = ?", (user["id"],))
                        conn.commit()
                    flash("Signed in successfully.", "success")
                    return redirect(session.pop("site_next_url", None) or url_for("account_cards"))

        return render_template("account_login.html", error=error, form=form)

    @app.route("/account/logout", methods=["POST"])
    def site_logout():
        if not is_valid_csrf_token(request.form.get("csrf_token")):
            return csrf_error_response(url_for("verify"))
        session.pop("site_user_id", None)
        session.pop("site_next_url", None)
        flash("Signed out.", "info")
        return redirect(url_for("verify"))

    @app.route("/account/cards")
    @service.require_login
    def account_cards():
        user = service.current_user()
        return render_template("account_cards.html", cards=service.get_user_card_rows(user["id"]))

    @app.route("/card/<cert_id>/claim", methods=["POST"])
    @service.require_login
    def claim_card(cert_id):
        card = service.get_card(cert_id)
        if not card:
            flash("Card not found. Please check the certificate ID.", "error")
            return redirect(url_for("verify"))
        if not is_valid_csrf_token(request.form.get("csrf_token")):
            return csrf_error_response(url_for("card_page", cert_id=card["cert_id"]))

        user = service.current_user()
        existing = service.get_active_card_binding(card["cert_id"])
        if existing and existing.get("user_id") == user["id"]:
            flash("This card is already in your collection.", "info")
            return redirect(url_for("card_page", cert_id=card["cert_id"]))
        if existing:
            flash("This card is already bound to another collector.", "error")
            return redirect(url_for("card_page", cert_id=card["cert_id"]))

        service.claim_card(
            card,
            user,
            normalize_visibility(request.form.get("visibility")),
            (request.form.get("note") or "").strip()[:1000],
        )
        flash("Card bound to your collector account.", "success")
        return redirect(url_for("card_page", cert_id=card["cert_id"]))

    @app.route("/card/<cert_id>/transfer", methods=["POST"])
    @service.require_login
    def transfer_card(cert_id):
        card = service.get_card(cert_id)
        if not card:
            flash("Card not found. Please check the certificate ID.", "error")
            return redirect(url_for("verify"))
        if not is_valid_csrf_token(request.form.get("csrf_token")):
            return csrf_error_response(url_for("card_page", cert_id=card["cert_id"]))

        user = service.current_user()
        binding = service.get_active_card_binding(card["cert_id"])
        if not binding or binding.get("user_id") != user["id"]:
            flash("Only the current bound collector can transfer this card.", "error")
            return redirect(url_for("card_page", cert_id=card["cert_id"]))

        recipient = service.get_user_by_email(request.form.get("recipient_email", ""))
        if not recipient:
            flash("Recipient account was not found. Ask them to register first.", "error")
            return redirect(url_for("card_page", cert_id=card["cert_id"]))
        if recipient["id"] == user["id"]:
            flash("You already own this card.", "info")
            return redirect(url_for("card_page", cert_id=card["cert_id"]))

        service.transfer_card(
            card,
            binding,
            user,
            recipient,
            normalize_visibility(request.form.get("visibility") or binding.get("visibility")),
            (request.form.get("message") or "").strip()[:1000],
        )
        flash("Card transfer recorded.", "success")
        return redirect(url_for("card_page", cert_id=card["cert_id"]))
