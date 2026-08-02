#!/usr/bin/env bash

# 启动若依管理前端（端口 3001）。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PLATFORM_ROOT/nxr-frontend-admin-ruoyi"
npm run dev
