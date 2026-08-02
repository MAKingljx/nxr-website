# Parallel Java Stage Deployment

This directory describes a production-host staging deployment that does not
replace or proxy the existing Python services.

## Isolation contract

- Python remains on ports `8080` and `8081` and keeps all public Nginx routes.
- Java binds only to `127.0.0.1:18088`.
- Java web/admin static builds bind only to `127.0.0.1:18080` and
  `127.0.0.1:18081`.
- Java uses the new MySQL database `nxr_java_stage` and account
  `nxr_java_stage`; it never reads or writes `/root/nxr_website/Data`.
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
   enable the stage admin in one MySQL transaction before testing login.
8. Install the two systemd units and local-only Nginx file. Run `nginx -t`
   before a graceful reload.
9. Run `verify-java-stage.sh`, then separately repeat the existing Python main
   site, verify page, card page, admin login, and hidden admin checks.

## Rollback

Disable and stop `nxr-java-stage` and `nxr-java-redis`, remove only the
local-only Nginx include, run `nginx -t`, and reload Nginx. Point
`/opt/nxr-java/current` back to the prior release if only an application
rollback is needed. Never remove the MySQL database during an ordinary
rollback; database deletion requires a separate explicit authorization.
