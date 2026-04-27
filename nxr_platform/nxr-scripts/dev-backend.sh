#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PLATFORM_ROOT/nxr-backend-java"
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
