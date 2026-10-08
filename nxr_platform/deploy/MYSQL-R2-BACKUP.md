# NXR MySQL backups to private R2

This tool backs up the Java MySQL database. It does not change the Java release, Flask services or database files, public card-image bucket, or the Python-to-Java synchronization schedule.

A native consistent SQL snapshot records its binary-log file and position. The tool restores this snapshot into a newly created verification schema using an importer restricted to that schema, compares table definitions and row statements, and removes only that run's scratch schema and importer. The validated SQL and metadata are encrypted with an age public recipient. The source server receives no recovery identity. Its backup token must be denied access to the public card bucket; a broader token is rejected before any dump. A verified R2 upload must download with the identical ciphertext hash before it becomes a retained backup.

Schema comparison normalizes only a redundant column `CHARACTER SET` immediately before the matching explicit `COLLATE`; [MySQL specifies that the collation determines its character set](https://dev.mysql.com/doc/refman/8.0/en/charset-column.html). Types, defaults, indexes, foreign keys, collations and auto-increment counters still require a match. Row hashes remain exact. Scratch imports batch transactions per native dump table, without changing source or global MySQL settings.

The backup bucket is private and separate from card images. The configuration and working files are private. Credentials, private recovery keys and runtime database files must never be included in source control or the tooling release. The recovery identity is held in the operator's local Keychain; maintain a separate approved recovery copy before relying on encrypted backups as the sole recovery source.

## Daily operation

The timer triggers at 00:00 Asia/Shanghai. The service waits until that day's existing Python-to-Java sync has completed successfully. It never starts the sync or changes its timer. If synchronization has not completed within two hours, the backup fails and retains the existing verified copies.

`/usr/local/sbin/nxr-mysql-r2-backup preflight` checks configuration and source eligibility. `run` creates a backup on demand. `status` displays backup metadata. `fetch <backup-id> --output <new-path>` downloads a verified encrypted backup. It does not import data into the active database.

The private cleanup journal records exact run ownership. SIGTERM stops and waits for subprocesses before cleanup. After an abrupt crash, `status` reports the exact pending resources; `recover-cleanup` removes only confirmed, attested objects. An uncertain CREATE/DROP result requires manual inspection and blocks later backups rather than guessing ownership.

Only this module's namespace is eligible for pruning. Keep the latest two verified and recoverable backup sets; a failed snapshot, restore, encryption or transfer must not remove a previous verified copy. Old migration backups and card images are outside this retention scope.

## Installation and recovery

Prepare a root-owned mode0600 `/etc/nxr-java/mysql-backup.env` using the example's public field names and a dedicated R2 token limited to the private backup bucket. Install a clean, checksummed backup component release with `install-mysql-r2-backup.sh`. The installer checks dependencies and configuration but does not restart application/database services or enable the timer.

After a real backup passes its isolated restore checks, fetch and decrypt the uploaded artifact with the separately stored recovery identity. Verify the manifest hashes and restore to a separate test instance/schema before enabling `nxr-mysql-r2-backup.timer`. Verify the next trigger with `systemctl list-timers`.

Recovering the production database is a separate operation requiring an approved recovery target, backup time/log position, current-state backup, compatibility checks and explicit authorization. A data snapshot does not include R2 card-image objects. Time-point recovery additionally requires a continuous archived binary-log chain after the snapshot's recorded coordinate; this full-backup component records the coordinate but does not by itself archive the entire log chain.
