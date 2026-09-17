#!/usr/bin/env python3
"""Create a new, disposable local agent-workbench acceptance database.

Never upgrades an existing database and never imports Python business data.
"""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import subprocess

platform = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--database', required=True)
parser.add_argument('--apply', action='store_true')
args = parser.parse_args()
if not re.fullmatch(r'nxr_acceptance_agent_[a-zA-Z0-9_]+', args.database):
    raise SystemExit('Only a new nxr_acceptance_agent_* database is allowed.')
files = [platform / 'nxr-backend-ruoyi/sql/ry_20260417.sql', platform / 'nxr-backend-ruoyi/sql/quartz.sql']
files += [p for p in sorted((platform / 'nxr-sql/ruoyi').glob('*.sql')) if int(p.name[:2]) in [1, 2, 3, 4, 5, *range(10, 27)]]
if not all(any(p.name.startswith(prefix) for p in files) for prefix in ('23_', '24_', '25_', '26_')):
    raise SystemExit('Agent workbench migrations 23 through 26 must exist first.')
for file in files:
    if re.search(r'^\s*(?:USE\s|CREATE\s+DATABASE|DROP\s+DATABASE|GRANT\s|CREATE\s+USER)', file.read_text(), re.I | re.M):
        raise SystemExit(f'Cross-database SQL is not accepted: {file.name}')
print(json.dumps({'database': args.database, 'migrations': [p.name for p in files], 'apply': args.apply}))
if not args.apply:
    raise SystemExit(0)
mysql = ['mysql', '-uroot', '--batch', '--skip-column-names']
existing = subprocess.check_output(mysql + ['-e', 'SHOW DATABASES'], text=True).splitlines()
if args.database in existing:
    raise SystemExit('Database already exists; choose a new disposable name. No changes made.')
subprocess.run(mysql + ['-e', f'CREATE DATABASE `{args.database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci'], check=True)
for file in files:
    sql = file.read_bytes()
    if file.name.startswith('13_'):
        sql = b'SET @nxr_seed_development_prices = 1;\n' + sql
    subprocess.run(mysql + [args.database], input=sql, check=True, stdout=subprocess.DEVNULL)
marker = 'QA acceptance agent ' + secrets.token_hex(12)
subprocess.run(mysql + [args.database], input=(
    "UPDATE sys_config SET config_value='false' WHERE config_key='sys.account.captchaEnabled';\n"
    f"UPDATE grading_service_price SET display_name='{marker}' WHERE price_code='basic_grading' AND currency_code='USD';\n"
).encode(), check=True)
context = Path('/tmp') / f'{args.database}-context.json'
context.write_text(json.dumps({'database': args.database, 'priceMarker': marker, 'baseUrl': 'http://127.0.0.1:8090'}, indent=2) + '\n')
os.chmod(context, 0o600)
print(f'Prepared disposable database; context: {context}')
