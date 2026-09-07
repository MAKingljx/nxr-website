from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock


PUBLISH_PATH = Path(__file__).resolve().parents[1] / "deploy" / "publish.py"
SPEC = importlib.util.spec_from_file_location("photo_renamer_publish", PUBLISH_PATH)
assert SPEC and SPEC.loader
publish = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publish)

OLD_RELEASE = "20260901T010101Z-111111111111"
NEW_RELEASE = "20260902T020202Z-222222222222"
OTHER_RELEASE = "20260903T030303Z-333333333333"


class DeploySafetyTest(unittest.TestCase):
    def test_local_audit_requires_exact_two_worker_release(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            dist = Path(temporary) / "dist"
            create_dist(dist, include_webp=True)

            files = publish.audit_dist(dist)

            self.assertEqual(len(files), 5)
            self.assertEqual(
                sorted(name for name in files if ".worker-" in name),
                ["assets/qr.worker-code.js", "assets/webp.worker-code.js"],
            )

    def test_local_audit_accepts_only_complete_ocr_assets(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            dist = Path(temporary) / "dist"
            create_dist(dist, include_webp=True, include_ocr=True)
            self.assertEqual(len(publish.audit_dist(dist)), 14)
            (dist / "assets/tesseract-core-relaxedsimd-lstm.wasm.js").unlink()
            with self.assertRaises(publish.PublishError):
                publish.audit_dist(dist)

    def test_ocr_release_is_recoverable_and_rejects_extra_private_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            create_release(root, NEW_RELEASE, verified=True, include_webp=True, include_ocr=True)
            release = root / "releases" / NEW_RELEASE
            cfg = {"project_id": publish.PROJECT_ID, "release": str(release)}
            body = publish.COMMON_REMOTE + '\nprint(json.dumps({"valid": verify_release_dir(P(CFG["release"])) is not None}))'
            self.assertTrue(execute_remote(body, cfg, subprocess_run=forbid_subprocess)["valid"])
            (release / "dist/assets/.PhoenixBrain").write_text("private")
            self.assertFalse(execute_remote(body, cfg, subprocess_run=forbid_subprocess)["valid"])
            with self.assertRaises(publish.PublishError):
                publish.audit_dist(release / "dist")

    def test_local_audit_rejects_legacy_release_for_new_publish(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            dist = Path(temporary) / "dist"
            create_dist(dist, include_webp=False)

            with self.assertRaisesRegex(publish.PublishError, "5 个文件"):
                publish.audit_dist(dist)

    def test_local_audit_rejects_standalone_wasm_assets(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            dist = Path(temporary) / "dist"
            create_dist(dist, include_webp=True)
            (dist / "assets" / "webp_enc-code.wasm").write_bytes(b"standalone-wasm")

            with self.assertRaisesRegex(publish.PublishError, "不在允许清单"):
                publish.audit_dist(dist)

    def test_owned_legacy_release_remains_recoverable(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            cfg, paths = make_active_install(Path(temporary))

            result = execute_remote(
                publish.PRECHECK,
                cfg,
                overrides=healthy_precheck_overrides(cfg, tool_status=200),
                subprocess_run=forbid_subprocess,
            )

            self.assertEqual(result["current_target"], str((paths["root"] / "releases" / OLD_RELEASE / "dist").resolve()))

    def test_nginx_csp_is_local_and_allows_only_wasm_evaluation(self) -> None:
        csp = (PUBLISH_PATH.parent / "nginx.conf").read_text()
        self.assertIn("script-src 'self' 'wasm-unsafe-eval'", csp)
        self.assertIn("worker-src 'self' blob:", csp)
        self.assertIn("connect-src 'none'", csp)
        self.assertNotIn("connect-src *", csp)
        self.assertNotIn("'unsafe-eval'", csp.replace("'wasm-unsafe-eval'", ""))

    def test_unowned_first_install_rejects_an_already_served_url(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            cfg, _paths = make_config(Path(temporary), create_root=False)
            with self.assertRaisesRegex(RuntimeError, "unowned first install URL is not 404"):
                execute_remote(
                    publish.PRECHECK,
                    cfg,
                    overrides="def http_status(url, method='GET'): return 200",
                    subprocess_run=forbid_subprocess,
                )

    def test_marker_only_first_install_is_a_retryable_owned_state(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            cfg, paths = make_config(Path(temporary), create_root=True, management_dirs=False)
            result = execute_remote(
                publish.PRECHECK,
                cfg,
                overrides=healthy_precheck_overrides(cfg, tool_status=404),
                subprocess_run=forbid_subprocess,
            )
            self.assertIsNone(result["current_target"])
            self.assertEqual(result["owned_releases"], [])
            self.assertFalse(paths["snippet"].exists())

    def test_failure_after_config_writes_can_restore_a_first_install(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            cfg, paths = make_config(Path(temporary), create_root=True)
            create_release(paths["root"], NEW_RELEASE, verified=False)
            cfg.update(
                release_id=NEW_RELEASE,
                baseline=baseline(cfg, paths, current=None),
                snippet_content="location ^~ /tools/photo-renamer/ { try_files $uri =404; }\n",
                require_verified=False,
            )
            initial_vhost = paths["vhost"].read_bytes()

            with self.assertRaisesRegex(RuntimeError, "injected current switch failure"):
                execute_remote(
                    publish.ACTIVATE,
                    cfg,
                    overrides="""
def atomic_link(path, target):
    raise RuntimeError('injected current switch failure')
""",
                    subprocess_run=successful_service_command,
                )

            operation = paths["root"] / "operations" / cfg["op_id"]
            self.assertTrue((operation / "after.json").is_file())
            self.assertTrue(paths["snippet"].is_file())
            self.assertNotEqual(paths["vhost"].read_bytes(), initial_vhost)

            rollback = execute_remote(
                publish.ROLLBACK_OPERATION,
                cfg,
                overrides=healthy_runtime_overrides(cfg),
                subprocess_run=successful_service_command,
            )
            self.assertTrue(rollback["rolledBack"])
            self.assertEqual(paths["vhost"].read_bytes(), initial_vhost)
            self.assertFalse(paths["snippet"].exists())
            self.assertFalse((paths["root"] / "current").exists())

    def test_current_cas_refuses_to_overwrite_a_concurrent_switch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            cfg, paths = make_active_install(Path(temporary))
            create_release(paths["root"], NEW_RELEASE, verified=False)
            create_release(paths["root"], OTHER_RELEASE, verified=True)
            original = baseline(cfg, paths, current=paths["root"] / "releases" / OLD_RELEASE / "dist")
            replace_current(paths["root"], OTHER_RELEASE)
            cfg.update(
                release_id=NEW_RELEASE,
                baseline=original,
                snippet_content=paths["snippet"].read_text(),
                require_verified=False,
            )
            before_vhost = paths["vhost"].read_bytes()
            before_snippet = paths["snippet"].read_bytes()

            with self.assertRaisesRegex(RuntimeError, "current release CAS mismatch"):
                execute_remote(publish.ACTIVATE, cfg, subprocess_run=forbid_subprocess)

            self.assertEqual(paths["vhost"].read_bytes(), before_vhost)
            self.assertEqual(paths["snippet"].read_bytes(), before_snippet)
            self.assertTrue((paths["root"] / "current").resolve().samefile(paths["root"] / "releases" / OTHER_RELEASE / "dist"))

    def test_unchanged_nginx_bytes_switch_only_current_without_reload(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            cfg, paths = make_active_install(Path(temporary))
            create_release(paths["root"], NEW_RELEASE, verified=False)
            cfg.update(
                release_id=NEW_RELEASE,
                baseline=baseline(cfg, paths, current=paths["root"] / "releases" / OLD_RELEASE / "dist"),
                snippet_content=paths["snippet"].read_text(),
                require_verified=False,
            )

            result = execute_remote(publish.ACTIVATE, cfg, subprocess_run=forbid_subprocess)
            self.assertFalse(result["nginxReloaded"])
            self.assertTrue((paths["root"] / "current").resolve().samefile(paths["root"] / "releases" / NEW_RELEASE / "dist"))

    def test_wasm_csp_change_requires_nginx_test_and_graceful_reload(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            cfg, paths = make_active_install(Path(temporary))
            create_release(paths["root"], NEW_RELEASE, verified=False, include_webp=True)
            cfg.update(
                release_id=NEW_RELEASE,
                baseline=baseline(cfg, paths, current=paths["root"] / "releases" / OLD_RELEASE / "dist"),
                snippet_content=(PUBLISH_PATH.parent / "nginx.conf").read_text(),
                require_verified=False,
            )
            commands: list[list[str]] = []

            def record_service_command(command, **kwargs):
                commands.append(command)
                return successful_service_command(command, **kwargs)

            result = execute_remote(publish.ACTIVATE, cfg, subprocess_run=record_service_command)

            self.assertTrue(result["nginxReloaded"])
            self.assertEqual(commands, [["nginx", "-t"], ["systemctl", "reload", "nginx"]])

    def test_legacy_rollback_uses_the_current_wasm_csp(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            cfg, paths = make_active_install(Path(temporary))
            create_release(paths["root"], NEW_RELEASE, verified=True, include_webp=True)
            replace_current(paths["root"], NEW_RELEASE)
            current_snippet = (PUBLISH_PATH.parent / "nginx.conf").read_text()
            cfg.update(
                release_id=OLD_RELEASE,
                baseline=baseline(cfg, paths, current=paths["root"] / "releases" / NEW_RELEASE / "dist"),
                snippet_content=current_snippet,
                require_verified=True,
            )

            result = execute_remote(publish.ACTIVATE, cfg, subprocess_run=successful_service_command)

            self.assertTrue(result["nginxReloaded"])
            self.assertEqual(paths["snippet"].read_text(), current_snippet)
            self.assertTrue(
                (paths["root"] / "current").resolve().samefile(paths["root"] / "releases" / OLD_RELEASE / "dist")
            )


def execute_remote(
    body: str,
    cfg: dict,
    *,
    overrides: str = "",
    subprocess_run,
) -> dict:
    assert body.startswith(publish.COMMON_REMOTE)
    injected = publish.COMMON_REMOTE + "\n" + overrides + "\n" + body[len(publish.COMMON_REMOTE):]
    source = publish.remote_source(cfg, injected)
    output = io.StringIO()
    namespace: dict[str, object] = {}
    with mock.patch("subprocess.run", side_effect=subprocess_run), contextlib.redirect_stdout(output):
        exec(compile(source, "<isolated-remote-deploy-test>", "exec"), namespace, namespace)
    lines = [line for line in output.getvalue().splitlines() if line.strip()]
    return json.loads(lines[-1]) if lines else {}


def make_config(base: Path, *, create_root: bool, management_dirs: bool = True):
    paths = {
        "root": base / "owned-root",
        "vhost": base / "nxr-vhost.conf",
        "snippet": base / "photo.conf",
        "default_vhost": base / "default.conf",
        "java_snippet": base / "java.conf",
    }
    anchor = "    include java.conf;\n"
    tool_include = "    include photo.conf;\n"
    paths["vhost"].write_text("server {\n" + anchor + "}\n")
    paths["default_vhost"].write_text("default\n")
    paths["java_snippet"].write_text("java\n")
    if create_root:
        paths["root"].mkdir()
        (paths["root"] / "project.json").write_text(json.dumps({"schemaVersion": 1, "projectId": publish.PROJECT_ID}))
        if management_dirs:
            for name in ("releases", ".staging", "operations"):
                (paths["root"] / name).mkdir()
    cfg = {
        "op_id": "a" * 32,
        "project_id": publish.PROJECT_ID,
        "root": str(paths["root"]),
        "vhost": str(paths["vhost"]),
        "snippet": str(paths["snippet"]),
        "default_vhost": str(paths["default_vhost"]),
        "java_snippet": str(paths["java_snippet"]),
        "anchor": anchor,
        "tool_include": tool_include,
        "tool_url": "https://nxrgrading.com/tools/photo-renamer/",
        "origin_ip": "127.0.0.1",
        "baseline_urls": ["https://nxrgrading.com/", "http://127.0.0.1:8080/"],
    }
    return cfg, paths


def make_active_install(base: Path):
    cfg, paths = make_config(base, create_root=True)
    create_release(paths["root"], OLD_RELEASE, verified=True)
    paths["snippet"].write_text("location ^~ /tools/photo-renamer/ { try_files $uri =404; }\n")
    paths["vhost"].write_text(paths["vhost"].read_text().replace(cfg["anchor"], cfg["anchor"] + cfg["tool_include"]))
    replace_current(paths["root"], OLD_RELEASE)
    return cfg, paths


def create_dist(dist: Path, *, include_webp: bool, include_ocr: bool = False) -> dict[str, bytes]:
    (dist / "assets").mkdir(parents=True)
    workers = ["qr.worker-code.js"]
    if include_webp:
        workers.append("webp.worker-code.js")
    content = {
        "index.html": b'<script type="module" src="./assets/index-app.js"></script><link rel="stylesheet" href="./assets/index-style.css">',
        "assets/index-app.js": (" ".join(workers)).encode(),
        "assets/index-style.css": b"style",
        "assets/qr.worker-code.js": b"qr-worker",
    }
    if include_webp:
        content["assets/webp.worker-code.js"] = b"webp-worker-with-inline-wasm"
    if include_ocr:
        content.update({name: b"bundled-ocr-asset" for name in publish.OCR_STATIC_ASSETS})
        content["assets/ocr-language-code.js"] = b"embedded-model"
        content["assets/index-app.js"] += b" ocr.worker-7.0.0.js ocr-language-code.js"
    for relative, data in content.items():
        (dist / relative).write_bytes(data)
    return content


def create_release(root: Path, release_id: str, *, verified: bool, include_webp: bool = False, include_ocr: bool = False) -> None:
    dist = root / "releases" / release_id / "dist"
    content = create_dist(dist, include_webp=include_webp, include_ocr=include_ocr)
    files = {}
    for relative, data in content.items():
        path = dist / relative
        path.write_bytes(data)
        files[relative] = {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    manifest = {
        "schemaVersion": 1,
        "projectId": publish.PROJECT_ID,
        "releaseId": release_id,
        "verified": verified,
        "files": files,
    }
    (dist.parent / "manifest.json").write_text(json.dumps(manifest))


def replace_current(root: Path, release_id: str) -> None:
    current = root / "current"
    current.unlink(missing_ok=True)
    current.symlink_to(root / "releases" / release_id / "dist")


def baseline(cfg: dict, paths: dict[str, Path], *, current: Path | None) -> dict:
    return {
        "vhost_hash": file_sha(paths["vhost"]),
        "default_vhost_hash": file_sha(paths["default_vhost"]),
        "java_snippet_hash": file_sha(paths["java_snippet"]),
        "snippet_hash": file_sha(paths["snippet"]) if paths["snippet"].is_file() else None,
        "vhost_has_include": cfg["tool_include"] in paths["vhost"].read_text(),
        "tool_status": 200 if current else 404,
        "current_target": str(current.resolve()) if current else None,
        "site_listener": {"pid": 8080},
        "admin_listener": {"pid": 8081},
        "nginx_master_pids": [99],
        "owned_releases": [],
        "http": {url: 200 for url in cfg["baseline_urls"]},
    }


def healthy_precheck_overrides(cfg: dict, *, tool_status: int) -> str:
    return f"""
def http_status(url, method='GET'):
    return {tool_status} if url == {cfg['tool_url']!r} else 200
def listener(port, role): return {{'pid': port}}
def pgrep(pattern, oldest=False): return [99]
"""


def healthy_runtime_overrides(cfg: dict) -> str:
    return """
def http_status(url, method='GET'): return 200
def listener(port, role): return {'pid': port}
def pgrep(pattern, oldest=False): return [99]
"""


def successful_service_command(command, **_kwargs):
    if command not in (["nginx", "-t"], ["systemctl", "reload", "nginx"]):
        raise AssertionError(f"unexpected subprocess command: {command}")
    return subprocess.CompletedProcess(command, 0, stdout="", stderr="ok")


def forbid_subprocess(command, **_kwargs):
    raise AssertionError(f"subprocess must not run in this test: {command}")


def file_sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


if __name__ == "__main__":
    unittest.main()
