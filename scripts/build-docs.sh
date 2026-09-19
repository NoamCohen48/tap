#!/usr/bin/env bash
# Builds the public documentation site into build/site.
#
# Generated inputs (all ignored by git, regenerated on every run):
#   docs/reference/kotlin/   Dokka HTML for :clients:kotlin:sdk and :clients:kotlin:junit5
#   docs/reference/grpc.md   protoc-gen-doc Markdown for contracts/api/proto/tap.proto
#   (Python reference)       rendered by mkdocstrings from clients/python at mkdocs time
#
# Requirements: JDK 17 on JAVA_HOME (Gradle), `buf` and `protoc-gen-doc` on PATH (or set BUF /
# PROTOC_GEN_DOC), and a Python with `pip install -r docs/requirements.txt` plus the Python
# client (`pip install -e clients/python`) so mkdocstrings can import `tap`.
set -euo pipefail
cd "$(dirname "$0")/.."

BUF=${BUF:-buf}
PROTOC_GEN_DOC=${PROTOC_GEN_DOC:-$(command -v protoc-gen-doc || true)}
MKDOCS=${MKDOCS:-mkdocs}

echo "== Kotlin reference (Dokka)"
./gradlew --quiet :dokkaGenerate
rm -rf docs/reference/kotlin
cp -r build/dokka/html docs/reference/kotlin

echo "== gRPC reference (protoc-gen-doc)"
[ -n "$PROTOC_GEN_DOC" ] || { echo "protoc-gen-doc not found; set PROTOC_GEN_DOC" >&2; exit 1; }
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
cat > "$tmp/buf.gen.yaml" <<YAML
version: v2
plugins:
  - local: $PROTOC_GEN_DOC
    out: $tmp/out
    opt: markdown,grpc.md
YAML
"$BUF" generate --template "$tmp/buf.gen.yaml" contracts/api
{
  echo "# gRPC service API (tap.v1)"
  echo
  echo "Generated from \`contracts/api/proto/tap.proto\` by protoc-gen-doc. The narrative"
  echo "contract — run liveness, the pool, sessions, status mapping — is in the repository's"
  echo "\`.docs/service-api.md\`."
  echo
  # protoc-gen-doc emits its own H1 and a long TOC; keep the body from the file heading on
  # (mkdocs renders its own TOC from the headings).
  sed -n '/^<a name="tap-proto"><\/a>/,$p' "$tmp/out/grpc.md" | grep -v '^<p align="right">'
} > docs/reference/grpc.md

echo "== Site (mkdocs)"
"$MKDOCS" build --strict
echo "built build/site"
