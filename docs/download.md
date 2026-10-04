# Download

Everything Tap ships, in one zip for your system: the `tap` server, the Kotlin client and JUnit
5 extension, the Python client and pytest plugin, `tap-agent`, Tap Studio, `tap-watcher`, this
documentation and an install script.

**Tap 0.0.2** (alpha):

[Linux x86-64](https://github.com/NoamCohen48/tap/releases/download/bundle/v0.0.2/tap-0.0.2-linux-x86_64.zip){ .md-button .md-button--primary }
[macOS Apple silicon](https://github.com/NoamCohen48/tap/releases/download/bundle/v0.0.2/tap-0.0.2-macos-aarch64.zip){ .md-button .md-button--primary }
[Any OS with Java 17](https://github.com/NoamCohen48/tap/releases/download/bundle/v0.0.2/tap-0.0.2-jvm.zip){ .md-button }

| Your system | Download | Server inside |
|---|---|---|
| Linux on x86-64 | `tap-0.0.2-linux-x86_64.zip` | native executable |
| macOS on Apple silicon | `tap-0.0.2-macos-aarch64.zip` | native executable |
| Windows, Linux on ARM, Intel Macs: anything with JDK 17+ | `tap-0.0.2-jvm.zip` | JVM distribution |

Checksums of the three zips are in `SHA256SUMS` on the
[release page](https://github.com/NoamCohen48/tap/releases/tag/bundle/v0.0.2).

## Install

On Linux and macOS:

```bash
unzip tap-0.0.2-linux-x86_64.zip
cd tap-0.0.2-linux-x86_64
./install.sh
```

The script:

1. checks every file in the bundle against its `SHA256SUMS`;
2. puts `tap` in `~/.local/bin` (with the jvm bundle, it unpacks the server to
   `~/.local/share/tap/server` and links it there; it needs Java 17+);
3. installs `tap-e2e`, `tap-agent`, `tap-studio` and `tap-watcher` into their own virtual
   environment (`~/.local/share/tap/venv`) and links `tap-agent`, `tap-studio` and
   `tap-watcher` into `~/.local/bin`
   (it needs Python 3.10+; the packages' dependencies come from PyPI);
4. copies the Kotlin artifacts (a local Maven repository), the wheels and the docs to
   `~/.local/share/tap`;
5. prints what to add to your Gradle build and the `pip install` command for your project.

| Option | Effect |
|---|---|
| `--prefix DIR` | install under `DIR` instead of `~/.local` |
| `--venv DIR` | put the Python packages into this virtual environment (for example your test project's) |
| `--no-server`, `--no-python` | skip that part |
| `--uninstall` | remove everything a previous run installed under the prefix |

Running a newer bundle's script upgrades in place. On Windows, unzip the jvm bundle and follow
its `INSTALL.md`, which also describes every step by hand.

## Use it

```bash
tap start          # the server, once per machine; tap stop ends it
```

=== "Kotlin"

    The bundle's Maven repository needs no GitHub token:

    ```kotlin
    repositories {
        maven(uri("${System.getProperty("user.home")}/.local/share/tap/maven"))
        mavenCentral()   // the client's own dependencies (gRPC, coroutines, ...)
    }

    dependencies {
        testImplementation("io.github.noamcohen48.tap:tap-junit5:0.0.2")
        testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    }
    ```

=== "Python"

    In your test project's virtual environment:

    ```bash
    pip install ~/.local/share/tap/python/tap_e2e-0.0.2-py3-none-any.whl
    ```

Then continue with [Getting started](guide/getting-started.md) from step 2 to write and run
your first test.

## What is inside

```text
tap-0.0.2-<platform>/
├── install.sh, INSTALL.md
├── VERSIONS          the version of each part
├── SHA256SUMS        a checksum for every file
├── server/           tap (native) or tap-0.0.2-jvm.zip
├── maven/            tap-junit5, tap-client, tap-api, tap-schema as a Maven repository
├── python/           tap_e2e, tap_agent, tap_studio, tap_watcher wheels
├── docs/             this documentation as Markdown, with the API references
└── LICENSE
```

The files are the ones published in each part's own release, repackaged. The parts are
versioned separately (see [Releases and versions](reference/releases.md)), so one bundle can
hold, for example, server 0.0.2 with Tap Studio 0.0.1; `VERSIONS` lists them.

## One part at a time

Every part also has its own release, if you only need one: the server binaries, the Python
wheels and the Kotlin artifacts (from GitHub Packages, which needs a token) are linked from
[Getting started](guide/getting-started.md) and listed on the
[releases page](https://github.com/NoamCohen48/tap/releases).
