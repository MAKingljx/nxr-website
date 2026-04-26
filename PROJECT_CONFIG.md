# NXR Website Project Config

## Local Workspace

- Local project root: `/Users/phoenix/Documents/Phoenxi/nxr_website`
- Main site entrypoint: `nxr_site/app.py`
- Admin/control backend entrypoint: `nxr_admin/app_updated.py`

## Production App

- Production root: `/root/nxr_website`
- Main site start command: `cd /root/nxr_website && /usr/bin/python3 app.py`
- Admin backend start command: `cd /root/nxr_website && /usr/bin/python3 nxr_admin/app_updated.py`
- Main site process pattern: `/usr/bin/python3 app.py`
- Admin process pattern: `/usr/bin/python3 /root/nxr_website/nxr_admin/app_updated.py`
- Main site port: `8080`
- Admin port: `8081`
- Reverse proxy: Nginx -> `127.0.0.1:8080`
- Public domain: `https://nxrgrading.com/`
- Public admin entry URL: `https://nxrgrading.com/x7k9m2q4r8v6c3p1`

### Admin Route Rules

- Public `https://nxrgrading.com/admin` is intentionally blocked and should return `404`.
- The real public admin entry path is the hidden route `https://nxrgrading.com/x7k9m2q4r8v6c3p1`.
- On the server, the admin Flask app listens on `127.0.0.1:8081` behind Nginx rewrite/proxy rules.
- Direct public access to `:8081` should not be relied on and should remain blocked externally.

## Validation Workflow

1. Change code locally first.
2. Run local validation before touching production.
3. Sync verified code and asset files to `/root/nxr_website`. Do not sync `Data/` through the normal file-sync path.
4. Restart the app on the server.
5. Smoke test `127.0.0.1:8080` and the public site.

Important override:

- `scripts/sync_to_server.sh` is now code-only and excludes `Data/` by default.
- Database sync, database replacement, database rollback, and any overwrite under `Data/` are forbidden unless the user gives explicit authorization in the current session.
- Do not treat prior approvals as reusable. Each database-changing action requires a fresh explicit user instruction.

## Preferred Future Deploy Workflow

- Target workflow: Git-based deploys, not ad-hoc file sync.
- Standard path: local commit -> push GitHub -> server pull -> restart -> smoke test.
- Deployment script: `scripts/deploy_via_git.sh`
- Remote restart helper: `scripts/restart_remote_apps.sh`
- Detailed process record: `GIT_DEPLOY_WORKFLOW.md`

### Important

- `scripts/deploy_via_git.sh` is intentionally strict.
- It aborts if the local worktree is dirty.
- It aborts if the remote worktree is dirty.
- It is only safe to use after the server repo has been normalized.

## Current Server Git Reality

- The production repo exists at `/root/nxr_website`.
- It currently needs `git safe.directory` handling.
- The remote worktree is not clean yet.
- This means Git pull should not be treated as safe until the one-time cleanup is completed.

## Key Project Files

- Main site code: `nxr_site/`
- Admin/control backend: `nxr_admin/`
- Local sync script: `scripts/sync_to_server.sh`
- Legacy production entrypoint still present on server: `app.py`
- Databases: `Data/cards.db`, `Data/temp_cards.db`
- Project prompt: `AGENTS.md`
- SSH notes: `SSH.md` (local-only)

## Backup Retention Rule

- Local backup retention limit: keep at most 2 backup snapshots.
- Prefer keeping the 2 most recent backups in `local_backups/`.
- Old backup archives should be deleted or archived elsewhere once newer validated backups exist.

## Database Safety Rule

- Strong rule: without explicit user authorization in the current session, do not sync, replace, restore, or overwrite any production database file.
- This applies to `Data/cards.db`, `Data/temp_cards.db`, and any other SQLite payload under `Data/`.
- Code deploys and file syncs must assume `Data/` is immutable unless the user explicitly says otherwise.
- If a future task needs a database change, stop and obtain explicit approval first, then create a dated backup before any write.

## Approved Upload Image Lifecycle

- Scope: approved entries uploaded from the admin upload flow.
- Local raw uploads live in `nxr_admin/uploads/`.
- Published site images live in `nxr_site/static/`.
- `temp_cards.front_image` / `temp_cards.back_image` are local source-image fields only.
- `temp_cards.published_front_image` / `temp_cards.published_back_image` are published-image path fields only.

### Upload Success Rule

1. Copy the local source images from `nxr_admin/uploads/` into `nxr_site/static/`.
2. Write the published `/static/...` paths into `published_front_image` and `published_back_image`.
3. Delete the local source files from `nxr_admin/uploads/`.
4. Clear `front_image` and `back_image`.
5. Keep `upload_status = uploaded`.

### Edit / Re-upload Rule

- If a new local image is uploaded later, it is stored again in `front_image` / `back_image`.
- Existing published images remain in `published_front_image` / `published_back_image` until the next successful upload.
- After the next successful upload, the new published paths replace the old published paths, and the local source files are deleted again.

### UI Rule

- Upload Manager should treat local source images as `Ready`.
- Upload Manager should treat published images as `Published`.
- Edit pages should preview local source images first, then fall back to published images when no local source image exists.

## Sync Command

- Preferred sync command: `./scripts/sync_to_server.sh`
- Override target host if needed: `DEPLOY_TARGET=root@your-server ./scripts/sync_to_server.sh`
- Override remote root if needed: `DEPLOY_ROOT=/root/nxr_website ./scripts/sync_to_server.sh`
- `Data/` is excluded from this sync command by policy.

## Remote Restart Rule

- Restart the main site and admin backend as two separate processes.
- Main site must be reachable at `127.0.0.1:8080`.
- Admin backend must be reachable at `127.0.0.1:8081/admin/login`.
- Preferred restart command: `./scripts/restart_remote_apps.sh`
- Do not assume restarting `app.py` also refreshes the admin backend. `nxr_admin/app_updated.py` must be restarted explicitly when admin code or templates change.
