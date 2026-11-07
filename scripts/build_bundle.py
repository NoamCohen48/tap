#!/usr/bin/env python3
"""Assemble unified release assets and platform bundles from one CI run, without network I/O."""
from __future__ import annotations

import argparse
import hashlib
import pathlib
import shutil
import runpy
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from email.parser import BytesParser

ROOT = pathlib.Path(__file__).resolve().parents[1]
python_version = runpy.run_path(str(ROOT / ".github/scripts/release_version.py"))["python_version"]

PLATFORMS = ("linux-x86_64", "macos-aarch64", "jvm")
PACKAGES = ("tap_e2e", "tap_agent", "tap_studio", "tap_watcher")
MAVEN = ("tap-schema", "tap-api", "tap-client", "tap-junit5", "tap-sync-sdk")
GROUP = pathlib.Path("io/github/noamcohen48/tap")


def checksums(directory: pathlib.Path) -> None:
    lines = []
    for path in sorted(directory.rglob("*")):
        if path.is_file() and path != directory / "SHA256SUMS":
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            lines.append(f"{digest}  {path.relative_to(directory).as_posix()}\n")
    (directory / "SHA256SUMS").write_text("".join(lines))


def archive(directory: pathlib.Path, target: pathlib.Path, *, prefix: bool = True) -> None:
    # ZipInfo.from_file preserves install.sh and native binary executable permissions.
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as zipped:
        for path in sorted(directory.rglob("*")):
            if path.is_file():
                name = path.relative_to(directory.parent if prefix else directory)
                zipped.write(path, name.as_posix())


def require(path: pathlib.Path) -> None:
    if not path.is_file() or not path.stat().st_size:
        raise ValueError(f"missing or empty artifact: {path}")


def assemble(version: str, assets: pathlib.Path, maven: pathlib.Path, docs: pathlib.Path,
             out: pathlib.Path, root: pathlib.Path = ROOT) -> None:
    py_version = python_version(version)
    if out.exists():
        raise ValueError(f"output already exists: {out}; use a fresh directory")
    require(docs / "README.md")
    expected = [f"tap-{version}-{platform}" for platform in PLATFORMS[:2]]
    expected.append(f"tap-{version}-server-jvm.zip")
    for package in PACKAGES:
        wheel = assets / f"{package}-{py_version}-py3-none-any.whl"
        require(wheel)
        require(assets / f"{package}-{py_version}.tar.gz")
        expected.extend((wheel.name, f"{package}-{py_version}.tar.gz"))
        with zipfile.ZipFile(wheel) as zipped:
            metadata = BytesParser().parsebytes(zipped.read(f"{package}-{py_version}.dist-info/METADATA"))
            if metadata["Version"] != py_version:
                raise ValueError(f"{wheel.name}: metadata version does not match {py_version}")
            if package in ("tap_studio", "tap_watcher") and f"{package}/static/index.html" not in zipped.namelist():
                raise ValueError(f"{wheel.name}: built browser page is missing")
    for name in expected:
        require(assets / name)
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    for artifact in MAVEN:
        directory = maven / GROUP / artifact / version
        for suffix in (".pom", ".module", "-sources.jar", ".aar" if artifact == "tap-sync-sdk" else ".jar"):
            require(directory / f"{artifact}-{version}{suffix}")
        pom = ET.parse(directory / f"{artifact}-{version}.pom").getroot()
        if pom.findtext("m:version", namespaces=ns) != version:
            raise ValueError(f"{artifact}: POM version does not match {version}")
        for dep in pom.findall("m:dependencies/m:dependency", ns):
            if dep.findtext("m:groupId", namespaces=ns) == "io.github.noamcohen48.tap" and dep.findtext("m:version", namespaces=ns) != version:
                raise ValueError(f"{artifact}: Tap dependency has a different version")
    with zipfile.ZipFile(assets / f"tap-{version}-server-jvm.zip") as zipped:
        if not any(name.endswith("/bin/tap") for name in zipped.namelist()):
            raise ValueError("JVM distribution has no tap launcher")

    out.mkdir(parents=True)
    for name in expected:
        shutil.copy2(assets / name, out / name)
    with tempfile.TemporaryDirectory(prefix="tap-release-") as temporary:
        work = pathlib.Path(temporary)
        repository = work / "maven"
        for artifact in MAVEN:
            relative = GROUP / artifact / version
            shutil.copytree(maven / relative, repository / relative)
        archive(repository, out / f"tap-{version}-maven.zip", prefix=False)
        for platform in PLATFORMS:
            bundle = work / f"tap-{version}-{platform}"
            (bundle / "server").mkdir(parents=True)
            (bundle / "python").mkdir()
            if platform == "jvm":
                shutil.copy2(assets / f"tap-{version}-server-jvm.zip", bundle / "server" / f"tap-{version}-jvm.zip")
            else:
                binary = bundle / "server" / "tap"
                shutil.copy2(assets / f"tap-{version}-{platform}", binary)
                binary.chmod(0o755)
            for package in PACKAGES:
                wheel = f"{package}-{py_version}-py3-none-any.whl"
                shutil.copy2(assets / wheel, bundle / "python" / wheel)
            shutil.copytree(repository, bundle / "maven")
            shutil.copytree(docs, bundle / "docs")
            shutil.copy2(root / "LICENSE", bundle / "LICENSE")
            shutil.copy2(root / "packaging/bundle/install.sh", bundle / "install.sh")
            (bundle / "install.sh").chmod(0o755)
            fields = {"bundle": version, "platform": platform, "engine": version,
                      **{f"client-{part}": version for part in ("kotlin", "python", "agent", "studio", "watcher")},
                      "sync-sdk": version}
            (bundle / "VERSIONS").write_text("".join(f"{key}={value}\n" for key, value in fields.items()))
            install = (root / "packaging/bundle/INSTALL.md").read_text()
            for token in ("BUNDLE", "ENGINE", "KOTLIN", "PYTHON", "AGENT", "STUDIO", "WATCHER"):
                install = install.replace(f"@{token}@", py_version if token in ("PYTHON", "AGENT", "STUDIO", "WATCHER") else version)
            install = install.replace("@PLATFORM@", platform)
            (bundle / "INSTALL.md").write_text(install)
            checksums(bundle)
            archive(bundle, out / f"{bundle.name}.zip")
    checksums(out)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    for name in ("assets", "maven", "docs", "out"):
        parser.add_argument(f"--{name}", type=pathlib.Path, required=True)
    args = parser.parse_args()
    assemble(args.version, args.assets.resolve(), args.maven.resolve(), args.docs.resolve(), args.out.resolve())


if __name__ == "__main__":
    main()
