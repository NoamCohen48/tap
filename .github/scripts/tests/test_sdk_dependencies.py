"""Tools use SDK APIs introduced in 0.3.0 (tap.v1 API epoch 2), not an older SDK."""
import re
import tomllib
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


class SdkDependencyTests(unittest.TestCase):
    def test_tools_require_sdk_with_logs_deaths_and_condition_waits(self):
        for project in ("agent", "studio", "watcher"):
            with self.subTest(project=project):
                metadata = tomllib.loads((ROOT / "clients" / project / "pyproject.toml").read_text())
                requirements = metadata["project"]["dependencies"]
                sdk = [item for item in requirements if item.startswith("tap-e2e")]
                self.assertEqual(len(sdk), 1)
                floor = re.fullmatch(r"tap-e2e>=(\d+)\.(\d+)\.(\d+)", sdk[0])
                self.assertIsNotNone(floor, "Declare an explicit minimum compatible SDK")
                assert floor is not None
                self.assertGreaterEqual(tuple(map(int, floor.groups())), (0, 3, 0))


if __name__ == "__main__":
    unittest.main()
