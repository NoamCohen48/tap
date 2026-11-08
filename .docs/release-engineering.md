# Versioning, CI and releases

## Decision: one Tap version

Approved by the owner on 2026-11-07: replace independent component versions and release sets
with **one version, initially 0.1.0**, for the daemon, bundled driver, contracts, Kotlin SDK,
JUnit extension, Python SDK, Tap agent, Tap Studio, Tap watcher and experimental sync SDK.
This deliberately supersedes this record's earlier per-family design. The original plan is
not the release source of truth.

Why: these components are developed together, share the same protocol and should be one
installable product. Independent tags forced dependency-order orchestration and allowed the
website to advertise a bundle that nobody had published. The 0.1.0 version-preparation commit
changed numbers and links but created no tags or release runs, so the first unified release
used that number without replacing anything. 0.1.0 shipped on 2026-11-08 (record below).

Earlier `daemon/v*`, `client-*/v*` and `sync-sdk/v*` tags/releases remain immutable history.
New releases use only `vX.Y.Z`. The Release set and Bundle workflows are removed; only
`release.yml` creates releases, so nothing can combine different component versions.

## Version sources

- `gradle.properties`: **`tap.version=0.1.0`**, the canonical release version.
- Root `build.gradle.kts`: every project uses that value and group
  `io.github.noamcohen48.tap`, including `device/sync-sdk` (version only; no synchronization
  behavior changes).
- Each Python `pyproject.toml` mirrors the same value. These are packaging mirrors, not
  independent version lines: a source distribution must build without files outside its
  package. `.github/scripts/release_version.py` checks all mirrors on every CI run and rejects
  obsolete `tap.version.*` properties or mismatched tags.
- `clients/python/scripts/gen_stubs.py` reads `tap.version` into the generated
  `_gen/engine_version.py`. Regenerate after a release version change; never hand-edit it.
- Gradle generates `ENGINE_VERSION` and `TAP_API_VERSION`; daemon, driver and handshake build
  IDs derive from the shared version. Driver `versionCode` remains major*10000+minor*100+patch.

During 0.x, features/breaking changes increment the minor; fixes increment the patch.
Release candidates use `X.Y.Z-rc.N` (also `alpha.N`/`beta.N`); Python filenames use their
PEP 440 form, such as `X.Y.ZrcN`. Numeric versions are regular GitHub Releases and marked
latest; only explicit pre-release suffixes set GitHub's pre-release flag. The project is
still alpha, with the experimental exceptions documented in `docs/reference/releases.md`.

A shared version is not a wire compatibility mechanism. Keep `API_COMPATIBILITY` and the
client's minimum server-version checks unchanged. Increment the compatibility epoch for
incompatible API changes, not release-only bumps. No device protocol change is needed here.
The optional sync SDK sharing the release number does not force consumers to upgrade it.

## Artifacts on one release

| Asset | Contents |
|---|---|
| `tap-<v>-linux-x86_64`, `tap-<v>-macos-aarch64` | Native server with bundled driver APKs |
| `tap-<v>-server-jvm.zip` | Standalone JVM server, Java 17+ |
| `tap_e2e`, `tap_agent`, `tap_studio`, `tap_watcher` wheel + sdist | All Python components; Studio/watcher carry their built pages |
| `tap-<v>-maven.zip` | `tap-schema`, `tap-api`, `tap-client`, `tap-junit5`, `tap-sync-sdk` at the same version, in Maven layout |
| `tap-<v>-linux-x86_64.zip`, `tap-<v>-macos-aarch64.zip`, `tap-<v>-jvm.zip` | Complete platform bundles |
| `SHA256SUMS` | Checksums for every downloadable asset |

The JVM server asset has `server-jvm` in its name to avoid colliding with the full JVM
bundle. Inside that bundle it remains `server/tap-<v>-jvm.zip`, as the installer expects.
The Maven zip and bundles need no GitHub token. Maven artifacts also go to GitHub Packages;
Python distributions optionally go to PyPI (`PUBLISH_TO_PYPI=true`, trusted publishing set
up for all four projects). Neither registry needs to be readable to assemble bundles.

