package io.github.noamcohen48.tap.protocol

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import io.github.noamcohen48.tap.api.v1.Bounds
import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.DeviceInfo
import io.github.noamcohen48.tap.api.v1.Direction
import io.github.noamcohen48.tap.api.v1.ElementSnapshot
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.api.v1.NodeFlag
import io.github.noamcohen48.tap.api.v1.StabilitySignal
import io.github.noamcohen48.tap.wire.v1.ArtifactInfo
import io.github.noamcohen48.tap.wire.v1.Authentication
import io.github.noamcohen48.tap.wire.v1.AuthenticationResult
import io.github.noamcohen48.tap.wire.v1.BlobEnd
import io.github.noamcohen48.tap.wire.v1.BlobStart
import io.github.noamcohen48.tap.wire.v1.Challenge
import io.github.noamcohen48.tap.wire.v1.Hello
import io.github.noamcohen48.tap.wire.v1.Negotiation
import io.github.noamcohen48.tap.wire.v1.Request
import io.github.noamcohen48.tap.wire.v1.Response
import io.github.noamcohen48.tap.wire.v1.SyncState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Golden wire fixtures under `src/test/resources/golden`: the exact protobuf bytes of
 * representative handshake, request, response and blob payloads, as lowercase hex. A fixture
 * whose bytes change, or that stops decoding to the same message, means the TAP1 payload
 * encoding changed; regenerate with `-Dtap.golden.update=true` only alongside the matching
 * `.docs/protocol-contract.md` edit.
 */
class GoldenWireTest {
    private val update = System.getProperty("tap.golden.update") == "true"
    private val goldenDir = Path.of(System.getProperty("tap.golden.dir") ?: "src/test/resources/golden")

    private val nonceA = ByteString.copyFrom(ByteArray(NONCE_BYTES) { it.toByte() })
    private val nonceB = ByteString.copyFrom(ByteArray(NONCE_BYTES) { (0xFF - it).toByte() })
    private val system = "com.google.android.permissioncontroller"

    private val negotiation =
        Negotiation.newBuilder()
            .addAllEnabledCapabilities(listOf("artifact.screenshot.v1", "synchronization.v1"))
            .setSelectedVersion(protocolVersion(3, 0))
            .build()

    private fun envelope(request: Request): Request = request.withEnvelope("7f7c6f5e-session", 3, 5_000)

