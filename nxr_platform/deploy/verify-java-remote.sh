#!/usr/bin/env bash

# Read-only HTTPS checks for the parallel Java entry points. This script does
# not log in or create, update, or delete any business record.

set -euo pipefail

PUBLIC_ORIGIN="${NXR_PUBLIC_ORIGIN:-https://nxrgrading.com}"
WEB_URL="${NXR_REMOTE_WEB_URL:-${PUBLIC_ORIGIN}/java-stage}"
ADMIN_URL="${NXR_REMOTE_ADMIN_URL:-${PUBLIC_ORIGIN}/java-stage-admin}"

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

check_contains "web redirect" '308' "$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$WEB_URL")"
check_contains "admin redirect" '308' "$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$ADMIN_URL")"
check_contains "web UI" '200' "$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$WEB_URL/")"
check_contains "web SPA route" '200' "$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$WEB_URL/verify")"
check_contains "admin UI" '200' "$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$ADMIN_URL/")"
check_contains "backend health" '"status":"ok"' "$(curl -fsS --max-time 10 "$WEB_URL/api/platform/health")"
check_contains "public overview" '"publishedCertificates"' "$(curl -fsS --max-time 10 "$WEB_URL/api/public/overview")"
check_contains "uppercase certificate" '"certId":"VRA003"' "$(curl -fsS --max-time 10 "$WEB_URL/api/public/cards/VRA003")"
check_contains "lowercase certificate" '"certId":"VRA003"' "$(curl -fsS --max-time 10 "$WEB_URL/api/public/cards/vra003")"
check_contains "missing certificate" '404' "$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 "$WEB_URL/api/public/cards/NXR-STAGE-MISSING")"
check_contains "anonymous admin blocked" '"code":401' "$(curl -fsS --max-time 10 "$ADMIN_URL/prod-api/api/admin/dashboard")"

printf '\nResult: %s passed, %s failed\n' "$PASS" "$FAIL"
[[ "$FAIL" == "0" ]]