## Release pipeline (`.github/workflows/release.yml`)

Entry points:

1. **Automatic:** successful **push-to-main CI** completion. The resolver checks the exact
   SHA's latest CI result, skips superseded main runs, validates every version mirror and
   creates `v<version>` only if untagged. A later commit with the same version cannot move
   the existing tag or republish it. `GITHUB_TOKEN` tag pushes do not create another run.
2. **Explicit `v*` tag push:** also requires successful main CI for the tagged SHA and
   ancestry on `main`.
3. **Recovery:** `gh workflow run Release --ref main -f tag=v0.1.0`. The tag must exist;
   checkout, assets and notes follow that tag, not the dispatch branch. It still checks CI.

A repository-wide release concurrency group prevents two runs from mutating release state
at once. External actions in CI, Docs and Release are pinned to immutable commits. Release
builds disable shared Gradle/Bun caches (including the docs composite action's release mode)
rather than consuming cache entries from ordinary CI. A failed CI run never tags or publishes
anything. Before 0.1.0, main CI failed on rotation restoration (SystemUI rewriting the
rotation lock after restore) and a hierarchy-dump test race; both were fixed, not skipped,
before the release. Do not hide or skip a failing test to release.

Jobs after the resolver:

- **Native matrix:** Linux x86-64 and macOS Apple silicon (macos-15), GraalVM 21 with Gradle
  on JDK 17. Build native images, check version, and run the no-device daemon RPC smoke on
  each platform. APKs are product-flavor resources inside the server.
- **JVM/Maven:** contract/core/daemon/Kotlin tests; JVM zip; all five Maven publications
  staged to a fresh local repository with `-Dmaven.repo.local`. No registry writes yet.
- **Python matrix:** SDK, agent, Studio, watcher tests; generated-stub checks; browser tests
  and builds where applicable; build wheel *from the sdist*, then check the installed version
  and CLI. Both wheel and sdist are staged.
- **Docs:** Markdown guide and generated references from the exact release SHA.
- **Assembly:** `scripts/build-bundle.sh` delegates to `scripts/build_bundle.py`, using only
  this run's staged artifacts. Verify required components, wheel metadata and built pages,
  Maven/POM versions and internal dependency versions, then construct bundles and checksums.
  No newest-family lookup or downloading from partially published releases. The complete
  asset directory is uploaded as `release-complete` before any external publication.
- **Publication:** create/resume a **draft** release, upload all assets, publish those exact
  staged Maven bytes (no rebuild), optionally publish Python distributions, then make the
  release public and verify every public asset URL. A published release is never clobbered.

`publish_maven.py` checks existing files before writing: identical files are retained,
missing files uploaded and read back, conflicting bytes rejected. This permits recovery
from a partially uploaded package without replacing a published file. Only explicit
version coordinates are published; unversioned Maven latest metadata is not needed by the
supported pinned dependencies. Optional PyPI retries skip existing versions; they never
replace them. A failure before the final visibility change leaves a draft, not a public
release with missing download bundles. Registries are not a transaction: an upload failure
can leave some packages visible before the GitHub Release is made public.

**Never move a release tag.** Retry its failed workflow or dispatch its exact tag. If a code
change is needed after tagging, bump the shared version and ship a new tag. If the final URL
check fails after publication, repair access/transient errors, not asset bytes. A recovery
run skips an already-public release; if publication succeeded but the final URL check or Docs
dispatch failed, repair the access/transient issue and run `gh workflow run Docs --ref main`.

## Shipping and validation

1. Bump `tap.version` and all four Python mirrors together, regenerate SDK stubs, update
   changelog and public install/download links. Commit code, generated files, tests and docs
   together on a branch, and merge through a PR after its checks pass.
2. Main CI must pass, including device and native lanes. The release workflow then tags and
   publishes the version automatically; a version-preparation commit alone is not a release.
3. Wait for Release to succeed. Check `gh release view v0.1.0`, all three bundle downloads,
   `SHA256SUMS`, and the website's Download page.
4. For an interrupted run, use the recovery dispatch above; do not manually create per-family
   or bundle tags.

Local checks for release tooling (Python 3.11+, actionlint and ShellCheck on PATH;
no device or credentials). Actionlint silently skips shell validation without ShellCheck,
so check its presence explicitly, as the hosted job does:

```bash
python .github/scripts/release_version.py v0.1.0
python -m unittest discover -s .github/scripts/tests -v
shellcheck --version
actionlint -shellcheck "$(command -v shellcheck)"
```

The regression suite tests tag/mirror rejection, main-CI gating, immutable/resumable
publication, every bundle's contents/checksums/modes and website download asset names.
A real local Gradle staging build additionally proves all POMs and artifacts use 0.1.0.
No local device matrix is required for this release-tooling change unless requested.

## CI and documentation deployment

`ci.yml` runs on PRs, main pushes and manual dispatch:

- Release tooling: version validation, publication/packaging tests, pinned actionlint.
- API contract: buf lint/breaking and generated Python stub checks.
- JVM: unit tests, driver/fixture APKs, daemon/validation distributions, local Maven POMs.
- Python 3.10/3.13: SDK and agent tests and wheels.
- Studio/watcher 3.10/3.13: generated code, browser tests/builds, backend tests and wheels.
- API 34 emulator: Kotlin/Python suites, agent/Studio smokes (not the local fault-injection
  validation matrix). This lane must stay enabled.
- Main-only native image: build and no-device RPC smoke to catch reflection metadata gaps.

Gradle caches are enabled; `setup-gradle` saves on main and PRs restore read-only. Device
jobs build their own distributions. Local API 29 hardware and fault-injection/reboot tests
remain outside CI; never run reboot tests on shared devices without approval.

`docs.yml` builds the guide and generated references through `scripts/build-docs.sh`, uploads
site/Markdown artifacts, and deploys main to <https://noamcohen48.github.io/tap/> when
`DEPLOY_DOCS=true` and GitHub Pages uses Actions. Deployment also requires the current
version's release to be public: a missing tag or a draft is not enough. Release explicitly
dispatches Docs after the public URL checks pass, because `GITHUB_TOKEN` release events do not
start other workflows. This prevents publishing download links ahead of their assets.
Website buttons and install snippets now point to `v0.1.0`, the same release the unified
pipeline creates. Product labels in the site
are **Tap agent** and **Tap watcher**; CLI/package names stay unchanged.

## Repository settings and remaining limits

- `main` changes through PRs only. Release tag protection must cover **`v*`** as well as
  historical `*/v*` tags; never force-update a protected tag.
- GitHub Packages readers need `read:packages`; bundled/local Maven repositories do not.
- PyPI remains opt-in; registering trusted publishers is required before enabling it.
- Windows uses the JVM bundle; no Windows native build is provided.
- Native builds and registry uploads require hosted CI.
- Maven Central, Windows native builds, signed binaries and an offline installer remain
  outside this change.

## Release record: 0.1.0 (2026-11-08)

- Release run 42424130991 succeeded on `main` CI's success for 397afd49; it created tag
  `v0.1.0` there and made the release public (latest, not a pre-release).
- 16 assets: native `linux-x86_64` and `macos-aarch64` servers, `server-jvm.zip`, the three
  platform bundles, `maven.zip`, wheel + sdist for `tap-e2e`, `tap-agent`, `tap-studio` and
  `tap-watcher`, and `SHA256SUMS`. Checksums verified after download; the Linux binary
  reports `tap daemon 0.1.0`.
- GitHub Packages: `tap-schema`, `tap-api`, `tap-client`, `tap-junit5` and `tap-sync-sdk` at 0.1.0.
- PyPI: not published (`PUBLISH_TO_PYPI` unset; trusted publishers not registered).
- Docs run 42425277388, dispatched by Release, deployed the site; every Download-page and
  install-snippet link to `v0.1.0` returns 200.
