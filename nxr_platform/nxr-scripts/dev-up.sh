#!/usr/bin/env bash

# 启动本地基础设施（免 Docker）：Homebrew MySQL + Redis。
# 如需 Docker 方式，可改用 `docker compose up -d`（见 ../docker-compose.yml）。

set -euo pipefail

brew services start mysql
brew services start redis

echo "等待 MySQL/Redis 就绪..."
for i in $(seq 1 15); do
  if mysqladmin -uroot status >/dev/null 2>&1 && redis-cli ping >/dev/null 2>&1; then
    echo "MySQL + Redis 已就绪"
    exit 0
  fi
  sleep 2
done

echo "基础设施未在预期时间内就绪，请检查 brew services list" >&2
exit 1
