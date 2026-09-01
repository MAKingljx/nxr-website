#!/usr/bin/env bash

# Zero-downtime Java stage deployment.
#
# Static-only releases are switched by one graceful Nginx reload. Backend
# releases start the inactive blue/green slot, pass read-only health checks,
# switch Nginx, drain the old slot, and only then stop it.

set -Eeuo pipefail
umask 027

JAVA_ROOT="${NXR_JAVA_ROOT:-/opt/nxr-java}"
RELEASES_ROOT="${NXR_JAVA_RELEASES_ROOT:-${JAVA_ROOT}/releases}"
SLOTS_ROOT="${NXR_JAVA_SLOTS_ROOT:-${JAVA_ROOT}/slots}"
CURRENT_LINK="${NXR_JAVA_CURRENT_LINK:-${JAVA_ROOT}/current}"
STATE_ROOT="${NXR_HOT_DEPLOY_STATE_ROOT:-/var/lib/nxr-java-deploy}"
BACKUP_ROOT="${NXR_HOT_DEPLOY_BACKUP_ROOT:-${STATE_ROOT}/backups}"
CONFIG_ROOT="${NXR_JAVA_CONFIG_ROOT:-/etc/nxr-java}"
NGINX_TEMPLATE="${NXR_HOT_DEPLOY_NGINX_TEMPLATE:-${CONFIG_ROOT}/nginx-java-stage.conf.template}"
NGINX_ACTIVE_CONF="${NXR_HOT_DEPLOY_NGINX_CONF:-/etc/nginx/conf.d/nxr-java-stage.conf}"
LOCK_FILE="${NXR_HOT_DEPLOY_LOCK_FILE:-/run/lock/nxr-java-hot-deploy.lock}"

BLUE_PORT="${NXR_HOT_DEPLOY_BLUE_PORT:-18088}"
GREEN_PORT="${NXR_HOT_DEPLOY_GREEN_PORT:-18089}"
HEALTH_TIMEOUT="${NXR_HOT_DEPLOY_HEALTH_TIMEOUT:-90}"
DRAIN_TIMEOUT="${NXR_HOT_DEPLOY_DRAIN_TIMEOUT:-300}"
MIN_HEADROOM_KB="${NXR_HOT_DEPLOY_MIN_HEADROOM_KB:-786432}"
JAVA_DATABASE_OVERRIDE="${NXR_HOT_DEPLOY_DATABASE:-}"

SYSTEMCTL_BIN="${NXR_HOT_DEPLOY_SYSTEMCTL_BIN:-systemctl}"
NGINX_BIN="${NXR_HOT_DEPLOY_NGINX_BIN:-nginx}"
CURL_BIN="${NXR_HOT_DEPLOY_CURL_BIN:-curl}"
MYSQL_BIN="${NXR_HOT_DEPLOY_MYSQL_BIN:-mysql}"
SS_BIN="${NXR_HOT_DEPLOY_SS_BIN:-ss}"

VERIFIED_RELEASE=""
ACTIVE_CONFIG_BACKUP=""
ACTIVE_CONFIG_HAD_PREVIOUS=0

usage() {
  cat <<'EOF'
Usage:
  hot-deploy-java.sh RELEASE_ID_OR_PATH
  hot-deploy-java.sh --bootstrap RELEASE_ID_OR_PATH
  hot-deploy-java.sh --verify-release RELEASE_ID_OR_PATH

The default command performs an atomic static switch when the backend source
is unchanged, otherwise a blue/green backend deployment. --bootstrap installs
the initial runtime state without stopping the currently running legacy unit.
EOF
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

require_root() {
  [[ "${EUID}" -eq 0 ]] || die "This operation must run as root."
}

validate_number() {
  local name="$1" value="$2"
  [[ "$value" =~ ^[0-9]+$ ]] || die "$name must be an unsigned integer."
}

slot_port() {
  case "$1" in
    blue) printf '%s\n' "$BLUE_PORT" ;;
    green) printf '%s\n' "$GREEN_PORT" ;;
    *) die "Invalid deployment slot: $1" ;;
  esac
}

other_slot() {
  case "$1" in
    blue) printf 'green\n' ;;
    green) printf 'blue\n' ;;
    *) die "Invalid deployment slot: $1" ;;
  esac
}

