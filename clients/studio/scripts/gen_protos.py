#!/usr/bin/env python3
"""Regenerates the studio's code from proto/studio.proto, or verifies it is current (--check).

- Python, into tap_studio/_gen: the messages (grpcio-tools' protoc) and the Connect server and
  client (protoc-gen-connect-python). The tap.v1 imports are pointed at tap-e2e's generated modules:
  protobuf registers each .proto file once per process, so the studio must share tap-e2e's.
- TypeScript, into web/src/gen: protobuf-es messages for studio.proto *and* the tap.v1 protos it
  imports (the page needs them typed too), with protoc-gen-es from web/node_modules (`bun install`).

Both outputs are committed, so neither the wheel nor the page build needs protoc; CI runs
`--check` to fail when the proto and the code drift apart.
"""

from __future__ import annotations

import filecmp
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

STUDIO = pathlib.Path(__file__).resolve().parents[1]
ROOT = STUDIO.parents[1]
CONTRACTS = ROOT / "contracts" / "proto"
PROTO = STUDIO / "proto" / "studio.proto"
PY_OUT = STUDIO / "tap_studio" / "_gen"
TS_OUT = STUDIO / "web" / "src" / "gen"
PROTOC_GEN_ES = STUDIO / "web" / "node_modules" / ".bin" / "protoc-gen-es"
# Output depends on the generator releases; CI installs exactly these for --check. grpcio-tools
# matches tap-e2e's (clients/python/scripts/gen_stubs.py); protoc-gen-es is pinned by web/bun.lock.
GRPCIO_TOOLS = "1.84.0"
CONNECT_PYTHON = "0.9.0"

# protoc emits `import selector_pb2 as selector__pb2`; studio's own modules sit in this package.
_ABSOLUTE = re.compile(r"^import (\w+_pb2) as (\w+)$", re.MULTILINE)
_HEADER = "# Generated from clients/studio/proto/studio.proto by scripts/gen_protos.py; do not edit.\n"


def _python_import(match: re.Match[str]) -> str:
    module, alias = match.groups()
    if module == "studio_pb2":
        return f"from . import {module} as {alias}"
    return f"from tap_e2e._gen import {module} as {alias}"


def generate(py_out: pathlib.Path, ts_out: pathlib.Path) -> None:
    py_out.mkdir(parents=True, exist_ok=True)
    ts_out.mkdir(parents=True, exist_ok=True)
    protoc = [sys.executable, "-m", "grpc_tools.protoc", f"-I{PROTO.parent}", f"-I{CONTRACTS}"]
    connect = shutil.which("protoc-gen-connect-python") or str(pathlib.Path(sys.executable).parent / "protoc-gen-connect-python")
    subprocess.run(
        [
            *protoc,
            f"--python_out={py_out}",
            f"--pyi_out={py_out}",
            f"--plugin=protoc-gen-connect-python={connect}",
            f"--connect-python_out={py_out}",
            str(PROTO),
        ],
        check=True,
    )
    for generated in py_out.glob("studio_*.py*"):
        content = _ABSOLUTE.sub(_python_import, generated.read_text())
        if generated.suffix == ".pyi":
            content = "# ruff: noqa\n" + content
        generated.write_text(content)
    (py_out / "__init__.py").write_text(_HEADER)
    if not PROTOC_GEN_ES.exists():
        sys.exit(f"{PROTOC_GEN_ES} is missing: run `bun install` in clients/studio/web")
    subprocess.run(
        [
            *protoc,
            f"--plugin=protoc-gen-es={PROTOC_GEN_ES}",
            f"--es_out={ts_out}",
            "--es_opt=target=ts,import_extension=none",
            str(PROTO),
            *map(str, sorted(CONTRACTS.glob("*.proto"))),
        ],
        check=True,
    )


def _files(directory: pathlib.Path) -> set[str]:
    return {p.name for p in directory.iterdir() if p.is_file()} if directory.exists() else set()


def _generators_match() -> bool:
    from importlib.metadata import version

    wanted = {"grpcio-tools": GRPCIO_TOOLS, "protoc-gen-connect-python": CONNECT_PYTHON}
    wrong = {name: version(name) for name, pinned in wanted.items() if version(name) != pinned}
    for name, installed in wrong.items():
        print(f"{name} {installed} installed, the generated code is pinned to {wanted[name]}", file=sys.stderr)
    return not wrong


def main(argv: list[str]) -> int:
    if argv not in ([], ["--check"]):
        print("usage: gen_protos.py [--check]", file=sys.stderr)
        return 2
    if not _generators_match():
        return 1
    if argv == ["--check"]:
        with tempfile.TemporaryDirectory() as tmp:
            py_fresh, ts_fresh = pathlib.Path(tmp, "py"), pathlib.Path(tmp, "ts")
            generate(py_fresh, ts_fresh)
            stale = []
            for fresh, committed in ((py_fresh, PY_OUT), (ts_fresh, TS_OUT)):
                names = _files(fresh) | _files(committed)
                stale += [
                    str((committed / name).relative_to(STUDIO))
                    for name in sorted(names)
                    if not (fresh / name).is_file()
                    or not (committed / name).is_file()
                    or not filecmp.cmp(fresh / name, committed / name, shallow=False)
                ]
        if stale:
            print(f"stale generated code: {', '.join(stale)}; run clients/studio/scripts/gen_protos.py", file=sys.stderr)
            return 1
        print("generated code is current")
        return 0
    for directory in (PY_OUT, TS_OUT):
        if directory.exists():
            shutil.rmtree(directory)
    generate(PY_OUT, TS_OUT)
    print(f"generated {PY_OUT.relative_to(STUDIO)} and {TS_OUT.relative_to(STUDIO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
