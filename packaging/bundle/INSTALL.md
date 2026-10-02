# Installing Tap @BUNDLE@ (@PLATFORM@)

This bundle holds everything Tap ships, so you can install it from one download:

| What | Version | Where in the bundle |
|---|---|---|
| `tap` server (with the on-device driver inside) | @ENGINE@ | `server/` |
| Kotlin client + JUnit 5 extension (`tap-client`, `tap-junit5`, with `tap-api`, `tap-schema`) | @KOTLIN@ | `maven/` (a Maven repository) |
| Python client + pytest plugin (`tap-e2e`) | @PYTHON@ | `python/tap_e2e-@PYTHON@-py3-none-any.whl` |
| Agent CLI + MCP server (`tap-agent`, experimental) | @AGENT@ | `python/tap_agent-@AGENT@-py3-none-any.whl` |
| Tap Studio, inspector + recorder (`tap-studio`, experimental) | @STUDIO@ | `python/tap_studio-@STUDIO@-py3-none-any.whl` |
| Documentation (guide and Kotlin, Python, gRPC references) | | `docs/` |

`VERSIONS` lists the same versions; `SHA256SUMS` has a checksum for every file.

## Requirements

- An Android device or emulator with API 26 or newer, visible to `adb devices` (Android SDK
  platform-tools on `PATH`).
- The `linux-x86_64` bundle: Linux on x86-64. The `macos-aarch64` bundle: macOS on Apple
  silicon. The `jvm` bundle: any system with Java 17 or newer (Windows included).
- Python 3.10 or newer for the Python packages. Their dependencies (grpcio, protobuf, ...)
  are downloaded from PyPI.

## Install with the script (Linux, macOS)

```bash
unzip tap-@BUNDLE@-@PLATFORM@.zip
cd tap-@BUNDLE@-@PLATFORM@
./install.sh
```

It checks the checksums, then installs under `~/.local`:

- `~/.local/bin/tap`: the server;
- `~/.local/bin/tap-agent`, `~/.local/bin/tap-studio`: the Python tools, in their own virtual
  environment at `~/.local/share/tap/venv`;
- `~/.local/share/tap/maven`: the Kotlin artifacts as a local Maven repository;
- `~/.local/share/tap/python`: the wheels, to install `tap-e2e` into your test project;
- `~/.local/share/tap/docs`: the documentation.

Options: `--prefix DIR` installs under another directory, `--venv DIR` puts the Python packages
into a virtual environment of your choice (for example your test project's) instead,
`--no-server` and `--no-python` skip a part, `--uninstall` removes everything again. Running it
again with a newer bundle upgrades in place. Make sure `~/.local/bin` is on your `PATH`.

## Install by hand (any system)

**Server.** Put `server/tap` on your `PATH` (`chmod +x` it; on macOS, run
`xattr -d com.apple.quarantine tap` if the system refuses to open it). With the `jvm` bundle,
unzip `server/tap-@ENGINE@-jvm.zip` and put its `bin/` directory on your `PATH` (it holds
`tap` and `tap.bat`). Check with `tap version`.

**Kotlin.** Copy `maven/` somewhere permanent and point Gradle at it:

```kotlin
repositories {
    maven(uri("/path/to/tap/maven"))
    mavenCentral()   // the clients' own dependencies (gRPC, coroutines, ...)
}
dependencies {
    testImplementation("io.github.noamcohen48.tap:tap-junit5:@KOTLIN@")   // brings tap-client
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
}
```

**Python.** In your test project's virtual environment:

```bash
pip install python/tap_e2e-@PYTHON@-py3-none-any.whl
pip install python/tap_agent-@AGENT@-py3-none-any.whl     # optional: agents
pip install python/tap_studio-@STUDIO@-py3-none-any.whl   # optional: Studio
```

## First run

```bash
tap start        # starts the server; it keeps running until `tap stop`
tap status
```

Then follow `docs/guide/getting-started.md` (also at
<https://noamcohen48.github.io/tap/guide/getting-started/>) from step 2 for your language: the
Gradle repository there is the GitHub one; with this bundle use the local one above, which
needs no token.

Tap is alpha software: any release may change its APIs. Release notes:
<https://github.com/NoamCohen48/tap/releases>.
