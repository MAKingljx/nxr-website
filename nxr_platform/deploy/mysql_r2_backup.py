#!/usr/bin/env python3
"""Private, encrypted MySQL snapshots; never restores into the source database.

The timer/installer owns scheduling. This module owns a narrowly bounded backup
namespace and verifies a native dump in a disposable, restricted schema before
publishing a VERIFIED record. Recovery keys do not belong on the source server.
"""
from __future__ import annotations

import argparse
import collections
import contextlib
import dataclasses
import datetime as dt
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
from typing import Any
from urllib.parse import urlsplit

TOOL = "nxr-mysql-r2-backup"
VERSION = 1
MEMBERS = {"database.sql.gz", "metadata.json"}
OBJECTS = {"backup.tar.age", "verified.json"}
DB_NAME = re.compile(r"[A-Za-z][A-Za-z0-9_]{0,63}\Z")
BUCKET = re.compile(r"[a-z0-9][a-z0-9-]{1,61}[a-z0-9]\Z")
BACKUP_ID = re.compile(r"\d{8}T\d{6}Z-[a-f0-9]{16}\Z")
SHA = re.compile(r"[a-f0-9]{64}\Z")
RECIPIENT = re.compile(r"age1[a-z0-9]{58}\Z")
IDENTIFIER = re.compile(r"[A-Za-z0-9_]+\Z")
CREATED = re.compile(rb"CREATE TABLE `((?:``|[^`])+)`")
INSERT = re.compile(rb"INSERT INTO `((?:``|[^`])+)` VALUES ")
COORDS = re.compile(rb"^-- CHANGE (?:REPLICATION SOURCE|MASTER) TO (?:SOURCE|MASTER)_LOG_FILE='([A-Za-z0-9_.-]+)', (?:SOURCE|MASTER)_LOG_POS=(\d+);$", re.MULTILINE)
ENV_KEYS = {"SOURCE_DATABASE", "MYSQL_SOCKET", "MYSQL_USER", "MYSQL_DEFAULTS_FILE", "R2_ENDPOINT", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_BUCKET", "IMAGE_BUCKET", "AGE_RECIPIENT", "PRIVATE_BUCKET_CONFIRMED", "WORK_ROOT", "STATE_ROOT", "PREFIX", "RETENTION", "ALLOW_ISOLATED_RESTORE", "MYSQL_BIN", "MYSQLDUMP_BIN", "AGE_BIN", "PROCESS_TIMEOUT"}


class BackupError(Exception):
    """Only deliberately sanitized descriptions cross the CLI boundary."""


def private_file(path: Path) -> None:
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise BackupError("private file must be owned by this user, regular, and mode 0600")


def private_dir(path: Path) -> Path:
    if not path.is_absolute() or path.is_symlink():
        raise BackupError("private directories must be absolute and not symbolic links")
    path.mkdir(parents=True, mode=0o700, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise BackupError("private directory must be owned by this user and mode 0700")
    return path


def write_private(path: Path, data: bytes) -> None:
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as handle:
        handle.write(data)


def json_bytes(value: Any) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def sha_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def safe_db(name: str) -> str:
    if not DB_NAME.fullmatch(name):
        raise BackupError("invalid database identifier")
    return name


def table_sql(name: str) -> str:
    # Native dumps quote arbitrary table names; escape the delimiter again.
    if "\x00" in name or len(name.encode()) > 192:
        raise BackupError("invalid table identifier")
    return "`" + name.replace("`", "``") + "`"


def load_env(path: Path | None) -> dict[str, str]:
    values: dict[str, str] = {}
    if path is not None:
        private_file(path)
        for line in path.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                raise BackupError("invalid backup environment file")
            key, value = line.split("=", 1)
            if not key.startswith("NXR_BACKUP_") or key.removeprefix("NXR_BACKUP_") not in ENV_KEYS:
                raise BackupError("unknown backup environment setting")
            # This is deliberately not shell evaluation or interpolation.
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            values[key.removeprefix("NXR_BACKUP_")] = value
    for key in ENV_KEYS:
        if "NXR_BACKUP_" + key in os.environ:
            values[key] = os.environ["NXR_BACKUP_" + key]
    return values


@dataclasses.dataclass(frozen=True)
class Config:
    source_database: str
    mysql_socket: str
    r2_endpoint: str
    r2_access_key_id: str = dataclasses.field(repr=False)
    r2_secret_access_key: str = dataclasses.field(repr=False)
    r2_bucket: str
    image_bucket: str
    age_recipient: str
    private_bucket_confirmed: bool
    work_root: Path
    state_root: Path
    allow_isolated_restore: bool = False
    mysql_user: str = "root"
    mysql_defaults_file: Path | None = None
    prefix: str = ""
    retention: int = 2
    mysql_bin: str = "mysql"
    mysqldump_bin: str = "mysqldump"
    age_bin: str = "age"
    process_timeout: int = 3600

    @classmethod
    def from_values(cls, values: dict[str, str]) -> "Config":
        required = ("SOURCE_DATABASE", "MYSQL_SOCKET", "R2_ENDPOINT", "R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY", "R2_BUCKET", "IMAGE_BUCKET", "AGE_RECIPIENT", "WORK_ROOT", "STATE_ROOT")
        if any(not values.get(key) for key in required):
            raise BackupError("required backup settings are missing")
        try:
            cfg = cls(
                source_database=values["SOURCE_DATABASE"], mysql_socket=values["MYSQL_SOCKET"],
                r2_endpoint=values["R2_ENDPOINT"], r2_access_key_id=values["R2_ACCESS_KEY_ID"],
                r2_secret_access_key=values["R2_SECRET_ACCESS_KEY"], r2_bucket=values["R2_BUCKET"],
                image_bucket=values["IMAGE_BUCKET"], age_recipient=values["AGE_RECIPIENT"],
                private_bucket_confirmed=values.get("PRIVATE_BUCKET_CONFIRMED") == "1",
                work_root=Path(values["WORK_ROOT"]), state_root=Path(values["STATE_ROOT"]),
                allow_isolated_restore=values.get("ALLOW_ISOLATED_RESTORE") == "1",
                mysql_user=values.get("MYSQL_USER", "root"),
                mysql_defaults_file=Path(values["MYSQL_DEFAULTS_FILE"]) if values.get("MYSQL_DEFAULTS_FILE") else None,
                prefix=values.get("PREFIX", ""), retention=int(values.get("RETENTION", "2")),
                mysql_bin=values.get("MYSQL_BIN", "mysql"), mysqldump_bin=values.get("MYSQLDUMP_BIN", "mysqldump"),
                age_bin=values.get("AGE_BIN", "age"), process_timeout=int(values.get("PROCESS_TIMEOUT", "3600")),
            )
        except (ValueError, TypeError):
            raise BackupError("invalid numeric backup setting") from None
        cfg.validate()
        return cfg

    def validate(self) -> None:
        safe_db(self.source_database)
        if self.source_database.startswith("nxr_backup_verify_") or self.source_database in {"mysql", "sys", "information_schema", "performance_schema"}:
            raise BackupError("system and verification schemas cannot be backup sources")
        if not all(BUCKET.fullmatch(value) for value in (self.r2_bucket, self.image_bucket)) or self.r2_bucket == self.image_bucket:
            raise BackupError("backup bucket must be valid and separate from the card image bucket")
        endpoint = urlsplit(self.r2_endpoint)
        if endpoint.scheme != "https" or endpoint.username or endpoint.password or endpoint.port or endpoint.path not in {"", "/"} or endpoint.query or endpoint.fragment or not re.fullmatch(r"[a-f0-9]{32}\.r2\.cloudflarestorage\.com", endpoint.hostname or ""):
            raise BackupError("only the private Cloudflare R2 S3 endpoint is supported")
        if not self.private_bucket_confirmed:
            raise BackupError("private backup bucket confirmation is required")
        if not RECIPIENT.fullmatch(self.age_recipient):
            raise BackupError("a public age X25519 recipient is required")
        if self.prefix and self.prefix != self.expected_prefix:
            raise BackupError("backup prefix must match the source database namespace")
        if self.retention != 2:
            raise BackupError("this backup policy retains exactly two verified sets")
        if not self.mysql_socket.startswith("/") or not IDENTIFIER.fullmatch(self.mysql_user) or not 30 <= self.process_timeout <= 86400:
            raise BackupError("invalid native client settings")
        for path in (self.work_root, self.state_root):
            if not path.is_absolute() or path.is_symlink():
                raise BackupError("backup directories must be absolute and not symbolic links")
        if self.work_root.resolve() == self.state_root.resolve():
            raise BackupError("working and state directories must be separate")
        if self.mysql_defaults_file:
            private_file(self.mysql_defaults_file)

    @property
    def expected_prefix(self) -> str:
        return "mysql/" + self.source_database + "/"


class NativeMySQL:
    def __init__(self, cfg: Config):
        self.cfg = cfg

    def client(self, binary: str, defaults: Path | None = None, user: str | None = None) -> list[str]:
        defaults = defaults or self.cfg.mysql_defaults_file
        settings = ["--defaults-file=" + str(defaults)] if defaults else ["--no-defaults"]
        return [binary, *settings, "--protocol=SOCKET", "--socket=" + self.cfg.mysql_socket, "--user=" + (user or self.cfg.mysql_user)]

    def execute(self, args: list[str], *, input_data: bytes | None = None, output: Any = subprocess.PIPE, stdin: Any = None) -> bytes:
        env = {key: value for key, value in os.environ.items() if key not in {"MYSQL_PWD", "MYSQL_TEST_LOGIN_FILE"}}
        # MySQL8 lacks --no-login-paths. Pin its documented login-file override
        # to the empty OS device so a personal .mylogin.cnf cannot override the importer.
        env["MYSQL_TEST_LOGIN_FILE"] = os.devnull
        try:
            result = subprocess.run(args, input=input_data, stdin=stdin, stdout=output, stderr=subprocess.DEVNULL, env=env, timeout=self.cfg.process_timeout, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise BackupError("native client could not complete") from None
        if result.returncode:
            raise BackupError("native client failed; raw diagnostics are withheld")
        return result.stdout or b""

    def query(self, sql: str) -> list[list[str]]:
        result = self.execute(self.client(self.cfg.mysql_bin) + ["--batch", "--raw", "--skip-column-names"], input_data=sql.encode())
        return [line.split("\t") for line in result.decode().splitlines()]

    def preflight(self) -> dict[str, Any]:
        db = self.cfg.source_database
        rows = self.query("SELECT TABLE_NAME, ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + db + "' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;")
        if not rows or any(len(row) != 2 or row[1] != "InnoDB" for row in rows):
            raise BackupError("backup source must exist and contain only InnoDB base tables")
        counts = self.query("SELECT (SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='" + db + "'), (SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA='" + db + "'), (SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA='" + db + "'), (SELECT COUNT(*) FROM information_schema.VIEWS WHERE TABLE_SCHEMA='" + db + "');")
        if counts != [["0", "0", "0", "0"]]:
            raise BackupError("triggers, routines, events or views need a separately reviewed backup policy")
        settings = self.query("SELECT VERSION(), @@GLOBAL.log_bin; SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='" + db + "';")
        if len(settings) != 2 or len(settings[0]) != 2 or settings[0][1] != "1" or len(settings[1]) != 2 or any(not IDENTIFIER.fullmatch(item) for item in settings[1]):
            raise BackupError("binary logging or schema settings cannot be verified")
        return {"mysql_version": settings[0][0], "character_set": settings[1][0], "collation": settings[1][1], "base_tables": len(rows)}

    def dump(self, database: str, path: Path, *, coordinates: bool = True) -> None:
        safe_db(database)
        args = self.client(self.cfg.mysqldump_bin) + ["--single-transaction", "--set-gtid-purged=OFF", "--no-tablespaces", "--hex-blob", "--order-by-primary", "--skip-extended-insert", "--default-character-set=utf8mb4", "--skip-triggers"]
        if coordinates:
            args.append("--source-data=2")
        with path.open("xb") as handle:
            os.chmod(path, 0o600)
            self.execute(args + [database], output=handle)

    def verify_restore(self, dump: Path, original: dict[str, Any], work: Path, schema: dict[str, Any]) -> dict[str, Any]:
        if not self.cfg.allow_isolated_restore:
            raise BackupError("isolated restore authorization is required")
        if any(not isinstance(schema.get(key), str) or not IDENTIFIER.fullmatch(schema[key]) for key in ("character_set", "collation")):
            raise BackupError("invalid verification schema settings")
        if inspect_dump(dump) != original:
            raise BackupError("snapshot changed before isolated import")
        suffix = secrets.token_hex(12)
        target = "nxr_backup_verify_" + suffix
        importer = "nxr_bv_" + suffix
        safe_db(target)
        if target == self.cfg.source_database or not re.fullmatch(r"nxr_backup_verify_[a-f0-9]{24}", target) or not re.fullmatch(r"nxr_bv_[a-f0-9]{24}", importer):
            raise BackupError("verification target is outside the scratch namespace")
        credential = work / "importer.cnf"
        password = secrets.token_urlsafe(36)
        write_private(credential, ("[client]\nuser=" + importer + "\npassword=" + password + "\n").encode())
        created_database = created_user = False
        try:
            # If either name already exists, MySQL refuses the command. Never adopt
            # or remove a database/user owned by another run.
            self.query("CREATE DATABASE `" + target + "` CHARACTER SET " + schema["character_set"] + " COLLATE " + schema["collation"] + ";")
            created_database = True
            self.query("CREATE USER '" + importer + "'@'localhost' IDENTIFIED BY '" + password + "';")
            created_user = True
            self.query("GRANT SELECT, INSERT, CREATE, DROP, ALTER, INDEX, LOCK TABLES, REFERENCES ON `" + target + "`.* TO '" + importer + "'@'localhost';")
            with dump.open("rb") as handle:
                self.execute(self.client(self.cfg.mysql_bin, credential, importer) + ["--binary-mode", "--skip-reconnect", "--local-infile=0", "--skip-auto-rehash", "--default-character-set=utf8mb4", target], stdin=handle)
            restored = work / "restored.sql"
            self.dump(target, restored, coordinates=False)
            verified = inspect_dump(restored, require_coordinates=False)
            if verified["tables"] != original["tables"] or verified["data_signatures"] != original["data_signatures"] or verified["ddl_signatures"] != original["ddl_signatures"]:
                raise BackupError("restored table data does not match the snapshot")
            checks = self.query("CHECK TABLE " + ",".join("`" + target + "`." + table_sql(name) for name in original["tables"]) + ";")
            if len(checks) != len(original["tables"]) or any(len(row) != 4 or row[2:] != ["status", "OK"] for row in checks):
                raise BackupError("restored table integrity check failed")
            return {"verified": True, "table_count": len(verified["tables"]), "row_count": sum(value["rows"] for value in verified["data_signatures"].values())}
        finally:
            cleanup_errors = []
            if created_database:
                try:
                    self.query("DROP DATABASE `" + target + "`;")
                except BackupError:
                    cleanup_errors.append("database")
            if created_user:
                try:
                    self.query("DROP USER '" + importer + "'@'localhost';")
                except BackupError:
                    cleanup_errors.append("user")
            credential.unlink(missing_ok=True)
            if cleanup_errors:
                # Scratch names carry no credentials and let an operator inspect
                # the exact orphan safely. Existing source schemas are untouched.
                raise BackupError("scratch cleanup failed: " + target + " / " + importer)


def inspect_dump(path: Path, *, require_coordinates: bool = True) -> dict[str, Any]:
    """Admit only native table dumps and hash the actual immutable row statements.

    mysqldump --skip-extended-insert gives one complete INSERT per line. Global
    controls, executable comments and client escape commands are rejected unless
    they are the native session-level prologue. The importer also has no global
    privilege and cannot select or write any other schema.
    """
    tables: set[str] = set()
    digests: dict[str, Any] = {}
    rows: collections.Counter[str] = collections.Counter()
    coordinates: list[tuple[str, int]] = []
    in_create = False
    ddl_name = ""
    ddl_digests: dict[str, Any] = {}
    allowed_comment = re.compile(rb"^/\*!\d{5} (?:SET |ALTER TABLE `[^`]+` (?:DISABLE|ENABLE) KEYS)(?:.*)\*/;\s*$")
    # Native SET prologue is restricted to session/user variables; never globals.
    allowed_set = re.compile(rb"^(?:SET (?:@|NAMES |CHARACTER_SET_CLIENT|CHARACTER_SET_RESULTS|COLLATION_CONNECTION|TIME_ZONE|UNIQUE_CHECKS|FOREIGN_KEY_CHECKS|SQL_MODE|SQL_NOTES)|/\*!)", re.I)
    with path.open("rb") as handle:
        for line in handle:
            if b"\x00" in line:
                raise BackupError("native dump contains an unexpected binary statement")
            stripped = line.strip()
            if not stripped or stripped.startswith(b"--"):
                matched = COORDS.fullmatch(stripped)
                if matched:
                    coordinates.append((matched[1].decode(), int(matched[2])))
                continue
            if not INSERT.match(stripped):
                if re.search(rb"`(?:``|[^`])+`\s*\.\s*`", stripped):
                    raise BackupError("native dump contains a qualified cross-schema identifier")
                if re.search(rb";\s*(?:USE\b|CREATE\s+(?:DATABASE|SCHEMA|USER)|DROP\s+(?:DATABASE|SCHEMA|USER)|SET\s+GLOBAL|GRANT\b|REVOKE\b|LOAD\b|CALL\b|SOURCE\b|SYSTEM\b|\\)", stripped, re.I):
                    raise BackupError("native dump contains an appended escape statement")
            if stripped.startswith(b"/*!"):
                if not allowed_comment.fullmatch(stripped) or re.search(rb"\b(?:GLOBAL|GTID_PURGED|SQL_LOG_BIN|CREATE|DROP|GRANT|REVOKE|SELECT|CALL|INTO\s+OUTFILE|LOAD|USE)\b", stripped, re.I):
                    raise BackupError("native dump contains a disallowed executable comment")
                continue
            if re.match(rb"(?:USE\b|CREATE\s+(?:DATABASE|SCHEMA|USER|TRIGGER|EVENT|VIEW|FUNCTION|PROCEDURE)|GRANT\b|REVOKE\b|CHANGE\b|RESET\b|FLUSH\b|SOURCE\b|SYSTEM\b|LOAD\b|\\)", stripped, re.I):
                raise BackupError("native dump contains a statement outside the restore schema")
            matched = CREATED.match(stripped)
            if matched:
                name = matched[1].replace(b"``", b"`").decode("utf-8")
                if in_create or name in tables or stripped != b"CREATE TABLE `" + matched[1] + b"` (":
                    raise BackupError("native table declaration is invalid")
                tables.add(name)
                digests.setdefault(name, hashlib.sha256())
                in_create = True
                ddl_name = name
                ddl_digests[name] = hashlib.sha256(line.rstrip(b"\r\n") + b"\n")
                continue
            matched = INSERT.match(stripped)
            if matched:
                name = matched[1].replace(b"``", b"`").decode("utf-8")
                if name not in tables or not stripped.endswith(b";"):
                    raise BackupError("native INSERT is not a complete in-schema row")
                digests[name].update(line.rstrip(b"\r\n") + b"\n")
                rows[name] += 1
                continue
            if re.match(rb"SET\b", stripped, re.I):
                if not allowed_set.match(stripped) or re.search(rb"\b(?:GLOBAL|GTID_PURGED|SQL_LOG_BIN|SELECT|CALL|OUTFILE|INFILE)\b", stripped, re.I):
                    raise BackupError("native dump contains disallowed session controls")
                continue
            # An importer with only scratch-schema grants is the final SQL
            # boundary; this parser also rejects obvious escape statements.
            if in_create and stripped.startswith((b"`", b"PRIMARY KEY", b"UNIQUE KEY", b"KEY ", b"CONSTRAINT ", b"CHECK ", b"FULLTEXT KEY ", b"SPATIAL KEY ")):
                ddl_digests[ddl_name].update(line.rstrip(b"\r\n") + b"\n")
                continue
            if in_create and re.fullmatch(rb"\) ENGINE=InnoDB [^\r\n]*;", stripped):
                ddl_digests[ddl_name].update(line.rstrip(b"\r\n") + b"\n")
                in_create = False
                continue
            if re.fullmatch(rb"DROP TABLE IF EXISTS `(?:``|[^`])+`;", stripped) or re.fullmatch(rb"LOCK TABLES `(?:``|[^`])+` WRITE;", stripped) or stripped == b"UNLOCK TABLES;":
                continue
            raise BackupError("native dump contains an unsupported statement")
    if in_create or not tables or (require_coordinates and len(coordinates) != 1):
        raise BackupError("native dump tables or binary log coordinates are missing")
    if len(coordinates) > 1 or (coordinates and coordinates[0][1] < 4):
        raise BackupError("invalid binary log coordinates")
    return {"tables": sorted(tables), "ddl_signatures": {name: ddl_digests[name].hexdigest() for name in sorted(tables)}, "data_signatures": {name: {"sha256": digests[name].hexdigest(), "rows": rows[name]} for name in sorted(tables)}, "binlog": {"file": coordinates[0][0], "position": coordinates[0][1]} if coordinates else None}


def create_package(sql: Path, metadata: dict[str, Any], work: Path) -> Path:
    compressed = work / "database.sql.gz"
    with sql.open("rb") as source, compressed.open("xb") as destination:
        os.chmod(compressed, 0o600)
        with gzip.GzipFile(filename="", mode="wb", fileobj=destination, mtime=0) as zipper:
            shutil.copyfileobj(source, zipper)
    meta = work / "metadata.json"
    write_private(meta, json_bytes(metadata))
    archive = work / "backup.tar"
    fd = os.open(archive, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as destination, tarfile.open(fileobj=destination, mode="w") as bundle:
        for member in sorted(MEMBERS):
            info = bundle.gettarinfo(str(work / member), arcname=member)
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            info.mode = 0o600
            with (work / member).open("rb") as handle:
                bundle.addfile(info, handle)
    os.chmod(archive, 0o600)
    audit_package(archive)
    return archive


def audit_package(path: Path) -> None:
    with tarfile.open(path, "r") as archive:
        members = archive.getmembers()
        if len(members) != 2 or {member.name for member in members} != MEMBERS or any(not member.isfile() or member.mode != 0o600 for member in members):
            raise BackupError("backup archive contains unexpected members")


class AgeEncryptor:
    def __init__(self, cfg: Config):
        self.cfg = cfg

    def preflight(self) -> None:
        try:
            result = subprocess.run([self.cfg.age_bin, "--encrypt", "--recipient", self.cfg.age_recipient], input=b"", stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
        except (OSError, subprocess.TimeoutExpired):
            raise BackupError("age encryption is unavailable") from None
        if result.returncode:
            raise BackupError("age recipient or encryption tool is invalid")

    def encrypt(self, source: Path, target: Path) -> None:
        try:
            fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(fd, "wb") as output:
                result = subprocess.run([self.cfg.age_bin, "--encrypt", "--recipient", self.cfg.age_recipient, "--output", "-", str(source)], stdout=output, stderr=subprocess.DEVNULL, timeout=self.cfg.process_timeout)
        except (OSError, subprocess.TimeoutExpired):
            raise BackupError("age encryption could not complete") from None
        if result.returncode or not target.is_file() or target.stat().st_size < 100:
            raise BackupError("age encryption failed")
        os.chmod(target, 0o600)
        with target.open("rb") as handle:
            if handle.read(22) != b"age-encryption.org/v1\n":
                raise BackupError("age ciphertext header is invalid")


class R2Storage:
    def __init__(self, cfg: Config):
        try:
            import boto3
            from botocore.config import Config as BotoConfig
        except ImportError:
            raise BackupError("backup boto3 dependency is unavailable") from None
        self.bucket = cfg.r2_bucket
        self.client = boto3.client("s3", endpoint_url=cfg.r2_endpoint, aws_access_key_id=cfg.r2_access_key_id, aws_secret_access_key=cfg.r2_secret_access_key, region_name="auto", config=BotoConfig(signature_version="s3v4", connect_timeout=10, read_timeout=120, retries={"max_attempts": 3}, s3={"addressing_style": "path"}))

    def preflight(self) -> None:
        self.client.head_bucket(Bucket=self.bucket)

    def upload(self, key: str, path: Path) -> None:
        self.client.upload_file(str(path), self.bucket, key, ExtraArgs={"ContentType": "application/octet-stream", "Metadata": {"sha256": sha_file(path)}})

    def download(self, key: str, path: Path) -> None:
        # Avoid download_file temporary paths: a private descriptor exists before
        # any ciphertext bytes arrive, regardless of the caller's umask.
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "wb") as output:
            self.client.download_fileobj(self.bucket, key, output)

    def put(self, key: str, body: bytes) -> None:
        self.client.put_object(Bucket=self.bucket, Key=key, Body=body, ContentType="application/json")

    def read(self, key: str) -> bytes:
        result = self.client.get_object(Bucket=self.bucket, Key=key)
        try:
            data = result["Body"].read(65537)
        finally:
            result["Body"].close()
        if len(data) > 65536:
            raise BackupError("backup marker is too large")
        return data

    def keys(self, prefix: str) -> list[str]:
        output = []
        paginator = self.client.get_paginator("list_objects_v2")
        for page in paginator.paginate(Bucket=self.bucket, Prefix=prefix):
            output.extend(item["Key"] for item in page.get("Contents", []))
            if len(output) > 10000:
                raise BackupError("backup namespace exceeds the safe listing limit")
        return output

    def delete(self, key: str) -> None:
        self.client.delete_object(Bucket=self.bucket, Key=key)


def owned_key(cfg: Config, backup_id: str, filename: str) -> str:
    if not BACKUP_ID.fullmatch(backup_id) or filename not in OBJECTS:
        raise BackupError("object is outside the owned backup namespace")
    return cfg.expected_prefix + backup_id + "/" + filename


def valid_marker(cfg: Config, backup_id: str, body: bytes) -> dict[str, Any]:
    try:
        marker = json.loads(body)
        valid = marker["tool"] == TOOL and marker["version"] == VERSION and marker["status"] == "VERIFIED" and marker["source_database"] == cfg.source_database and marker["backup_id"] == backup_id and BACKUP_ID.fullmatch(backup_id) and SHA.fullmatch(marker["ciphertext_sha256"]) and marker["object"] == owned_key(cfg, backup_id, "backup.tar.age") and marker["isolated_restore"]["verified"] is True and marker["ciphertext_roundtrip_verified"] is True and type(marker["bytes"]) is int and marker["bytes"] > 100
        restoration = marker["isolated_restore"]
        log = marker["binlog"]
        valid = valid and type(restoration["table_count"]) is int and restoration["table_count"] > 0 and type(restoration["row_count"]) is int and restoration["row_count"] >= 0 and re.fullmatch(r"[A-Za-z0-9_.-]+", log["file"]) and type(log["position"]) is int and log["position"] >= 4
        created = dt.datetime.fromisoformat(marker["created_at"])
        if not valid or created.tzinfo is None or created.utcoffset() != dt.timedelta(0) or created.strftime("%Y%m%dT%H%M%SZ-") != backup_id[:17]:
            raise ValueError()
        return marker
    except (ValueError, KeyError, TypeError):
        raise BackupError("invalid verified backup marker") from None


class BackupService:
    def __init__(self, cfg: Config, mysql: Any = None, storage: Any = None, encryption: Any = None):
        cfg.validate()
        self.cfg = cfg
        self.mysql = mysql or NativeMySQL(cfg)
        self.storage = storage or R2Storage(cfg)
        self.encryption = encryption or AgeEncryptor(cfg)

    def preflight(self) -> dict[str, Any]:
        source = self.mysql.preflight()
        self.encryption.preflight()
        self.storage.preflight()
        return {"tool": TOOL, "source_database": self.cfg.source_database, "bucket": self.cfg.r2_bucket, "prefix": self.cfg.expected_prefix, "source": source, "isolated_restore_authorized": self.cfg.allow_isolated_restore}

    def status(self) -> dict[str, Any]:
        keys = set(self.storage.keys(self.cfg.expected_prefix))
        records = []
        ignored = 0
        for key in sorted(keys):
            relative = key.removeprefix(self.cfg.expected_prefix)
            parts = relative.split("/")
            if len(parts) != 2 or parts[1] != "verified.json" or not BACKUP_ID.fullmatch(parts[0]):
                continue
            try:
                marker = valid_marker(self.cfg, parts[0], self.storage.read(key))
                if marker["object"] not in keys:
                    raise BackupError("verified backup ciphertext is missing")
                records.append(marker)
            except BackupError:
                ignored += 1
        return {"tool": TOOL, "source_database": self.cfg.source_database, "bucket": self.cfg.r2_bucket, "verified_backups": sorted(records, key=lambda item: (item["created_at"], item["backup_id"]), reverse=True), "invalid_markers": ignored}

    def prune(self, current_id: str) -> list[str]:
        records = self.status()["verified_backups"]
        if current_id not in {item["backup_id"] for item in records}:
            raise BackupError("current backup must be verified before retention")
        if current_id not in {item["backup_id"] for item in records[:2]}:
            raise BackupError("clock moved backwards; retention requires review")
        # Retention is limited to marker-owned pairs. Unknown and failed artifacts
        # are never adopted or deleted, nor are legacy backups/card images.
        removed = []
        for record in reversed(records[2:]):
            backup_id = record["backup_id"]
            if backup_id == current_id:
                raise BackupError("clock moved backwards; retention requires review")
            self.storage.delete(owned_key(self.cfg, backup_id, "backup.tar.age"))
            self.storage.delete(owned_key(self.cfg, backup_id, "verified.json"))
            removed.append(backup_id)
        return removed

    @contextlib.contextmanager
    def lock(self):
        state = private_dir(self.cfg.state_root)
        fd = os.open(state / "backup.lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        try:
            info = os.fstat(fd)
            if info.st_uid != os.getuid() or info.st_mode & 0o077 or not stat.S_ISREG(info.st_mode):
                raise BackupError("unsafe backup lock")
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise BackupError("another backup is already running") from None
            yield
        finally:
            os.close(fd)

    def run(self) -> dict[str, Any]:
        if not self.cfg.allow_isolated_restore:
            raise BackupError("isolated restore authorization is required before backup")
        with self.lock():
            preflight = self.preflight()
            root = private_dir(self.cfg.work_root)
            created_at = dt.datetime.now(dt.timezone.utc)
            backup_id = created_at.strftime("%Y%m%dT%H%M%SZ-") + secrets.token_hex(8)
            with tempfile.TemporaryDirectory(prefix=backup_id + "-", dir=root) as temporary:
                work = Path(temporary)
                sql = work / "database.sql"
                self.mysql.dump(self.cfg.source_database, sql)
                snapshot = inspect_dump(sql)
                restored = self.mysql.verify_restore(sql, snapshot, work, preflight["source"])
                metadata = {"tool": TOOL, "version": VERSION, "backup_id": backup_id, "source_database": self.cfg.source_database, "created_at": created_at.isoformat(), "dump_sha256": sha_file(sql), "native_options": ["single-transaction", "source-data=2", "set-gtid-purged=OFF", "no-tablespaces", "hex-blob", "order-by-primary", "skip-extended-insert"], "source": preflight["source"], "snapshot": snapshot, "isolated_restore": restored}
                package = create_package(sql, metadata, work)
                encrypted = work / "backup.tar.age"
                self.encryption.encrypt(package, encrypted)
                encrypted_digest = sha_file(encrypted)
                object_key = owned_key(self.cfg, backup_id, "backup.tar.age")
                marker_key = owned_key(self.cfg, backup_id, "verified.json")
                if any(key.startswith(self.cfg.expected_prefix + backup_id + "/") for key in self.storage.keys(self.cfg.expected_prefix)):
                    raise BackupError("backup identifier collision")
                upload_started = verified = False
                try:
                    upload_started = True
                    self.storage.upload(object_key, encrypted)
                    returned = work / "roundtrip.age"
                    self.storage.download(object_key, returned)
                    if sha_file(returned) != encrypted_digest:
                        raise BackupError("R2 ciphertext roundtrip checksum mismatch")
                    marker = {"tool": TOOL, "version": VERSION, "status": "VERIFIED", "source_database": self.cfg.source_database, "backup_id": backup_id, "created_at": metadata["created_at"], "object": object_key, "ciphertext_sha256": encrypted_digest, "bytes": encrypted.stat().st_size, "binlog": snapshot["binlog"], "isolated_restore": restored, "ciphertext_roundtrip_verified": True}
                    self.storage.put(marker_key, json_bytes(marker))
                    if valid_marker(self.cfg, backup_id, self.storage.read(marker_key)) != marker:
                        raise BackupError("R2 verified marker readback mismatch")
                    verified = True
                finally:
                    if upload_started and not verified:
                        # These exact two freshly generated keys are the only
                        # cleanup targets. Existing snapshots are not touched.
                        try:
                            self.storage.delete(marker_key)
                            self.storage.delete(object_key)
                        except Exception:
                            pass
                removed = self.prune(backup_id)
                marker["pruned_backups"] = removed
                marker["retained_verified"] = len(self.status()["verified_backups"])
                self.save_state(marker)
                return marker

    def save_state(self, marker: dict[str, Any]) -> None:
        target = self.cfg.state_root / "last-success.json"
        temporary = self.cfg.state_root / ("last-success-" + secrets.token_hex(8) + ".json")
        write_private(temporary, json_bytes(marker))
        os.replace(temporary, target)

    def resume(self, backup_id: str) -> dict[str, Any]:
        """Finish retention/state after a crash following VERIFIED publication.

        An unverified upload is never promoted: the immutable marker must already
        exist, and its ciphertext is downloaded and checked again first.
        """
        with self.lock():
            root = private_dir(self.cfg.work_root)
            with tempfile.TemporaryDirectory(prefix="resume-", dir=root) as temporary:
                self.fetch(backup_id, Path(temporary) / "backup.tar.age")
            marker = valid_marker(self.cfg, backup_id, self.storage.read(owned_key(self.cfg, backup_id, "verified.json")))
            marker["pruned_backups"] = self.prune(backup_id)
            marker["retained_verified"] = len(self.status()["verified_backups"])
            self.save_state(marker)
            return marker

    def fetch(self, backup_id: str, destination: Path) -> dict[str, Any]:
        key = owned_key(self.cfg, backup_id, "backup.tar.age")
        marker = valid_marker(self.cfg, backup_id, self.storage.read(owned_key(self.cfg, backup_id, "verified.json")))
        if not destination.is_absolute() or destination.exists() or destination.is_symlink():
            raise BackupError("download destination must be a new absolute file")
        private_dir(destination.parent)
        try:
            self.storage.download(key, destination)
            if sha_file(destination) != marker["ciphertext_sha256"] or destination.stat().st_size != marker["bytes"]:
                raise BackupError("download checksum mismatch")
        except Exception:
            destination.unlink(missing_ok=True)
            raise
        return {"backup_id": backup_id, "ciphertext_sha256": marker["ciphertext_sha256"], "bytes": destination.stat().st_size, "download_verified": True}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("check", aliases=["preflight"])
    sub.add_parser("run", aliases=["backup"])
    sub.add_parser("status")
    resume = sub.add_parser("resume", help="finish retention for an already VERIFIED snapshot")
    resume.add_argument("backup_id")
    fetch = sub.add_parser("fetch", aliases=["download"])
    fetch.add_argument("backup_id")
    fetch.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    os.umask(0o077)
    try:
        cfg = Config.from_values(load_env(args.env_file))
        service = BackupService(cfg)
        if args.command in {"check", "preflight"}:
            result = service.preflight()
        elif args.command in {"run", "backup"}:
            result = service.run()
        elif args.command == "status":
            result = service.status()
        elif args.command == "resume":
            result = service.resume(args.backup_id)
        else:
            result = service.fetch(args.backup_id, args.output)
        print(json.dumps(result, sort_keys=True))
        return 0
    except BackupError as error:
        print(json.dumps({"tool": TOOL, "status": "FAILED", "error": str(error)}), file=sys.stderr)
    except Exception:
        # SDK/client error strings can contain credentials, SQL and response data.
        print(json.dumps({"tool": TOOL, "status": "FAILED", "error": "backup operation failed; raw diagnostics are withheld"}), file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
