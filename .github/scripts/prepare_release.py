#!/usr/bin/env python3
"""Gate a unified release on successful main CI, immutable tags and unpublished versions."""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys

from release_version import resolve


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True).strip()


def api(repository: str, path: str, *, missing_ok: bool = False):
    response = subprocess.run(["gh", "api", f"repos/{repository}/{path}"], text=True, capture_output=True)
    if response.returncode:
        if missing_ok and "(HTTP 404)" in response.stderr:
            return None
        raise ValueError(f"GitHub API failed: {response.stderr.strip()}")
    try:
        return json.loads(response.stdout)
    except json.JSONDecodeError as error:
        raise ValueError("GitHub API returned invalid JSON") from error


def prepare(repository: str, requested_tag: str | None, completed_sha: str | None) -> dict[str, str]:
    values = resolve(requested_tag)
    tag = values["tag"]
    sha = git("rev-parse", "HEAD")
    main = git("rev-parse", "origin/main")
    if completed_sha and (completed_sha != sha or sha != main):
        return {"publish": "false", "reason": "a newer main commit superseded this CI run"}
    subprocess.run(["git", "merge-base", "--is-ancestor", sha, main], check=True)
    runs = api(repository, f"actions/workflows/ci.yml/runs?head_sha={sha}&branch=main&event=push&per_page=100")
    if not isinstance(runs, dict):
        raise ValueError("GitHub API returned an invalid workflow response")
    candidates = [run for run in runs["workflow_runs"] if run["head_sha"] == sha and run["head_branch"] == "main" and run["event"] == "push"]
    if not candidates or candidates[0]["conclusion"] != "success":
        raise ValueError(f"{sha} has no successful latest main CI run; refusing to release")
    refs = git("tag", "--list", tag)
    if refs:
        tagged = git("rev-parse", f"{tag}^{{commit}}")
        if tagged != sha:
            if completed_sha:
                return {"publish": "false", "reason": f"{tag} already names another commit; bump tap.version for new code"}
            raise ValueError(f"{tag} does not name checked-out commit {sha}")
    existing = api(repository, f"releases/tags/{tag}", missing_ok=True)
    if existing and not existing["draft"]:
        return {"publish": "false", "reason": f"{tag} is already published and will never be replaced"}
    if not refs:
        if not completed_sha:
            raise ValueError(f"tag {tag} does not exist; manual releases require an existing tag")
        # GITHUB_TOKEN tag pushes do not trigger another workflow; this run ships the tag.
        subprocess.run(["git", "tag", tag, sha], check=True)
        subprocess.run(["git", "push", "origin", f"refs/tags/{tag}"], check=True)
    return {**values, "sha": sha, "publish": "true"}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag")
    parser.add_argument("--completed-sha")
    args = parser.parse_args()
    if bool(args.tag) == bool(args.completed_sha):
        parser.error("give exactly one of --tag or --completed-sha")
    for key, value in prepare(os.environ["GITHUB_REPOSITORY"], args.tag, args.completed_sha).items():
        print(f"{key}={value}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
