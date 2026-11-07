#!/usr/bin/env python3
"""Do not deploy download links before the shared release is public."""
from __future__ import annotations

import os
import pathlib
import runpy
import sys

from release_version import resolve

api = runpy.run_path(str(pathlib.Path(__file__).with_name("prepare_release.py")))["api"]


def ready(repository: str, tag: str) -> bool:
    release = api(repository, f"releases/tags/{tag}", missing_ok=True)
    return bool(release and not release["draft"])


def main() -> None:
    tag = resolve()["tag"]
    available = ready(os.environ["GITHUB_REPOSITORY"], tag)
    print(f"ready={str(available).lower()}")
    if not available:
        print(f"Waiting for public {tag}; Release dispatches Docs after publication.", file=sys.stderr)


if __name__ == "__main__":
    main()
