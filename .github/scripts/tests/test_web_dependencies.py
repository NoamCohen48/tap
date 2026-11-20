"""The inspector pages must not resolve the vulnerable Vite 8.3.0–8.3.2 releases."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


class WebDependencyTests(unittest.TestCase):
    def test_manifest_and_lock_require_patched_vite(self):
        for project in ("studio", "watcher"):
            with self.subTest(project=project):
                web = ROOT / "clients" / project / "web"
                manifest = json.loads((web / "package.json").read_text())
                floor = re.fullmatch(r"\^?(\d+)\.(\d+)\.(\d+)", manifest["devDependencies"]["vite"])
                self.assertIsNotNone(floor, "Keep an explicit patched Vite minimum")
                assert floor is not None
                self.assertGreaterEqual(tuple(map(int, floor.groups())), (8, 3, 3))
                resolved = re.findall(r'\["vite@(\d+)\.(\d+)\.(\d+)"', (web / "bun.lock").read_text())
                self.assertTrue(resolved, "Check the committed resolution, not just its allowed range")
                for version in resolved:
                    self.assertGreaterEqual(tuple(map(int, version)), (8, 3, 3))


if __name__ == "__main__":
    unittest.main()
