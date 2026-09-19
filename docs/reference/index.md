# API reference

The references are **generated from the source** on every docs build, so they cannot drift
from the code:

| | Generated from | With |
|---|---|---|
| [Kotlin client](kotlin.md) | KDoc in `clients/kotlin/sdk` and `clients/kotlin/junit5` | [Dokka](https://kotlinlang.org/docs/dokka-introduction.html) |
| [Python client](python.md) | docstrings and type hints in `clients/python/tap` | [mkdocstrings](https://mkdocstrings.github.io/) |
| [gRPC service API](grpc.md) | `contracts/api/proto/tap.proto` (`tap.v1`) | [protoc-gen-doc](https://github.com/pseudomuto/protoc-gen-doc) |

`scripts/build-docs.sh` runs the three generators and then `mkdocs build --strict`; the
guide pages are plain Markdown under `docs/` and can be served alone with `mkdocs serve`.

The service API page is the contract every client implements. If you want a client in another
language, that page plus the proto is all you need; the Kotlin and Python clients are ~1 000
lines each over it.
