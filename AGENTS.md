# NXR Website Project Prompt

## Preferred Workflow

Work locally first. Do not treat the production server as the primary editing environment unless there is an emergency hotfix.

1. Make code and template changes locally in `/Users/phoenix/Documents/Phoenxi/nxr_website`.
2. Run local validation before touching the server.
3. Verify the affected routes, templates, and data paths with targeted tests or quick scripts.
4. Only after local verification passes, sync the changed files to the server at `/root/nxr_website`. Do not sync `Data/` through the normal file-sync path.
5. Restart the app on the server and run a live smoke test against `127.0.0.1:8080`.
6. Keep GitHub in sync after the fix is verified.

Important override:

- The public admin URL is fixed at `https://nxrgrading.com/x7k9m2q4r8v6c3p1`.
- `https://nxrgrading.com/admin` is intentionally not the public admin entry and should not be used for user-facing guidance.
- The production main site and admin backend are separate processes and must be treated separately during restart/debug work.

## Validation Expectations

- At minimum, run `python3 -m py_compile nxr_site/app.py nxr_admin/app_updated.py` after Python changes.
- Use a quick Flask test client script for route-level checks when possible.
- For card verification changes, test:
  - `/verify`
  - an existing uppercase cert id like `VRA002`
  - an existing lowercase cert id like `vra003`
  - a missing cert id
- Confirm that image paths resolve to local `/static/...` URLs instead of hard-coded production URLs.
- The main site source now lives under `nxr_site/`.
- The admin/control backend source now lives under `nxr_admin/`.

## Server Rules

- Avoid editing production files first when the same work can be done locally.
- Prefer archiving obsolete backups to a dated archive directory instead of deleting them immediately.
- Keep the production directory tidy: only live app files should remain in `/root/nxr_website`.
- Strong database rule: without explicit user authorization in the current session, do not sync, replace, restore, or overwrite any production database under `Data/`.
- Do not reuse an earlier approval for later database actions. Every database write needs a fresh explicit user instruction.
- Database backup memory:
  - Pulling a production DB backup to local is allowed when explicitly requested in the current session because it is read-only on the server side.
  - Preferred method: generate a remote SQLite `.backup` snapshot under `/tmp`, copy it to local, then delete the remote temporary snapshot.
  - Do not overwrite local `Data/` when pulling a production backup. Store remote copies under `local_backups/remote_db_snapshots/`.
  - Retention rule stays at 2 local backup directories unless the user explicitly changes it again.

## Deployment Notes

- App root on server: `/root/nxr_website`
- Local sync script: `/Users/phoenix/Documents/Phoenxi/nxr_website/scripts/sync_to_server.sh`
- Local remote-DB backup script: `/Users/phoenix/Documents/Phoenxi/nxr_website/scripts/pull_remote_db_backup.sh`
- Database directory: `/Users/phoenix/Documents/Phoenxi/nxr_website/Data/` -> `/root/nxr_website/Data/`
- Local main site entrypoint: `/Users/phoenix/Documents/Phoenxi/nxr_website/nxr_site/app.py`
- Local admin entrypoint: `/Users/phoenix/Documents/Phoenxi/nxr_website/nxr_admin/app_updated.py`
- Current production main-site start command: `/usr/bin/python3 app.py`
- Current production admin start command: `/usr/bin/python3 /root/nxr_website/nxr_admin/app_updated.py`
- Current main-site port: `8080`
- Current admin port: `8081`
- Public site is served through Nginx and proxied to `127.0.0.1:8080`
- Public admin path is served through Nginx rewrite/proxy to `127.0.0.1:8081`
- Preferred remote restart helper: `/Users/phoenix/Documents/Phoenxi/nxr_website/scripts/restart_remote_apps.sh`

## Independent Photo Renamer

- User requirement recorded on 2026-09-06: the photo-renaming webpage is a separately managed static tool. Its deployment and maintenance must not conflict with or disrupt the existing production systems.
- Its source and dedicated deployment rules are under `nxr_platform/nxr-photo-renamer/`; read that directory's `AGENTS.md` before changing or deploying the tool.
- Its verified standalone production root is `/var/www/nxr-photo-renamer`, served at `https://nxrgrading.com/tools/photo-renamer/` through `/etc/nginx/snippets/nxr-photo-renamer.conf`. Preserve this dedicated include and URL prefix during unrelated Nginx work.
- Keep its release files and configuration separate from `/root/nxr_website`. Do not use the main project sync/restart scripts to deploy it, and do not change production databases, Python application processes, Java traffic switching, or existing synchronization jobs for this tool.
- Any shared Nginx change must preserve existing routes and configuration, check for concurrent edits, pass `nginx -t`, and use a graceful reload. Verify the existing public site and admin service before and after the change.

## Network Routing Preference

- User preference recorded on 2026-09-07: ordinary requests should use a direct connection. Use Clash proxy nodes only for specific services or scenarios that need them, with explicit domain/IP rules when necessary.
- Preserve rule-based routing and existing unrelated rules. Do not switch all traffic to a global VPN/proxy or globally disable the user's network setup for a single task.
- Keep localhost, private networks, and this project's production server on direct routes. When a TUN interface intercepts traffic, removing proxy environment variables alone is not proof of direct routing; verify the actual route or connection.
- The photo-renamer deployment helper supports macOS `--direct-interface` for SSH/SCP. Determine the current physical interface before using it; do not assume an interface name from an earlier session.
