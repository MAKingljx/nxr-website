#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_TARGET="${DEPLOY_TARGET:-root@147.182.183.201}"
DEPLOY_ROOT="${DEPLOY_ROOT:-/root/nxr_website}"

ssh "$DEPLOY_TARGET" "set -euo pipefail
cd '$DEPLOY_ROOT'

pkill -f '/usr/bin/python3 app.py' || true
pkill -f '/usr/bin/python3 $DEPLOY_ROOT/nxr_admin/app_updated.py' || true
pkill -f 'nxr_admin/app_updated.py' || true

nohup /usr/bin/python3 app.py >'$DEPLOY_ROOT/output.log' 2>&1 </dev/null &
nohup /usr/bin/python3 '$DEPLOY_ROOT/nxr_admin/app_updated.py' >'$DEPLOY_ROOT/nxr_admin/output.log' 2>&1 </dev/null &

sleep 3

curl -fsSI http://127.0.0.1:8080/ >/dev/null
curl -fsSI http://127.0.0.1:8081/admin/login >/dev/null

echo 'site: http://127.0.0.1:8080'
echo 'admin: http://127.0.0.1:8081/admin/login'
pgrep -af '/usr/bin/python3 app.py|app_updated.py' || true
"
