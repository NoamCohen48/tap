# Module tap-junit5

The JUnit 5 extension for Tap. Annotate a test class with
[TapTest][io.github.noamcohen48.tap.junit5.TapTest], take a `Device` (or, with
[TapDevices][io.github.noamcohen48.tap.junit5.TapDevices], `Devices`) as a test parameter, and write the
body inside [tapTest][io.github.noamcohen48.tap.junit5.tapTest]. The extension attaches the devices
before each test, detaches them afterwards and saves failure artifacts.

Configuration: <https://noamcohen48.github.io/tap/guide/configuration/>

# Package io.github.noamcohen48.tap.junit5

`@TapTest`, `@TapDevice`, `@TapDevices`, `Devices`, `tapTest` and the configuration it reads.