slot_unit() {
  printf 'nxr-java-stage@%s.service\n' "$1"
}

read_state_value() {
  local path="$1"
  [[ -s "$path" ]] || return 1
  tr -d '\r\n' < "$path"
}

checksum_file() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$path" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$path" | awk '{print $1}'
  else
    die "Neither sha256sum nor shasum is available."
  fi
}

verify_manifest_checksums() {
  local release="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$release" && sha256sum --check --strict --quiet SHA256SUMS)
  elif command -v shasum >/dev/null 2>&1; then
    (cd "$release" && shasum -a 256 -c SHA256SUMS >/dev/null)
  else
    die "Neither sha256sum nor shasum is available."
  fi
}

resolve_and_verify_release() {
  local requested="$1" candidate resolved releases_resolved invalid_line duplicate_path
  local actual_count listed_count whitespace_path writable_path symlink_path

  if [[ "$requested" == */* ]]; then
    candidate="$requested"
  else
    candidate="${RELEASES_ROOT}/${requested}"
  fi

  [[ -d "$RELEASES_ROOT" ]] || die "Release root does not exist: $RELEASES_ROOT"
  releases_resolved="$(cd "$RELEASES_ROOT" && pwd -P)"
  [[ -d "$candidate" ]] || die "Release directory does not exist: $candidate"
  resolved="$(cd "$candidate" && pwd -P)"
  case "$resolved" in
    "${releases_resolved}/"*) ;;
    *) die "Release must be inside ${releases_resolved}: $resolved" ;;
  esac

  [[ -f "$resolved/ruoyi-admin.jar" ]] || die "Release is missing ruoyi-admin.jar."
  [[ -s "$resolved/SHA256SUMS" ]] || die "Release is missing SHA256SUMS."
  for required_dir in web admin web-remote admin-remote; do
    [[ -f "$resolved/$required_dir/index.html" ]] || \
      die "Release is missing $required_dir/index.html."
  done

  symlink_path="$(find "$resolved" -type l -print -quit)"
  [[ -z "$symlink_path" ]] || die "Release contains a symbolic link: $symlink_path"
  writable_path="$(find "$resolved" \( -perm -020 -o -perm -002 \) -print -quit)"
  [[ -z "$writable_path" ]] || die "Release contains a group/world-writable path: $writable_path"

  whitespace_path=""
  while IFS= read -r -d '' release_path; do
    if [[ "$release_path" =~ [[:space:]] ]]; then
      whitespace_path="$release_path"
      break
    fi
  done < <(find "$resolved" -type f -print0)
  [[ -z "$whitespace_path" ]] || die "Release filenames may not contain whitespace: $whitespace_path"

  invalid_line="$(awk '
    NF != 2 || length($1) != 64 || $1 !~ /^[0-9a-f]+$/ ||
    $2 !~ /^\.\// || $2 ~ /(^|\/)\.\.(\/|$)/ || $2 == "./SHA256SUMS" {
      print NR; exit
    }
  ' "$resolved/SHA256SUMS")"
  [[ -z "$invalid_line" ]] || die "Invalid SHA256SUMS entry on line $invalid_line."

  duplicate_path="$(awk '{print $2}' "$resolved/SHA256SUMS" | LC_ALL=C sort | uniq -d | head -n 1)"
  [[ -z "$duplicate_path" ]] || die "Duplicate SHA256SUMS path: $duplicate_path"

  actual_count="$(find "$resolved" -type f ! -path "$resolved/SHA256SUMS" | wc -l | tr -d ' ')"
  listed_count="$(wc -l < "$resolved/SHA256SUMS" | tr -d ' ')"
  [[ "$actual_count" == "$listed_count" ]] || \
    die "SHA256SUMS lists $listed_count files, but the release contains $actual_count."

  verify_manifest_checksums "$resolved" || die "Release checksum verification failed."
  VERIFIED_RELEASE="$resolved"
}

atomic_write() {
  local value="$1" destination="$2" mode="${3:-0600}" temp
  temp="$(mktemp "${destination}.tmp.XXXXXX")"
  printf '%s\n' "$value" > "$temp"
  chmod "$mode" "$temp"
  mv -f "$temp" "$destination"
}

atomic_symlink() {
  local target="$1" destination="$2" temp
  temp="${destination}.tmp.$$"
  [[ ! -e "$temp" && ! -L "$temp" ]] || die "Temporary link already exists: $temp"
  ln -s "$target" "$temp"
  mv -Tf "$temp" "$destination"
}

render_nginx_config() {
  local release="$1" port="$2" output="$3"
  [[ "$release" =~ ^/[A-Za-z0-9._/-]+$ ]] || die "Unsafe release path for Nginx rendering."
  [[ "$port" =~ ^[0-9]+$ ]] || die "Unsafe backend port for Nginx rendering."
  [[ -s "$NGINX_TEMPLATE" ]] || die "Nginx template is missing: $NGINX_TEMPLATE"

  sed \
    -e "s|@@NXR_RELEASE_ROOT@@|${release}|g" \
    -e "s|@@NXR_BACKEND_PORT@@|${port}|g" \
    "$NGINX_TEMPLATE" > "$output"
  chmod 0644 "$output"
  if grep -q '@@NXR_' "$output"; then
    die "Nginx template still contains unresolved placeholders."
  fi
}

restore_active_config() {
  if [[ "$ACTIVE_CONFIG_HAD_PREVIOUS" -eq 1 ]]; then
    cp -a "$ACTIVE_CONFIG_BACKUP" "$NGINX_ACTIVE_CONF"
  else
    rm -f -- "$NGINX_ACTIVE_CONF"
  fi
  "$NGINX_BIN" -t >/dev/null
  "$SYSTEMCTL_BIN" reload nginx
}

activate_config() {
  local candidate="$1" install_temp
  ACTIVE_CONFIG_BACKUP="$(mktemp "${STATE_ROOT}/.nginx-previous.XXXXXX")"
  ACTIVE_CONFIG_HAD_PREVIOUS=0
  if [[ -f "$NGINX_ACTIVE_CONF" ]]; then
    cp -a "$NGINX_ACTIVE_CONF" "$ACTIVE_CONFIG_BACKUP"
    ACTIVE_CONFIG_HAD_PREVIOUS=1
  fi

  install_temp="$(mktemp "$(dirname "$NGINX_ACTIVE_CONF")/.nxr-java-stage.XXXXXX")"
  install -m 0644 "$candidate" "$install_temp"
  mv -f "$install_temp" "$NGINX_ACTIVE_CONF"

  if ! "$NGINX_BIN" -t >/dev/null; then
    restore_active_config
    return 1
  fi
  if ! "$SYSTEMCTL_BIN" reload nginx; then
    restore_active_config
    return 1
  fi
}

health_ok() {
  local port="$1"
  "$CURL_BIN" -fsS --max-time 5 \
    "http://127.0.0.1:${port}/api/platform/health" | grep -q '"status":"ok"'
}

wait_for_health() {
  local port="$1" deadline
  deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
  while (( $(date +%s) < deadline )); do
    if health_ok "$port"; then
      return 0
    fi
    sleep 2
  done
  return 1
}

stage_routes_ok() {
  "$CURL_BIN" -fsS --max-time 10 \
    "http://127.0.0.1:18080/api/platform/health" | grep -q '"status":"ok"' &&
    "$CURL_BIN" -fsS --max-time 10 -o /dev/null "http://127.0.0.1:18080/" &&
    "$CURL_BIN" -fsS --max-time 10 -o /dev/null "http://127.0.0.1:18081/"
}

active_unit_for_slot() {
  local slot="$1" unit
  unit="$(slot_unit "$slot")"
  if "$SYSTEMCTL_BIN" is-active --quiet "$unit"; then
    printf '%s\n' "$unit"
    return 0
  fi
  if [[ "$slot" == "blue" ]] && "$SYSTEMCTL_BIN" is-active --quiet nxr-java-stage.service; then
    printf 'nxr-java-stage.service\n'
    return 0
  fi
  return 1
}

check_memory_headroom() {
  local available_kb swap_free_kb headroom_kb
  [[ -r /proc/meminfo ]] || die "Cannot read /proc/meminfo for the blue/green safety check."
  available_kb="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)"
  swap_free_kb="$(awk '/^SwapFree:/ {print $2}' /proc/meminfo)"
  headroom_kb=$(( available_kb + swap_free_kb ))
  (( headroom_kb >= MIN_HEADROOM_KB )) || \
    die "Insufficient memory headroom for a second Java slot (${headroom_kb}KB available)."
}

resolve_java_database() {
  local jdbc_url database
  if [[ -n "$JAVA_DATABASE_OVERRIDE" ]]; then
    database="$JAVA_DATABASE_OVERRIDE"
  elif [[ -r "$CONFIG_ROOT/stage.env" ]]; then
    jdbc_url="$(sed -n 's/^NXR_DB_URL=//p' "$CONFIG_ROOT/stage.env" | tail -n 1)"
    case "$jdbc_url" in
      jdbc:mysql://*/*)
        jdbc_url="${jdbc_url%%\?*}"
        database="${jdbc_url##*/}"
        ;;
      *) die "Unable to resolve the Java database from the shared runtime configuration." ;;
    esac
  else
    die "Missing Java database configuration: $CONFIG_ROOT/stage.env"
  fi
  [[ "$database" =~ ^[A-Za-z0-9_]+$ ]] || die "The configured Java database name is unsafe."
  printf '%s\n' "$database"
}

