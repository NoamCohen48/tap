#!/usr/bin/env python3
"""Stage all verified assets on a draft release; public releases are immutable."""
from __future__ import annotations

import os
import pathlib
import runpy
import subprocess

api = runpy.run_path(str(pathlib.Path(__file__).with_name("prepare_release.py")))["api"]
from release_version import resolve


def main() -> None:
    tag = os.environ["TAG"]
    values = resolve(tag)
    version = values["version"]
    repository = os.environ["GITHUB_REPOSITORY"]
    existing = api(repository, f"releases/tags/{tag}", missing_ok=True)
    if existing and not existing["draft"]:
        raise ValueError(f"{tag} is already public; refusing to replace its assets")
    notes = pathlib.Path("build/release-notes.md")
    notes.write_text(pathlib.Path("packaging/release-notes.md").read_text().replace("@VERSION@", version))
    if not existing:
        subprocess.run(["gh", "release", "create", tag, "--verify-tag", "--draft",
                        "--title", f"Tap {version}", "--notes-file", str(notes)], check=True)
    assets = sorted(pathlib.Path("build/release").iterdir())
    if not assets or not all(asset.is_file() for asset in assets):
        raise ValueError("expected a complete release asset directory")
    subprocess.run(["gh", "release", "upload", tag, *map(str, assets), "--clobber"], check=True)


if __name__ == "__main__":
    main()
