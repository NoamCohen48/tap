"""Release regressions: no credentials, registry or device required."""
from __future__ import annotations

import hashlib
import importlib.util
import os
import pathlib
import re
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / ".github/scripts"))


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    if not spec or not spec.loader:
        raise RuntimeError(f"cannot load {relative}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


versions = load("release_version", ".github/scripts/release_version.py")
plan = load("prepare_release", ".github/scripts/prepare_release.py")
publisher = load("publish_maven", ".github/scripts/publish_maven.py")
bundles = load("build_bundle", "scripts/build_bundle.py")
stage = load("stage_release", ".github/scripts/stage_release.py")
site = load("site_release", ".github/scripts/site_release.py")


class VersionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        (self.root / "gradle.properties").write_text("tap.version=0.1.0\n")
        for project in versions.PYTHON_PROJECTS:
            path = self.root / f"clients/{project}/pyproject.toml"
            path.parent.mkdir(parents=True)
            path.write_text('[project]\nversion = "0.1.0"\n')

    def test_committed_repository_uses_one_version(self):
        canonical = versions.committed_version(ROOT)
        self.assertEqual(versions.resolve(f"v{canonical}", ROOT)["version"], canonical)
        build = (ROOT / "build.gradle.kts").read_text()
        self.assertIn('gradleProperty("tap.version")', build)
        self.assertNotIn("when (path)", build)

    def test_workflow_actions_are_immutable(self):
        files = [*(ROOT / ".github/workflows").glob("*.yml"), ROOT / ".github/actions/build-docs/action.yml"]
        for path in files:
            for action in re.findall(r"uses:\s+([^\s#]+)", path.read_text()):
                if not action.startswith("./"):
                    with self.subTest(file=path.name, action=action):
                        self.assertRegex(action, r"@[0-9a-f]{40}$")

    def test_tag_and_all_python_mirrors_match(self):
        self.assertEqual(versions.resolve("v0.1.0", self.root)["tag"], "v0.1.0")
        for tag in ("v0.2.0", "daemon/v0.1.0", "bundle/v0.1.0", "v0.1.0\n"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                versions.resolve(tag, self.root)
        (self.root / "clients/watcher/pyproject.toml").write_text('[project]\nversion = "0.0.1"\n')
        with self.assertRaisesRegex(ValueError, "watcher"):
            versions.resolve(root=self.root)

    def test_independent_or_duplicate_gradle_versions_are_rejected(self):
        for extra in ("tap.version.sync-sdk=0.0.1\n", "tap.version=0.1.0\n"):
            (self.root / "gradle.properties").write_text("tap.version=0.1.0\n" + extra)
            with self.assertRaises(ValueError):
                versions.resolve(root=self.root)

    def test_prerelease_wheel_normalization_and_stability(self):
        for value, normalized in (("0.2.0-rc.1", "0.2.0rc1"), ("0.2.0-alpha.2", "0.2.0a2"), ("0.2.0-beta.3", "0.2.0b3")):
            self.assertEqual(versions.python_version(value), normalized)
        for value in ("../0.1.0", "0.1", "01.2.3", "0.1.0+unsafe", "0.1.0-rc.01"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                versions.python_version(value)
        self.assertEqual(versions.resolve(root=self.root)["prerelease"], "false")


class PlanTests(unittest.TestCase):
    def setUp(self):
        self.tags = ""
        self.tagged_sha = "head"
        self.publication = None
        self.runs = [{"head_sha": "head", "head_branch": "main", "event": "push", "conclusion": "success"}]
        patch.object(plan, "resolve", return_value={"tag": "v0.1.0", "version": "0.1.0"}).start()
        self.git = patch.object(plan, "git", side_effect=self.fake_git).start()
        self.api = patch.object(plan, "api", side_effect=self.fake_api).start()
        self.command = patch.object(plan.subprocess, "run").start()
        self.addCleanup(patch.stopall)

    def fake_git(self, *args):
        if args == ("rev-parse", "HEAD") or args == ("rev-parse", "origin/main"):
            return "head"
        if args[0] == "tag":
            return self.tags
        if args == ("rev-parse", "v0.1.0^{commit}"):
            return self.tagged_sha
        raise AssertionError(args)

    def fake_api(self, repository, path, **kwargs):
        return {"workflow_runs": self.runs} if path.startswith("actions/") else self.publication

    def test_successful_main_creates_one_tag_then_publishes(self):
        self.assertEqual(plan.prepare("owner/tap", None, "head")["publish"], "true")
        self.command.assert_any_call(["git", "push", "origin", "refs/tags/v0.1.0"], check=True)

    def test_stale_ci_run_does_not_ship(self):
        self.assertEqual(plan.prepare("owner/tap", None, "old")["publish"], "false")
        self.api.assert_not_called()
        self.command.assert_not_called()

    def test_failed_or_missing_ci_blocks_every_entry_point(self):
        for runs in ([], [{**self.runs[0], "conclusion": "failure"}], [{**self.runs[0], "event": "pull_request"}]):
            self.runs = runs
            with self.assertRaisesRegex(ValueError, "successful"):
                plan.prepare("owner/tap", "v0.1.0", None)

    def test_already_published_version_is_never_replaced(self):
        self.tags = "v0.1.0"
        self.publication = {"draft": False}
        self.assertEqual(plan.prepare("owner/tap", "v0.1.0", None)["publish"], "false")
        self.assertEqual(self.command.call_count, 1)  # ancestry check only

    def test_existing_draft_can_resume_from_its_exact_tag(self):
        self.tags = "v0.1.0"
        self.publication = {"draft": True}
        self.assertEqual(plan.prepare("owner/tap", "v0.1.0", None)["publish"], "true")

    def test_new_commit_cannot_move_a_version_tag(self):
        self.tags, self.tagged_sha = "v0.1.0", "old"
        self.assertEqual(plan.prepare("owner/tap", None, "head")["publish"], "false")
        with self.assertRaisesRegex(ValueError, "does not name"):
            plan.prepare("owner/tap", "v0.1.0", None)

    def test_manual_release_needs_existing_tag(self):
        with self.assertRaisesRegex(ValueError, "does not exist"):
            plan.prepare("owner/tap", "v0.1.0", None)


class ApiTests(unittest.TestCase):
    def test_only_an_explicit_404_means_missing(self):
        with patch.object(plan.subprocess, "run") as command:
            command.return_value.returncode = 1
            command.return_value.stderr = "gh: Not Found (HTTP 404)"
            self.assertIsNone(plan.api("owner/tap", "releases/tags/v0.1.0", missing_ok=True))
            for error in ("authentication failed (HTTP 401)", "network unavailable", "server error (HTTP 500)"):
                command.return_value.stderr = error
                with self.assertRaisesRegex(ValueError, "API failed"):
                    plan.api("owner/tap", "releases/tags/v0.1.0", missing_ok=True)
            command.return_value.returncode = 0
            command.return_value.stdout = "not JSON"
            with self.assertRaisesRegex(ValueError, "invalid JSON"):
                plan.api("owner/tap", "releases")

    def test_website_waits_for_public_downloads_not_just_a_tag(self):
        for response, expected in ((None, False), ({"draft": True}, False), ({"draft": False}, True)):
            with self.subTest(response=response), patch.object(site, "api", return_value=response):
                self.assertEqual(site.ready("owner/tap", "v0.1.0"), expected)

    def test_public_release_assets_cannot_be_clobbered(self):
        with patch.dict(os.environ, {"TAG": "v0.1.0", "GITHUB_REPOSITORY": "owner/tap"}), \
                patch.object(stage, "resolve", return_value={"version": "0.1.0"}), \
                patch.object(stage, "api", return_value={"draft": False}), \
                patch.object(stage.subprocess, "run") as command:
            with self.assertRaisesRegex(ValueError, "already public"):
                stage.main()
            command.assert_not_called()


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        self.assets = self.root / "assets"
        self.assets.mkdir()
        self.maven = self.root / "maven"
        self.docs = self.root / "docs"
        self.docs.mkdir()
        (self.docs / "README.md").write_text("# Tap\n")
        self.out = self.root / "release"
        for platform in bundles.PLATFORMS[:2]:
            (self.assets / f"tap-0.1.0-{platform}").write_bytes(b"native server")
        with zipfile.ZipFile(self.assets / "tap-0.1.0-server-jvm.zip", "w") as zipped:
            zipped.writestr("tap-0.1.0/bin/tap", "#!/bin/sh\n")
        for package in bundles.PACKAGES:
            with zipfile.ZipFile(self.assets / f"{package}-0.1.0-py3-none-any.whl", "w") as zipped:
                zipped.writestr(f"{package}-0.1.0.dist-info/METADATA", "Metadata-Version: 2.1\nVersion: 0.1.0\n")
                if package in ("tap_studio", "tap_watcher"):
                    zipped.writestr(f"{package}/static/index.html", "<html>Tap</html>")
            (self.assets / f"{package}-0.1.0.tar.gz").write_bytes(b"sdist")
        for artifact in bundles.MAVEN:
            directory = self.maven / bundles.GROUP / artifact / "0.1.0"
            directory.mkdir(parents=True)
            for suffix in (".pom", ".module", "-sources.jar", ".aar" if artifact == "tap-sync-sdk" else ".jar"):
                (directory / f"{artifact}-0.1.0{suffix}").write_text("artifact")
            (directory / f"{artifact}-0.1.0.pom").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0"><version>0.1.0</version></project>')

    def assemble(self):
        bundles.assemble("0.1.0", self.assets, self.maven, self.docs, self.out)

    def test_every_download_and_checksum_is_complete(self):
        self.assemble()
        for line in (self.out / "SHA256SUMS").read_text().splitlines():
            digest, name = line.split("  ")
            self.assertEqual(hashlib.sha256((self.out / name).read_bytes()).hexdigest(), digest)
        for platform in bundles.PLATFORMS:
            name = f"tap-0.1.0-{platform}"
            with zipfile.ZipFile(self.out / f"{name}.zip") as zipped:
                self.assertIn("sync-sdk=0.1.0", zipped.read(f"{name}/VERSIONS").decode())
                self.assertEqual(zipped.getinfo(f"{name}/install.sh").external_attr >> 16 & 0o777, 0o755)
                for line in zipped.read(f"{name}/SHA256SUMS").decode().splitlines():
                    digest, path = line.split("  ")
                    self.assertEqual(hashlib.sha256(zipped.read(f"{name}/{path}")).hexdigest(), digest)
                self.assertNotIn("@ENGINE@", zipped.read(f"{name}/INSTALL.md").decode())
                if platform != "jvm":
                    self.assertEqual(zipped.getinfo(f"{name}/server/tap").external_attr >> 16 & 0o777, 0o755)
        # Website download buttons must name assets this same pipeline actually creates.
        download = (ROOT / "docs/download.md").read_text()
        links = re.findall(r"releases/download/([^/]+)/([^\s)]+)", download)
        self.assertGreaterEqual(len(links), 3)
        current = versions.committed_version(ROOT)
        for tag, asset in links:
            self.assertEqual(tag, f"v{current}")
            self.assertTrue((self.out / asset.replace(current, "0.1.0")).is_file(), asset)
        self.assertTrue((self.out / "tap-0.1.0-maven.zip").is_file())

    def test_all_public_install_links_use_assets_on_the_shared_release(self):
        self.assemble()
        version = versions.committed_version(ROOT)
        sources = [ROOT / "README.md", *(ROOT / "docs").rglob("*.md")]
        for path in sources:
            text = path.read_text()
            for tag, asset in re.findall(r"github\.com/NoamCohen48/tap/releases/download/([^/]+)/([^\s)`]+)", text):
                with self.subTest(path=str(path), asset=asset):
                    self.assertEqual(tag, f"v{version}")
                    self.assertTrue((self.out / asset.replace(version, "0.1.0")).is_file())

    def test_missing_components_fail_before_output_is_created(self):
        (self.assets / "tap_watcher-0.1.0-py3-none-any.whl").unlink()
        with self.assertRaisesRegex(ValueError, "missing"):
            self.assemble()
        self.assertFalse(self.out.exists())

    def test_wheel_metadata_and_built_page_are_verified(self):
        wheel = self.assets / "tap_watcher-0.1.0-py3-none-any.whl"
        with zipfile.ZipFile(wheel, "w") as zipped:
            zipped.writestr("tap_watcher-0.1.0.dist-info/METADATA", "Version: 0.0.1\n")
        with self.assertRaisesRegex(ValueError, "metadata version"):
            self.assemble()
        with zipfile.ZipFile(wheel, "w") as zipped:
            zipped.writestr("tap_watcher-0.1.0.dist-info/METADATA", "Version: 0.1.0\n")
        with self.assertRaisesRegex(ValueError, "built browser page"):
            self.assemble()

    def test_mismatched_maven_dependency_is_rejected(self):
        path = self.maven / bundles.GROUP / "tap-client/0.1.0/tap-client-0.1.0.pom"
        path.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><version>0.1.0</version>'
                        '<dependencies><dependency><groupId>io.github.noamcohen48.tap</groupId>'
                        '<version>0.0.2</version></dependency></dependencies></project>')
        with self.assertRaisesRegex(ValueError, "different version"):
            self.assemble()

    def test_rebuilding_an_existing_release_directory_is_refused(self):
        self.out.mkdir()
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.assemble()

    def test_registry_publication_resumes_without_replacing_bytes(self):
        published = {}
        writes = []

        def request(url, authorization, data=None):
            self.assertTrue(authorization.startswith("Basic "))
            if data is not None:
                writes.append(url)
                published[url] = data
            return published.get(url)

        with patch.object(publisher, "request", side_effect=request):
            publisher.upload(self.maven, "0.1.0", "https://example.test", "actor", "token")
            count = len(writes)
            self.assertEqual(count, 80)  # five artifacts, four files, four checksums/content
            publisher.upload(self.maven, "0.1.0", "https://example.test", "actor", "token")
            self.assertEqual(len(writes), count)
            published[next(iter(published))] = b"different published bytes"
            with self.assertRaisesRegex(ValueError, "refusing to replace"):
                publisher.upload(self.maven, "0.1.0", "https://example.test", "actor", "token")
            self.assertEqual(len(writes), count)


if __name__ == "__main__":
    unittest.main()
