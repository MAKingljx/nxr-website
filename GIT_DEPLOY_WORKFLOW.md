# Git Deployment Workflow

This file defines the standard deployment workflow for `nxr_website`.

## Goal

Use Git as the single source of truth for code deployment.

Local changes -> commit -> push to GitHub -> server pulls Git commit -> restart -> smoke test.

Do not use ad-hoc file sync as the default deployment path once the server repo is normalized.

## Current Constraint

As of 2026-04-12, the production repo at `/root/nxr_website` is not yet safe for Git-based deploys:

- Git requires `safe.directory` for `/root/nxr_website`
- The remote worktree is dirty
- The directory owner is not `root`

Because of that, Git deploy must abort until the repo is normalized.

## One-Time Server Normalization

Run these steps once before switching fully to Git deploys:

1. SSH to the server.
2. Add Git safe directory:
   - `git config --global --add safe.directory /root/nxr_website`
3. Inspect remote changes:
   - `git -C /root/nxr_website status --short`
4. Archive the current server code state to `/root/nxr_website_archive/<timestamp>/`.
5. Fix ownership so the deploy user owns `/root/nxr_website`.
6. Decide what to do with the dirty worktree:
   - either commit/archive the server-only changes
   - or reset the code tree to Git after a verified backup
7. Confirm the remote repo is clean:
   - `git -C /root/nxr_website status --short`
8. Confirm the target branch:
   - `git -C /root/nxr_website branch --show-current`

Do not switch to Git deploy until `git status --short` is empty on the server.

## Standard Deploy Flow

Once the server repo is clean, always use this flow:

1. Work locally in `/Users/phoenix/Documents/Phoenxi/nxr_website`.
2. Validate locally.
3. Review `git diff`.
4. Commit the change locally.
5. Push the branch to GitHub.
6. Run `./scripts/deploy_via_git.sh`.
7. Verify the service on `127.0.0.1:8080`.
8. Verify the affected public or admin routes.

## Local Validation Minimum

At minimum:

- `python3 -m py_compile nxr_site/app.py nxr_admin/app_updated.py`

For admin/backend changes, also run targeted route checks or a Flask test client script.

## Remote Smoke Test Minimum

After restart, verify:

- `curl -I http://127.0.0.1:8080/`
- `curl -I http://127.0.0.1:8081/admin/login`

For admin-only fixes, also run an authenticated smoke test manually or with a cookie jar.

Important:

- Do not use `https://nxrgrading.com/admin` as the public admin check. That path is intentionally blocked and should return `404`.
- The public admin entry URL is `https://nxrgrading.com/x7k9m2q4r8v6c3p1`.

## Database Rule

Git deploy is for code only.

Database files under `Data/` are ignored by Git and must be handled separately when schema or content changes are part of the release.

Strong rule:

- Without explicit user authorization in the current session, do not sync, replace, restore, or overwrite any production database file.
- Prior approval does not carry forward. Every database-changing action needs a fresh explicit instruction from the user.
- `scripts/sync_to_server.sh` is code-only and must not be used to push `Data/`.

Before any database change on production:

1. Archive the current database files.
2. Apply the migration or copy the validated database payload.
3. Restart and smoke test.

## Rollback

If a deploy fails after the remote Git pull:

1. SSH to the server.
2. Find the previous revision:
   - `cat /root/nxr_website_archive/<timestamp>/git_revision.txt`
3. Reset the server repo to that revision.
4. Restart the app.
5. Re-run smoke tests.

Rollback should only be done after confirming the backup revision and affected files.

## Temporary Exception

Until the server repo is normalized, emergency releases may still use targeted file sync.

If that exception is used:

- sync only the intended files
- keep `Data/` excluded unless the user explicitly authorizes a database operation in the current session
- archive the previous server files first
- record the exception in the task notes
- return to Git-based deploy as the default path after normalization
