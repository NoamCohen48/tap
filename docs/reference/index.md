# API reference

The references are **generated from the source** on every docs build, so they cannot drift
from the code:

| | Generated from | With |
|---|---|---|
| [Kotlin client](kotlin.md) | KDoc in `clients/kotlin/sdk` and `clients/kotlin/junit5` | [Dokka](https://kotlinlang.org/docs/dokka-introduction.html) |
| [Python client](python.md) | docstrings and type hints in `clients/python/tap_e2e` | [mkdocstrings](https://mkdocstrings.github.io/) |
| [gRPC server API](grpc.md) | `contracts/proto/*.proto` (`tap.v1`) | [protoc-gen-doc](https://github.com/pseudomuto/protoc-gen-doc) |

`scripts/build-docs.sh` runs the three generators and then `mkdocs build --strict`; the
guide pages are plain Markdown under `docs/` and can be served alone with `mkdocs serve`.
The same script also produces a **Markdown edition** of everything: the guide as-is, the
Kotlin reference through Dokka's GFM renderer, the Python reference through lazydocs, the
gRPC reference, the `tap-agent` README and skill, and the changelog, for reading offline, in a
repository or by a coding agent. Every GitHub Release carries it as
`tap-docs-<version>.zip` and `tap-docs-<version>.tar.gz`, built from the tagged commit.

`tap-agent` (experimental) has no generated reference: see [Coding agents](../guide/agents.md)
and `tap-agent --help`.

The server API page is the contract every client implements. If you want a client in another
language, that page plus the proto is all you need; the Kotlin and Python clients are ~1 000
lines each over it.
