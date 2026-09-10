#!/usr/bin/env bash
# Disposable commerce acceptance runtime; never point this at a business database.
set -euo pipefail
if [[ ! "${NXR_QA_DATABASE:-}" =~ ^nxr_acceptance_[a-zA-Z0-9_]+$ ]]; then
  echo "Set NXR_QA_DATABASE to a prepared nxr_acceptance_* disposable database." >&2
  exit 1
fi
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/../nxr-backend-ruoyi"
export SERVER_ADDRESS=127.0.0.1 SERVER_PORT=8090 SPRING_PROFILES_ACTIVE=druid
export SPRING_DATA_REDIS_HOST=127.0.0.1 SPRING_DATA_REDIS_DATABASE=14
export SPRING_QUARTZ_AUTO_STARTUP=false
export NXR_DB_USERNAME="${NXR_QA_DB_USERNAME:-root}" NXR_DB_PASSWORD="${NXR_QA_DB_PASSWORD:-}"
export NXR_DB_URL="jdbc:mysql://127.0.0.1:3306/${NXR_QA_DATABASE}?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=GMT%2B8"
QA_RUNTIME_ROOT="/tmp/${NXR_QA_DATABASE}"
mkdir -p "$QA_RUNTIME_ROOT/media" "$QA_RUNTIME_ROOT/exports"
export NXR_STORAGE_DRIVER=local NXR_MEDIA_STORAGE_ROOT="$QA_RUNTIME_ROOT/media" NXR_EXPORTS_STORAGE_ROOT="$QA_RUNTIME_ROOT/exports"
export NXR_PUBLIC_SITE_BASE_URL=http://127.0.0.1:3002
export NXR_CORS_ALLOWED_ORIGINS=http://127.0.0.1:3002,http://127.0.0.1:3003,http://localhost:3002,http://localhost:3003
export NXR_TOKEN_SECRET=local-qa-commerce-isolation-20260908
export NXR_NOTIFICATION_DELIVERY_ENABLED=false NXR_PAYMENT_CALLBACK_TOKEN=
export NXR_RESEND_KEY= NXR_SMTP_HOST=
export LOGGING_LEVEL_COM_RUOYI=INFO
LOCAL_PAYMENT_KEY="${XDG_CONFIG_HOME:-$HOME/.config}/nxr-local/payment-master.key"
if [ -f "$LOCAL_PAYMENT_KEY" ]; then export NXR_PAYMENT_MASTER_KEY_FILE="$LOCAL_PAYMENT_KEY"; fi
exec java -Xmx512m -jar "${NXR_QA_JAR_FILE:-ruoyi-admin/target/ruoyi-admin.jar}"
