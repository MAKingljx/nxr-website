# Parallel Java Stage Deployment

This directory describes a production-host staging deployment that does not
replace or proxy the existing Python services.

## Isolation contract

- Python remains on ports `8080` and `8081` and keeps all public Nginx routes.
- The active Java slot binds to `127.0.0.1:18088` (blue) or `127.0.0.1:18089`
  (green). Both ports may be live only during a checked blue/green handover.
- Java web/admin static builds bind only to `127.0.0.1:18080` and
  `127.0.0.1:18081`.
- Optional remote Java builds remain loopback-only on `127.0.0.1:18082` and
  `127.0.0.1:18083`; the existing TLS virtual host exposes only
  `/java-stage/` and `/java-stage-admin/`.
- Java uses the new MySQL database `nxr_java_stage` and account
  `nxr_java_stage`; it never reads or writes `/root/nxr_website/Data`.
- The optional Python-to-Java synchronizer reads both SQLite files read-only.
  MySQL failures stop only that one-shot task and never enter Flask requests.
- Redis is a dedicated instance on `127.0.0.1:16379`.
- Real secrets live only in root-readable `/etc/nxr-java` files.

## Release layout

```text
/opt/nxr-java/
  current -> releases/<git-commit>/
  slots/
    blue -> releases/<git-commit>/
    green -> releases/<git-commit>/
  releases/<git-commit>/
    ruoyi-admin.jar
    RELEASE_COMMIT
    BACKEND_SOURCE_TREE
    SHA256SUMS
    web/
    admin/
    web-remote/
    admin-remote/
    scripts/
      migrate_python_to_java_mysql.py
      sync_python_to_java_mysql.py
    sql/
      10_nxr_product_types.sql
```

Runtime files live under `/var/lib/nxr-java`; bounded application logs live
under `/var/log/nxr-java/<slot>`. Deployment state and its two most recent
configuration backups live under `/var/lib/nxr-java-deploy`. Each Java slot is
capped at 640 MB, Redis at 96 MB, and the accompanying MySQL drop-in at 768 MB.
The inactive Java slot is normally stopped.

## Hot-deploy contract

- A release is accepted only when every file is covered by a valid
  `SHA256SUMS` manifest and no release path is writable by group or other users.
- `BACKEND_SOURCE_TREE` records the Git tree for the Java backend. When it is
  unchanged, the release builder may produce a different JAR, but deployment
  updates only the four static roots with one graceful Nginx reload; Java is
  not restarted.
- When backend source changed, the inactive slot starts first and must pass the
  read-only health endpoint. Nginx then switches the backend port and all four
  absolute static roots in one graceful reload. Existing requests stay with
  old Nginx workers while new requests use the new slot.
- The old Java slot is stopped only after its established connections remain at
  zero and Spring completes graceful shutdown. A failed candidate or failed
  Nginx/post-switch check automatically restores the previous configuration.
- Blue/green overlap is refused when available memory plus free swap is below
  768 MB, the target slot is already running, or any `sys_job` row is enabled.
  The last guard prevents duplicate Quartz execution until clustered scheduling
  is introduced. The current stage jobs are expected to remain paused.
- These operations never copy, replace, restore, or write the Python `Data/`
  directory and never change the Python ports or public catch-all routes.

## Required order

1. Record the current production Git commit, Python service status, listeners,
   memory, disk, Nginx test, and HTTP smoke results.
2. Pull the reviewed commit from GitHub and fetch release artifacts whose
   SHA-256 values match the release manifest.
3. Install OpenJDK 17, MySQL 8, and Redis 7 without changing Python services.
4. Create the `nxr-java` system account and the independent `/opt`, `/etc`,
   `/var/lib`, and `/var/log` paths.
5. Configure MySQL using `mysql-nxr-stage.cnf`; validate the configuration
   before restart. Create only `nxr_java_stage` and its scoped database user.
6. Run `init-new-stage-db.sh` only after its empty-database guard reports zero
   tables. It leaves seeded staff accounts disabled.
7. Generate database, Redis, token, and initial admin secrets outside Git.
   Store the local operator credential in Keychain. Set a new BCrypt hash and
   enable the stage admin in one MySQL transaction before testing login. The
   RuoYi login pre-check accepts passwords from 5 to 20 characters, so use a
   20-character URL-safe random password rather than a longer generated value.
