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

# Local payment credentials use an OS-private key kept outside the repository.
# Never replace a configured key: existing encrypted settings depend on it.
LOCAL_PAYMENT_KEY="${XDG_CONFIG_HOME:-$HOME/.config}/nxr-local/payment-master.key"
if [ -z "${NXR_PAYMENT_MASTER_KEY:-}" ] && [ -z "${NXR_PAYMENT_MASTER_KEY_FILE:-}" ] && [ -f "$LOCAL_PAYMENT_KEY" ]; then
  export NXR_PAYMENT_MASTER_KEY_FILE="$LOCAL_PAYMENT_KEY"
fi
# Customer emails remain opt-in while the mailbox/provider setup is pending.
export NXR_NOTIFICATION_DELIVERY_ENABLED="${NXR_NOTIFICATION_DELIVERY_ENABLED:-false}"

# Keep the Druid console opt-in outside production, but make the local
# "数据监控" menu usable. The servlet remains loopback-only and protected by
# its own credentials; every value can still be overridden by the caller.
export NXR_DRUID_WEB_STATS_ENABLED="${NXR_DRUID_WEB_STATS_ENABLED:-true}"
export NXR_DRUID_CONSOLE_ENABLED="${NXR_DRUID_CONSOLE_ENABLED:-true}"
export NXR_DRUID_CONSOLE_ALLOW="${NXR_DRUID_CONSOLE_ALLOW:-127.0.0.1}"
export NXR_DRUID_CONSOLE_USERNAME="${NXR_DRUID_CONSOLE_USERNAME:-nxr-monitor}"
export NXR_DRUID_CONSOLE_PASSWORD="${NXR_DRUID_CONSOLE_PASSWORD:-nxr-local-monitor}"

JAR="ruoyi-admin/target/ruoyi-admin.jar"
if [ ! -f "$JAR" ]; then
  echo "未找到 $JAR，先执行打包..."
  mvn -q -T 1C install -DskipTests
fi

exec java -jar "$JAR"
