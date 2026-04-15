import os
import re
import sqlite3
import smtplib
import threading
import hashlib
import json
from email.message import EmailMessage
from html import escape
from pathlib import Path

import requests
from flask import Flask, render_template, request, jsonify, redirect, send_from_directory

SITE_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SITE_DIR.parent
DATA_DIR = PROJECT_ROOT / "Data"
DATA_DIR.mkdir(exist_ok=True)
DB_PATH = DATA_DIR / "cards.db"
STATIC_PREFIX = "/static/"
PLACEHOLDER_IMAGE = f"{STATIC_PREFIX}placeholder.png"
EMAIL_PATTERN = re.compile(r"^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$", re.IGNORECASE)
AI_LANGUAGE_NAMES = {
    "en": "English",
    "zh": "中文",
    "es": "Español",
    "fr": "Français",
    "de": "Deutsch",
    "nl": "Nederlands",
    "ja": "日本語",
    "ko": "한국어",
}
AI_CHARACTER_PROMPT_VERSION = "v2"
AI_ANIME_CONTEXT_HINTS = {
    "pokemon": {
        "pikachu": "可以自然融入动画中的场景设定，例如皮卡丘山谷这类地点会通过皮毛与外观特征强化角色辨识度与出身背景。",
        "charizard": "可以自然融入喷火龙山谷这类动画场景，用来体现喷火龙的成长、力量与族群生态。",
        "psyduck": "可以自然融入动画中头痛引发超能力爆发的桥段，用来体现这一角色平时迟钝、关键时刻失控发力的反差感。",
        "mewtwo": "可以自然融入实验室、新岛、复制体等动画叙事情境，用来体现其人工诞生与身份认同冲突。",
    },
}

app = Flask(
    __name__,
    template_folder=str(SITE_DIR / "templates"),
    static_folder=str(SITE_DIR / "static"),
    static_url_path="/static",
)


def get_db_connection():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def load_env_file():
    env_path = PROJECT_ROOT / ".env"
    if not env_path.exists():
        return

    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        os.environ.setdefault(key, value)