8. Install Redis plus the Java slot template and local-only Nginx template.
   Run `install-java-hot-deploy.sh`; it renders the currently active release,
   runs `nginx -t`, and gracefully reloads without stopping the legacy process.
9. Run `verify-java-stage.sh`, then separately repeat the existing Python main
   site, verify page, card page, admin login, and hidden admin checks.

## Build and hot deploy

Build only from a clean, reviewed commit. The output directory must be empty
and should be outside the repository:

```bash
commit="$(git rev-parse HEAD)"
release_root="$(mktemp -d)"
nxr_platform/deploy/build-java-release.sh "$release_root/$commit" "$commit"
```

Copy that checksummed directory to `/opt/nxr-java/releases/<commit>` without
copying `Data/`. On the first upgrade, install the runtime files from the same
commit and bootstrap the current release:

```bash
nxr_platform/deploy/install-java-hot-deploy.sh
```

Deploy or roll back by naming an already installed, verified release. The same
command automatically selects static-only or blue/green mode:

```bash
/usr/local/sbin/nxr-java-hot-deploy <full-git-commit>
nxr_platform/deploy/verify-java-stage.sh
nxr_platform/deploy/verify-java-remote.sh
```

The active slot is recorded in `/var/lib/nxr-java-deploy/active-slot`.
`verify-java-stage.sh` reads it automatically, so it checks port `18088` or
`18089` as appropriate. Do not manually repoint `current`, edit the active
Nginx file, or start both slots outside the deployment command.

## Python data synchronization

Install `08_nxr_python_sync.sql` only in the cloned Java database selected for
migration. Apply `10_nxr_product_types.sql` after a verified MySQL backup when
upgrading a database created before product types were introduced. Put its
exact database name twice in the root-readable
`/etc/nxr-java/python-sync.env`, install the `nxr-python-java-sync` service and
timer, then run one manual full synchronization before enabling the timer.
Create `/opt/nxr-java/python-sync-venv` with the system Python venv module and
install only `requirements-mysql.txt`; the system Python environment stays
unchanged.

Use a dedicated `nxr_python_sync` MySQL account for the selected clone. Grant
`SELECT`, `INSERT`, `UPDATE`, and `CREATE TEMPORARY TABLES` on that database,
plus `DELETE` only on its `grading_score` table. The table-scoped delete is
required when Python reclassifies a previously graded record as merchandise or
a vintage product; do not grant row deletion on any other table. Store the
generated password only in the same root-readable synchronization environment
file.

The timer runs the synchronizer once per day at 00:00 Asia/Shanghai. It has no
minute-level, boot-time, or missed-run catch-up trigger. The synchronizer still
selects a full source reconciliation when the previous full sync is at least 24
hours old. Source cursors and target writes commit in the same MySQL transaction.
The SQLite files remain the source of truth and are never opened writable by
this task.

## Optional HTTPS access

Build both remote profiles with `npm run build:java-remote`. Install the
resulting `dist-remote` directories as `web-remote` and `admin-remote`, then
install `nginx-java-remote-locations.conf` as a snippet inside the existing
`nxrgrading.com` TLS server block. Validate the complete configuration with
`nginx -t` before a graceful reload.

Before restarting Java, add `https://nxrgrading.com` to
`NXR_CORS_ALLOWED_ORIGINS`, and set both `NXR_MEDIA_PUBLIC_BASE_URL` and
`NXR_PUBLIC_SITE_BASE_URL` to `https://nxrgrading.com/java-stage`. Preserve all
secret values already present in `/etc/nxr-java/stage.env`.

The public stage URLs are `https://nxrgrading.com/java-stage/` and
`https://nxrgrading.com/java-stage-admin/`. They do not replace the Python
catch-all route or the existing hidden Python admin route. Run
`verify-java-remote.sh` after deployment and independently recheck the Python
routes.

## Rollback

For an application rollback, run `/usr/local/sbin/nxr-java-hot-deploy` with the
previous release commit. It uses the same health checks, atomic Nginx switch,
and connection drain in reverse. To remove the parallel Java stage entirely,
disable and stop both `nxr-java-stage@blue` and `nxr-java-stage@green`, stop
`nxr-java-redis`, remove only the Java Nginx include, run `nginx -t`, and reload
Nginx. Never remove the MySQL database during an ordinary rollback; database
deletion requires a separate explicit authorization.
