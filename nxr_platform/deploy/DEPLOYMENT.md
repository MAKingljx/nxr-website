# Parallel Java Stage Deployment

This directory describes a production-host staging deployment that does not
replace or proxy the existing Python services.

## Isolation contract

- Python remains on ports `8080` and `8081` and keeps all public Nginx routes.
- Java binds only to `127.0.0.1:18088`.
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
  releases/<git-commit>/
    ruoyi-admin.jar
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
under `/var/log/nxr-java`. The Java service is capped at 640 MB, Redis at 96
MB, and the accompanying MySQL drop-in at 768 MB.

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
8. Install the two systemd units and local-only Nginx file. Run `nginx -t`
   before a graceful reload.
9. Run `verify-java-stage.sh`, then separately repeat the existing Python main
   site, verify page, card page, admin login, and hidden admin checks.

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

Disable and stop `nxr-java-stage` and `nxr-java-redis`, remove only the
local-only Nginx include, run `nginx -t`, and reload Nginx. Point
`/opt/nxr-java/current` back to the prior release if only an application
rollback is needed. Never remove the MySQL database during an ordinary
rollback; database deletion requires a separate explicit authorization.
