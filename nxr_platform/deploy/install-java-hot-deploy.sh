#!/usr/bin/env bash

# Install or refresh the blue/green runtime without stopping the active Java
# process. The bootstrap step renders an equivalent Nginx configuration and
# verifies it before a graceful reload.

set -Eeuo pipefail
umask 027

DEPLOY_SOURCE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_ROOT="${NXR_JAVA_ROOT:-/opt/nxr-java}"
CURRENT_LINK="${NXR_JAVA_CURRENT_LINK:-${JAVA_ROOT}/current}"
CONFIG_ROOT="${NXR_JAVA_CONFIG_ROOT:-/etc/nxr-java}"
STATE_ROOT="${NXR_HOT_DEPLOY_STATE_ROOT:-/var/lib/nxr-java-deploy}"
SYSTEMD_ROOT="${NXR_SYSTEMD_ROOT:-/etc/systemd/system}"
HOT_DEPLOY_TARGET="${NXR_HOT_DEPLOY_TARGET:-/usr/local/sbin/nxr-java-hot-deploy}"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

[[ "${EUID}" -eq 0 ]] || die "This installer must run as root."
[[ -s "$CONFIG_ROOT/stage.env" ]] || die "Missing shared Java environment: $CONFIG_ROOT/stage.env"
[[ -L "$CURRENT_LINK" ]] || die "Missing active Java release link: $CURRENT_LINK"

current_release="$(readlink -f "$CURRENT_LINK")"
[[ -d "$current_release" ]] || die "Current Java release cannot be resolved."
case "$current_release" in
  "${JAVA_ROOT}/releases/"*) ;;
  *) die "Current release is outside ${JAVA_ROOT}/releases: $current_release" ;;
esac

install -d -m 0755 "$JAVA_ROOT/releases" "$JAVA_ROOT/slots" "$CONFIG_ROOT/slots"
install -d -m 0700 "$STATE_ROOT" "$STATE_ROOT/backups"
install -d -m 0750 -o nxr-java -g nxr-java /var/log/nxr-java/blue /var/log/nxr-java/green
install -d -m 0750 -o nxr-java -g nxr-java /var/lib/nxr-java/python-uploads

install -m 0644 "$DEPLOY_SOURCE/nxr-java-stage@.service" \
  "$SYSTEMD_ROOT/nxr-java-stage@.service"
install -m 0644 "$DEPLOY_SOURCE/nginx-java-stage.conf.template" \
  "$CONFIG_ROOT/nginx-java-stage.conf.template"
install -m 0644 "$DEPLOY_SOURCE/nxr-java-slot-blue.env" \
  "$CONFIG_ROOT/slots/blue.env"
install -m 0644 "$DEPLOY_SOURCE/nxr-java-slot-green.env" \
  "$CONFIG_ROOT/slots/green.env"
install -m 0750 "$DEPLOY_SOURCE/hot-deploy-java.sh" "$HOT_DEPLOY_TARGET"

systemctl daemon-reload
"$HOT_DEPLOY_TARGET" --bootstrap "$current_release"

printf 'Java hot-deploy runtime installed. Future releases: %s RELEASE_ID\n' \
  "$HOT_DEPLOY_TARGET"
