#!/usr/bin/env python3
"""Regenerates tap/_gen from contracts/api/proto/tap.proto, or verifies it is current (--check).

The generated modules are committed so `pip install tap-e2e` needs no protoc; CI runs
`gen_stubs.py --check` to fail when the proto and the stubs drift apart.
"""
from __future__ import annotations

import filecmp
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[3]
API = ROOT / "contracts" / "api" / "proto"
OUT = ROOT / "clients" / "python" / "tap" / "_gen"
FILES = ("tap_pb2.py", "tap_pb2_grpc.py", "tap_pb2.pyi")


def generate(into: pathlib.Path) -> None:
    into.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            sys.executable, "-m", "grpc_tools.protoc", f"-I{API}",
            f"--python_out={into}", f"--grpc_python_out={into}", f"--pyi_out={into}",
            str(API / "tap.proto"),
        ],
        check=True,
    )
    # protoc emits an absolute import; the stubs live inside the package.
    grpc_file = into / "tap_pb2_grpc.py"
    grpc_file.write_text(grpc_file.read_text().replace("import tap_pb2 as tap__pb2", "from . import tap_pb2 as tap__pb2"))
    (into / "__init__.py").write_text("# Generated from contracts/api/proto/tap.proto by scripts/gen_stubs.py; do not edit.\n")


def main(argv: list[str]) -> int:
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
