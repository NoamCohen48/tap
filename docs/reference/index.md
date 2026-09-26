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
The same script also produces a **Markdown edition** of everything (`build/docs-md/`, the
`docs-md` CI artifact): the guide as-is, the Kotlin reference through Dokka's GFM renderer
and the Python reference through lazydocs, for reading offline or in a repository.

The server API page is the contract every client implements. If you want a client in another
language, that page plus the proto is all you need; the Kotlin and Python clients are ~1 000
lines each over it.
