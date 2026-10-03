# Versioning, CI and releases

Status: implemented 2026-09-19 (`.github/workflows/ci.yml`, `.github/workflows/release.yml`).
The first release is **0.0.1 (alpha)** for every family (tagged 2026-09-29); the earlier 0.1.0–0.3.0
numbers were never published and were reset to it. `sync-sdk` is experimental and is not
tagged yet. Stability promises and the experimental list are public in
`docs/reference/releases.md`; `CHANGELOG.md` records each release.

## What is an artifact, and why

The repository builds seven artifact families, each on its own version line:

| Family | Tag | Artifacts | Version source |
|---|---|---|---|
| **engine** (`server`) | `daemon/vX.Y.Z` | `tap` native binary (linux-x86_64, macos-aarch64) and JVM dist on a GitHub Release; Maven `io.github.noamcohen48.tap:tap-schema` (generated protobuf-lite messages of `contracts/proto`) and `tap-api` (the `tap.v1` gRPC stubs, depending on `tap-schema`) to GitHub Packages | `gradle.properties` `tap.version.engine` |
| **Kotlin client** | `client-kotlin/vX.Y.Z` | Maven `io.github.noamcohen48.tap:tap-client`, `io.github.noamcohen48.tap:tap-junit5` | `gradle.properties` `tap.version.client.kotlin` |
| **Python client** | `client-python/vX.Y.Z` | `tap-e2e` wheel + sdist on a GitHub Release (PyPI opt-in) | `clients/python/pyproject.toml` |
| **Agent tools** | `client-agent/vX.Y.Z` | `tap-agent` wheel + sdist (CLI + MCP server, depends on `tap-e2e`) on a GitHub Release (PyPI opt-in, after `tap-e2e`) | `clients/agent/pyproject.toml` |
| **Studio** (experimental) | `client-studio/vX.Y.Z` | `tap-studio` wheel + sdist (browser inspector + recorder with the built page inside, depends on `tap-e2e`) on a GitHub Release (PyPI opt-in, after `tap-e2e`) | `clients/studio/pyproject.toml` |
| **Watcher** (experimental) | `client-watcher/vX.Y.Z` | `tap-watcher` wheel + sdist (read-only activity + video viewer with the built page inside, depends on `tap-e2e`) on a GitHub Release (PyPI opt-in, after `tap-e2e`) | `clients/watcher/pyproject.toml` |
| **sync-sdk** | `sync-sdk/vX.Y.Z` | Maven `io.github.noamcohen48.tap:tap-sync-sdk` (AAR) | `gradle.properties` `tap.version.sync-sdk` |

The watcher is not released yet (`client-watcher` has no tag): the first bundle after it
landed needs a `client-watcher/v0.0.1` release first, since the bundle takes every family
(below). Its user docs are `docs/watcher/`.

### The host daemon and the driver are one artifact (the engine)

The driver APKs (`device/driver`) are not published on their own. They ride inside the
server (`host/daemon` bundles `driver.apk`/`driver-test.apk` as resources and installs them
per device) and they share one version with it:

- The server ↔ driver contract (TAP1, `contracts/protocol`) is internal and changes with
  both sides at once. A separately versioned driver would create a compatibility matrix that
  nobody needs: no user ever picks a driver version, `tap serve` installs the one it was built
  with, and the handshake's `HOST_BUILD_ID`/`DRIVER_APK_BUILD_ID` (both = `ENGINE_VERSION`)
  already refuse a mismatch.
- The TAP1 protocol version (`4.0`) remains the *wire* compatibility statement inside the
  handshake; the engine version is the *release* identifier. They move independently: many
  engine releases per protocol version.
