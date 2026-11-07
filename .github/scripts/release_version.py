#!/usr/bin/env python3
"""Validate Tap's single release version and the self-contained Python metadata mirrors.

Run without arguments in CI, or with vX.Y.Z when publishing a tagged release.
Pre-releases use X.Y.Z-rc.N (also alpha.N/beta.N); wheel filenames use PEP 440.
"""
from __future__ import annotations

import pathlib
import re
import sys
import tomllib

ROOT = pathlib.Path(__file__).resolve().parents[2]
PYTHON_PROJECTS = ("python", "agent", "studio", "watcher")
VERSION = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(alpha|beta|rc)\.(0|[1-9]\d*))?")


def python_version(version: str) -> str:
    match = VERSION.fullmatch(version)
    if not match:
        raise ValueError(f"invalid version {version!r}; expected X.Y.Z or X.Y.Z-rc.N")
    major, minor, patch, pre, number = match.groups()
    suffix = {"alpha": "a", "beta": "b", "rc": "rc"}.get(pre, "")
    return f"{major}.{minor}.{patch}" + (f"{suffix}{number}" if pre else "")


def committed_version(root: pathlib.Path = ROOT) -> str:
    properties = (root / "gradle.properties").read_text()
    versions = re.findall(r"^tap\.version=(\S+)$", properties, re.MULTILINE)
    if len(versions) != 1:
        raise ValueError("gradle.properties must contain exactly one tap.version")
    if re.search(r"^tap\.version\.", properties, re.MULTILINE):
        raise ValueError("independent tap.version.* properties are no longer supported")
    version = versions[0]
    normalized = python_version(version)
    for project in PYTHON_PROJECTS:
        path = root / "clients" / project / "pyproject.toml"
        metadata = tomllib.loads(path.read_text())["project"]
        if metadata["version"] not in (version, normalized):
            raise ValueError(f"{path.relative_to(root)} says {metadata['version']}, expected {version}")
    return version


def resolve(tag: str | None = None, root: pathlib.Path = ROOT) -> dict[str, str]:
    version = committed_version(root)
    expected = f"v{version}"
    if tag is not None and tag != expected:
        raise ValueError(f"tag {tag!r} does not match committed version {expected}")
    return {
        "version": version,
        "python_version": python_version(version),
        "tag": expected,
        "prerelease": str("-" in version).lower(),
    }


def main() -> int:
    if len(sys.argv) > 2:
        raise ValueError("usage: release_version.py [vX.Y.Z]")
    for key, value in resolve(sys.argv[1] if len(sys.argv) == 2 else None).items():
        print(f"{key}={value}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, KeyError) as error:
        sys.exit(str(error))
