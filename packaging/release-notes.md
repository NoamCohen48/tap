# Tap @VERSION@

Tap @VERSION@ uses one version for the server, bundled Android driver, Kotlin and Python clients, Tap agent, Tap Studio, Tap watcher and the experimental sync SDK.

## Download

Choose one complete bundle, unpack it, and run `./install.sh` or follow `INSTALL.md` inside:

- `tap-@VERSION@-linux-x86_64.zip`: Linux x86-64, native server
- `tap-@VERSION@-macos-aarch64.zip`: macOS Apple silicon, native server
- `tap-@VERSION@-jvm.zip`: any OS with Java 17+, including Windows

Each bundle includes the Kotlin artifacts as a local Maven repository (no GitHub token), all four Python wheels, the documentation and checksums. Python dependencies still come from PyPI; this is not an offline installer. Screen video needs a separately supplied scrcpy 4.1 server JAR.

For individual components, this same release carries the native server binaries, `tap-@VERSION@-server-jvm.zip`, Python wheels and source distributions, and `tap-@VERSION@-maven.zip`. `SHA256SUMS` covers every downloadable asset. Maven coordinates use `io.github.noamcohen48.tap` and the shared version; they are also published to this repository's GitHub Packages registry.

Tap is alpha software. New features and breaking changes increment the minor version during 0.x; fixes increment the patch. Experimental features may change in any release. Runtime protocol compatibility checks remain separate from the release version.

[Documentation](https://noamcohen48.github.io/tap/) · [Changelog](https://github.com/NoamCohen48/tap/blob/v@VERSION@/CHANGELOG.md)