- `contracts/protocol` is therefore not published to Maven either; it is compiled into the
  server and the driver. Only `contracts/schema` (the lite messages of `contracts/proto`;
  clients use the `tap.v1` ones, `tap.wire.v1` rides along unused) and `contracts/api` (the
  `tap.v1` service stubs) are published, at the engine version, because clients depend on
  them. Both use protobuf-javalite, the one runtime the Android driver can share with the
  host. `contracts/api` also generates the
  grpc-kotlin coroutine stubs, so it carries `grpc-kotlin-stub`, `kotlinx-coroutines-core`
  and `kotlin-stdlib` at compile scope in the published POM: grpc-kotlin 1.5.0 with grpc-java
  1.75.0 (pinned in `contracts/api/build.gradle.kts`; grpc-kotlin releases lag grpc-java, so
  the pair is chosen deliberately, not bumped with the rest). Coroutines are in the artifact's
  ABI — `Attach` returns a `Flow` — so they cannot be an implementation dependency.

The one on-device piece that *is* published separately is `sync-sdk`: it goes into the app
under test's build, is chosen by the app team, changes rarely, and its contract with the
driver is the content-provider protocol, not TAP1. Coupling it to engine releases would force
app rebuilds for every server fix.

### Clients own their versions

`tap-client`/`tap-junit5` and `tap-e2e` each release independently: a client fix should not
require a server rollout and vice versa. Each client release records which engine it was
built against (the `tap-api` version in the Kotlin POM; the Python release notes and the
committed `_gen` stubs), and the runtime check is `Info().daemon_version` /
`protocol_version` from the server. `tap.v1` is the API compatibility contract: within it,
fields are only added (`buf breaking` in CI enforces wire compatibility), so a newer client
against an older server degrades to "unknown field ignored" rather than breaking, and an
older client against a newer server keeps working. A `tap.v2` package would be new
files under `contracts/proto`.

Because `tap-client` depends on `tap-api:<engine>`, a `client-kotlin/v*` release checks that
the `tap-api` of the current engine version is already in GitHub Packages and fails with a
pointer to tag `daemon/v<engine>` first.

History from before the reset to 0.0.1 (none of these versions were published): client 0.2.0
(2026-09-21) was the breaking coroutine release: every `Device`/`App`/`Element`
call is `suspend` behind `tapTest`/`tapScope`, `TapConnection` owns its attach scope, and
`TapClient`/`TapConnection`/`Device` close via `suspend` (no `AutoCloseable`). The wire is
unchanged (`tap.v1` only gains no fields here), so the break is source-only: 0.1.x callers
recompile against `tapTest`/`tapScope`. Engine and Python/sync lines did not move.

## Versions in the build

`gradle.properties` holds the three Gradle-side versions; the root `build.gradle.kts` assigns
each module the version of its family (`clients/kotlin/*` and `samples` → Kotlin client,
`device/sync-sdk` → sync-sdk, everything else → engine) and the group `io.github.noamcohen48.tap`.
From the engine version:

- `:contracts:protocol` generates `EngineVersion.kt` (`ENGINE_VERSION`), which is what
  `HOST_BUILD_ID`, `DRIVER_APK_BUILD_ID`, `DRIVER_TEST_APK_BUILD_ID` and `DAEMON_VERSION`
  (`tap version`, `Info()`, `daemon.json`) are.
- `:device:driver` gets `versionName = ENGINE_VERSION` and `versionCode = major*10000 +
  minor*100 + patch`.
- The JVM distribution is `tap-<engine>.zip`.

The Python packages report `tap_e2e.__version__` / `tap_agent.__version__` from its installed distribution metadata.

Bumping: edit the property (or `pyproject.toml`), commit, then tag `<family>/v<version>` on
that commit. `.github/scripts/release_version.py` refuses a tag whose version differs from
the committed one. Pre-release suffixes (`1.2.0-rc.1`) are accepted.

## CI (`ci.yml`, on push to `main`, pull requests, manual)

