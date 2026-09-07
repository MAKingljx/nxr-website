#!/usr/bin/env python3
"""Publish the audited NXR photo-renamer dist without touching NXR apps/data.

The default invocation is read-only. Pass --apply to upload/switch a release.
All remote mutations are restricted to this tool's owned root, snippet, one
exact include line, and a graceful Nginx reload after `nginx -t` succeeds.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shlex
import subprocess
import sys
import tempfile
import uuid
from pathlib import Path
from typing import Any


PROJECT_ID = "nxr-photo-renamer"
DEFAULT_TARGET = "root@147.182.183.201"
REMOTE_ROOT = "/var/www/nxr-photo-renamer"
REMOTE_SNIPPET = "/etc/nginx/snippets/nxr-photo-renamer.conf"
REMOTE_VHOST = "/etc/nginx/sites-available/nxrgrading.com"
REMOTE_DEFAULT_VHOST = "/etc/nginx/sites-available/nxr"
REMOTE_JAVA_SNIPPET = "/etc/nginx/snippets/nxr-java-remote-locations.conf"
INCLUDE_ANCHOR = "    include /etc/nginx/snippets/nxr-java-remote-locations.conf;\n"
TOOL_INCLUDE = "    include /etc/nginx/snippets/nxr-photo-renamer.conf;\n"
TOOL_URL = "https://nxrgrading.com/tools/photo-renamer/"
PUBLIC_URL = "https://nxrgrading.com/"
PUBLIC_ADMIN_URL = "https://nxrgrading.com/x7k9m2q4r8v6c3p1"
DIRECT_SITE_URL = "http://127.0.0.1:8080/"
DIRECT_ADMIN_URL = "http://127.0.0.1:8081/admin/login"
RELEASE_RE = re.compile(r"^\d{8}T\d{6}Z-[0-9a-f]{12}$")
TARGET_RE = re.compile(r"^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+$")
OCR_STATIC_ASSETS = {
    "assets/ocr.worker-7.0.0.js", "assets/ocr.third-party-licenses.txt",
    *(f"assets/tesseract-core{variant}.wasm.js" for variant in
      ("", "-simd", "-relaxedsimd", "-lstm", "-simd-lstm", "-relaxedsimd-lstm")),
}
ASSET_RE = re.compile(
    r"^assets/(?:index-[A-Za-z0-9_-]+\.(?:js|css)|(?:qr|webp)\.worker-[A-Za-z0-9_-]+\.js|ocr-language-[A-Za-z0-9_-]+\.js)$"
)
MAX_FILE_BYTES = 8 * 1024 * 1024
MAX_TOTAL_BYTES = 32 * 1024 * 1024

HERE = Path(__file__).resolve().parent
PROJECT_ROOT = HERE.parent
DEFAULT_DIST = PROJECT_ROOT / "dist"
DEFAULT_EVIDENCE = PROJECT_ROOT / "test-results" / "deploy"


class PublishError(RuntimeError):
    pass


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def utc_stamp() -> str:
    return dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def audit_dist(dist: Path) -> dict[str, dict[str, Any]]:
    if not dist.is_dir() or dist.is_symlink():
        raise PublishError(f"构建目录不存在或不是普通目录：{dist}")
    files: dict[str, dict[str, Any]] = {}
    total = 0
    for root, dirs, names in os.walk(dist, followlinks=False):
        root_path = Path(root)
        for directory in dirs:
            path = root_path / directory
            relative = path.relative_to(dist).as_posix()
            if path.is_symlink() or directory.startswith("."):
                raise PublishError(f"构建目录含符号链接或隐藏目录：{relative}")
        for name in names:
            path = root_path / name
            relative = path.relative_to(dist).as_posix()
            if path.is_symlink() or not path.is_file() or name.startswith("."):
                raise PublishError(f"构建目录含非常规或隐藏文件：{relative}")
            if relative != "index.html" and relative not in OCR_STATIC_ASSETS and not ASSET_RE.fullmatch(relative):
                raise PublishError(f"构建产物不在允许清单：{relative}")
            size = path.stat().st_size
            if size <= 0 or size > MAX_FILE_BYTES:
                raise PublishError(f"构建文件大小异常：{relative} ({size})")
            total += size
            files[relative] = {"sha256": sha256_file(path), "size": size}
    if total > MAX_TOTAL_BYTES:
        raise PublishError(f"构建产物总大小超过限制：{total}")
    if len(files) not in (5, 14) or "index.html" not in files:
        raise PublishError("dist 必须为基础版 5 个文件或含完整 OCR 资源的 14 个文件")
    assets = sorted(name for name in files if name != "index.html")
    ocr_files = {name for name in assets if name in OCR_STATIC_ASSETS or name.startswith("assets/ocr-language-")}
    if len(files) == 14 and (not OCR_STATIC_ASSETS.issubset(files) or len(ocr_files) != 9):
        raise PublishError("OCR 资源必须包含专属 worker、六种 core、语言 chunk 与许可证")
    if len(files) == 5 and ocr_files:
        raise PublishError("OCR 资源不完整")
    base_assets = set(assets) - ocr_files
    if sum(name.endswith(".css") for name in base_assets) != 1 or sum(name.endswith(".js") for name in base_assets) != 3:
        raise PublishError("dist 基础资产必须为 1 个 CSS、主 JS、QR Worker 和 WebP Worker")
    qr_workers = [name for name in assets if name.startswith("assets/qr.worker-")]
    webp_workers = [name for name in assets if name.startswith("assets/webp.worker-")]
    if len(qr_workers) != 1:
        raise PublishError("dist 必须包含唯一的 QR Worker")
    if len(webp_workers) != 1:
        raise PublishError("dist 必须包含唯一的 WebP Worker")

    index = (dist / "index.html").read_text(encoding="utf-8")
    if 'src="./assets/' not in index or 'href="./assets/' not in index or 'src="/assets/' in index:
        raise PublishError("index.html 必须使用相对 ./assets/ 路径")
    workers = qr_workers + webp_workers
    main_js = next(name for name in base_assets if name.startswith("assets/index-") and name.endswith(".js"))
    main_source = (dist / main_js).read_text(encoding="utf-8")
    for worker in workers:
        if Path(worker).name not in main_source:
            raise PublishError(f"主 JS 未引用审计到的 Worker：{worker}")
    if ocr_files:
        model = next(name for name in ocr_files if name.startswith("assets/ocr-language-"))
        if Path(model).name not in main_source or "ocr.worker-7.0.0.js" not in main_source:
            raise PublishError("主 JS 未引用审计到的 OCR 资源")
    return dict(sorted(files.items()))


def release_id_for(files: dict[str, dict[str, Any]]) -> str:
    canonical = "".join(f"{name}\0{meta['sha256']}\0{meta['size']}\n" for name, meta in files.items())
    return f"{utc_stamp()}-{sha256_bytes(canonical.encode())[:12]}"


def remote_source(config: dict[str, Any], body: str) -> str:
    encoded = json.dumps(config, ensure_ascii=True, separators=(",", ":"))
    return f"CFG = {encoded!r}\nimport json\nCFG = json.loads(CFG)\n" + body


SSH_OPTIONS = [
    "-o", "BatchMode=yes",
    "-o", "ConnectTimeout=15",
    "-o", "ServerAliveInterval=5",
    "-o", "ServerAliveCountMax=3",
    "-o", "StrictHostKeyChecking=yes",
]


def run(command: list[str], *, input_text: str | None = None, timeout: int = 120) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            command,
            input=input_text,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=True,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired as exc:
        raise PublishError(f"命令超时：{shlex.join(command[:3])}") from exc
    except subprocess.CalledProcessError as exc:
        detail = (exc.stderr or exc.stdout or "无错误输出").strip()
        raise PublishError(f"命令失败：{detail[-2000:]}") from exc


def remote_python(target: str, config: dict[str, Any], body: str, *, timeout: int = 120) -> dict[str, Any]:
    result = run(["ssh", *SSH_OPTIONS, target, "python3", "-"], input_text=remote_source(config, body), timeout=timeout)
    try:
        value = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise PublishError(f"远端返回无法解析：{result.stdout[-1000:]}") from exc
    if not isinstance(value, dict):
        raise PublishError("远端返回结构无效")
    return value


COMMON_REMOTE = r'''
import hashlib, json, os, pathlib, re, subprocess, urllib.error, urllib.request
P = pathlib.Path

def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def file_hash(path):
    return sha(path) if P(path).is_file() and not P(path).is_symlink() else None

def http_status(url, method="GET"):
    req = urllib.request.Request(url, method=method, headers={"User-Agent": "nxr-photo-renamer-deploy/1"})
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            response.read(1)
            return response.status
    except urllib.error.HTTPError as exc:
        return exc.code

def pgrep(pattern, oldest=False):
    command = ["pgrep"] + (["-o"] if oldest else []) + ["-f", pattern]
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    return sorted(int(item) for item in result.stdout.split() if item.isdigit())

def listener(port, role):
    result = subprocess.run(["ss", "-H", "-ltnp", "sport = :%d" % port], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0: raise RuntimeError("cannot inspect listener %d: %s" % (port, result.stderr[-500:]))
    pids = sorted(set(int(value) for value in re.findall(r"pid=(\d+)", result.stdout)))
    if len(pids) != 1: raise RuntimeError("expected exactly one listener PID on %d, got %r" % (port, pids))
    pid = pids[0]; proc = P("/proc") / str(pid)
    cwd = os.path.realpath(proc / "cwd")
    command = (proc / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace").strip()
    if cwd != "/root/nxr_website" or "python" not in command.lower():
        raise RuntimeError("listener %d is not the expected NXR Python process" % port)
    if role == "admin" and "nxr_admin/app_updated.py" not in command:
        raise RuntimeError("listener 8081 is not the NXR admin entrypoint")
    if role == "site" and not (re.search(r"(^| )(?:/root/nxr_website/)?app\.py($| )", command) or "nxr_site" in command):
        raise RuntimeError("listener 8080 is not the NXR site entrypoint")
    return {"pid": pid, "cwd": cwd, "commandSha256": hashlib.sha256(command.encode()).hexdigest()}

def owner_ok(root):
    marker = root / "project.json"
    if not root.exists(): return False
    if root.is_symlink() or not marker.is_file() or marker.is_symlink(): return False
    try: data = json.loads(marker.read_text())
    except Exception: return False
    return data.get("projectId") == CFG["project_id"] and data.get("schemaVersion") == 1

def current_target(root):
    current = root / "current"
    return os.path.realpath(current) if current.is_symlink() else None

def verify_release_dir(release, require_verified=True):
    release = P(release)
    if release.is_symlink() or not release.is_dir() or not re.fullmatch(r"\d{8}T\d{6}Z-[0-9a-f]{12}", release.name): return None
    dist = release / "dist"; assets = dist / "assets"; marker = release / "manifest.json"
    if dist.is_symlink() or not dist.is_dir() or assets.is_symlink() or not assets.is_dir() or marker.is_symlink() or not marker.is_file(): return None
    try: data = json.loads(marker.read_text())
    except Exception: return None
    if data.get("projectId") != CFG["project_id"] or data.get("releaseId") != release.name: return None
    if require_verified and data.get("verified") is not True: return None
    files = data.get("files")
    if not isinstance(files, dict) or len(files) not in (4, 5, 14) or "index.html" not in files: return None
    ocr_static = {
        "assets/ocr.worker-7.0.0.js", "assets/ocr.third-party-licenses.txt",
        *("assets/tesseract-core%s.wasm.js" % variant for variant in
          ("", "-simd", "-relaxedsimd", "-lstm", "-simd-lstm", "-relaxedsimd-lstm")),
    }
    asset_pattern = re.compile(r"assets/(?:index-[A-Za-z0-9_-]+\.(?:js|css)|(?:qr|webp)\.worker-[A-Za-z0-9_-]+\.js|ocr-language-[A-Za-z0-9_-]+\.js)")
    for name, meta in files.items():
        if name != "index.html" and name not in ocr_static and not asset_pattern.fullmatch(name): return None
        if not isinstance(meta, dict) or not isinstance(meta.get("size"), int) or meta["size"] <= 0 or not re.fullmatch(r"[0-9a-f]{64}", str(meta.get("sha256", ""))): return None
        path = dist / name
        if not path.is_file() or path.is_symlink() or path.stat().st_size != meta["size"] or sha(path) != meta["sha256"]: return None
    assets_in_manifest = set(files) - {"index.html"}
    ocr_files = {name for name in assets_in_manifest if name in ocr_static or name.startswith("assets/ocr-language-")}
    if len(files) == 14 and (not ocr_static.issubset(files) or len(ocr_files) != 9): return None
    if len(files) in (4, 5) and ocr_files: return None
    assets_in_manifest -= ocr_files
    css_count = sum(name.endswith(".css") for name in assets_in_manifest)
    qr_count = sum(name.startswith("assets/qr.worker-") for name in assets_in_manifest)
    webp_count = sum(name.startswith("assets/webp.worker-") for name in assets_in_manifest)
    js_count = sum(name.endswith(".js") for name in assets_in_manifest)
    if css_count != 1 or qr_count != 1: return None
    if len(files) == 4 and (webp_count != 0 or js_count != 2): return None
    if len(files) in (5, 14) and (webp_count != 1 or js_count != 3): return None
    actual = {path.relative_to(dist).as_posix() for path in dist.rglob("*") if path.is_file() and not path.is_symlink()}
    if actual != set(files): return None
    return data

def validate_owned_tree(root):
    if not owner_ok(root): raise RuntimeError("project ownership marker is invalid")
    allowed = {"project.json", "releases", ".staging", "operations", "current"}
    unexpected = sorted(path.name for path in root.iterdir() if path.name not in allowed)
    if unexpected: raise RuntimeError("owned root has unexpected entries: " + ",".join(unexpected))
    for name in ("releases", ".staging", "operations"):
        path = root / name
        if path.exists() and (path.is_symlink() or not path.is_dir()): raise RuntimeError("owned subdirectory is unsafe: " + name)
        if path.is_dir():
            for child in path.rglob("*"):
                if child.is_symlink(): raise RuntimeError("owned tree contains a symlink: " + str(child))
    releases = root / "releases"
    if releases.is_dir():
        for candidate in releases.iterdir():
            if verify_release_dir(candidate, False) is None: raise RuntimeError("owned release is incomplete or unsafe: " + candidate.name)
    staging = root / ".staging"
    if staging.is_dir():
        for candidate in staging.iterdir():
            if candidate.is_symlink() or not candidate.is_dir() or not re.fullmatch(r"\d{8}T\d{6}Z-[0-9a-f]{12}-[0-9a-f]{32}", candidate.name):
                raise RuntimeError("owned staging entry is unsafe: " + candidate.name)
    operations = root / "operations"
    if operations.is_dir():
        for candidate in operations.iterdir():
            if candidate.is_symlink() or not candidate.is_dir() or not re.fullmatch(r"[0-9a-f]{32}", candidate.name):
                raise RuntimeError("owned operation entry is unsafe: " + candidate.name)

def validate_owned_layout(root):
    validate_owned_tree(root)
    current = root / "current"
    if not current.is_symlink(): raise RuntimeError("owned deployment has no current symlink")
    target = P(os.path.realpath(current))
    try: relative = target.relative_to((root / "releases").resolve())
    except ValueError: raise RuntimeError("current points outside owned releases")
    if len(relative.parts) != 2 or relative.parts[1] != "dist" or not re.fullmatch(r"\d{8}T\d{6}Z-[0-9a-f]{12}", relative.parts[0]) or not target.is_dir():
        raise RuntimeError("current target layout is invalid")
    if verify_release_dir(target.parent, True) is None: raise RuntimeError("current release is not fully recoverable")
    return str(target)

def atomic_bytes(path, data, mode=0o644):
    path = P(path)
    temporary = path.with_name(path.name + ".tmp." + CFG["op_id"])
    if temporary.exists() or temporary.is_symlink(): raise RuntimeError("temporary path already exists: " + str(temporary))
    previous = path.stat() if path.exists() and not path.is_symlink() else None
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    try:
        os.fchmod(fd, mode)
        if previous is not None: os.fchown(fd, previous.st_uid, previous.st_gid)
        with os.fdopen(fd, "wb") as stream:
            stream.write(data); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists(): temporary.unlink()

def atomic_json(path, value, mode=0o600):
    atomic_bytes(path, (json.dumps(value, indent=2, sort_keys=True) + "\n").encode(), mode)

def atomic_link(path, target):
    path = P(path); temporary = path.with_name(path.name + ".tmp." + CFG["op_id"])
    if temporary.exists() or temporary.is_symlink(): raise RuntimeError("temporary link exists")
    os.symlink(target, temporary); os.replace(temporary, path)
'''


PRECHECK = COMMON_REMOTE + r'''
root = P(CFG["root"]); vhost = P(CFG["vhost"]); snippet = P(CFG["snippet"])
if not vhost.is_file() or vhost.is_symlink(): raise RuntimeError("main TLS vhost missing or unsafe")
vhost_text = vhost.read_text()
if vhost_text.count(CFG["anchor"]) != 1: raise RuntimeError("Java include anchor is not unique")
if vhost_text.count(CFG["tool_include"]) > 1: raise RuntimeError("photo-renamer include is duplicated")
owned = owner_ok(root)
if (root.exists() or root.is_symlink()) and not owned: raise RuntimeError("remote root exists without matching project ownership")
tool_status = http_status(CFG["tool_url"])
if owned:
    active_shape = (root / "current").is_symlink() and snippet.is_file() and not snippet.is_symlink() and CFG["tool_include"] in vhost_text
    incomplete_shape = not (root / "current").exists() and not (root / "current").is_symlink() and not snippet.exists() and CFG["tool_include"] not in vhost_text
    if active_shape:
        validate_owned_layout(root)
        if tool_status != 200: raise RuntimeError("owned active deployment URL is not 200")
    elif incomplete_shape:
        validate_owned_tree(root)
        if tool_status != 404: raise RuntimeError("owned incomplete install unexpectedly occupies the URL")
    else:
        raise RuntimeError("owned deployment has a mixed current/snippet/include state")
else:
    if snippet.exists() or snippet.is_symlink() or CFG["tool_include"] in vhost_text:
        raise RuntimeError("unowned first install conflicts with an existing snippet/include")
    if tool_status != 404: raise RuntimeError("unowned first install URL is not 404")
owned_releases = []
if owned and (root / "releases").is_dir():
    for candidate in sorted((root / "releases").iterdir(), key=lambda item: item.name, reverse=True):
        marker = candidate / "manifest.json"
        if candidate.is_symlink() or not candidate.is_dir() or marker.is_symlink() or not marker.is_file(): continue
        try: data = json.loads(marker.read_text())
        except Exception: continue
        if data.get("projectId") == CFG["project_id"] and data.get("releaseId") == candidate.name:
            owned_releases.append({"releaseId": candidate.name, "verified": data.get("verified") is True})
baseline = {
  "vhost_hash": sha(vhost),
  "default_vhost_hash": file_hash(CFG["default_vhost"]),
  "java_snippet_hash": file_hash(CFG["java_snippet"]),
  "snippet_hash": file_hash(snippet),
  "vhost_has_include": CFG["tool_include"] in vhost_text,
  "tool_status": tool_status,
  "current_target": current_target(root),
  "site_listener": listener(8080, "site"),
  "admin_listener": listener(8081, "admin"),
  "nginx_master_pids": pgrep(r"nginx: master process", True),
  "owned_releases": owned_releases,
  "http": {url: http_status(url) for url in CFG["baseline_urls"]},
}
if len(baseline["nginx_master_pids"]) != 1:
    raise RuntimeError("production process baseline is incomplete")
if any(status != 200 for status in baseline["http"].values()):
    raise RuntimeError("production HTTP baseline is not healthy: %r" % baseline["http"])
print(json.dumps(baseline, sort_keys=True))
'''


PREPARE = COMMON_REMOTE + r'''
root = P(CFG["root"]); release = root / "releases" / CFG["release_id"]
stage = root / ".staging" / (CFG["release_id"] + "-" + CFG["op_id"])
if (root.exists() or root.is_symlink()) and not owner_ok(root): raise RuntimeError("refusing unowned project root")
if not root.exists():
    root.mkdir(mode=0o755)
    atomic_json(root / "project.json", {"schemaVersion": 1, "projectId": CFG["project_id"]}, 0o600)
for path, mode in ((root / "releases", 0o755), (root / ".staging", 0o700), (root / "operations", 0o700)):
    path.mkdir(mode=mode, exist_ok=True); os.chmod(path, mode)
if release.exists() or release.is_symlink(): raise RuntimeError("release already exists")
if stage.exists() or stage.is_symlink(): raise RuntimeError("staging path already exists")
(stage / "dist" / "assets").mkdir(parents=True, mode=0o755)
op = root / "operations" / CFG["op_id"]; op.mkdir(mode=0o700)
vhost = P(CFG["vhost"]); (op / "vhost.before").write_bytes(vhost.read_bytes())
snippet = P(CFG["snippet"])
if snippet.is_file() and not snippet.is_symlink(): (op / "snippet.before").write_bytes(snippet.read_bytes())
atomic_json(op / "before.json", {"vhostHash": sha(vhost), "snippetHash": file_hash(snippet), "currentTarget": current_target(root)}, 0o600)
print(json.dumps({"stage": str(stage), "operation": str(op)}, sort_keys=True))
'''


FINALIZE = COMMON_REMOTE + r'''
root = P(CFG["root"])
if not owner_ok(root): raise RuntimeError("project ownership changed")
stage = P(CFG["stage"]); release = root / "releases" / CFG["release_id"]
expected = CFG["files"]
actual = {}
for path in stage.rglob("*"):
    if path.is_symlink(): raise RuntimeError("uploaded release contains symlink")
    if path.is_file(): actual[path.relative_to(stage / "dist").as_posix()] = {"sha256": sha(path), "size": path.stat().st_size}
if actual != expected: raise RuntimeError("uploaded release manifest mismatch")
for path in stage.rglob("*"):
    os.chmod(path, 0o755 if path.is_dir() else 0o644)
manifest = {"schemaVersion": 1, "projectId": CFG["project_id"], "releaseId": CFG["release_id"], "createdAt": CFG["created_at"], "verified": False, "files": expected}
atomic_json(stage / "manifest.json", manifest, 0o600)
os.replace(stage, release)
print(json.dumps({"release": str(release), "manifestHash": sha(release / "manifest.json")}, sort_keys=True))
'''


READ_RELEASE = COMMON_REMOTE + r'''
root=P(CFG["root"]); release=root/"releases"/CFG["release_id"]
data=verify_release_dir(release, True)
if data is None: raise RuntimeError("rollback release is not an owned, verified and recoverable version")
print(json.dumps({"files": data["files"], "verifiedAt": data.get("verifiedAt")}, sort_keys=True))
'''


ACTIVATE = COMMON_REMOTE + r'''
root = P(CFG["root"]); vhost = P(CFG["vhost"]); snippet = P(CFG["snippet"])
if not owner_ok(root): raise RuntimeError("project ownership changed")
for path, expected in ((vhost, CFG["baseline"]["vhost_hash"]), (P(CFG["default_vhost"]), CFG["baseline"]["default_vhost_hash"]), (P(CFG["java_snippet"]), CFG["baseline"]["java_snippet_hash"])):
    if file_hash(path) != expected: raise RuntimeError("configuration CAS mismatch: " + str(path))
if file_hash(snippet) != CFG["baseline"]["snippet_hash"]: raise RuntimeError("snippet CAS mismatch")
if current_target(root) != CFG["baseline"]["current_target"]: raise RuntimeError("current release CAS mismatch")
if (root / "current").exists() and not (root / "current").is_symlink(): raise RuntimeError("current is not a symlink")
release = root / "releases" / CFG["release_id"]; manifest_path = release / "manifest.json"
if release.is_symlink() or not manifest_path.is_file(): raise RuntimeError("release is missing or unsafe")
manifest = json.loads(manifest_path.read_text())
if manifest.get("projectId") != CFG["project_id"] or manifest.get("releaseId") != CFG["release_id"]: raise RuntimeError("release ownership mismatch")
if CFG.get("require_verified") and manifest.get("verified") is not True: raise RuntimeError("rollback release is not verified")
if verify_release_dir(release, bool(CFG.get("require_verified"))) is None: raise RuntimeError("release manifest or asset set is unsafe")
for name, meta in manifest.get("files", {}).items():
    path = release / "dist" / name
    if not path.is_file() or path.is_symlink() or path.stat().st_size != meta["size"] or sha(path) != meta["sha256"]: raise RuntimeError("release verification failed: " + name)
op = root / "operations" / CFG["op_id"]
op.mkdir(mode=0o700, exist_ok=True)
if not (op / "vhost.before").exists(): (op / "vhost.before").write_bytes(vhost.read_bytes())
if snippet.is_file() and not (op / "snippet.before").exists(): (op / "snippet.before").write_bytes(snippet.read_bytes())
before = {"vhostHash": sha(vhost), "snippetHash": file_hash(snippet), "currentTarget": current_target(root)}
atomic_json(op / "before.json", before, 0o600)

snippet_bytes = CFG["snippet_content"].encode()
snippet_hash = hashlib.sha256(snippet_bytes).hexdigest()
vhost_bytes = vhost.read_bytes(); anchor = CFG["anchor"].encode(); tool_include = CFG["tool_include"].encode()
if vhost_bytes.count(tool_include) == 0:
    if vhost_bytes.count(anchor) != 1: raise RuntimeError("include anchor changed")
    candidate_vhost = vhost_bytes.replace(anchor, anchor + tool_include, 1)
elif vhost_bytes.count(tool_include) == 1:
    candidate_vhost = vhost_bytes
else:
    raise RuntimeError("tool include count is unsafe")
target = str(release / "dist")
after = {"vhostHash": hashlib.sha256(candidate_vhost).hexdigest(), "snippetHash": snippet_hash, "currentTarget": os.path.realpath(target)}
# Persist the complete intended after-state before the first mutable component.
# Recovery therefore handles failures after any subset of the three writes.
atomic_json(op / "after.json", after, 0o600)

rewrite_snippet = before["snippetHash"] != after["snippetHash"]
rewrite_vhost = before["vhostHash"] != after["vhostHash"]
switch_current = before["currentTarget"] != after["currentTarget"]
if rewrite_snippet:
    if file_hash(snippet) != before["snippetHash"]: raise RuntimeError("snippet changed immediately before write")
    atomic_bytes(snippet, snippet_bytes, 0o644)
if rewrite_vhost:
    if sha(vhost) != before["vhostHash"]: raise RuntimeError("main vhost changed immediately before write")
    atomic_bytes(vhost, candidate_vhost, vhost.stat().st_mode & 0o777)
if switch_current:
    if current_target(root) != before["currentTarget"]: raise RuntimeError("current changed immediately before switch")
    if (root / "current").exists() and not (root / "current").is_symlink(): raise RuntimeError("current became a regular file")
    atomic_link(root / "current", target)

test_output = "not required; Nginx configuration bytes unchanged"
if rewrite_snippet or rewrite_vhost:
    test = subprocess.run(["nginx", "-t"], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if test.returncode != 0: raise RuntimeError("nginx -t failed: " + test.stderr[-1000:])
    test_output = test.stderr[-2000:]
    reload_result = subprocess.run(["systemctl", "reload", "nginx"], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if reload_result.returncode != 0: raise RuntimeError("nginx reload failed: " + reload_result.stderr[-1000:])
print(json.dumps({"operation": str(op), "after": after, "nginxTest": test_output, "nginxReloaded": rewrite_snippet or rewrite_vhost}, sort_keys=True))
'''


POSTCHECK = COMMON_REMOTE + r'''
root = P(CFG["root"]); release = root / "releases" / CFG["release_id"]
failures = []
def check(condition, message):
    if not condition: failures.append(message)

check(listener(8080, "site") == CFG["baseline"]["site_listener"], "site listener changed")
check(listener(8081, "admin") == CFG["baseline"]["admin_listener"], "admin listener changed")
check(pgrep(r"nginx: master process", True) == CFG["baseline"]["nginx_master_pids"], "nginx master changed")
for path_key, expected_key in (("default_vhost", "default_vhost_hash"), ("java_snippet", "java_snippet_hash")):
    check(file_hash(CFG[path_key]) == CFG["baseline"][expected_key], path_key + " changed")
check(file_hash(CFG["vhost"]) == CFG["after"]["vhostHash"], "main vhost differs from exact candidate")
check(file_hash(CFG["snippet"]) == CFG["after"]["snippetHash"], "tool snippet changed")
check(current_target(root) == CFG["after"]["currentTarget"], "current release link changed")
for url in CFG["baseline_urls"]:
    check(http_status(url) == 200, "production URL unhealthy: " + url)
check(http_status(CFG["tool_url"]) == 200, "tool index is not 200")
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl): return None
try:
    urllib.request.build_opener(NoRedirect).open(urllib.request.Request(CFG["tool_url"].rstrip("/"), headers={"User-Agent": "nxr-photo-renamer-deploy/1"}), timeout=15)
    redirect_status = 200
except urllib.error.HTTPError as exc:
    redirect_status = exc.code
check(redirect_status == 308, "tool canonical redirect is not 308")
check(http_status(CFG["tool_url"] + "missing-file") == 404, "missing tool asset is not 404")
check(http_status(CFG["tool_url"], "POST") == 403, "tool POST is not 403")
for name, meta in CFG["files"].items():
    url = CFG["tool_url"] + ("" if name == "index.html" else name)
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "nxr-photo-renamer-deploy/1"}), timeout=20) as response:
            data = response.read()
            headers = response.headers
        matches = len(data) == meta["size"] and hashlib.sha256(data).hexdigest() == meta["sha256"]
        if not matches:
            diagnostic = {"expectedBytes": meta["size"], "receivedBytes": len(data), "server": headers.get("Server"), "cache": headers.get("CF-Cache-Status"), "encoding": headers.get("Content-Encoding")}
            if name == "index.html":
                diagnostic["scripts"] = re.findall(r"<script[^>]*src=['\"]([^'\"]+)", data.decode(errors="replace"))
            failures.append("HTTPS asset mismatch: " + name + " " + json.dumps(diagnostic))
        if name == "index.html":
            check("no-store" in headers.get("Cache-Control", ""), "Cache-Control no-store missing")
            check(headers.get("X-Content-Type-Options", "").lower() == "nosniff", "nosniff header missing")
            check(headers.get("X-Frame-Options", "").upper() == "DENY", "frame denial header missing")
            check(headers.get("Referrer-Policy", "").lower() == "no-referrer", "referrer policy missing")
            check("max-age=31536000" in headers.get("Strict-Transport-Security", ""), "HSTS header missing")
            csp = headers.get("Content-Security-Policy", "")
            check("script-src 'self' 'wasm-unsafe-eval'" in csp and "connect-src 'none'" in csp and "worker-src 'self' blob:" in csp and "frame-ancestors 'none'" in csp, "CSP isolation headers missing")
    except Exception as exc:
        failures.append("HTTPS asset failed: %s: %s" % (name, exc))
    origin_url = CFG["tool_url"] + ("" if name == "index.html" else name)
    origin = subprocess.run(
        ["curl", "--fail", "--silent", "--show-error", "--max-time", "20", "--resolve", "nxrgrading.com:443:" + CFG["origin_ip"], origin_url],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    check(origin.returncode == 0, "origin HTTPS fetch failed: " + name)
    if origin.returncode == 0:
        check(len(origin.stdout) == meta["size"] and hashlib.sha256(origin.stdout).hexdigest() == meta["sha256"], "origin HTTPS asset mismatch: " + name)
if failures: raise RuntimeError("; ".join(failures))
print(json.dumps({"verified": True, "release": str(release), "checks": "production-pids-config-http-and-all-assets"}, sort_keys=True))
'''


ROLLBACK_OPERATION = COMMON_REMOTE + r'''
root = P(CFG["root"]); op = root / "operations" / CFG["op_id"]
before = json.loads((op / "before.json").read_text()); after = json.loads((op / "after.json").read_text())
vhost = P(CFG["vhost"]); snippet = P(CFG["snippet"]); current = root / "current"
states = {"vhost": sha(vhost), "snippet": file_hash(snippet), "current": current_target(root)}
for label, key in (("vhost", "vhostHash"), ("snippet", "snippetHash"), ("current", "currentTarget")):
    if states[label] not in (before[key], after[key]):
        raise RuntimeError("rollback CAS conflict on %s; refusing concurrent overwrite" % label)
config_restored = False
if states["vhost"] == after["vhostHash"] and after["vhostHash"] != before["vhostHash"]:
    atomic_bytes(vhost, (op / "vhost.before").read_bytes(), vhost.stat().st_mode & 0o777); config_restored = True
if states["snippet"] == after["snippetHash"] and after["snippetHash"] != before["snippetHash"]:
    if before["snippetHash"] is None: snippet.unlink()
    else: atomic_bytes(snippet, (op / "snippet.before").read_bytes(), 0o644)
    config_restored = True
if states["current"] == after["currentTarget"] and after["currentTarget"] != before["currentTarget"]:
    if before["currentTarget"] is None: current.unlink()
    else: atomic_link(current, before["currentTarget"])
test_output = "not required; Nginx configuration bytes were already at baseline"
if config_restored:
    test = subprocess.run(["nginx", "-t"], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if test.returncode != 0: raise RuntimeError("rollback nginx -t failed: " + test.stderr[-1000:])
    test_output = test.stderr[-2000:]
    reload_result = subprocess.run(["systemctl", "reload", "nginx"], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if reload_result.returncode != 0: raise RuntimeError("rollback nginx reload failed: " + reload_result.stderr[-1000:])
if listener(8080, "site") != CFG["baseline"]["site_listener"] or listener(8081, "admin") != CFG["baseline"]["admin_listener"]:
    raise RuntimeError("production listeners changed during rollback")
if pgrep(r"nginx: master process", True) != CFG["baseline"]["nginx_master_pids"]:
    raise RuntimeError("Nginx master changed during rollback")
if any(http_status(url) != 200 for url in CFG["baseline_urls"]):
    raise RuntimeError("production HTTP baseline failed after rollback")
if file_hash(CFG["default_vhost"]) != CFG["baseline"]["default_vhost_hash"] or file_hash(CFG["java_snippet"]) != CFG["baseline"]["java_snippet_hash"]:
    raise RuntimeError("protected configuration changed during rollback")
print(json.dumps({"rolledBack": True, "restoredCurrent": before["currentTarget"], "nginxReloaded": config_restored, "nginxTest": test_output}, sort_keys=True))
'''


COMMIT = COMMON_REMOTE + r'''
import shutil
root = P(CFG["root"]); release = root / "releases" / CFG["release_id"]
manifest_path = release / "manifest.json"; manifest = json.loads(manifest_path.read_text())
if manifest.get("projectId") != CFG["project_id"] or manifest.get("releaseId") != CFG["release_id"]: raise RuntimeError("release ownership mismatch")
if verify_release_dir(release, False) is None: raise RuntimeError("current release files are not recoverable")
manifest["verified"] = True; manifest["verifiedAt"] = CFG["verified_at"]
atomic_json(manifest_path, manifest, 0o600)
active = current_target(root)
if os.path.realpath(release / "dist") != active: raise RuntimeError("current no longer points to committed release")
owned = []; unverified_owned = []
for candidate in (root / "releases").iterdir():
    data = verify_release_dir(candidate, False)
    if data is None: continue
    if data.get("verified") is True: owned.append(candidate)
    elif os.path.realpath(candidate / "dist") != active: unverified_owned.append(candidate)
history = sorted((item for item in owned if os.path.realpath(item / "dist") != active), key=lambda item: item.name, reverse=True)
# Re-verify current and both retained histories immediately before any prune.
if verify_release_dir(release, True) is None: raise RuntimeError("verified current release changed before prune")
for candidate in history[:2]:
    if verify_release_dir(candidate, True) is None: raise RuntimeError("retained history is not recoverable: " + candidate.name)
removed = []
for candidate in history[2:]:
    if verify_release_dir(candidate, True) is None: raise RuntimeError("prune candidate changed: " + candidate.name)
    resolved = candidate.resolve()
    if resolved.parent != (root / "releases").resolve(): raise RuntimeError("unsafe prune target")
    shutil.rmtree(candidate); removed.append(candidate.name)
removed_unverified = []
for candidate in unverified_owned:
    data = verify_release_dir(candidate, False)
    if data is None or data.get("verified") is True: raise RuntimeError("unverified cleanup candidate changed: " + candidate.name)
    resolved = candidate.resolve()
    if resolved.parent != (root / "releases").resolve(): raise RuntimeError("unsafe unverified cleanup target")
    shutil.rmtree(candidate); removed_unverified.append(candidate.name)
print(json.dumps({"committed": CFG["release_id"], "removedOwnedHistory": removed, "removedOwnedUnverified": removed_unverified}, sort_keys=True))
'''


def base_config(op_id: str) -> dict[str, Any]:
    return {
        "op_id": op_id,
        "project_id": PROJECT_ID,
        "root": REMOTE_ROOT,
        "snippet": REMOTE_SNIPPET,
        "vhost": REMOTE_VHOST,
        "default_vhost": REMOTE_DEFAULT_VHOST,
        "java_snippet": REMOTE_JAVA_SNIPPET,
        "anchor": INCLUDE_ANCHOR,
        "tool_include": TOOL_INCLUDE,
        "tool_url": TOOL_URL,
        "origin_ip": "147.182.183.201",
        "baseline_urls": [PUBLIC_URL, PUBLIC_ADMIN_URL, DIRECT_SITE_URL, DIRECT_ADMIN_URL],
    }


def upload_dist(target: str, dist: Path, stage: str, files: dict[str, dict[str, Any]]) -> None:
    for relative in files:
        destination = f"{target}:{stage}/dist/{relative}"
        run(["scp", *SSH_OPTIONS, str(dist / relative), destination], timeout=180)


def save_evidence(directory: Path, payload: dict[str, Any]) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"{utc_stamp()}-{uuid.uuid4().hex[:8]}.json"
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
    return path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="安全发布 NXR 图片命名静态工具（默认只读预检）")
    parser.add_argument("--apply", action="store_true", help="通过预检后执行远端写入")
    parser.add_argument("--rollback", metavar="RELEASE_ID", help="切换到一个仍保留的本工具已验证版本")
    parser.add_argument("--target", default=DEFAULT_TARGET)
    parser.add_argument("--direct-interface", help="macOS: 将 SSH/SCP 绑定到已核验的物理网卡，绕过 TUN")
    parser.add_argument("--dist", type=Path, default=DEFAULT_DIST)
    parser.add_argument("--evidence-dir", type=Path, default=DEFAULT_EVIDENCE)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not TARGET_RE.fullmatch(args.target) or args.target != DEFAULT_TARGET:
        raise PublishError(f"SSH 目标必须是已核验主机：{DEFAULT_TARGET}")
    if args.rollback and not RELEASE_RE.fullmatch(args.rollback):
        raise PublishError("回滚版本号格式无效")
    if args.direct_interface:
        import socket
        if sys.platform != "darwin" or not re.fullmatch(r"en[0-9]+", args.direct_interface):
            raise PublishError("直连模式仅支持 macOS 已核验的 enN 物理网卡")
        socket.if_nametoindex(args.direct_interface)
        connector = shlex.join([sys.executable, str(HERE / "direct_connect.py"), "--interface", args.direct_interface])
        SSH_OPTIONS.extend(["-o", f"ProxyCommand={connector} %h %p"])
    op_id = uuid.uuid4().hex
    config = base_config(op_id)
    baseline = remote_python(args.target, config, PRECHECK)
    evidence: dict[str, Any] = {"schemaVersion": 1, "projectId": PROJECT_ID, "target": args.target, "mode": "rollback" if args.rollback else "publish", "apply": args.apply, "baseline": baseline}
    evidence["directInterface"] = args.direct_interface

    if args.rollback:
        release_id = args.rollback
        retained = remote_python(args.target, {**config, "release_id": release_id}, READ_RELEASE)
        files = retained["files"]
        evidence["retainedRelease"] = retained
    else:
        files = audit_dist(args.dist.resolve())
        release_id = release_id_for(files)
        evidence["localFiles"] = files
    evidence["releaseId"] = release_id
    if not args.apply:
        evidence["result"] = "read-only preflight passed"
        path = save_evidence(args.evidence_dir, evidence)
        print(f"只读预检通过；未写服务器。计划版本：{release_id}")
        print(f"证据：{path}")
        return 0

    if not args.rollback:
        try:
            prepare_config = {**config, "release_id": release_id}
            prepared = remote_python(args.target, prepare_config, PREPARE)
            evidence["prepared"] = prepared
            upload_dist(args.target, args.dist.resolve(), prepared["stage"], files)
            finalized = remote_python(args.target, {**prepare_config, "stage": prepared["stage"], "files": files, "created_at": dt.datetime.now(dt.timezone.utc).isoformat()}, FINALIZE)
            evidence["finalized"] = finalized
        except Exception as exc:
            evidence["failure"] = str(exc)
            path = save_evidence(args.evidence_dir, evidence)
            raise PublishError(f"上传或固化版本失败；共享配置和 current 尚未切换。证据：{path}") from exc
    else:
        # ACTIVATE re-validates ownership, manifest and every remote asset.
        evidence["rollbackRequested"] = release_id

    snippet_content = (HERE / "nginx.conf").read_text(encoding="utf-8")
    activate_config = {**config, "release_id": release_id, "baseline": baseline, "snippet_content": snippet_content, "require_verified": bool(args.rollback)}
    try:
        activated = remote_python(args.target, activate_config, ACTIVATE)
        evidence["activated"] = activated
        post = remote_python(args.target, {**config, "release_id": release_id, "baseline": baseline, "after": activated["after"], "files": files}, POSTCHECK, timeout=240)
        evidence["postcheck"] = post
    except Exception as exc:
        evidence["failure"] = str(exc)
        try:
            evidence["automaticRollback"] = remote_python(args.target, {**config, "baseline": baseline}, ROLLBACK_OPERATION)
        except Exception as rollback_exc:
            evidence["automaticRollbackFailure"] = str(rollback_exc)
        path = save_evidence(args.evidence_dir, evidence)
        raise PublishError(f"发布验证失败；已尝试仅回滚本工具。证据：{path}") from exc

    try:
        committed = remote_python(args.target, {**config, "release_id": release_id, "verified_at": dt.datetime.now(dt.timezone.utc).isoformat()}, COMMIT)
    except Exception as exc:
        evidence["commitFailure"] = str(exc)
        evidence["result"] = "serving checks passed, but release commit/prune failed"
        path = save_evidence(args.evidence_dir, evidence)
        raise PublishError(f"页面与现网隔离检查已通过，但版本标记或历史清理失败；未盲目回滚。证据：{path}") from exc
    evidence["commit"] = committed; evidence["result"] = "verified"
    path = save_evidence(args.evidence_dir, evidence)
    print(f"发布并验证完成：{TOOL_URL}")
    print(f"当前版本：{release_id}")
    print(f"证据：{path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except PublishError as exc:
        print(f"错误：{exc}", file=sys.stderr)
        raise SystemExit(1)
