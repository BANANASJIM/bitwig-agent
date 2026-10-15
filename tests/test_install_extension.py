"""Installer filesystem behavior, isolated from the user's Bitwig directory."""
import hashlib
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "scripts").mkdir()
        (self.root / "build").mkdir()
        self.script = self.root / "scripts" / "install-extension.py"
        shutil.copyfile(Path(__file__).resolve().parents[1] / "scripts" / self.script.name, self.script)
        self.source = self.root / "build" / "BitwigAgent.bwextension"
        self.source.write_bytes(b"new build")
        self.destination = self.root / "Bitwig Studio" / "Extensions"
        self.destination.mkdir(parents=True)
        self.target = self.destination / self.source.name

    def install(self):
        return subprocess.run(
            [sys.executable, str(self.script), "--destination", str(self.destination)],
            check=True, capture_output=True, text=True,
        ).stdout

    def test_different_build_is_backed_up_outside_scan_tree(self):
        old = b"previous build"
        self.target.write_bytes(old)
        self.install()
        backup = self.destination.parent / ".bitwig-agent-backups" / hashlib.sha256(old).hexdigest() / self.source.name
        self.assertEqual(backup.read_bytes(), old)
        self.assertEqual(self.target.read_bytes(), self.source.read_bytes())
        self.assertEqual(list(self.destination.rglob("*.bwextension")), [self.target])
        self.assertFalse(self.target.with_suffix(".bwextension.tmp").exists())

    def test_same_build_does_not_rewrite_or_create_backup(self):
        self.target.write_bytes(self.source.read_bytes())
        before = self.target.stat()
        output = self.install()
        after = self.target.stat()
        self.assertIn("Already installed:", output)
        self.assertEqual((after.st_ino, after.st_mtime_ns), (before.st_ino, before.st_mtime_ns))
        self.assertFalse((self.destination.parent / ".bitwig-agent-backups").exists())


if __name__ == "__main__":
    unittest.main()
