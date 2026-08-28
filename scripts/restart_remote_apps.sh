#!/usr/bin/env bash

set -euo pipefail

DEPLOY_TARGET="${DEPLOY_TARGET:-root@147.182.183.201}"
DEPLOY_ROOT="${DEPLOY_ROOT:-/root/nxr_website}"
APP_SCOPE="${APP_SCOPE:-${1:-all}}"

case "$APP_SCOPE" in
    admin|site|all) ;;
    *)
        echo "Usage: APP_SCOPE=admin|site|all $0" >&2
        exit 2
        ;;
esac

ssh "$DEPLOY_TARGET" bash -s -- "$DEPLOY_ROOT" "$APP_SCOPE" <<'REMOTE'
set -euo pipefail

deploy_root="$1"
app_scope="$2"
cd "$deploy_root"

restart_and_verify() {
    local service="$1"
    local port="$2"
    local health_url="$3"

    systemctl restart "$service"

    local attempt
    for attempt in $(seq 1 20); do
        if systemctl is-active --quiet "$service" && curl -fsS "$health_url" >/dev/null; then
            break
        fi
        sleep 1
    done

    systemctl is-active --quiet "$service"
    curl -fsS "$health_url" >/dev/null

    local main_pid listener_pid
    main_pid="$(systemctl show --property MainPID --value "$service")"
    listener_pid="$(ss -ltnp "sport = :$port" 2>/dev/null | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' | head -1)"
    if [[ -z "$main_pid" || "$main_pid" == "0" || "$listener_pid" != "$main_pid" ]]; then
        echo "$service does not own port $port (service pid=$main_pid, listener pid=${listener_pid:-none})." >&2
        exit 3
    fi

    if [[ "$service" == "nxr-admin.service" ]] &&
       ! tr '\0' '\n' < "/proc/$main_pid/environ" | grep -qx 'NXR_STORAGE_DRIVER=r2'; then
        echo "nxr-admin.service started without NXR_STORAGE_DRIVER=r2." >&2
        exit 4
    fi

    echo "$service ready on $health_url (pid=$main_pid)"
}

if [[ "$app_scope" == "site" || "$app_scope" == "all" ]]; then
    restart_and_verify nxr-site.service 8080 http://127.0.0.1:8080/
fi
if [[ "$app_scope" == "admin" || "$app_scope" == "all" ]]; then
    restart_and_verify nxr-admin.service 8081 http://127.0.0.1:8081/admin/login
fi
REMOTE
