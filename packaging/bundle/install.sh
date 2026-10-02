#!/usr/bin/env bash
# Installs a Tap bundle (see INSTALL.md next to this script): the `tap` server, the Python tools
# (tap-agent, tap-studio, with tap-e2e) in their own virtual environment, and the Kotlin
# artifacts as a local Maven repository plus the docs under <prefix>/share/tap.
#
#   ./install.sh [--prefix DIR] [--venv DIR] [--no-server] [--no-python] [--uninstall]
#
# Works with the bash 3.2 that macOS ships.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./install.sh [options]

  --prefix DIR   install under DIR (default: ~/.local): DIR/bin/tap, DIR/share/tap
  --venv DIR     install the Python packages into this virtual environment (created if
                 missing) instead of DIR/share/tap/venv, and link no commands into DIR/bin
  --no-server    skip the tap server
  --no-python    skip the Python packages
  --uninstall    remove what a previous run installed under the prefix
  -h, --help     show this help
EOF
}

prefix="$HOME/.local"
venv=""
server=1
python_tools=1
uninstall=0
while [ $# -gt 0 ]; do
  case "$1" in
    --prefix) prefix="$2"; shift 2 ;;
    --venv) venv="$2"; shift 2 ;;
    --no-server) server=0; shift ;;
    --no-python) python_tools=0; shift ;;
    --uninstall) uninstall=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

say() { printf '==> %s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

bundle="$(cd "$(dirname "$0")" && pwd)"
bin="$prefix/bin"
share="$prefix/share/tap"

if [ "$uninstall" = 1 ]; then
  for cmd in tap tap-agent tap-studio; do
    # Only what this script put there: a link into share/tap, or the native server it copied.
    if [ -L "$bin/$cmd" ]; then
      case "$(readlink "$bin/$cmd")" in "$share"/*) rm -f "$bin/$cmd" ;; esac
    elif [ "$cmd" = tap ] && [ -f "$bin/tap" ] && [ -f "$share/VERSIONS" ]; then
      rm -f "$bin/tap"
    fi
  done
  rm -rf "$share"
  say "removed $share and its commands in $bin"
  exit 0
fi

[ -f "$bundle/VERSIONS" ] || die "run this script from an unpacked Tap bundle"
field() { sed -n "s/^$1=//p" "$bundle/VERSIONS"; }
platform="$(field platform)"
engine="$(field engine)"
kotlin="$(field client-kotlin)"
say "Tap bundle $(field bundle) ($platform) -> $prefix"

# ---- integrity --------------------------------------------------------------------------------
if command -v sha256sum > /dev/null 2>&1; then
  (cd "$bundle" && sha256sum -c --quiet SHA256SUMS) || die "checksum mismatch: download the bundle again"
elif command -v shasum > /dev/null 2>&1; then
  (cd "$bundle" && shasum -a 256 -c --quiet SHA256SUMS) || die "checksum mismatch: download the bundle again"
else
  warn "no sha256sum or shasum: skipping the checksum check"
fi

# ---- server -------------------------------------------------------------------------------------
host="$(uname -s)-$(uname -m)"
if [ "$server" = 1 ]; then
  mkdir -p "$bin" "$share"
  case "$platform" in
    linux-x86_64|macos-aarch64)
      case "$platform:$host" in
        linux-x86_64:Linux-x86_64|macos-aarch64:Darwin-arm64) ;;
        *) die "this bundle is for $platform but this machine is $host: use the jvm bundle" ;;
      esac
      rm -f "$bin/tap"
      cp "$bundle/server/tap" "$bin/tap"
      chmod 755 "$bin/tap"
      # A browser download is quarantined on macOS and the unsigned binary would be blocked.
      if [ "$platform" = macos-aarch64 ]; then
        xattr -d com.apple.quarantine "$bin/tap" 2> /dev/null || true
      fi
      ;;
    jvm)
      command -v java > /dev/null 2>&1 || die "the jvm bundle needs Java 17 or newer on PATH"
      java_major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)"
      if [ -n "$java_major" ] && [ "$java_major" -lt 17 ]; then
        die "the jvm bundle needs Java 17 or newer, found $java_major"
      fi
      rm -rf "$share/server"
      mkdir -p "$share/server"
      if command -v unzip > /dev/null 2>&1; then
        unzip -q "$bundle/server/tap-$engine-jvm.zip" -d "$share/server"
      else
        python3 -m zipfile -e "$bundle/server/tap-$engine-jvm.zip" "$share/server"
      fi
      launcher="$(ls -d "$share/server"/*/bin/tap | head -n 1)"
      chmod 755 "$launcher"
      rm -f "$bin/tap"
      ln -s "$launcher" "$bin/tap"
      ;;
    *) die "unknown platform in VERSIONS: $platform" ;;
  esac
  "$bin/tap" version > /dev/null || die "the installed tap does not run: $bin/tap"
  say "server: $("$bin/tap" version) at $bin/tap"
