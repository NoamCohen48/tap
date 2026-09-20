# Command-line parsing for `tap` — library options and the decision

Date: 2026-09-20. Research note for `host/service/src/main/kotlin/com/company/tap/service/ServiceMain.kt`,
which parses the `tap` command line by hand.

## What the CLI is today

```
tap serve   [--port N] [--state-dir DIR] [--adb PATH] [--serials a,b]
tap status  [--state-dir DIR]
tap stop    [--state-dir DIR]
```

Three verbs, four `--key value` flags, env fallbacks (`TAP_ADB`, `TAP_STATE_DIR`) read with
`System.getenv` at the use site. `parseOptions` is ~15 lines: every token must start with `--`,
the next token is its value unless it also starts with `--`; anything else prints usage and
exits. It has no `--help`, `--port abc` dies with a `NumberFormatException`, and unknown flags
are accepted silently into the map.

The service ships as a GraalVM native image (`:host:service:nativeCompile`) with recorded
reachability metadata under `host/service/src/main/resources/META-INF/native-image/`. Any
library added here must not need reflection config, or must generate its own.

## Candidates

| Library | Shape | Native image | Weight | Notes |
|---|---|---|---|---|
| **Clikt** 5.1.0 (Jan 2025), `com.github.ajalt.clikt` | Kotlin-idiomatic: one `CliktCommand` class per verb, options as property delegates (`val port by option().int().default(0)`), `subcommands(...)`, `envvar = "TAP_ADB"`, typed converters, generated `--help`. | No reflection. `clikt-core` (since 5.0) has zero dependencies. The full `clikt` artifact pulls Mordant for styled help; Mordant had GraalVM/native crashes fixed in Clikt 4.2.1. | small with `clikt-core` | Multiplatform, actively maintained. Preferred. |
| **picocli** (`info.picocli`) | Java, annotation-driven (`@Command`, `@Option`, `@Parameters`), subcommands, completion scripts, ANSI help. | First-class, but reflection-based: its annotation processor emits the GraalVM reflection/resource/proxy config into the jar; that config must be regenerated whenever options change. | single jar, no deps | The most battle-tested choice for native CLIs on the JVM; Quarkus's default. |
| **kotlinx-cli** (JetBrains) | Property delegates like Clikt, fewer features. | fine | small | Effectively unmaintained; Clikt's own comparison of it is fair. Not recommended. |

## Decision

**Not now.** The current surface is small enough that the hand parser is correct and cheaper
than a dependency. A library earns its place the moment the CLI grows a second tier —
`tap devices`, `tap doctor`, `tap logs <session>`, or an agent-facing surface in the shape of
callstack/agent-device (see `upstream-reference-audit.md`) — because that is when per-command
help, typed options and consistent error messages stop being nice-to-have.

When that happens, adopt **Clikt with `clikt-core`**, in the same change that adds the first
new command:

- one `CliktCommand` per verb under `com.company.tap.service.cli`, `main` = `Tap().subcommands(Serve(), Status(), Stop(), ...).main(args)`;
- `--adb` / `--state-dir` declared with `envvar` (`TAP_ADB`, `TAP_STATE_DIR`) instead of
  `System.getenv` at the use site; `--port` as `int()`, `--serials` as `split(",")`;
- keep the existing flag names and defaults so `README.md`, `docs/guide/configuration.md`,
  the CI workflow and `ServiceDiscovery.start` (which spawns `tap serve --state-dir DIR`) are
  unchanged;
- run the native-image smoke (`tap serve` / `status` / `stop` on the native binary) to confirm
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
