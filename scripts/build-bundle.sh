#!/usr/bin/env bash
# Builds the one-download bundles from already published releases: one zip per platform
# (linux-x86_64, macos-aarch64, jvm) holding the server, the Python wheels (tap-e2e, tap-agent,
# tap-studio, tap-watcher), the Kotlin artifacts as a local Maven repository, the Markdown docs,
# install.sh and INSTALL.md (packaging/bundle/). Nothing but the docs is built: every other file
# is the released one.
#
#   scripts/build-bundle.sh --version 0.0.2 --docs build/docs-md
#       [--daemon daemon/v0.0.2] [--kotlin client-kotlin/v0.0.2] [--python client-python/v0.0.2]
#       [--agent client-agent/v0.0.2] [--studio client-studio/v0.0.1]
#       [--watcher client-watcher/v0.0.1] [--out build/bundle]
#   scripts/build-bundle.sh --resolve-only [--daemon ...] ...
#
# A family left out uses its newest release. --docs is the Markdown edition scripts/build-docs.sh
# writes to build/docs-md, built from the commit of the newest tag in the set (`docs=` in
# resolved.env; bundle.yml does that). --resolve-only writes <out>/resolved.env (the tags used,
# the docs tag, the daemon tag's commit) and stops. Needs gh (authenticated; GH_TOKEN in CI),
# curl, zip, and a token that can read this repository's GitHub Packages (read:packages).
# Writes <out>/tap-<version>-<platform>.zip, <out>/SHA256SUMS and <out>/resolved.env.
set -euo pipefail

repo="${GITHUB_REPOSITORY:-NoamCohen48/tap}"
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/build/bundle"
version="" docs="" resolve_only=0 daemon="" kotlin="" python="" agent="" studio="" watcher=""

while [ $# -gt 0 ]; do
  case "$1" in
    --version) version="$2"; shift 2 ;;
    --docs) docs="$2"; shift 2 ;;
    --resolve-only) resolve_only=1; shift ;;
    --daemon) daemon="$2"; shift 2 ;;
    --kotlin) kotlin="$2"; shift 2 ;;
    --python) python="$2"; shift 2 ;;
    --agent) agent="$2"; shift 2 ;;
    --studio) studio="$2"; shift 2 ;;
    --watcher) watcher="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
if [ "$resolve_only" = 0 ]; then
  [ -n "$version" ] || { echo "--version is required" >&2; exit 2; }
  [ -d "$docs" ] || { echo "--docs must be a built docs directory (scripts/build-docs.sh: build/docs-md)" >&2; exit 2; }
  docs="$(cd "$docs" && pwd)"
fi

