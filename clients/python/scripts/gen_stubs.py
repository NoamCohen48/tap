#!/usr/bin/env python3
"""Regenerates tap/_gen from contracts/api/proto/*.proto, or verifies it is current (--check).

The generated modules are committed so `pip install tap-e2e` needs no protoc; CI runs
`gen_stubs.py --check` to fail when the proto and the stubs drift apart.
"""
from __future__ import annotations

import filecmp
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[3]
API = ROOT / "contracts" / "api" / "proto"
OUT = ROOT / "clients" / "python" / "tap" / "_gen"
PROTOS = sorted(API.glob("*.proto"))
# Every proto yields <name>_pb2.py/.pyi and <name>_pb2_grpc.py (empty of stubs when it has no service).
FILES = tuple(
    f"{proto.stem}_pb2{suffix}" for proto in PROTOS for suffix in (".py", ".pyi", "_grpc.py")
) + ("__init__.py",)
# protoc emits absolute imports between generated modules; the stubs live inside one package.
SIBLING_IMPORT = re.compile(r"^(?:import (\w+_pb2) as (\w+)|from (\w+_pb2) import)", re.M)
# protoc output depends on the generator release; CI installs exactly this one for --check.
GENERATOR_VERSION = "1.84.0"


def generate(into: pathlib.Path) -> None:
    into.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            sys.executable, "-m", "grpc_tools.protoc", f"-I{API}",
            f"--python_out={into}", f"--grpc_python_out={into}", f"--pyi_out={into}",
            *map(str, PROTOS),
        ],
        check=True,
    )
    for generated in into.glob("*_pb2*.py*"):
        generated.write_text(SIBLING_IMPORT.sub(_relative_import, generated.read_text()))
    modules = ", ".join(f"{proto.stem}_pb2" for proto in PROTOS)
    exports = "".join(f"from .{proto.stem}_pb2 import *  # noqa: F401,F403\n" for proto in PROTOS)
    (into / "__init__.py").write_text(
        "# Generated from contracts/api/proto/*.proto by scripts/gen_stubs.py; do not edit.\n"
        f"# The package namespace is the union of {modules}: `from tap._gen import Selector`.\n" + exports
    )


def _relative_import(match: re.Match[str]) -> str:
    module, alias, from_module = match.groups()
    if from_module:
        return f"from .{from_module} import"
    return f"from . import {module} as {alias}"


def check_generator() -> None:
    from importlib.metadata import version

    installed = version("grpcio-tools")
    if installed != GENERATOR_VERSION:
        print(f"warning: grpcio-tools {installed} installed, stubs are pinned to {GENERATOR_VERSION}", file=sys.stderr)


def main(argv: list[str]) -> int:
    check_generator()
    if "--check" in argv:
        with tempfile.TemporaryDirectory() as tmp:
            fresh = pathlib.Path(tmp)
            generate(fresh)
            stale = [name for name in FILES if not filecmp.cmp(fresh / name, OUT / name, shallow=False)]
        if stale:
            print(f"stale generated stubs: {', '.join(stale)}; run clients/python/scripts/gen_stubs.py", file=sys.stderr)
            return 1
        print("generated stubs are current")
        return 0
    if OUT.exists():
        shutil.rmtree(OUT)
    generate(OUT)
    print(f"generated {', '.join(FILES)} into {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