| Job | Proves |
|---|---|
| `api-contract` | `buf lint` on `contracts/proto` (STANDARD minus the naming rules the file deliberately breaks, see `contracts/proto/buf.yaml`); `buf breaking` (`WIRE_JSON`) against the PR base / previous push, so removing, renumbering or retyping a field fails; `gen_stubs.py --check` with the pinned `grpcio-tools`, so the committed Python stubs match the proto. |
| `jvm` | Unit tests (`:contracts:protocol`, `:host:core`, `:host:daemon`, `:device:driver:command-engine`, both Kotlin client modules); assembles driver APKs, fixture, daemon dist/zip and validation executable; `publishToMavenLocal` for the five Maven artifacts (POMs resolve); `tap version` equals `tap.version.engine`. Uploads the JVM dist. |
| `python` | On 3.10 and 3.13: installs `tap-e2e` and `tap-agent`, runs their unit tests over the in-process fake daemon (`clients/python/tests/unit`, `clients/agent/tests`), builds both wheels, `tap-agent --help`. Uploads the wheels (`python-dists`). |
| `studio` | On 3.10 and 3.13: `tap-studio` (experimental, `.docs/recorder.md`; released as `client-studio/v*`). `gen_protos.py --check` (the committed Python and TypeScript code matches `clients/studio/proto/studio.proto`, with the pinned `grpcio-tools` and `protoc-gen-connect-python`); the page with Bun 1.3.11: `bun install --frozen-lockfile`, Vitest, `tsc --noEmit` (TypeScript 7) and the Vite build; the back end's unit tests; the wheel, checked to carry the built page, `tap-studio --version`. Uploads the wheel (`studio-dists`). |
| `watcher` | On 3.10 and 3.13: `tap-watcher` (experimental, `.docs/test-watcher.md`; released as `client-watcher/v*`). The same checks as `studio`: `gen_protos.py --check` against `clients/watcher/proto/watcher.proto`, the page (Vitest, `tsc --noEmit`, Vite build), the back end's unit tests, the wheel checked to carry the built page, `tap-watcher --version`. Uploads the wheel (`watcher-dists`). No device smoke yet. |
| `device-tests` | `reactivecircus/android-emulator-runner` API 34 x86_64 running `.github/scripts/device-tests.sh` (the action runs each `script` line as its own `sh -c`, so the lane is one script): `:samples:fixture-tests:test -Ptap.serials=emulator-5554` and the Python sample suite through a server each suite starts and stops (`tap.manageDaemon` / `TAP_MANAGE_DAEMON`), then `.github/scripts/agent-smoke.sh` — a `tap-agent` session (attach, install, cold launch, snapshot, tap by ref with `--settle`, wait, export) whose JSON export is checked (install and cold launch first, the ref logged as its `view_button` selector, every call ok), then `.github/scripts/studio_smoke.py` on the `studio` job's wheel: `tap-studio --serial --package` driven through its own Connect API with the launch-link cookie (the page needs it, frames carry a PNG and selector candidates; a cold launch, taps, set text, a tap picked with `.at(1)` recorded without an exactly-one wait, text assertions; export, New, Open, Replay all passed; SIGTERM releases the device; the exported file replays through `tap-e2e` in a fresh connection). One serial, so two-device tests are skipped. Failure artifacts (incl. `build/agent-smoke`, `build/studio-smoke`) are uploaded. Needs only `api-contract` and `studio` (both < 1 min); it builds what it runs, so it runs side by side with `jvm` rather than after it. |
| `native-image` | (push to `main` only) GraalVM 21 `nativeCompile`, then `tap start` and `.github/scripts/daemon_smoke.py`: every RPC that needs no device (Info, ListDevices, held Connect, ListConnections, Events, Disconnect, an observed connection) on the native binary, which catches missing reflection metadata before a release. |

Duration (2026-10-02 runs, before the changes below): about 17 min end to end, almost all of
it the critical path `jvm` (5 min, 162 tasks compiled from scratch) → `device-tests` (11 min: 2
min Gradle build, 1.5 min emulator SDK install and cold boot, 3.5 min Kotlin suite, 2 min Python
suite, 0.5 min smokes, 0.7 min freeing disk space). Everything else is under 1 min. Since then:

- `device-tests` no longer waits for `jvm`/`python` (≈5 min off the wall time; costs emulator
  minutes on a run a unit test would have failed);
- the Gradle build cache is on (`org.gradle.caching=true`); `setup-gradle` saves it with the
  Gradle home on `main` and PRs restore it read-only, so unchanged modules come `FROM-CACHE` in
  `jvm`, `device-tests` and the docs build. Device test tasks are `cacheIf { false }`;
