#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_TARGET="${DEPLOY_TARGET:-root@147.182.183.201}"
DEPLOY_ROOT="${DEPLOY_ROOT:-/root/nxr_website}"

rsync -avz --delete \
  --exclude '.git/' \
  --exclude '.playwright-cli/' \
  --exclude '.phoenix/' \
  --exclude '.env' \
  --exclude '.env.*' \
  --exclude '__pycache__/' \
  --exclude '*.pyc' \
  --exclude '*.log' \
  --exclude 'output.log' \
  --exclude 'venv/' \
  --exclude 'nxr_admin/uploads/' \
  --exclude 'nxr_admin/exports/' \
  --exclude 'slabs photo/' \
  "$PROJECT_ROOT/" \
  "$DEPLOY_TARGET:$DEPLOY_ROOT/"
