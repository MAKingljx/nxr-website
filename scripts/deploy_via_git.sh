#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_TARGET="${DEPLOY_TARGET:-root@147.182.183.201}"
DEPLOY_ROOT="${DEPLOY_ROOT:-/root/nxr_website}"
ARCHIVE_ROOT="${ARCHIVE_ROOT:-/root/nxr_website_archive}"
BRANCH="${BRANCH:-$(git -C "$PROJECT_ROOT" branch --show-current)}"

if [[ -z "${BRANCH}" ]]; then
  echo "Unable to determine current Git branch." >&2
  exit 1
fi

if ! git -C "$PROJECT_ROOT" diff --quiet || ! git -C "$PROJECT_ROOT" diff --cached --quiet; then
  echo "Local worktree is not clean. Commit or stash changes before Git deploy." >&2
  exit 1
fi

echo "Running local validation..."
python3 -m py_compile "$PROJECT_ROOT/nxr_site/app.py" "$PROJECT_ROOT/nxr_admin/app_updated.py"

echo "Pushing branch ${BRANCH} to origin..."
git -C "$PROJECT_ROOT" push origin "$BRANCH"

echo "Checking remote repo state on ${DEPLOY_TARGET}..."
ssh "$DEPLOY_TARGET" "git config --global --add safe.directory '$DEPLOY_ROOT' >/dev/null 2>&1 || true"

REMOTE_STATUS="$(ssh "$DEPLOY_TARGET" "git -C '$DEPLOY_ROOT' -c safe.directory='$DEPLOY_ROOT' status --short")"
if [[ -n "${REMOTE_STATUS}" ]]; then
  echo "Remote worktree is not clean. Aborting Git deploy." >&2
  echo "${REMOTE_STATUS}" >&2
  exit 1
fi

TIMESTAMP="$(date '+%Y%m%d_%H%M%S')"
REMOTE_HEAD="$(ssh "$DEPLOY_TARGET" "git -C '$DEPLOY_ROOT' -c safe.directory='$DEPLOY_ROOT' rev-parse HEAD")"

echo "Archiving remote Git revision ${REMOTE_HEAD}..."
ssh "$DEPLOY_TARGET" "mkdir -p '$ARCHIVE_ROOT/$TIMESTAMP' && printf '%s\n' '$REMOTE_HEAD' > '$ARCHIVE_ROOT/$TIMESTAMP/git_revision.txt'"

echo "Pulling latest code on remote..."
ssh "$DEPLOY_TARGET" "git -C '$DEPLOY_ROOT' -c safe.directory='$DEPLOY_ROOT' fetch origin '$BRANCH' && git -C '$DEPLOY_ROOT' -c safe.directory='$DEPLOY_ROOT' checkout '$BRANCH' && git -C '$DEPLOY_ROOT' -c safe.directory='$DEPLOY_ROOT' pull --ff-only origin '$BRANCH'"

echo "Restarting app..."
ssh "$DEPLOY_TARGET" "cd '$DEPLOY_ROOT' && pkill -f '/usr/bin/python3 app.py' || true && nohup /usr/bin/python3 app.py >'$DEPLOY_ROOT/output.log' 2>&1 </dev/null &"

echo "Running remote smoke tests..."
ssh "$DEPLOY_TARGET" "sleep 2 && curl -fsSI http://127.0.0.1:8080/ >/dev/null && curl -fsSI http://127.0.0.1:8080/admin/login >/dev/null"

echo "Git deploy completed successfully."
echo "Remote backup revision saved to: $ARCHIVE_ROOT/$TIMESTAMP/git_revision.txt"
