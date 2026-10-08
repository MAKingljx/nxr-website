#!/usr/bin/env bash
# Install a verified backup-tool release; never restarts Java/Flask/MySQL or runs a backup.
set -Eeuo pipefail
umask 077
[[ "$EUID" -eq 0 && "$(uname -s)" == Linux ]] || { echo 'Run this installer as root on the Linux server.' >&2; exit 1; }
[[ $# -eq 1 ]] || { echo 'Usage: install-mysql-r2-backup.sh VERIFIED_RELEASE_DIRECTORY' >&2; exit 2; }
release="$(realpath -- "$1")"
[[ ! -L "$1" && "$release" == /opt/nxr-mysql-backup/releases/* ]] || { echo 'Release must be a real directory under the dedicated backup release root.' >&2; exit 1; }
[[ -s "$release/RELEASE_COMMIT" && -s "$release/SHA256SUMS" ]] || { echo 'Release identity and checksums are required.' >&2; exit 1; }
commit="$(tr -d '\r\n' < "$release/RELEASE_COMMIT")"
[[ "$commit" =~ ^[0-9a-f]{40}$ && "$(basename "$release")" == "$commit" ]] || { echo 'Release directory and commit must match.' >&2; exit 1; }
(cd "$release" && sha256sum --check --strict --quiet SHA256SUMS)
for file in mysql_r2_backup.py wait_for_python_java_sync.py nxr-mysql-r2-backup.service nxr-mysql-r2-backup.timer; do
  [[ -f "$release/$file" && ! -L "$release/$file" ]] || { echo "Required release member missing: $file" >&2; exit 1; }
done
for tool in python3 mysql mysqldump age flock; do command -v "$tool" >/dev/null || { echo "Required tool missing: $tool" >&2; exit 1; }; done
python3 -c 'import boto3'
[[ -f /etc/nxr-java/mysql-backup.env && ! -L /etc/nxr-java/mysql-backup.env ]] || { echo 'Prepare the private backup configuration first.' >&2; exit 1; }
[[ "$(stat -c '%a:%u' /etc/nxr-java/mysql-backup.env)" == 600:0 ]] || { echo 'Backup configuration must be root-owned with mode0600.' >&2; exit 1; }
install -d -m 0700 /var/lib/nxr-mysql-backup /var/lib/nxr-mysql-backup/work /var/lib/nxr-mysql-backup/state
python3 "$release/mysql_r2_backup.py" --env-file /etc/nxr-java/mysql-backup.env preflight
systemd-analyze verify "$release/nxr-mysql-r2-backup.service" "$release/nxr-mysql-r2-backup.timer"
install -m 0644 "$release/nxr-mysql-r2-backup.service" /etc/systemd/system/nxr-mysql-r2-backup.service
install -m 0644 "$release/nxr-mysql-r2-backup.timer" /etc/systemd/system/nxr-mysql-r2-backup.timer
cat > /usr/local/sbin/nxr-mysql-r2-backup.new <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
exec /usr/bin/python3 /opt/nxr-mysql-backup/current/mysql_r2_backup.py --env-file /etc/nxr-java/mysql-backup.env "$@"
EOF
chmod 0755 /usr/local/sbin/nxr-mysql-r2-backup.new
mv /usr/local/sbin/nxr-mysql-r2-backup.new /usr/local/sbin/nxr-mysql-r2-backup
ln -s "$release" /opt/nxr-mysql-backup/current.new
mv -T /opt/nxr-mysql-backup/current.new /opt/nxr-mysql-backup/current
systemctl daemon-reload
printf 'Backup tool installed: %s\nThe installer does not enable the timer. Check its actual state before the first real backup and recovery check.\n' "$commit"