- the disk clean-up only runs when the runner has under 30 GB free (current runners have ~90).

Not done yet, in order of payoff for effort:

- split the device lane over two emulator jobs (Kotlin suite / Python suite + smokes; ≈2–3 min);
- cache the AVD snapshot (`android-emulator-runner`'s documented cache step; ≈1 min — the cache
  restore of a multi-GB snapshot eats much of the boot it saves);
- a configuration-cache encryption key for `setup-gradle` (`cache-encryption-key`), so the
  configuration cache is saved too (≈5–10 s per Gradle invocation);
- `paths-ignore` for docs-only pushes (`docs/**`, `.docs/**`, `*.md`) on the device job.

Not in CI, still local: `:host:validation:deviceTest` (needs the two-device local matrix; the
`reboot`-tagged scenario reboots), the Samsung API 29 lane, and a native-image run against a
device (the emulator lane uses the JVM dist).

## Docs (`docs.yml`)

Runs on changes to `docs/`, `mkdocs.yml`, the clients or the API proto: `scripts/build-docs.sh`
(Dokka → `docs/reference/kotlin-api/`, protoc-gen-doc → `docs/reference/grpc.md`, mkdocstrings at
build time, `mkdocs build --strict`; then Dokka GFM + lazydocs into `build/docs-md/`) and
uploads `build/site` as the `site` artifact and `tap-docs-md.zip` as `docs-md`. Deploy to
GitHub Pages is gated on the `DEPLOY_DOCS=true` repository variable, with "GitHub Actions" as
the Pages source. Both were set on 2026-09-29, when the repository went public: the site is
https://noamcohen48.github.io/tap/ and redeploys from every docs change on `main`.

Repository settings since going public (2026-09-29): secret scanning with push protection,
Dependabot alerts, private vulnerability reporting, a read-only default `GITHUB_TOKEN`
(workflows that write declare it), approval required before workflows run for outside
contributors, and two rulesets: `main` cannot be deleted or force-pushed, and release tags
(`*/v*`) cannot be deleted, moved or force-pushed. Status checks are not required on `main`.

## Releases (`release.yml`, on tags)

`resolve` maps the tag to a family and checks the version; then one job set per family (see
the table above). If GitHub drops a tag-push event and no run starts (seen 2026-09-30),
dispatch the workflow manually with the tag name (`gh workflow run Release --ref main -f
tag=<family>/v<version>`): checkouts, the version check, release assets and install URLs
all follow the input tag, never the dispatch branch.

Shipping a user-visible set (e.g. engine 0.0.2 plus tap-studio 0.0.1) is one manual run
of `Release set` (`.github/workflows/release-set.yml`): give it the tags, it dispatches
one Release run per family in dependency order (daemon first), waits for all of them,
then checks every Release page carries its assets. A dry run validates the tags and
prints the plan without starting anything.

### Shipping a release: step by step

Each family has its own version line (engine 0.0.2 and studio 0.0.1 can ship
together), so a user-visible release is a *set* of per-family releases. The
full flow, using the 0.0.2 set as the example:

1. **Bump versions and docs.** Edit the version (`gradle.properties`
   `tap.version.*`, or the family's `pyproject.toml`), add a `CHANGELOG.md`
   section, point the install links in `README.md` and the guides at the new
   tag names. Commit and push to `main`.
2. **Wait for CI green.** The device lane takes ~22 min. Never tag on red:
   a tag on a broken commit ships broken artifacts, and a published version
   can never be replaced (registries refuse republishes), so a bad release
   means a whole new patch version.
3. **Tag each family on the green commit** and push the tags:

   ```bash
   git tag daemon/v0.0.2
   git push origin daemon/v0.0.2
   ```

   One tag per family (`daemon/v*`, `client-kotlin/v*`, `client-python/v*`,
   `client-agent/v*`, `client-studio/v*`, `client-watcher/v*`). Each tag push starts one Release
   run automatically. Tags cannot be deleted or moved (ruleset), so a typo
   means a new version, not a re-push — double-check the name.
4. **Ship the set with one action** instead of watching runs one by one:
   Actions tab → `Release set` → `Run workflow`, fill in the tags (leave a
   family empty to skip it), or the same from the CLI:

   ```bash
   gh workflow run "Release set" --ref main -f daemon=daemon/v0.0.2 \
     -f client_kotlin=client-kotlin/v0.0.2 -f client_python=client-python/v0.0.2 \
     -f client_agent=client-agent/v0.0.2 -f client_studio=client-studio/v0.0.1 \
     -f client_watcher=client-watcher/v0.0.1
   ```

   It releases the daemon first (the Kotlin client refuses to publish until
   the daemon's `tap-api` is out), then the rest, waits for all of them
   (native builds take up to ~1 h), and finally checks every Release page
   carries its assets — wheels/sdist for the Python families, binaries +
   `SHA256SUMS` for the daemon (the Kotlin release only has to exist; its
   artifacts are in GitHub Packages).
   Last, it builds the one-download bundles (`bundle/v<version>`, see below).
   Not sure about the tags? Run it first with `-f dry_run=true`: it only
   validates the tags exist and prints the plan.
5. **If a tag push starts no run** (seen 2026-09-30: four tags pushed, zero
   runs), don't re-push — dispatch that family directly:

   ```bash
   gh workflow run Release --ref main -f tag=client-studio/v0.0.1
   ```

   Same code path as a tag push (version check, checkouts, assets all follow
   the tag). Then run the set for the rest, or let the set cover it next time.
6. **Finish the Release pages.** Mark each as a pre-release (alpha) and make
   sure none is flagged latest, as was done by hand for 0.0.1:

   ```bash
   for t in daemon/v0.0.2 client-kotlin/v0.0.2 client-python/v0.0.2 \
     client-agent/v0.0.2 client-studio/v0.0.1; do
     gh release edit "$t" --prerelease --latest=false
   done
   ```

   Then open one Release page and confirm the assets a user needs are there
   (e.g. `tap_studio-0.0.1-py3-none-any.whl` on `client-studio/v0.0.1`).

### One-download bundles (`bundle.yml`)

Users who want "everything" should not have to collect five Release pages and a GitHub
token. After a set ships, `Release set` calls the `Bundle` workflow, which runs
`scripts/build-bundle.sh` and publishes the Release `bundle/v<version>` (pre-release, never
latest, tagged on the daemon tag's commit) with one zip per platform plus `SHA256SUMS`:

| Zip | Server inside |
|---|---|
| `tap-<v>-linux-x86_64.zip` | native `server/tap` |
| `tap-<v>-macos-aarch64.zip` | native `server/tap` |
| `tap-<v>-jvm.zip` | `server/tap-<engine>-jvm.zip` (any OS with Java 17; the Windows route) |

Each also holds `maven/` (`tap-schema`, `tap-api`, `tap-client`, `tap-junit5` with POM,
Gradle module metadata and sources, downloaded from GitHub Packages and laid out as a Maven
repository, so Gradle needs no token), `python/` (the `tap-e2e`, `tap-agent`, `tap-studio`,
`tap-watcher` wheels), `docs/` (the Markdown edition from `scripts/build-docs.sh`, built by the workflow's
`docs` job from the newest tagged commit in the set), `LICENSE`,
`install.sh` and `INSTALL.md` (`packaging/bundle/`, versions filled in), `VERSIONS` and a
per-file `SHA256SUMS`. Nothing else is rebuilt: every other file is a released one, the daemon's
`SHA256SUMS` is checked on download, and the build fails if `tap-client`'s POM names a
different `tap-api` than the server's version (an inconsistent set).

The bundle version defaults to the daemon tag's version (`bundle` input of `Release set`;
`none` skips it). A family not in the set contributes its newest release; a family with no
release at all fails the bundle (`no client-watcher release found`), so a new family ships its
first release before, or in, the set it is bundled with. To bundle releases
that already exist (e.g. the 0.0.2 set), run the workflow by hand:
`gh workflow run Bundle --ref main -f version=0.0.2` (families empty = newest). A bundle
version is never replaced; the workflow refuses an existing `bundle/v<version>`.

The public entry point is the site's Download page (`docs/download.md`); its links name the
bundle version, so bump them with the other install links at release time.

`install.sh` (bash 3.2-compatible for macOS) checks `SHA256SUMS`, refuses a native bundle on
the wrong OS/arch, installs the server into `<prefix>/bin` (default `~/.local`; the jvm
dist is unpacked under `<prefix>/share/tap/server` and linked), the wheels into a virtual
environment `<prefix>/share/tap/venv` (`python -m venv`, `uv` as fallback; `--venv DIR` for
the user's own) with `tap-agent`/`tap-studio`/`tap-watcher` linked into `bin`, and merges `maven/` into
`<prefix>/share/tap/maven` (older versions stay resolvable). `--uninstall` removes it all.
Python dependencies still come from PyPI; the bundle is not an offline installer.

The per-family releases carry no docs (until 2026-10 every one attached `tap-docs-<version>.zip`
/ `.tar.gz`; 0.0.1 and 0.0.2 still do): the Markdown edition ships only inside the bundles.

Maven goes to this repository's GitHub Packages registry
(`https://maven.pkg.github.com/NoamCohen48/tap`; readers need a token with `read:packages`
even for public repositories). Binaries and wheels go to a GitHub Release named after the
tag, with `SHA256SUMS` for the server. PyPI publishing is wired (trusted publishing) but off
until the repository variable `PUBLISH_TO_PYPI=true` is set and the project is registered on
PyPI. `tap-agent` depends on `tap-e2e`, so until both are on PyPI its release notes say to
install the `tap-e2e` wheel first.

Consuming:

```kotlin
repositories { maven("https://maven.pkg.github.com/NoamCohen48/tap") { credentials { … } } }
testImplementation("io.github.noamcohen48.tap:tap-junit5:0.0.2")
```

```bash
pip install https://github.com/NoamCohen48/tap/releases/download/client-python/v0.0.2/tap_e2e-0.0.2-py3-none-any.whl
pip install https://github.com/NoamCohen48/tap/releases/download/client-agent/v0.0.2/tap_agent-0.0.2-py3-none-any.whl
curl -L -o tap https://github.com/NoamCohen48/tap/releases/download/daemon/v0.0.2/tap-0.0.2-linux-x86_64 && chmod +x tap
```

## Open points

- **Pending, once PR #17 (the bundles) is merged:** create the 0.0.2 bundle the site's
  Download page, the README and the getting-started guide already link to, from the existing
  0.0.2 releases:

  ```bash
  gh workflow run Bundle --ref main -f version=0.0.2
  ```

  Until it runs, those links 404. Then check that the `bundle/v0.0.2` Release has the three zips
  and `SHA256SUMS`, and delete this item.
- Group id and packages are `io.github.noamcohen48.tap` (decided in the 2026-09-26 review, X-4);
  changing it after a release breaks every consumer.
- License: Apache-2.0 (owner's call, 2026-09-29: anyone may use, fork and contribute). `LICENSE`
  at the root, copied into `clients/python` and `clients/agent` so the wheels carry it; the POMs
  name it. Releases before that commit (0.0.1) carry no license file.
- A published version is never replaced (GitHub Packages refuses a republish, PyPI never
  allows one, and a replaced GitHub Release asset breaks caches and `SHA256SUMS`): a fix is
  a new patch release. Test the pipeline with a pre-release (`X.Y.Z-rc.N`) when in doubt.
- Downgrading the engine on a device that has a newer driver is handled: the driver is
  uninstalled and reinstalled on `INSTALL_FAILED_VERSION_DOWNGRADE` (as on a signature
  mismatch).
- Maven Central / PyPI instead of GitHub Packages / Release assets once the repository is
  public: Central needs a verified namespace and signing; PyPI needs the trusted-publisher
  registration mentioned above. The workflows are structured so only the publish steps change.
- Windows native binary: not built; the JVM dist works there.
- Version skew check at run time (client refuses a server whose engine is older than the
  `tap-api` it was built with): `Info()` has the data; the check is not implemented.