def initialize_site_database():
    with get_db_connection() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS waitlist (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                email TEXT UNIQUE NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS ai_character_cache (
                cert_id TEXT NOT NULL,
                language TEXT NOT NULL,
                prompt_hash TEXT NOT NULL,
                content_json TEXT NOT NULL,
                rendered_html TEXT NOT NULL,
                model TEXT NOT NULL,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (cert_id, language, prompt_hash)
            )
            """
        )
        conn.commit()


def normalize_email_address(email):
    normalized = (email or "").strip().lower()
    return normalized


def is_valid_email(email):
    return bool(EMAIL_PATTERN.fullmatch(email or ""))


def get_waitlist_count():
    with get_db_connection() as conn:
        result = conn.execute("SELECT COUNT(*) FROM waitlist").fetchone()
        return int(result[0] if result else 0)


def send_waitlist_confirmation(email):
    if send_waitlist_confirmation_via_resend(email):
        return True
    return send_waitlist_confirmation_via_smtp(email)


def queue_waitlist_confirmation(email):
    thread = threading.Thread(
        target=send_waitlist_confirmation,
        args=(email,),
        daemon=True,
    )
    thread.start()


def send_waitlist_confirmation_via_resend(email):
    resend_key = os.environ.get("NXR_RESEND_KEY", "").strip()
    if not resend_key:
        return False

    fallback_from_email = "NXR <onboarding@resend.dev>"
    primary_from_email = os.environ.get("NXR_EMAIL_FROM", fallback_from_email).strip() or fallback_from_email

    def post_email(from_email):
        payload = {
            "from": from_email,
            "to": [email],
            "subject": "You're on the NXR waitlist",
            "html": (
                "<p>You're officially on the NXR waitlist.</p>"
                "<p>We'll email you as soon as private beta access expands.</p>"
                "<p>NXR Grading</p>"
            ),
        }
        return requests.post(
            "https://api.resend.com/emails",
            headers={
                "Authorization": f"Bearer {resend_key}",
                "Content-Type": "application/json",
            },
            json=payload,
            timeout=10,
        )

    try:
        response = post_email(primary_from_email)
        if response.ok:
            return True
        if (
            primary_from_email != fallback_from_email
            and response.status_code == 403
            and "domain is not verified" in response.text.lower()
        ):
            fallback_response = post_email(fallback_from_email)
            if fallback_response.ok:
                app.logger.warning(
                    "Resend sender fallback applied for %s after unverified domain response.",
                    email,
                )
                return True
            response = fallback_response
        app.logger.warning("Resend waitlist email failed: %s", response.text)
    except requests.RequestException as exc:
        app.logger.warning("Resend waitlist email error: %s", exc)
    return False


def send_waitlist_confirmation_via_smtp(email):
    smtp_user = os.environ.get("NXR_SMTP_USER", "").strip()
    smtp_pass = os.environ.get("NXR_SMTP_PASS", "").strip()
    smtp_host = os.environ.get("NXR_SMTP_HOST", "").strip()
    if not smtp_user or not smtp_pass or not smtp_host:
        return False

    smtp_port = int(os.environ.get("NXR_SMTP_PORT", "587"))
    from_email = os.environ.get("NXR_EMAIL_FROM", smtp_user).strip()

    message = EmailMessage()
    message["Subject"] = "You're on the NXR waitlist"
    message["From"] = from_email
    message["To"] = email
    message.set_content(
        "You're officially on the NXR waitlist.\n\n"
        "We'll email you as soon as private beta access expands.\n\n"
        "NXR Grading"
    )

    try:
        with smtplib.SMTP(smtp_host, smtp_port, timeout=10) as server:
            server.starttls()
            server.login(smtp_user, smtp_pass)
            server.send_message(message)
        return True
    except Exception as exc:  # noqa: BLE001
        app.logger.warning("SMTP waitlist email error: %s", exc)
        return False


load_env_file()
initialize_site_database()


def normalize_asset_path(asset_path, fallback=PLACEHOLDER_IMAGE):
    if not asset_path:
        return fallback

    asset_path = asset_path.strip()
    if asset_path.startswith(("http://", "https://", "/")):
        return asset_path
    if asset_path.startswith("static/"):
        return f"/{asset_path}"
    return f"{STATIC_PREFIX}{asset_path.lstrip('/')}"


def get_card(cert_id):
    lookup = (cert_id or "").strip()
    if not lookup:
        return None

    with get_db_connection() as conn:
        row = conn.execute(
            "SELECT * FROM cards WHERE cert_id = ? COLLATE NOCASE", (lookup,)
        ).fetchone()
        if row:
            card = dict(row)
            # Normalize image fields for both old and new templates.
            front_image = normalize_asset_path(card.get("front_image") or card.get("image"))
            back_image = normalize_asset_path(card.get("back_image"), fallback=front_image)

            card["front_image"] = front_image
            card["back_image"] = back_image
            card["front_img"] = front_image
            card["back_img"] = back_image
            return card
    return None


def build_ai_card_context(card, fallback_brand="", fallback_character=""):
    return {
        "cert_id": (card or {}).get("cert_id", ""),
        "card_name": (card or {}).get("card_name", fallback_character).strip(),
        "brand": (card or {}).get("brand", fallback_brand).strip(),
        "year": str((card or {}).get("year", "") or "").strip(),
        "set_name": ((card or {}).get("set_name", "") or "").strip(),
        "card_number": ((card or {}).get("card_number", "") or "").strip(),
        "variety": ((card or {}).get("variety", "") or "").strip(),
        "language": ((card or {}).get("language", "") or "").strip(),
        "grade": str((card or {}).get("grade", "") or (card or {}).get("final_grade_text", "") or "").strip(),
        "population": str((card or {}).get("pop", "") or "").strip(),
    }


def build_ai_character_prompt_hash(card_context, language):
    payload = {
        "version": AI_CHARACTER_PROMPT_VERSION,
        "language": language,
        "card_context": card_context,
    }
    return hashlib.sha256(json.dumps(payload, sort_keys=True).encode("utf-8")).hexdigest()


def get_cached_ai_character_info(cert_id, language, prompt_hash):
    if not cert_id:
        return None

    with get_db_connection() as conn:
        row = conn.execute(
            """
            SELECT content_json, rendered_html, model
            FROM ai_character_cache
            WHERE cert_id = ? AND language = ? AND prompt_hash = ?
            """,
            (cert_id, language, prompt_hash),
        ).fetchone()

    if not row:
        return None

    return {
        "payload": json.loads(row["content_json"]),
        "content": row["rendered_html"],
        "model": row["model"],
        "cached": True,
    }


def save_ai_character_cache(cert_id, language, prompt_hash, payload, rendered_html, model):
    if not cert_id:
        return

    with get_db_connection() as conn:
        conn.execute(
            """
            INSERT OR REPLACE INTO ai_character_cache
            (cert_id, language, prompt_hash, content_json, rendered_html, model)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (
                cert_id,
                language,
                prompt_hash,
                json.dumps(payload, ensure_ascii=False),
                rendered_html,
                model,
            ),
        )
        conn.commit()


