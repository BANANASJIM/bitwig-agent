"""Installer behavior, isolated from Bitwig and public release servers."""
from contextlib import redirect_stderr, redirect_stdout
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib.metadata import version
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

from bitwig_agent import install


class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root / "local-build.bwextension"
        self.source.write_bytes(b"new build")
        self.destination = self.root / "Bitwig Studio" / "Extensions"
        self.destination.mkdir(parents=True)
        self.target = self.destination / "BitwigAgent.bwextension"

    def run_cli(self, *args):
        return subprocess.run(
            [sys.executable, "-m", "bitwig_agent.install", "--destination", str(self.destination), *args],
            check=False, capture_output=True, text=True,
        )

    def install_local(self):
        result = self.run_cli("--extension", str(self.source))
        self.assertEqual(result.returncode, 0, result.stderr)

    def backup_path(self, data):
        return self.destination.parent / ".bitwig-agent-backups" / hashlib.sha256(data).hexdigest() / self.target.name

    def release_server(self, manifest=None, artifact=b"new build", status=200, extra_length=0):
        if manifest is None:
            manifest = f"{hashlib.sha256(artifact).hexdigest()}  BitwigAgent.bwextension\n".encode()
        requests = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append(self.path)
                data = manifest if self.path.endswith("/SHA256SUMS") else artifact
                self.send_response(status)
                self.send_header("Content-Length", str(len(data) + extra_length))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        def stop():
            server.shutdown()
            server.server_close()
            thread.join()

        self.addCleanup(stop)
        base = f"http://127.0.0.1:{server.server_port}"
        return base, requests

    def install_release(self, base):
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(install, "_RELEASES", base), redirect_stdout(stdout), redirect_stderr(stderr):
            try:
                install.main(["--destination", str(self.destination)])
            except SystemExit as exc:
                return exc.code, stderr.getvalue()
        return 0, stderr.getvalue()

    def test_different_build_is_backed_up_outside_scan_tree(self):
        old = b"previous build"
        self.target.write_bytes(old)
        self.install_local()
        self.assertEqual(self.backup_path(old).read_bytes(), old)
        self.assertEqual(self.target.read_bytes(), self.source.read_bytes())
        self.assertEqual(list(self.destination.rglob("*.bwextension")), [self.target])
        self.assertEqual(list(self.destination.iterdir()), [self.target])

    def test_same_build_does_not_rewrite_or_create_backup(self):
        self.target.write_bytes(self.source.read_bytes())
        before = self.target.stat()
        self.install_local()
        after = self.target.stat()
        self.assertEqual((after.st_ino, after.st_mtime_ns), (before.st_ino, before.st_mtime_ns))
        self.assertFalse((self.destination.parent / ".bitwig-agent-backups").exists())

    def test_local_install_does_not_contact_release_server(self):
        base, requests = self.release_server(status=503)
        with patch.object(install, "_RELEASES", base), redirect_stdout(io.StringIO()):
            install.main(["--extension", str(self.source), "--destination", str(self.destination)])
        self.assertEqual(requests, [])
        self.assertEqual(self.target.read_bytes(), b"new build")

    def test_release_install_uses_matching_version_and_checksum(self):
        old = b"previous build"
        self.target.write_bytes(old)
        base, requests = self.release_server()
        self.assertEqual(self.install_release(base), (0, ""))
        self.assertEqual(self.target.read_bytes(), b"new build")
        self.assertEqual(self.backup_path(old).read_bytes(), old)
        prefix = f"/v{version('bitwig-agent')}/"
        self.assertEqual(requests, [prefix + "SHA256SUMS", prefix + self.target.name])

    def test_corrupt_download_preserves_installation_and_existing_backup(self):
        old = b"working installation"
        self.target.write_bytes(old)
        backup = self.backup_path(old)
        backup.parent.mkdir(parents=True)
        backup.write_bytes(old)
        before = self.target.stat(), backup.stat()
        manifest = f"{hashlib.sha256(b'expected').hexdigest()}  {self.target.name}\n".encode()
        base, _ = self.release_server(manifest, b"corrupt download")
        code, error = self.install_release(base)
        self.assertEqual(code, 1)
        self.assertNotIn("Traceback", error)
        self.assertEqual(self.target.read_bytes(), old)
        self.assertEqual(backup.read_bytes(), old)
        for previous, current in zip(before, (self.target.stat(), backup.stat())):
            self.assertEqual((previous.st_ino, previous.st_mtime_ns), (current.st_ino, current.st_mtime_ns))
        self.assertEqual(list(self.destination.iterdir()), [self.target])

    def test_invalid_manifests_never_create_destination(self):
        digest = hashlib.sha256(b"new build").hexdigest()
        valid = f"{digest}  {self.target.name}\n"
        manifests = [b"", f"{digest}  other.whl\n".encode(), (valid * 2).encode(),
                     b"not-a-hash  BitwigAgent.bwextension\n", (valid + "malformed\n").encode(),
                     f"{digest}  nested/BitwigAgent.bwextension\n".encode(), b"\xff"]
        self.destination.rmdir()
        for manifest in manifests:
            with self.subTest(manifest=manifest):
                base, requests = self.release_server(manifest)
                code, error = self.install_release(base)
                self.assertEqual(code, 1)
                self.assertNotIn("Traceback", error)
                self.assertFalse(self.destination.exists())
                self.assertFalse((self.destination.parent / ".bitwig-agent-backups").exists())
                self.assertEqual(len(requests), 1)

    def test_binary_checksum_marker_is_accepted(self):
        digest = hashlib.sha256(b"new build").hexdigest()
        base, _ = self.release_server(f"{digest.upper()} *{self.target.name}\n".encode())
        self.assertEqual(self.install_release(base), (0, ""))
        self.assertEqual(self.target.read_bytes(), b"new build")

    def test_download_size_limit_preserves_installation(self):
        self.target.write_bytes(b"old")
        base, _ = self.release_server()
        with patch.object(install, "_MAX_EXTENSION_BYTES", 8):
            code, error = self.install_release(base)
        self.assertEqual(code, 1)
        self.assertNotIn("Traceback", error)
        self.assertEqual(self.target.read_bytes(), b"old")
        self.assertFalse((self.destination.parent / ".bitwig-agent-backups").exists())

    def test_http_failure_has_no_retry_or_destination_mutation(self):
        self.destination.rmdir()
        base, requests = self.release_server(status=404)
        code, error = self.install_release(base)
        self.assertEqual(code, 1)
        self.assertNotIn("Traceback", error)
        self.assertEqual(len(requests), 1)
        self.assertFalse(self.destination.exists())

    def test_missing_local_source_is_clean_failure(self):
        self.destination.rmdir()
        result = self.run_cli("--extension", str(self.root / "missing"))
        self.assertEqual(result.returncode, 1)
        self.assertNotIn("Traceback", result.stderr)
        self.assertFalse(self.destination.exists())

    def test_truncated_download_does_not_create_destination(self):
        self.destination.rmdir()
        base, _ = self.release_server(extra_length=1)
        code, error = self.install_release(base)
        self.assertEqual(code, 1)
        self.assertNotIn("Traceback", error)
        self.assertFalse(self.destination.exists())

    def test_checksum_manifest_size_is_bounded(self):
        self.destination.rmdir()
        base, _ = self.release_server()
        with patch.object(install, "_MAX_CHECKSUM_BYTES", 8):
            code, error = self.install_release(base)
        self.assertEqual(code, 1)
        self.assertNotIn("Traceback", error)
        self.assertFalse(self.destination.exists())

    def test_existing_backup_is_not_rewritten(self):
        old = b"old"
        self.target.write_bytes(old)
        backup = self.backup_path(old)
        backup.parent.mkdir(parents=True)
        backup.write_bytes(old)
        before = backup.stat()
        self.install_local()
        self.assertEqual(backup.read_bytes(), old)
        self.assertEqual(backup.stat().st_mtime_ns, before.st_mtime_ns)
        self.assertEqual(backup.stat().st_ino, before.st_ino)

    def test_conflicting_backup_stops_installation(self):
        self.target.write_bytes(b"old")
        backup = self.backup_path(b"old")
        backup.parent.mkdir(parents=True)
        backup.write_bytes(b"unexpected")
        result = self.run_cli("--extension", str(self.source))
        self.assertEqual(result.returncode, 1)
        self.assertNotIn("Traceback", result.stderr)
        self.assertEqual(self.target.read_bytes(), b"old")
        self.assertEqual(backup.read_bytes(), b"unexpected")

    def test_failed_atomic_replace_keeps_old_target_and_cleans_staging(self):
        self.target.write_bytes(b"old")
        unrelated = self.target.with_suffix(".bwextension.tmp")
        unrelated.write_bytes(b"unrelated file")
        original_replace = Path.replace

        def fail_target_replace(path, target):
            if target == self.target:
                raise PermissionError("replacement denied")
            return original_replace(path, target)

        stderr = io.StringIO()
        with patch.object(Path, "replace", fail_target_replace), redirect_stdout(io.StringIO()), redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as caught:
                install.main(["--extension", str(self.source), "--destination", str(self.destination)])
        self.assertEqual(caught.exception.code, 1)
        self.assertNotIn("Traceback", stderr.getvalue())
        self.assertEqual(self.target.read_bytes(), b"old")
        self.assertEqual(self.backup_path(b"old").read_bytes(), b"old")
        self.assertEqual(unrelated.read_bytes(), b"unrelated file")
        self.assertEqual(set(self.destination.iterdir()), {self.target, unrelated})

    def test_config_is_pure_json_without_install_side_effects(self):
        self.destination.rmdir()
        base, requests = self.release_server(status=503)
        stdout = io.StringIO()
        with patch.object(install, "_RELEASES", base), redirect_stdout(stdout):
            install.main(["--mcp-config", "--extension", str(self.root / "missing"),
                          "--destination", str(self.destination)])
        json.loads(stdout.getvalue())
        self.assertEqual(requests, [])
        self.assertFalse(self.destination.exists())

    def test_default_destination_follows_platform(self):
        for platform, documents in (("linux", False), ("darwin", True), ("win32", True)):
            with self.subTest(platform=platform), patch.object(sys, "platform", platform), patch.object(Path, "home", return_value=self.root):
                base = self.root / "Documents" if documents else self.root
                self.assertEqual(install._default_destination(), base / "Bitwig Studio" / "Extensions")


if __name__ == "__main__":
    unittest.main()
