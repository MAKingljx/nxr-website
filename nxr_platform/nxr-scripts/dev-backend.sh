#!/usr/bin/env bash

# 启动若依后端（端口 8088）。
# 依赖：本地 MySQL（nxr_ruoyi 库）与 Redis 已启动（见 dev-up.sh）。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PLATFORM_ROOT/nxr-backend-ruoyi"

# Local-only defaults. Production uses /etc/nxr-java/stage.env and has no
# fallback database password or token secret.
export NXR_DB_PASSWORD="${NXR_DB_PASSWORD:-nxr_dev_password}"
export NXR_TOKEN_SECRET="${NXR_TOKEN_SECRET:-local-development-token-change-me}"

JAR="ruoyi-admin/target/ruoyi-admin.jar"
if [ ! -f "$JAR" ]; then
  echo "未找到 $JAR，先执行打包..."
  mvn -q -T 1C install -DskipTests
fi

exec java -jar "$JAR"
