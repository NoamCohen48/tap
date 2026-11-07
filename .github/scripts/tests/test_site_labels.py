"""Product labels are prose; executable and package names keep their hyphens."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]


class SiteLabelTests(unittest.TestCase):
    def test_product_names_in_navigation_and_page_titles(self):
        nav = (ROOT / "mkdocs.yml").read_text()
        for name, directory in (("Tap agent", "agent"), ("Tap watcher", "watcher")):
            with self.subTest(name=name):
                self.assertIn(f"  - {name}:\n", nav)
                self.assertTrue((ROOT / f"docs/{directory}/index.md").read_text().startswith(f"# {name}\n"))
                self.assertIn(f"[{name}]({directory}/index.md)", (ROOT / "docs/index.md").read_text())
        self.assertNotIn("  - tap-agent:", nav)
        self.assertNotIn("  - tap-watcher:", nav)

    def test_commands_and_packages_are_not_renamed(self):
        agent = (ROOT / "docs/agent/index.md").read_text()
        watcher = (ROOT / "docs/watcher/index.md").read_text()
        self.assertIn("tap-agent mcp", agent)
        self.assertIn("tap_agent-", agent)
        self.assertIn("tap-watcher ", watcher)
        self.assertIn("tap_watcher-", watcher)


if __name__ == "__main__":
    unittest.main()
