#!/usr/bin/env bash

# Read-only deployment checks. No customer, ownership, order, waitlist, media,
# or business record is created or changed by this script.

set -euo pipefail

if [[ -n "${NXR_STAGE_BACKEND_URL:-}" ]]; then
  BACKEND_URL="$NXR_STAGE_BACKEND_URL"
elif [[ -s /var/lib/nxr-java-deploy/active-slot ]]; then
  ACTIVE_SLOT="$(tr -d '\r\n' < /var/lib/nxr-java-deploy/active-slot)"
  case "$ACTIVE_SLOT" in
    blue) BACKEND_URL="http://127.0.0.1:18088" ;;
    green) BACKEND_URL="http://127.0.0.1:18089" ;;
    *) printf 'Invalid Java active slot: %s\n' "$ACTIVE_SLOT" >&2; exit 1 ;;
  esac
else
  BACKEND_URL="http://127.0.0.1:18088"
fi
WEB_URL="${NXR_STAGE_WEB_URL:-http://127.0.0.1:18080}"
ADMIN_URL="${NXR_STAGE_ADMIN_URL:-http://127.0.0.1:18081}"

PASS=0
FAIL=0

check_contains() {
  local name="$1" expected="$2" actual="$3"
  if [[ "$actual" == *"$expected"* ]]; then
    printf 'PASS  %s\n' "$name"
    PASS=$((PASS + 1))
  else
    printf 'FAIL  %s expected [%s], got [%s]\n' "$name" "$expected" "$actual"
    FAIL=$((FAIL + 1))
  fi
}

check_contains "backend health" '"status":"ok"' "$(curl -fsS --max-time 10 "$BACKEND_URL/api/platform/health")"
check_contains "public overview" '"publishedCertificates"' "$(curl -fsS --max-time 10 "$BACKEND_URL/api/public/overview")"
check_contains "uppercase certificate" '"certId":"VRA003"' "$(curl -fsS --max-time 10 "$BACKEND_URL/api/public/cards/VRA003")"
check_contains "lowercase certificate" '"certId":"VRA003"' "$(curl -fsS --max-time 10 "$BACKEND_URL/api/public/cards/vra003")"
check_contains "missing certificate" '404' "$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$BACKEND_URL/api/public/cards/NXR-STAGE-MISSING")"
check_contains "anonymous admin blocked" '"code":401' "$(curl -fsS --max-time 10 "$BACKEND_URL/api/admin/dashboard")"
check_contains "web UI" '200' "$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$WEB_URL/")"
check_contains "admin UI" '200' "$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$ADMIN_URL/")"

printf '\nResult: %s passed, %s failed\n' "$PASS" "$FAIL"
[[ "$FAIL" == "0" ]]