check_no_enabled_jobs() {
  local java_database enabled_jobs
  java_database="$(resolve_java_database)"
  if ! enabled_jobs="$("$MYSQL_BIN" --batch --skip-column-names "$java_database" \
      -e "SELECT COUNT(*) FROM sys_job WHERE status = '0';" 2>/dev/null)"; then
    die "Unable to verify Quartz job state in the configured Java database; refusing an overlapping backend start."
  fi
  [[ "$enabled_jobs" =~ ^[0-9]+$ ]] || die "Unexpected Quartz job count: $enabled_jobs"
  (( enabled_jobs == 0 )) || \
    die "$enabled_jobs Quartz job(s) are enabled; pause them or add clustered scheduling before hot deployment."
}

backend_is_equivalent() {
  local old_release="$1" new_release="$2" old_tree new_tree
  if [[ -s "$old_release/BACKEND_SOURCE_TREE" && -s "$new_release/BACKEND_SOURCE_TREE" ]]; then
    old_tree="$(tr -d '\r\n' < "$old_release/BACKEND_SOURCE_TREE")"
    new_tree="$(tr -d '\r\n' < "$new_release/BACKEND_SOURCE_TREE")"
    if [[ "$old_tree" =~ ^[0-9a-f]{40,64}$ && "$old_tree" == "$new_tree" ]]; then
      return 0
    fi
  fi
  [[ "$(checksum_file "$old_release/ruoyi-admin.jar")" == \
     "$(checksum_file "$new_release/ruoyi-admin.jar")" ]]
}

