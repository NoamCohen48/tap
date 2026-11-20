"""PRs and main share validation; publication is gated in separate workflows."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


class CiParityTests(unittest.TestCase):
    def test_prs_and_main_run_the_same_validation_jobs_including_native(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        triggers = workflow.split("permissions:", 1)[0]
        self.assertIn("branches: [main]", triggers)
        self.assertRegex(triggers, r"(?m)^  pull_request:\s*$")
        # Validation jobs must not be filtered by event/branch. Step-level failure
        # artifact guards are allowed; publishing/deployment have their own workflows.
        self.assertNotRegex(workflow, r"(?m)^    if:")
        native = workflow.split("\n  native-image:\n", 1)[1]
        self.assertIn("needs: [jvm]", native)
        self.assertIn(":host:daemon:nativeCompile", native)
        self.assertIn(".github/scripts/daemon_cli_smoke.py", native)
        self.assertIn(".github/scripts/daemon_smoke.py", native)


if __name__ == "__main__":
    unittest.main()