def normalize_ai_character_key(value):
    normalized = re.sub(r"[^a-z0-9]+", " ", (value or "").strip().lower())
    return " ".join(normalized.split())


def get_ai_anime_context_hint(card_context):
    brand_key = normalize_ai_character_key(card_context.get("brand"))
    character_key = normalize_ai_character_key(card_context.get("card_name"))
    if not brand_key or not character_key:
        return ""

    brand_hints = AI_ANIME_CONTEXT_HINTS.get(brand_key, {})
    for hint_key, hint_text in brand_hints.items():
        if hint_key in character_key:
            return hint_text
    return ""


def build_ai_character_messages(card_context, language):
    language_name = AI_LANGUAGE_NAMES.get(language, AI_LANGUAGE_NAMES["en"])
    anime_hint = get_ai_anime_context_hint(card_context)
    system_prompt = (
        "You are a careful collectible card detail-page writer. "
        "Generate a concise role overview for the named character and write all prose in "
        f"{language_name}. "
        "Return exactly two short HTML paragraphs using <p> tags only. "
        "Do not return headings, lists, JSON, markdown, or source labels. "
        "In paragraph one, describe the character's appearance, most recognizable traits, "
        "and core abilities. In paragraph two, describe the character's typical role in the "
        "anime or narrative, and naturally blend anime setting details into the prose instead "
        "of separating them as a source note. Prefer scene, location, or story-setting context "
        "over episode-title lists unless the user explicitly gives a scene hint. "
        "Use only high-confidence, broadly known facts plus the supplied hint. If uncertain, "
        "omit the detail instead of inventing it. Avoid designers, rankings, prices, release years, "
        "and market commentary."
    )
    user_prompt = (
        "Write the character overview for this trading card detail page.\n"
        "The final text must be suitable for direct display inside a modal.\n"
        "Card context:\n"
        f"{json.dumps(card_context, ensure_ascii=False, indent=2)}\n"
    )
    if anime_hint:
        user_prompt += f"Known anime setting hint:\n{anime_hint}\n"
    user_prompt += (
        "Blend any reliable anime-setting detail naturally into paragraph two, not as a separate source line."
    )
    return [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt},
    ]


def sanitize_ai_character_html(text):
    raw = (text or "").strip()
    paragraph_matches = re.findall(r"<p\b[^>]*>(.*?)</p>", raw, flags=re.IGNORECASE | re.DOTALL)

    if paragraph_matches:
        paragraphs = paragraph_matches
    else:
        normalized = re.sub(r"<br\s*/?>", "\n", raw, flags=re.IGNORECASE)
        normalized = re.sub(r"</p\s*>", "\n\n", normalized, flags=re.IGNORECASE)
        normalized = re.sub(r"<[^>]+>", "", normalized)
        paragraphs = [part.strip() for part in re.split(r"\n\s*\n+", normalized) if part.strip()]

    cleaned = []
    for paragraph in paragraphs:
        text_only = re.sub(r"<[^>]+>", "", paragraph).strip()
        if text_only:
            cleaned.append(f"<p>{escape(text_only)}</p>")
        if len(cleaned) == 2:
            break

    if not cleaned:
        raise ValueError("Model did not return usable paragraph content")

    return "".join(cleaned)


def post_deepseek_chat_completions(payload):
    deepseek_key = os.environ.get("DEEPSEEK_API_KEY", "").strip()
    if not deepseek_key:
        raise RuntimeError("DeepSeek API key is not configured")

    base_url = os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com").rstrip("/")
    headers = {
        "Authorization": f"Bearer {deepseek_key}",
        "Content-Type": "application/json",
    }

    candidate_urls = [
        f"{base_url}/chat/completions",
        f"{base_url}/v1/chat/completions",
    ]

    last_response = None
    for url in candidate_urls:
        response = requests.post(url, headers=headers, json=payload, timeout=30)
        if response.status_code != 404:
            return response
        last_response = response

    return last_response


def generate_ai_character_payload(card_context, language):
    model = os.environ.get("DEEPSEEK_MODEL", "deepseek-chat").strip() or "deepseek-chat"
    payload = {
        "model": model,
        "messages": build_ai_character_messages(card_context, language),
        "temperature": 0.2,
        "max_tokens": 700,
    }

    response = post_deepseek_chat_completions(payload)
    if response is None:
        raise RuntimeError("DeepSeek API endpoint did not respond")
    if not response.ok:
        raise RuntimeError(f"DeepSeek API error {response.status_code}: {response.text[:300]}")

    body = response.json()
    raw_content = body["choices"][0]["message"]["content"]
    rendered_html = sanitize_ai_character_html(raw_content)
    return {"html": rendered_html, "raw_content": raw_content}, model