    private val fixtures: Map<String, Pair<MessageLite, (ByteArray) -> MessageLite>> =
        linkedMapOf(
            "hello" to (
                Hello.newBuilder()
                    .setHostBuildId("1.0.0")
                    .setHostNonce(nonceA)
                    .setSessionGeneration(3)
                    .setSessionId("7f7c6f5e-session")
                    .addSupportedVersions(protocolVersion(3, 0))
                    .build() to Hello::parseFrom
            ),
            "challenge" to (
                Challenge.newBuilder()
                    .setAndroidApiLevel(34)
                    .addAllCapabilities(listOf("artifact.screenshot.v1", "synchronization.v1"))
                    .setDriverApkBuildId("1.0.0")
                    .setDriverInstanceId("driver-instance")
                    .setHostNonce(nonceA)
                    .setDriverNonce(nonceB)
                    .setDriverTestApkBuildId("1.0.0")
                    .setSessionGeneration(3)
                    .setSessionId("7f7c6f5e-session")
                    .addAllSupportedOperations(listOf("exists", "health", "tap"))
                    .addSupportedVersions(protocolVersion(3, 0))
                    .setUiAutomatorBuildId("2.4.0")
                    .build() to Challenge::parseFrom
            ),
            "negotiation" to (negotiation to Negotiation::parseFrom),
            "authentication" to (
                Authentication.newBuilder()
                    .setNegotiation(negotiation.toByteString())
                    .setTranscriptHmac(nonceB)
                    .build() to Authentication::parseFrom
            ),
            "authentication-result-ok" to (
                AuthenticationResult.newBuilder()
                    .setOk(true)
                    .addAllEnabledCapabilities(negotiation.enabledCapabilitiesList)
                    .setSelectedVersion(negotiation.selectedVersion)
                    .setTranscriptHmac(nonceA)
                    .build() to AuthenticationResult::parseFrom
            ),
            "authentication-result-rejected" to (
                AuthenticationResult.newBuilder().setOk(false).setError(ErrorCode.ERR_UNAUTHENTICATED.label).build() to
                    AuthenticationResult::parseFrom
            ),
            "request-health" to (envelope(Requests.health()) to Request::parseFrom),
            "request-screenshot" to (envelope(Requests.screenshot()) to Request::parseFrom),
            "request-sync-poll" to (
                envelope(Requests.syncPoll(4242, "1234567", "5b4c2f4e-0a8d-4d2a-9d63-7a0c9f5f6f01", "session-identity")) to
                    Request::parseFrom
            ),
            "request-tap-aut-resource" to (envelope(Requests.of(Commands.tap(Selectors.of(Nodes.autResource("login"))))) to Request::parseFrom),
            "request-press-key" to (envelope(Requests.of(Commands.pressKey(KEYCODE_BACK))) to Request::parseFrom),
            "request-set-text" to (
                envelope(Requests.of(Commands.setText(Selectors.androidResource(AUT, "email"), "user@example.com"))) to Request::parseFrom
            ),
            "request-wait-screen-stable" to (
                envelope(Requests.of(Commands.waitScreenStable(AUT, stableForMs = 750, signal = StabilitySignal.STABILITY_PIXELS))) to
                    Request::parseFrom
            ),
            "request-swipe-default-distance" to (
                envelope(Requests.of(Commands.swipe(Selectors.rawResource("pager"), Direction.DIR_LEFT))) to Request::parseFrom
            ),
            "request-scroll-until" to (
                envelope(
                    Requests.of(
                        Commands.scrollUntil(
                            Selectors.text("Row 40"),
                            Selectors.androidResource(AUT, "list"),
                            direction = Direction.DIR_UP,
                            distancePercent = 50,
                            maxScrolls = 10,
                        ).toBuilder().setTimeoutMs(9_000).build(),
                    ),
                ) to Request::parseFrom
            ),
            "request-selector-relational" to (
                envelope(
                    Requests.of(
                        Commands.tap(
                            Selectors.of(
                                Nodes.className("android.widget.Button") and
                                    Nodes.flag(NodeFlag.FLAG_CLICKABLE) and
                                    Nodes.ancestor(Nodes.androidResource(AUT, "row")) and
                                    Nodes.child(Nodes.text("Row \\d+", MatchMode.MATCH_REGEX)),
                            ).pickAt(1),
                        ),
                    ),
                ) to Request::parseFrom
            ),
            "request-selector-any-of-system-first" to (
                envelope(
                    Requests.of(
                        Commands.exists(
                            Selectors.of(
                                (Nodes.text("Allow") or Nodes.text("While using the app", MatchMode.MATCH_CONTAINS)) and
                                    Nodes.flag(NodeFlag.FLAG_ENABLED),
                            ).inSystemPackage(system).pickFirst(),
                        ),
                    ),
                ) to Request::parseFrom
            ),
            "response-done" to (Responses.done().stamped(31, 12, 3) to Response::parseFrom),
            "response-bool" to (result { setBool(true) } to Response::parseFrom),
            "response-moved-false" to (result { setMoved(false) } to Response::parseFrom),
            "response-count" to (result { setCount(MAX_MATCH_COUNT) } to Response::parseFrom),
            "response-text" to (result { setText("<hierarchy rotation=\"0\"/>") } to Response::parseFrom),
            "response-snapshot" to (
                result {
                    setSnapshot(
                        ElementSnapshot.newBuilder()
                            .setClassName("android.widget.Button")
                            .setPackageName(AUT)
                            .setResourceName("$AUT:id/login")
                            .setText("Sign in")
                            .setBounds(Bounds.newBuilder().setLeft(10).setTop(20).setRight(300).setBottom(120))
                            .setClickable(true)
                            .setEnabled(true)
                            .setFocusable(true),
                    )
                } to Response::parseFrom
            ),
            "response-device-info" to (
                result {
                    setDeviceInfo(
                        DeviceInfo.newBuilder()
                            .setApiLevel(34)
                            .setManufacturer("Google")
                            .setModel("sdk_gphone64_x86_64")
                            .setProduct("sdk_gphone64_x86_64")
                            .setDisplayWidth(1080)
                            .setDisplayHeight(2400)
                            .setCurrentPackage(AUT),
                    )
                } to Response::parseFrom
            ),
            "response-error-ambiguous" to (Responses.failure(ErrorCode.ERR_AMBIGUOUS).stamped(4, 12, 3) to Response::parseFrom),
            "response-error-indeterminate" to (
                Responses.failure(
                    ErrorCode.ERR_INDETERMINATE,
                    detail = "WAIT_TIMEOUT", // the original code, as the pipeline rewrites it after the gate
                    message = "WAIT_TIMEOUT after the mutation started",
                ).stamped(5_000, 12, 3) to Response::parseFrom
            ),
            "response-artifact" to (
                Response.newBuilder(Responses.done().stamped(250, 12, 3))
                    .setArtifact(
                        ArtifactInfo.newBuilder()
                            .setBlobId("00112233-4455-6677-8899-aabbccddeeff")
                            .setMediaType("image/png")
                            .setByteCount(123_456)
                            .setSha256("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
                            .setWidth(1080)
                            .setHeight(2400),
                    ).build() to Response::parseFrom
            ),
            "response-sync" to (
                Response.newBuilder(Responses.done().stamped(8, 12, 3))
                    .setSync(
                        SyncState.newBuilder()
                            .setInitialized(true)
                            .setProcessId(4242)
                            .setProcessStartUuid("5b4c2f4e-0a8d-4d2a-9d63-7a0c9f5f6f01")
                            .setSessionIdentity("session-identity")
                            .setGeneration(2)
                            .setBusyCount(1)
                            .setLastTransitionElapsedMs(987_654),
                    ).build() to Response::parseFrom
            ),
            "blob-start" to (
                BlobStart.newBuilder()
                    .setBlobId("00112233-4455-6677-8899-aabbccddeeff")
                    .setMediaType("image/png")
                    .setTotalLength(123_456)
                    .setSha256("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
                    .build() to BlobStart::parseFrom
            ),
            "blob-end" to (
                BlobEnd.newBuilder()
                    .setBlobId("00112233-4455-6677-8899-aabbccddeeff")
                    .setByteCount(123_456)
                    .setSha256("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
                    .build() to BlobEnd::parseFrom
            ),
        )

    @Test
    fun payloadsEncodeToTheirGoldenBytes() {
        if (update) Files.createDirectories(goldenDir)
        val mismatches =
            fixtures.mapNotNull { (name, fixture) ->
                val (message, parse) = fixture
                val file = goldenDir.resolve("$name.hex")
                val actual = message.toByteArray().toHex()
                if (update) {
                    Files.writeString(file, actual + "\n")
                    return@mapNotNull null
                }
                if (!Files.exists(file)) return@mapNotNull "$name: missing fixture $file"
                val golden = Files.readString(file).trim()
                when {
                    golden != actual -> "$name: encoding changed\n  golden $golden\n  actual $actual"
                    parse(golden.fromHex()) != message -> "$name: golden bytes decode to a different message"
                    else -> null
                }
            }
        if (mismatches.isNotEmpty()) fail(mismatches.joinToString("\n"))
    }

    @Test
    fun everyFixtureFileHasAMessage() {
        if (update || !Files.isDirectory(goldenDir)) return
        val files = Files.list(goldenDir).use { stream -> stream.map { it.fileName.toString().removeSuffix(".hex") }.toList().toSet() }
        assertEquals(fixtures.keys, files, "stale or missing golden fixtures")
    }

    @Test
    fun decodingKeepsFieldsThisBuildDoesNotKnow() {
        // A newer peer's extra field (number 99, varint 1) survives a parse/re-encode round trip.
        val base = Responses.done().stamped(1, 2, 3).toByteArray()
        val extended = base + byteArrayOf(0x98.toByte(), 0x06, 0x01)
        val parsed = Response.parseFrom(extended)
        assertTrue(parsed.ok)
        assertEquals(extended.toHex(), parsed.toByteArray().toHex())
    }

    private fun result(outcome: CommandResult.Builder.() -> CommandResult.Builder): Response =
        Response.newBuilder().setResult(CommandResult.newBuilder().outcome()).build().stamped(17, 12, 3)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
