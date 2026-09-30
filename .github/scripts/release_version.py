#!/usr/bin/env python3
"""Resolves a release tag to its artifact family and checks the version in the build files.

Tags are `<family>/v<version>`: daemon, client-kotlin, client-python, client-agent,
client-studio, sync-sdk. Prints `family=<family>` and `version=<version>` (GitHub Actions output
format) or fails when the tag's version does not match the one committed in gradle.properties /
pyproject.toml, so a release always ships the version its artifacts claim.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
FAMILIES = {
    "daemon": ("gradle.properties", r"^tap\.version\.engine=(.+)$"),
    "client-kotlin": ("gradle.properties", r"^tap\.version\.client\.kotlin=(.+)$"),
    "sync-sdk": ("gradle.properties", r"^tap\.version\.sync-sdk=(.+)$"),
    "client-python": ("clients/python/pyproject.toml", r'^version = "(.+)"$'),
    "client-agent": ("clients/agent/pyproject.toml", r'^version = "(.+)"$'),
    "client-studio": ("clients/studio/pyproject.toml", r'^version = "(.+)"$'),
}


def committed_version(family: str) -> str:
    path, pattern = FAMILIES[family]
    text = (ROOT / path).read_text()
    match = re.search(pattern, text, re.MULTILINE)
    if not match:
        sys.exit(f"{path}: no version for {family}")
    return match.group(1).strip()


def main(tag: str) -> int:
    families = "|".join(re.escape(family) for family in FAMILIES)
    match = re.fullmatch(rf"({families})/v(\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)", tag)
    if not match:
        sys.exit(f"unrecognised release tag {tag!r}; expected <family>/v<semver>")
    family, version = match.groups()
    expected = committed_version(family)
    if version != expected:
        sys.exit(f"tag {tag} says {version} but {FAMILIES[family][0]} says {expected}")
    print(f"family={family}")
    print(f"version={version}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else ""))