# ========== 主要页面路由 ==========

@app.route("/favicon.ico")
def favicon():
    return send_from_directory(
        PROJECT_ROOT,
        "favicon.ico",
        mimetype="image/vnd.microsoft.icon",
    )

@app.route("/")
def index():
    return render_template("index.html")


@app.route("/services")
def services():
    return render_template("services.html")


@app.route("/submit")
def submit():
    return render_template("submit.html")


@app.route("/api/waitlist_count")
def waitlist_count():
    return jsonify({"status": "ok", "count": get_waitlist_count()})


@app.route("/api/waitlist", methods=["POST"])
def join_waitlist():
    payload = request.get_json(silent=True) or {}
    email = normalize_email_address(payload.get("email", ""))

    if not email:
        return jsonify({"status": "error", "msg": "Email is required."}), 400

    if not is_valid_email(email):
        return jsonify({"status": "error", "msg": "Please enter a valid email address."}), 400

    try:
        with get_db_connection() as conn:
            conn.execute("INSERT INTO waitlist (email) VALUES (?)", (email,))
            conn.commit()
    except sqlite3.IntegrityError:
        return jsonify(
            {
                "status": "error",
                "msg": "This email is already on the waitlist.",
                "count": get_waitlist_count(),
            }
        ), 409

    queue_waitlist_confirmation(email)
    return jsonify(
        {
            "status": "ok",
            "email": email,
            "count": get_waitlist_count(),
            "email_queued": True,
        }
    )


@app.route("/about")
def about():
    return render_template("about.html")


@app.route("/card/<cert_id>")
def card_page(cert_id):
    card = get_card(cert_id)
    status_code = 200 if card else 404
    return render_template("card.html", card=card, cert_id=cert_id), status_code


@app.route("/verify", methods=["GET", "POST"])
def verify():
    if request.method == "POST":
        cert_id = request.form.get("cert_id", "").strip()
        if not cert_id:
            return render_template("verify.html", error="Please enter a Certificate ID")
        card = get_card(cert_id)
        if not card:
            return render_template("verify.html", error="Certificate ID not found")
        return redirect(f"/card/{cert_id}")
    return render_template("verify.html")


@app.route("/faq")
def faq():
    return render_template("faq.html")


@app.route("/query")
def query():
    return render_template("query.html")


@app.route("/upload")
def upload():
    return render_template("upload.html")


# ========== AI Character Information ==========

@app.route("/api/ai_character_info", methods=["POST"])
def ai_character_info():
    try:
        data = request.get_json(silent=True) or {}
        cert_id = (data.get("cert_id") or "").strip()
        brand = (data.get("brand") or "").strip()
        character = (data.get("character") or "").strip()
        language = (data.get("language") or "en").strip().lower()

        if language not in AI_LANGUAGE_NAMES:
            language = "en"

        card = get_card(cert_id) if cert_id else None
        card_context = build_ai_card_context(card, fallback_brand=brand, fallback_character=character)
        if not card_context["card_name"]:
            return jsonify({"status": "error", "msg": "Character name is required"}), 400

        prompt_hash = build_ai_character_prompt_hash(card_context, language)
        cached = get_cached_ai_character_info(card_context["cert_id"], language, prompt_hash)
        if cached:
            return jsonify(
                {
                    "status": "ok",
                    "content": cached["content"],
                    "character": card_context["card_name"],
                    "brand": card_context["brand"],
                    "language": language,
                    "model": cached["model"],
                    "cached": True,
                }
            )

        payload, model = generate_ai_character_payload(card_context, language)
        rendered_html = payload["html"]
        save_ai_character_cache(
            card_context["cert_id"],
            language,
            prompt_hash,
            payload,
            rendered_html,
            model,
        )

        return jsonify(
            {
                "status": "ok",
                "content": rendered_html,
                "character": card_context["card_name"],
                "brand": card_context["brand"],
                "language": language,
                "model": model,
                "cached": False,
            }
        )
    except Exception as exc:  # noqa: BLE001
        app.logger.warning("AI character info error: %s", exc)
        return jsonify(
            {
                "status": "error",
                "msg": "Unable to generate character information right now.",
            }
        ), 500


if __name__ == "__main__":
    app.run(
        host="0.0.0.0",
        port=int(os.environ.get("PORT", "8080")),
        debug=os.environ.get("FLASK_DEBUG") == "1",
        use_reloader=False,
    )