# The newest release of a family, by creation time (pre-releases included: Tap is alpha).
latest() {
  gh release list --repo "$repo" --limit 100 --json tagName,createdAt \
    --jq "[.[] | select(.tagName | startswith(\"$1/v\"))] | sort_by(.createdAt) | last | .tagName // empty"
}
resolve() { # <given tag> <family>
  local tag="$1"
  [ -n "$tag" ] || tag="$(latest "$2")"
  [ -n "$tag" ] || { echo "no $2 release found" >&2; exit 1; }
  case "$tag" in "$2"/v*) ;; *) echo "$tag is not a $2 tag" >&2; exit 1 ;; esac
  gh release view "$tag" --repo "$repo" --json tagName > /dev/null
  echo "$tag"
}
daemon="$(resolve "$daemon" daemon)"
kotlin="$(resolve "$kotlin" client-kotlin)"
python="$(resolve "$python" client-python)"
agent="$(resolve "$agent" client-agent)"
studio="$(resolve "$studio" client-studio)"
watcher="$(resolve "$watcher" client-watcher)"
v() { echo "${1#*/v}"; }
engine="$(v "$daemon")"
# The docs come from the newest release in the set: the most recent commit's guide and references.
docs_tag="$(gh release list --repo "$repo" --limit 100 --json tagName,createdAt --jq \
  "[.[] | select(.tagName == \"$daemon\" or .tagName == \"$kotlin\" or .tagName == \"$python\"
   or .tagName == \"$agent\" or .tagName == \"$studio\" or .tagName == \"$watcher\")] | sort_by(.createdAt) | last | .tagName")"
echo "bundle ${version:-?}: $daemon $kotlin $python $agent $studio $watcher (docs from $docs_tag)"

rm -rf "$out"
mkdir -p "$out"
cat > "$out/resolved.env" <<EOF
daemon=$daemon
client_kotlin=$kotlin
client_python=$python
client_agent=$agent
client_studio=$studio
client_watcher=$watcher
docs=$docs_tag
commit=$(gh api "repos/$repo/commits/$daemon" --jq .sha)
EOF
[ "$resolve_only" = 0 ] || { cat "$out/resolved.env"; exit 0; }

work="$out/work"
mkdir -p "$work/assets" "$work/maven"

# ---- release assets ------------------------------------------------------------------------
gh release download "$daemon" --repo "$repo" -D "$work/assets" \
  -p "tap-$engine-linux-x86_64" -p "tap-$engine-macos-aarch64" -p "tap-$engine-jvm.zip" -p SHA256SUMS
(cd "$work/assets" && sha256sum -c --ignore-missing --quiet SHA256SUMS && rm SHA256SUMS)
gh release download "$python" --repo "$repo" -D "$work/assets" -p "tap_e2e-$(v "$python")-py3-none-any.whl"
gh release download "$agent" --repo "$repo" -D "$work/assets" -p "tap_agent-$(v "$agent")-py3-none-any.whl"
gh release download "$studio" --repo "$repo" -D "$work/assets" -p "tap_studio-$(v "$studio")-py3-none-any.whl"
gh release download "$watcher" --repo "$repo" -D "$work/assets" -p "tap_watcher-$(v "$watcher")-py3-none-any.whl"

# ---- Kotlin artifacts from GitHub Packages, laid out as a Maven repository ------------------
token="${GH_TOKEN:-${GITHUB_TOKEN:-$(gh auth token)}}"
group_path="io/github/noamcohen48/tap"
fetch() { # <artifact> <version> <file suffix> <required: yes|no>
  local dir="$work/maven/$group_path/$1/$2" file="$1-$2$3"
  mkdir -p "$dir"
  if ! curl -fsSL -u "x:$token" -o "$dir/$file" \
    "https://maven.pkg.github.com/$repo/$group_path/$1/$2/$file"; then
    rm -f "$dir/$file"
    [ "$4" = no ] || { echo "cannot download $file from GitHub Packages" >&2; exit 1; }
  fi
}
maven_artifact() { # <artifact> <version>
  fetch "$1" "$2" .pom yes
  fetch "$1" "$2" .jar yes
  fetch "$1" "$2" .module no
  fetch "$1" "$2" -sources.jar no
}
kv="$(v "$kotlin")"
maven_artifact tap-schema "$engine"
maven_artifact tap-api "$engine"
maven_artifact tap-client "$kv"
maven_artifact tap-junit5 "$kv"
# The client must be built against this server's tap-api, or the set is inconsistent.
api_dep="$(grep -A1 '<artifactId>tap-api</artifactId>' "$work/maven/$group_path/tap-client/$kv/tap-client-$kv.pom" \
  | sed -n 's:.*<version>\(.*\)</version>.*:\1:p')"
if [ "$api_dep" != "$engine" ]; then
  echo "tap-client $kv depends on tap-api $api_dep, but the server is $engine" >&2
  exit 1
fi

# ---- one directory, then one zip, per platform -----------------------------------------------
# Python's zipfile CLI drops the executable bit of server/tap and install.sh; keep the modes.
zip_dir() { # <parent> <dir name> <zip>
  python3 - "$1" "$2" "$3" <<'PY'
import os, sys, zipfile
parent, name, target = sys.argv[1:]
with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as z:
    for base, dirs, files in os.walk(os.path.join(parent, name)):
        dirs.sort()
        for f in sorted(files):
            path = os.path.join(base, f)
            info = zipfile.ZipInfo.from_file(path, os.path.relpath(path, parent))
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(path, "rb") as fh:
                z.writestr(info, fh.read())
PY
}
cd "$out"
: > SHA256SUMS
for platform in linux-x86_64 macos-aarch64 jvm; do
  name="tap-$version-$platform"
  dir="$work/$name"
  mkdir -p "$dir/server" "$dir/python"
  if [ "$platform" = jvm ]; then
    cp "$work/assets/tap-$engine-jvm.zip" "$dir/server/"
  else
    cp "$work/assets/tap-$engine-$platform" "$dir/server/tap"
    chmod 755 "$dir/server/tap"
  fi
  cp "$work/assets/"*.whl "$dir/python/"
  cp -R "$work/maven" "$dir/maven"
  cp -R "$docs" "$dir/docs"
  cp "$root/LICENSE" "$dir/"
  cp "$root/packaging/bundle/install.sh" "$dir/"
  chmod 755 "$dir/install.sh"
  cat > "$dir/VERSIONS" <<EOF
bundle=$version
platform=$platform
engine=$engine
client-kotlin=$kv
client-python=$(v "$python")
client-agent=$(v "$agent")
client-studio=$(v "$studio")
client-watcher=$(v "$watcher")
EOF
  sed -e "s/@BUNDLE@/$version/g" -e "s/@PLATFORM@/$platform/g" -e "s/@ENGINE@/$engine/g" \
    -e "s/@KOTLIN@/$kv/g" -e "s/@PYTHON@/$(v "$python")/g" -e "s/@AGENT@/$(v "$agent")/g" \
    -e "s/@STUDIO@/$(v "$studio")/g" -e "s/@WATCHER@/$(v "$watcher")/g" "$root/packaging/bundle/INSTALL.md" > "$dir/INSTALL.md"
  (cd "$dir" && find . -type f | sed 's:^\./::' | LC_ALL=C sort | xargs sha256sum > "$work/sums")
  mv "$work/sums" "$dir/SHA256SUMS"
  zip_dir "$work" "$name" "$out/$name.zip"
  sha256sum "$name.zip" >> SHA256SUMS
done
rm -rf "$work"
ls -l "$out"
