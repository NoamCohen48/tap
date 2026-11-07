#!/usr/bin/env python3
"""Upload staged Maven files without rebuilding or overwriting any published bytes.

Reruns skip byte-identical files, including a previous run's partially uploaded package.
Only versioned coordinates are uploaded; dependency resolution uses explicit versions.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import os
import pathlib
import urllib.error
import urllib.request

GROUP = pathlib.Path("io/github/noamcohen48/tap")
ARTIFACTS = ("tap-schema", "tap-api", "tap-client", "tap-junit5", "tap-sync-sdk")


def request(url: str, authorization: str, data: bytes | None = None) -> bytes | None:
    req = urllib.request.Request(url, data=data, headers={"Authorization": authorization},
                                 method="PUT" if data is not None else "GET")
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        if data is None and error.code == 404:
            return None
        raise RuntimeError(f"Maven {'upload' if data is not None else 'read'} failed: HTTP {error.code}") from error


def upload(maven: pathlib.Path, version: str, base: str, actor: str, token: str) -> None:
    authorization = "Basic " + base64.b64encode(f"{actor}:{token}".encode()).decode()
    files: list[tuple[pathlib.Path, bytes]] = []
    for artifact in ARTIFACTS:
        relative = GROUP / artifact / version
        for suffix in (".pom", ".module", "-sources.jar", ".aar" if artifact == "tap-sync-sdk" else ".jar"):
            path = relative / f"{artifact}-{version}{suffix}"
            content = (maven / path).read_bytes()
            if not content:
                raise ValueError(f"empty Maven artifact: {path}")
            files.append((path, content))
            for algorithm in ("md5", "sha1", "sha256"):
                digest = hashlib.new(algorithm, content).hexdigest().encode()
                files.append((path.with_name(f"{path.name}.{algorithm}"), digest))
    # Validate every already-published file before uploading anything new.
    missing = []
    for relative, content in files:
        url = f"{base.rstrip('/')}/{relative.as_posix()}"
        existing = request(url, authorization)
        if existing is None:
            missing.append((url, content))
        elif existing != content:
            raise ValueError(f"refusing to replace published Maven file {relative}")
    for url, content in missing:
        # The registry may generate checksums after a primary file is uploaded.
        # Re-check instead of overwriting those files (or a concurrent publisher's).
        existing = request(url, authorization)
        if existing is None:
            request(url, authorization, content)
        elif existing != content:
            raise ValueError("refusing to replace a Maven file created during upload")
        if request(url, authorization) != content:
            raise ValueError("Maven upload verification failed")
    print(f"Maven: {len(files) - len(missing)} identical files retained, {len(missing)} uploaded")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--maven", type=pathlib.Path, required=True)
    args = parser.parse_args()
    upload(args.maven, args.version, f"https://maven.pkg.github.com/{os.environ['GITHUB_REPOSITORY']}",
           os.environ["GITHUB_ACTOR"], os.environ["GITHUB_TOKEN"])


if __name__ == "__main__":
    main()