fi

# ---- Kotlin artifacts, docs, wheels -----------------------------------------------------------------
mkdir -p "$share/maven"
# Merged, not replaced: projects pinned to an older bundle keep resolving.
cp -R "$bundle/maven/." "$share/maven/"
rm -rf "$share/docs" "$share/python"
cp -R "$bundle/docs" "$share/docs"
cp -R "$bundle/python" "$share/python"
cp "$bundle/VERSIONS" "$bundle/INSTALL.md" "$share/"
say "Kotlin Maven repository: $share/maven"
say "docs: $share/docs"

# ---- Python -------------------------------------------------------------------------------------------
pick_python() {
  for candidate in python3 python; do
    if command -v "$candidate" > /dev/null 2>&1 &&
      "$candidate" -c 'import sys; sys.exit(sys.version_info < (3, 10))' 2> /dev/null; then
      echo "$candidate"
      return
    fi
  done
}
if [ "$python_tools" = 1 ]; then
  py="$(pick_python)"
  if [ -z "$py" ]; then
    warn "no Python 3.10+ found: skipped tap-e2e, tap-agent and tap-studio (rerun with Python on PATH)"
  else
    target="${venv:-$share/venv}"
    if [ ! -x "$target/bin/python" ]; then
      if ! "$py" -m venv "$target" 2> /dev/null; then
        command -v uv > /dev/null 2>&1 || die "cannot create a virtual environment with $py (install the venv module, e.g. python3-venv, or uv)"
        uv venv --quiet --python "$py" "$target"
      fi
    fi
    wheels="$(ls "$share/python"/*.whl)"
    # Dependencies (grpcio, protobuf, ...) come from PyPI.
    # shellcheck disable=SC2086
    if "$target/bin/python" -m pip --version > /dev/null 2>&1; then
      "$target/bin/python" -m pip install --quiet --upgrade $wheels
    else
      command -v uv > /dev/null 2>&1 || die "$target has no pip and uv is not installed"
      uv pip install --quiet --python "$target/bin/python" --upgrade $wheels
    fi
    if [ -z "$venv" ]; then
      mkdir -p "$bin"
      for cmd in tap-agent tap-studio; do
        if [ -x "$target/bin/$cmd" ]; then
          rm -f "$bin/$cmd"
          ln -s "$target/bin/$cmd" "$bin/$cmd"
        fi
      done
      say "tap-agent, tap-studio: $bin (virtual environment $target)"
    else
      say "Python packages installed into $target"
    fi
  fi
fi

# ---- what next ------------------------------------------------------------------------------------------
case ":$PATH:" in
  *":$bin:"*) ;;
  *) warn "$bin is not on your PATH: add it, e.g. export PATH=\"$bin:\$PATH\"" ;;
esac
command -v adb > /dev/null 2>&1 || warn "adb is not on PATH: install the Android SDK platform-tools"

e2e_wheel="$(ls "$share/python"/tap_e2e-*.whl 2> /dev/null | head -n 1)"
cat <<EOF

Done. Next:

  tap start                      # start the server (once per machine; tap stop ends it)

Kotlin + JUnit 5, in your build.gradle.kts:

  repositories {
      maven(uri("$share/maven"))
      mavenCentral()
  }
  dependencies {
      testImplementation("io.github.noamcohen48.tap:tap-junit5:$kotlin")
  }

Python + pytest, in your project's virtual environment:

  pip install $e2e_wheel

Docs: $share/docs/README.md   (online: https://noamcohen48.github.io/tap/)
EOF
