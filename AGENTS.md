# NXR Website Project Prompt

## Preferred Workflow

Work locally first. Do not treat the production server as the primary editing environment unless there is an emergency hotfix.

1. Make code and template changes locally in `/Users/phoenix/Documents/Phoenxi/nxr_website`.
2. Run local validation before touching the server.
3. Verify the affected routes, templates, and data paths with targeted tests or quick scripts.
4. Only after local verification passes, sync the changed files to the server at `/root/nxr_website`.
5. Restart the app on the server and run a live smoke test against `127.0.0.1:8080`.
6. Keep GitHub in sync after the fix is verified.

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

## Deployment Notes

- App root on server: `/root/nxr_website`
- Local main site entrypoint: `/Users/phoenix/Documents/Phoenxi/nxr_website/nxr_site/app.py`
- Local admin entrypoint: `/Users/phoenix/Documents/Phoenxi/nxr_website/nxr_admin/app_updated.py`
- Current production start command: `/usr/bin/python3 app.py`
- Current app port: `8080`
- Public site is served through Nginx and proxied to `127.0.0.1:8080`
