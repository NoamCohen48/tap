# Command-line parsing for `tap` — library options and the decision

Date: 2026-09-20. Research note for `host/daemon/src/main/kotlin/io/github/noamcohen48/tap/daemon/TapDaemonMain.kt`,
which parses the `tap` command line by hand.

## What the CLI is today

```
tap start   [--port N] [--state-dir DIR] [--adb PATH]
tap serve   [--port N] [--state-dir DIR] [--adb PATH]
tap status  [--state-dir DIR]
tap stop    [--state-dir DIR]
```

Three verbs, four `--key value` flags, env fallbacks (`TAP_ADB`, `TAP_STATE_DIR`) read with
`System.getenv` at the use site. `parseOptions` is ~15 lines: every token must start with `--`,
the next token is its value unless it also starts with `--`; anything else prints usage and
exits. It has no `--help`, `--port abc` dies with a `NumberFormatException`, and unknown flags
are accepted silently into the map.

The daemon ships as a GraalVM native image (`:host:daemon:nativeCompile`) with recorded
reachability metadata under `host/daemon/src/main/resources/META-INF/native-image/`. Any
library added here must not need reflection config, or must generate its own.

## Candidates

| Library | Shape | Native image | Weight | Notes |
|---|---|---|---|---|
| **Clikt** 5.1.0 (Jan 2025), `com.github.ajalt.clikt` | Kotlin-idiomatic: one `CliktCommand` class per verb, options as property delegates (`val port by option().int().default(0)`), `subcommands(...)`, `envvar = "TAP_ADB"`, typed converters, generated `--help`. | No reflection. `clikt-core` (since 5.0) has zero dependencies. The full `clikt` artifact pulls Mordant for styled help; Mordant had GraalVM/native crashes fixed in Clikt 4.2.1. | small with `clikt-core` | Multiplatform, actively maintained. Preferred. |
| **picocli** (`info.picocli`) | Java, annotation-driven (`@Command`, `@Option`, `@Parameters`), subcommands, completion scripts, ANSI help. | First-class, but reflection-based: its annotation processor emits the GraalVM reflection/resource/proxy config into the jar; that config must be regenerated whenever options change. | single jar, no deps | The most battle-tested choice for native CLIs on the JVM; Quarkus's default. |
| **kotlinx-cli** (JetBrains) | Property delegates like Clikt, fewer features. | fine | small | Effectively unmaintained; Clikt's own comparison of it is fair. Not recommended. |

## Current decision — daemon administration CLI

The operator explicitly approved expanding daemon administration while keeping `tap` and
`tap-agent` independent, and removed the requirement to adopt Clikt. The CLI therefore keeps
an explicit parser with a single metadata table (`CliCommands.kt`) driving command paths,
option arity/validation, per-command help and shell completions. There is no new parser
library or reflection dependency. The earlier mandatory Clikt trigger below is superseded;
its comparison remains historical research, not an implementation requirement.

The supported shape is `tap [--state-dir DIR] <command> [subcommand] [arguments] [options]`.
Help is successful, side-effect-free stdout output at root and every command; usage failures
are stderr/2. Existing startup flags, environment fallbacks, paired APK validation and
`start` → `serve` forwarding remain intact. Both `--key value` and `--key=value` work.
Inspection uses authenticated daemon RPCs directly, never a Python executable, and never
opens or renews a client connection. Interaction remains in separate SDK/agent/Studio clients.

The expanded commands cover device/session list and details, forced session close with an
audit reason and cleanup results, guarded restart, status/watch, setup doctor, log tail/follow,
configuration provenance, restricted diagnostic archives and static bash/zsh/fish completion.
Restart's active-session guard is explicitly a preflight, not exclusive admission control.
See the public [server guide](../docs/guide/server.md) for behavior and examples.

Verification is in `CliDiscoveryTest`, `AdministrationTest`, the connection/core lifecycle
suites and `.github/scripts/daemon_cli_smoke.py`. The latter drives either executable build
with fake ADB and real RPC-created sessions; it touches no Android devices. Native CI and
release smoke run it after installing the Python SDK as the *test fixture*, not a CLI dependency.

Final verification after rebasing onto `31010856`: **666 JVM tests** (host core/daemon,
protocol, command engine and Kotlin SDK), **566 Python client/agent unit tests**, stub freshness,
buf lint/breaking, full HTML/Markdown documentation builds, and JVM/native CLI executable smoke
all passed. The metadata commit was also tested independently with daemon/protocol suites.
Native build/smoke used **GraalVM 25**. CE 21.0.2 hits the already-recorded compiler error in
unchanged `AppDeaths.follow` (see `fast-attach.md`); it is not a successful CE 21.0.2 validation
or a toolchain configuration change. No device matrix was requested or run.

## Original decision (superseded)

**Not now.** The current surface is small enough that the hand parser is correct and cheaper
than a dependency. A library earns its place the moment the CLI grows a second tier —
`tap devices`, `tap doctor`, `tap logs <session>`, or an agent-facing surface in the shape of
callstack/agent-device (see `upstream-reference-audit.md`) — because that is when per-command
help, typed options and consistent error messages stop being nice-to-have.

When that happens, adopt **Clikt with `clikt-core`**, in the same change that adds the first
new command:

- one `CliktCommand` per verb under `io.github.noamcohen48.tap.daemon.cli`, `main` = `Tap().subcommands(Start(), Serve(), Status(), Stop(), ...).main(args)`;
- `--adb` / `--state-dir` declared with `envvar` (`TAP_ADB`, `TAP_STATE_DIR`) instead of
  `System.getenv` at the use site; `--port` as `int()`;
- keep the existing flag names and defaults so `README.md`, `docs/guide/configuration.md`,
  the CI workflow and `TapDaemonProcess` / `start_daemon` (which run `tap start --state-dir DIR`)
  are unchanged; `start` re-executes the binary as `serve --port N …`, so `serve` must keep
  accepting exactly the options `start` forwards;
- run the native-image smoke (`tap start` / `status` / `stop` on the native binary) to confirm
  the recorded reachability metadata needs no additions — `clikt-core` uses no reflection, so
  none are expected; if the Mordant-backed artifact is chosen instead, re-record with the
  tracing agent per `CLAUDE.md`.

picocli remains the fallback if a Java-ecosystem standard is preferred over a Kotlin one; the
cost is regenerating its reflection config with every option change.

## Sources

- Clikt: https://ajalt.github.io/clikt/ — releases https://github.com/ajalt/clikt/releases —
  "Why not kotlinx.cli" https://ajalt.github.io/clikt/whyclikt/
- picocli: https://picocli.info/ — https://github.com/remkop/picocli
- Native CLI with picocli and GraalVM: https://dev.to/jbebar/native-cli-with-picocli-and-graalvm-566m
- Kotlin + Quarkus + GraalVM CLI (2025): https://maarten.mulders.it/2025/07/building-a-cli-with-quarkus-kotlin-and-graalvm/
