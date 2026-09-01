#!/usr/bin/env bash

# Build a complete, checksummed Java stage release from one clean Git commit.

set -Eeuo pipefail
umask 022

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_ROOT="$(cd "$DEPLOY_DIR/.." && pwd)"
PROJECT_ROOT="$(cd "$PLATFORM_ROOT/.." && pwd)"
BACKEND_ROOT="$PLATFORM_ROOT/nxr-backend-ruoyi"
WEB_ROOT="$PLATFORM_ROOT/nxr-frontend-web"
ADMIN_ROOT="$PLATFORM_ROOT/nxr-frontend-admin-ruoyi"
SQL_ROOT="$PLATFORM_ROOT/nxr-sql/ruoyi"

usage() {
  printf 'Usage: build-java-release.sh OUTPUT_DIRECTORY [GIT_COMMIT]\n'
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

[[ "$#" -ge 1 && "$#" -le 2 ]] || { usage; exit 2; }
output_directory="$1"
release_commit="${2:-$(git -C "$PROJECT_ROOT" rev-parse HEAD)}"

[[ "$release_commit" =~ ^[0-9a-f]{40}$ ]] || die "GIT_COMMIT must be a full 40-character SHA."
[[ "$(git -C "$PROJECT_ROOT" rev-parse HEAD)" == "$release_commit" ]] || \
  die "The requested commit is not the checked-out HEAD."
[[ -z "$(git -C "$PROJECT_ROOT" status --porcelain --untracked-files=normal)" ]] || \
  die "The Git worktree must be clean before building a release."

if [[ -e "$output_directory" ]]; then
  [[ -d "$output_directory" ]] || die "Output path is not a directory."
  [[ -z "$(find "$output_directory" -mindepth 1 -maxdepth 1 -print -quit)" ]] || \
    die "Output directory must be empty: $output_directory"
else
  install -d -m 0755 "$output_directory"
fi
output_directory="$(cd "$output_directory" && pwd -P)"

command -v mvn >/dev/null 2>&1 || die "Maven is required."
command -v npm >/dev/null 2>&1 || die "npm is required."

printf 'Building backend...\n'
(cd "$BACKEND_ROOT" && mvn -q -T 1C clean package -DskipTests)

printf 'Building public web profiles...\n'
(cd "$WEB_ROOT" && npm ci --no-audit --no-fund && npm run build:java-stage && npm run build:java-remote)

printf 'Building admin profiles...\n'
(cd "$ADMIN_ROOT" && npm ci --no-audit --no-fund && npm run build:java-stage && npm run build:java-remote)

install -d -m 0755 \
  "$output_directory/web" \
  "$output_directory/admin" \
  "$output_directory/web-remote" \
  "$output_directory/admin-remote" \
  "$output_directory/scripts" \
  "$output_directory/sql"

install -m 0644 "$BACKEND_ROOT/ruoyi-admin/target/ruoyi-admin.jar" \
  "$output_directory/ruoyi-admin.jar"
cp -R "$WEB_ROOT/dist/." "$output_directory/web/"
cp -R "$WEB_ROOT/dist-remote/." "$output_directory/web-remote/"
cp -R "$ADMIN_ROOT/dist/." "$output_directory/admin/"
cp -R "$ADMIN_ROOT/dist-remote/." "$output_directory/admin-remote/"

install -m 0644 "$PROJECT_ROOT/scripts/migrate_python_to_java_mysql.py" \
  "$output_directory/scripts/migrate_python_to_java_mysql.py"
install -m 0644 "$PROJECT_ROOT/scripts/sync_python_to_java_mysql.py" \
  "$output_directory/scripts/sync_python_to_java_mysql.py"
install -m 0644 "$PROJECT_ROOT/requirements-mysql.txt" \
  "$output_directory/scripts/requirements-mysql.txt"

shopt -s nullglob
sql_files=("$SQL_ROOT"/1[0-9]_*.sql)
(( ${#sql_files[@]} > 0 )) || die "No versioned Java SQL files were found."
install -m 0644 "${sql_files[@]}" "$output_directory/sql/"
shopt -u nullglob

printf '%s\n' "$release_commit" > "$output_directory/RELEASE_COMMIT"
git -C "$PROJECT_ROOT" rev-parse "$release_commit:nxr_platform/nxr-backend-ruoyi" \
  > "$output_directory/BACKEND_SOURCE_TREE"

find "$output_directory" -type d -exec chmod 0755 {} +
find "$output_directory" -type f -exec chmod 0644 {} +

whitespace_path=""
while IFS= read -r -d '' release_path; do
  if [[ "$release_path" =~ [[:space:]] ]]; then
    whitespace_path="$release_path"
    break
  fi
done < <(find "$output_directory" -type f -print0)
[[ -z "$whitespace_path" ]] || die "Release filename contains whitespace: $whitespace_path"

(
  cd "$output_directory"
  if command -v sha256sum >/dev/null 2>&1; then
    find . -type f ! -path ./SHA256SUMS -print | LC_ALL=C sort |
      while IFS= read -r release_path; do sha256sum "$release_path"; done > SHA256SUMS
  elif command -v shasum >/dev/null 2>&1; then
    find . -type f ! -path ./SHA256SUMS -print | LC_ALL=C sort |
      while IFS= read -r release_path; do shasum -a 256 "$release_path"; done > SHA256SUMS
  else
    die "Neither sha256sum nor shasum is available."
  fi
  chmod 0644 SHA256SUMS
)

NXR_JAVA_RELEASES_ROOT="$(dirname "$output_directory")" \
  "$DEPLOY_DIR/hot-deploy-java.sh" --verify-release "$output_directory"
printf 'Release built: %s\n' "$output_directory"
