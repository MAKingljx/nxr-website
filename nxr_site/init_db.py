import sqlite3
from pathlib import Path

SITE_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SITE_DIR.parent
DB_PATH = PROJECT_ROOT / "cards.db"

conn = sqlite3.connect(DB_PATH)
c = conn.cursor()

c.execute('''
CREATE TABLE IF NOT EXISTS cards (
    cert_id TEXT PRIMARY KEY,
    card_name TEXT,
    grade TEXT,
    year TEXT,
    brand TEXT,
    player TEXT,
    variety TEXT,
    image TEXT,
    pop TEXT
)
''')

# 插入测试数据（你以后可以批量加）
c.execute('''
INSERT OR IGNORE INTO cards VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
''', (
    "VRA002",
    "Charizard VMAX",
    "9.9",
    "2023",
    "Pokemon",
    "Charizard",
    "Holo Rare",
    "/static/charizard.jpg",
    "1200"
))

conn.commit()
conn.close()
print("✅ 数据库创建完成")