create_backup() {
  local label="$1" timestamp backup_dir checksum_tool
  timestamp="$(date -u '+%Y%m%dT%H%M%SZ')"
  backup_dir="${BACKUP_ROOT}/${timestamp}-${label}-$$"
  install -d -m 0700 "$BACKUP_ROOT" "$backup_dir"

  if [[ -f "$NGINX_ACTIVE_CONF" ]]; then
    cp -a "$NGINX_ACTIVE_CONF" "$backup_dir/nginx-java-stage.conf"
  fi
  if [[ -f "$STATE_ROOT/active-slot" ]]; then
    cp -a "$STATE_ROOT/active-slot" "$backup_dir/active-slot"
  fi
  if [[ -f "$STATE_ROOT/active-release" ]]; then
    cp -a "$STATE_ROOT/active-release" "$backup_dir/active-release"
  fi
  if [[ -L "$CURRENT_LINK" ]]; then
    readlink -f "$CURRENT_LINK" > "$backup_dir/current-release"
  fi
  printf 'created_utc=%s\nlabel=%s\n' "$timestamp" "$label" > "$backup_dir/backup-info"

  if command -v sha256sum >/dev/null 2>&1; then
    checksum_tool="sha256sum"
  else
    checksum_tool="shasum -a 256"
  fi
  (
    cd "$backup_dir"
    find . -type f ! -name CHECKSUMS -print | LC_ALL=C sort | xargs $checksum_tool > CHECKSUMS
    if [[ "$checksum_tool" == "sha256sum" ]]; then
      sha256sum --check --strict --quiet CHECKSUMS
    else
      shasum -a 256 -c CHECKSUMS >/dev/null
    fi
  )

  prune_backups
  printf 'Backup: %s\n' "$backup_dir"
}

