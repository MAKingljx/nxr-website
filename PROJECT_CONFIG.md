# NXR Website Project Config

## Local Workspace

- Local project root: `/Users/phoenix/Documents/Phoenxi/nxr_website`
- Main site entrypoint: `nxr_site/app.py`
- Admin/control backend entrypoint: `nxr_admin/app_updated.py`

## Production App

- Production root: `/root/nxr_website`
- Current production start command: `cd /root/nxr_website && /usr/bin/python3 app.py`
- Current running process: `/usr/bin/python3 app.py`
- App port: `8080`
- Reverse proxy: Nginx -> `127.0.0.1:8080`
- Public domain: `https://nxrgrading.com/`

## Validation Workflow

1. Change code locally first.
2. Run local validation before touching production.
3. Sync verified files to `/root/nxr_website`. If the change touches databases, sync `Data/` as well.
4. Restart the app on the server.
5. Smoke test `127.0.0.1:8080` and the public site.

## Key Project Files

- Main site code: `nxr_site/`
- Admin/control backend: `nxr_admin/`
- Local sync script: `scripts/sync_to_server.sh`
- Legacy production entrypoint still present on server: `app.py`
- Editor page: `editor/`
- Databases: `Data/cards.db`, `Data/temp_cards.db`
- Project prompt: `AGENTS.md`
- SSH notes: `SSH.md` (local-only)

## Sync Command

- Preferred sync command: `./scripts/sync_to_server.sh`
- Override target host if needed: `DEPLOY_TARGET=root@your-server ./scripts/sync_to_server.sh`
- Override remote root if needed: `DEPLOY_ROOT=/root/nxr_website ./scripts/sync_to_server.sh`
