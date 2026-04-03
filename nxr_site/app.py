import os
import sqlite3
from pathlib import Path
from flask import Flask, render_template, request, jsonify, redirect, send_from_directory

SITE_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SITE_DIR.parent
DATA_DIR = PROJECT_ROOT / "Data"
DATA_DIR.mkdir(exist_ok=True)
DB_PATH = DATA_DIR / "cards.db"
STATIC_PREFIX = "/static/"
PLACEHOLDER_IMAGE = f"{STATIC_PREFIX}placeholder.png"

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


# ========== AI角色信息API - 修复多语言 ==========

@app.route("/api/ai_character_info", methods=["POST"])
def ai_character_info():
    """获取AI角色信息 - 修复多语言问题"""
    try:
        data = request.json
        brand = data.get("brand", "").strip()
        character = data.get("character", "").strip()
        language = data.get("language", "en")
        
        if not character:
            return jsonify({"status": "error", "msg": "Character name is required"}), 400
        
        # 语言名称映射
        language_names = {
            "en": "English",
            "zh": "中文",
            "es": "Español",
            "fr": "Français", 
            "de": "Deutsch",
            "ja": "日本語",
            "ko": "한국어"
        }
        
        language_name = language_names.get(language, "English")
        
        # 宝可梦品牌的详细内容
        brand_lower = brand.lower()
        if "pokemon" in brand_lower or "poke" in brand_lower:
            # 为每种语言提供不同的内容
            if language == "zh":  # 中文
                content = f"""
                <h3>{character} - 中文信息</h3>
                <p>语言: <strong>中文</strong></p>
                
                <h3>首次出现</h3>
                <p><strong>{character}</strong> 首次出现在<strong>第一世代</strong>（1996年）。</p>
                
                <h3>设计起源</h3>
                <p>由<strong>杉森建</strong>设计。</p>
                
                <h3>关键特征</h3>
                <ul>
                    <li><strong>属性：</strong>水</li>
                    <li><strong>进化：</strong>{character} → 哥达鸭</li>
                    <li><strong>特性：</strong>湿气</li>
                </ul>
                
                <h3>人气</h3>
                <p>在2020年投票中排名第53位。</p>
                
                <h3>收藏价值</h3>
                <p>首次出现在基础系列（1999年）。</p>
                """
            elif language == "es":  # 西班牙语
                content = f"""
                <h3>{character} - Información en Español</h3>
                <p>Idioma: <strong>Español</strong></p>
                
                <h3>Primera Aparición</h3>
                <p><strong>{character}</strong> apareció en <strong>Generación I</strong> (1996).</p>
                
                <h3>Diseño</h3>
                <p>Diseñado por <strong>Ken Sugimori</strong>.</p>
                
                <h3>Características</h3>
                <ul>
                    <li><strong>Tipo:</strong> Agua</li>
                    <li><strong>Evolución:</strong> {character} → Golduck</li>
                    <li><strong>Habilidad:</strong> Humedad</li>
                </ul>
                
                <h3>Popularidad</h3>
                <p>Clasificado #53 en 2020.</p>
                """
            elif language == "fr":  # 法语
                content = f"""
                <h3>{character} - Informations en Français</h3>
                <p>Langue: <strong>Français</strong></p>
                
                <h3>Première Apparition</h3>
                <p><strong>{character}</strong> est apparu dans <strong>Génération I</strong> (1996).</p>
                
                <h3>Design</h3>
                <p>Conçu par <strong>Ken Sugimori</strong>.</p>
                
                <h3>Caractéristiques</h3>
                <ul>
                    <li><strong>Type :</strong> Eau</li>
                    <li><strong>Évolution :</strong> {character} → Akwakwak</li>
                    <li><strong>Talent :</strong> Moiteur</li>
                </ul>
                
                <h3>Popularité</h3>
                <p>Classé #53 en 2020.</p>
                """
            elif language == "de":  # 德语
                content = f"""
                <h3>{character} - Informationen auf Deutsch</h3>
                <p>Sprache: <strong>Deutsch</strong></p>
                
                <h3>Erstes Erscheinen</h3>
                <p><strong>{character}</strong> erschien in <strong>Generation I</strong> (1996).</p>
                
                <h3>Design</h3>
                <p>Entworfen von <strong>Ken Sugimori</strong>.</p>
                
                <h3>Merkmale</h3>
                <ul>
                    <li><strong>Typ:</strong> Wasser</li>
                    <li><strong>Entwicklung:</strong> {character} → Entoron</li>
                    <li><strong>Fähigkeit:</strong> Feuchtigkeit</li>
                </ul>
                
                <h3>Beliebtheit</h3>
                <p>Platz #53 in 2020.</p>
                """
            elif language == "ja":  # 日语
                content = f"""
                <h3>{character} - 日本語情報</h3>
                <p>言語: <strong>日本語</strong></p>
                
                <h3>初登場</h3>
                <p><strong>{character}</strong>は<strong>第一世代</strong>（1996年）で初登場。</p>
                
                <h3>デザイン</h3>
                <p><strong>杉森建</strong>によってデザイン。</p>
                
                <h3>特徴</h3>
                <ul>
                    <li><strong>タイプ：</strong>みず</li>
                    <li><strong>進化：</strong>{character} → ゴルダック</li>
                    <li><strong>とくせい：</strong>しめりけ</li>
                </ul>
                
                <h3>人気</h3>
                <p>2020年で53位。</p>
                """
            elif language == "ko":  # 韩语
                content = f"""
                <h3>{character} - 한국어 정보</h3>
                <p>언어: <strong>한국어</strong></p>
                
                <h3>첫 등장</h3>
                <p><strong>{character}</strong>는 <strong>1세대</strong> (1996년) 첫 등장.</p>
                
                <h3>디자인</h3>
                <p><strong>Ken Sugimori</strong>가 디자인.</p>
                
                <h3>특징</h3>
                <ul>
                    <li><strong>타입:</strong> 물</li>
                    <li><strong>진화:</strong> {character} → 골덕</li>
                    <li><strong>특성:</strong> 습기</li>
                </ul>
                
                <h3>인기</h3>
                <p>2020년 53위.</p>
                """
            else:  # 英语 (默认)
                content = f"""
                <h3>{character} - English Information</h3>
                <p>Language: <strong>English</strong></p>
                
                <h3>First Appearance</h3>
                <p><strong>{character}</strong> first appeared in <strong>Generation I</strong> (1996).</p>
                
                <h3>Design Origin</h3>
                <p>Designed by <strong>Ken Sugimori</strong>.</p>
                
                <h3>Key Characteristics</h3>
                <ul>
                    <li><strong>Type:</strong> Water</li>
                    <li><strong>Evolution:</strong> {character} → Golduck</li>
                    <li><strong>Ability:</strong> Damp</li>
                </ul>
                
                <h3>Popularity</h3>
                <p>Ranked #53 in the 2020 Pokémon poll.</p>
                
                <h3>Collector Value</h3>
                <p>First appeared in the Base Set (1999).</p>
                """
        else:
            # 非宝可梦品牌
            content = f"""
            <h3>{character} Information</h3>
            <p>Language: <strong>{language_name}</strong></p>
            <p>This character is from the <strong>{brand}</strong> franchise.</p>
            <p>Sample content in {language_name}.</p>
            """
        
        return jsonify({
            "status": "ok",
            "content": content,
            "character": character,
            "brand": brand,
            "language": language
        })
        
    except Exception as e:
        print(f"AI API error: {e}")
        return jsonify({
            "status": "error",
            "msg": f"Internal server error: {str(e)}"
        }), 500


if __name__ == "__main__":
    app.run(
        host="0.0.0.0",
        port=int(os.environ.get("PORT", "8080")),
        debug=os.environ.get("FLASK_DEBUG") == "1",
        use_reloader=False,
    )