prune_backups() {
  local -a backup_dirs
  local remove_count index target
  mapfile -t backup_dirs < <(
    find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' |
      LC_ALL=C sort -n | awk '{print $2}'
  )
  remove_count=$(( ${#backup_dirs[@]} - 2 ))
  (( remove_count > 0 )) || return 0

  for (( index=0; index<remove_count; index++ )); do
    target="${backup_dirs[$index]}"
    case "$target" in
      "${BACKUP_ROOT}/"*) rm -rf -- "$target" ;;
      *) die "Refusing to prune an unsafe backup path: $target" ;;
    esac
  done
}

wait_for_drain() {
  local port="$1" deadline connections stable_zero=0
  deadline=$(( $(date +%s) + DRAIN_TIMEOUT ))
  while (( $(date +%s) < deadline )); do
    connections="$("$SS_BIN" -Hnt state established "( sport = :${port} )" | wc -l | tr -d ' ')"
    if [[ "$connections" == "0" ]]; then
      stable_zero=$(( stable_zero + 1 ))
      if (( stable_zero >= 5 )); then
        return 0
      fi
    else
      stable_zero=0
    fi
    sleep 1
  done
  return 1
}

bootstrap_runtime() {
  local requested_release="$1" active_slot active_port config_candidate
  require_root
  require_command "$SYSTEMCTL_BIN"
  require_command "$NGINX_BIN"
  require_command "$CURL_BIN"
  require_command flock
  validate_number BLUE_PORT "$BLUE_PORT"
  validate_number GREEN_PORT "$GREEN_PORT"

  install -d -m 0755 "$RELEASES_ROOT" "$SLOTS_ROOT"
  install -d -m 0700 "$STATE_ROOT" "$BACKUP_ROOT"
  exec 9>"$LOCK_FILE"
  flock -n 9 || die "Another Java deployment is already running."

  resolve_and_verify_release "$requested_release"
  active_slot="$(read_state_value "$STATE_ROOT/active-slot" || printf 'blue')"
  active_port="$(slot_port "$active_slot")"
  health_ok "$active_port" || die "Current Java backend is not healthy on port $active_port."

  create_backup bootstrap
  config_candidate="$(mktemp "${STATE_ROOT}/.nginx-candidate.XXXXXX")"
  render_nginx_config "$VERIFIED_RELEASE" "$active_port" "$config_candidate"
  if ! activate_config "$config_candidate"; then
    rm -f -- "$config_candidate" "$ACTIVE_CONFIG_BACKUP"
    die "Nginx rejected the rendered Java stage configuration."
  fi
  if ! stage_routes_ok; then
    restore_active_config
    rm -f -- "$config_candidate" "$ACTIVE_CONFIG_BACKUP"
    die "Java stage routes failed after the bootstrap reload; configuration was restored."
  fi

  atomic_symlink "$VERIFIED_RELEASE" "$SLOTS_ROOT/$active_slot"
  atomic_symlink "$VERIFIED_RELEASE" "$CURRENT_LINK"
  atomic_write "$active_slot" "$STATE_ROOT/active-slot"
  atomic_write "$VERIFIED_RELEASE" "$STATE_ROOT/active-release"
  rm -f -- "$config_candidate" "$ACTIVE_CONFIG_BACKUP"
  printf 'Bootstrap complete: slot=%s port=%s release=%s\n' \
    "$active_slot" "$active_port" "$VERIFIED_RELEASE"
}

deploy_static_release() {
  local active_slot="$1" active_port="$2" old_release="$3" new_release="$4"
  local config_candidate
  create_backup "before-static-$(basename "$new_release")"
  config_candidate="$(mktemp "${STATE_ROOT}/.nginx-candidate.XXXXXX")"
  render_nginx_config "$new_release" "$active_port" "$config_candidate"

  if ! activate_config "$config_candidate"; then
    rm -f -- "$config_candidate" "$ACTIVE_CONFIG_BACKUP"
    die "Nginx rejected the static release; the old configuration remains active."
  fi
  if ! stage_routes_ok; then
    restore_active_config
    rm -f -- "$config_candidate" "$ACTIVE_CONFIG_BACKUP"
    die "Static release verification failed; Nginx was rolled back."
  fi

  if ! {
    atomic_symlink "$new_release" "$SLOTS_ROOT/$active_slot"
    atomic_symlink "$new_release" "$CURRENT_LINK"
    atomic_write "$new_release" "$STATE_ROOT/active-release"
  }; then
    restore_active_config
    atomic_symlink "$old_release" "$SLOTS_ROOT/$active_slot"
    atomic_symlink "$old_release" "$CURRENT_LINK"
    atomic_write "$old_release" "$STATE_ROOT/active-release"
    rm -f -- "$config_candidate" "$ACTIVE_CONFIG_BACKUP"
    die "Unable to commit the static release state; the old release was restored."
  fi

  rm -f -- "$config_candidate" "$ACTIVE_CONFIG_BACKUP"
  printf 'Hot deployment complete: mode=static slot=%s release=%s backend_restart=no\n' \
    "$active_slot" "$new_release"
}

rollback_backend_switch() {
  local old_slot="$1" old_release="$2" candidate_unit="$3" old_unit="$4"
  restore_active_config || true
  atomic_symlink "$old_release" "$SLOTS_ROOT/$old_slot" || true
  atomic_symlink "$old_release" "$CURRENT_LINK" || true
  atomic_write "$old_slot" "$STATE_ROOT/active-slot" || true
  atomic_write "$old_release" "$STATE_ROOT/active-release" || true
  "$SYSTEMCTL_BIN" disable "$candidate_unit" >/dev/null 2>&1 || true
  "$SYSTEMCTL_BIN" stop "$candidate_unit" >/dev/null 2>&1 || true
  "$SYSTEMCTL_BIN" enable "$old_unit" >/dev/null 2>&1 || true
}

deploy_backend_release() {
  local active_slot="$1" active_port="$2" old_release="$3" new_release="$4"
  local inactive_slot inactive_port candidate_unit old_unit config_candidate

  require_command "$MYSQL_BIN"
  require_command "$SS_BIN"
  check_memory_headroom
  check_no_enabled_jobs

  inactive_slot="$(other_slot "$active_slot")"
  inactive_port="$(slot_port "$inactive_slot")"
  candidate_unit="$(slot_unit "$inactive_slot")"
  old_unit="$(active_unit_for_slot "$active_slot")" || \
    die "No active Java service matches slot $active_slot."

  if "$SYSTEMCTL_BIN" is-active --quiet "$candidate_unit"; then
    die "Inactive slot is still running: $candidate_unit"
  fi
  health_ok "$active_port" || die "Active backend is not healthy on port $active_port."

  create_backup "before-backend-$(basename "$new_release")"
  install -d -m 0750 -o nxr-java -g nxr-java "/var/log/nxr-java/$inactive_slot"
  atomic_symlink "$new_release" "$SLOTS_ROOT/$inactive_slot"
  "$SYSTEMCTL_BIN" reset-failed "$candidate_unit" >/dev/null 2>&1 || true

  if ! "$SYSTEMCTL_BIN" start "$candidate_unit"; then
    die "Unable to start candidate service: $candidate_unit"
  fi
  if ! wait_for_health "$inactive_port"; then
    "$SYSTEMCTL_BIN" stop "$candidate_unit" >/dev/null 2>&1 || true
    die "Candidate did not become healthy on port $inactive_port."
  fi

  config_candidate="$(mktemp "${STATE_ROOT}/.nginx-candidate.XXXXXX")"
  render_nginx_config "$new_release" "$inactive_port" "$config_candidate"
  if ! activate_config "$config_candidate"; then
    "$SYSTEMCTL_BIN" stop "$candidate_unit" >/dev/null 2>&1 || true
    rm -f -- "$config_candidate" "$ACTIVE_CONFIG_BACKUP"
    die "Nginx switch failed; candidate was stopped and the old slot remains active."
  fi
  if ! stage_routes_ok; then
    rollback_backend_switch "$active_slot" "$old_release" "$candidate_unit" "$old_unit"
    rm -f -- "$config_candidate" "$ACTIVE_CONFIG_BACKUP"
    die "Post-switch checks failed; traffic and runtime state were rolled back."
  fi

  if ! {
    atomic_symlink "$new_release" "$CURRENT_LINK"
    atomic_write "$inactive_slot" "$STATE_ROOT/active-slot"
    atomic_write "$new_release" "$STATE_ROOT/active-release"
    "$SYSTEMCTL_BIN" enable "$candidate_unit" >/dev/null
    "$SYSTEMCTL_BIN" disable "$old_unit" >/dev/null
  }; then
    rollback_backend_switch "$active_slot" "$old_release" "$candidate_unit" "$old_unit"
    rm -f -- "$config_candidate" "$ACTIVE_CONFIG_BACKUP"
    die "Unable to commit the blue/green state; the old slot was restored."
  fi

  rm -f -- "$config_candidate" "$ACTIVE_CONFIG_BACKUP"
  if ! wait_for_drain "$active_port"; then
    printf 'ERROR: traffic switched, but old slot %s still has connections after %ss; it was left running.\n' \
      "$active_slot" "$DRAIN_TIMEOUT" >&2
    exit 2
  fi
  "$SYSTEMCTL_BIN" stop "$old_unit"
  "$SYSTEMCTL_BIN" reset-failed "$old_unit" >/dev/null 2>&1 || true

  printf 'Hot deployment complete: mode=blue-green slot=%s port=%s release=%s old_slot_stopped=yes\n' \
    "$inactive_slot" "$inactive_port" "$new_release"
}

deploy_release() {
  local requested_release="$1" active_slot active_port old_release
  require_root
  require_command "$SYSTEMCTL_BIN"
  require_command "$NGINX_BIN"
  require_command "$CURL_BIN"
  require_command flock
  validate_number BLUE_PORT "$BLUE_PORT"
  validate_number GREEN_PORT "$GREEN_PORT"
  validate_number HEALTH_TIMEOUT "$HEALTH_TIMEOUT"
  validate_number DRAIN_TIMEOUT "$DRAIN_TIMEOUT"
  validate_number MIN_HEADROOM_KB "$MIN_HEADROOM_KB"

  [[ -d "$STATE_ROOT" ]] || die "Hot-deploy runtime is not bootstrapped."
  exec 9>"$LOCK_FILE"
  flock -n 9 || die "Another Java deployment is already running."

  active_slot="$(read_state_value "$STATE_ROOT/active-slot")" || \
    die "Missing active-slot state; run --bootstrap first."
  active_port="$(slot_port "$active_slot")"
  old_release="$(read_state_value "$STATE_ROOT/active-release")" || \
    die "Missing active-release state; run --bootstrap first."
  resolve_and_verify_release "$old_release"
  old_release="$VERIFIED_RELEASE"
  resolve_and_verify_release "$requested_release"

  if [[ "$VERIFIED_RELEASE" == "$old_release" ]]; then
    printf 'No change: release is already active (%s).\n' "$old_release"
    return 0
  fi
  if backend_is_equivalent "$old_release" "$VERIFIED_RELEASE"; then
    deploy_static_release "$active_slot" "$active_port" "$old_release" "$VERIFIED_RELEASE"
  else
    deploy_backend_release "$active_slot" "$active_port" "$old_release" "$VERIFIED_RELEASE"
  fi
}

main() {
  local mode="deploy"
  [[ "$#" -gt 0 ]] || { usage; exit 2; }
  case "$1" in
    --help|-h) usage; return 0 ;;
    --verify-release) mode="verify"; shift ;;
    --bootstrap) mode="bootstrap"; shift ;;
  esac
  [[ "$#" -eq 1 ]] || { usage; exit 2; }

  case "$mode" in
    verify)
      resolve_and_verify_release "$1"
      printf 'Release verified: %s\n' "$VERIFIED_RELEASE"
      ;;
    bootstrap) bootstrap_runtime "$1" ;;
    deploy) deploy_release "$1" ;;
  esac
}

main "$@"
