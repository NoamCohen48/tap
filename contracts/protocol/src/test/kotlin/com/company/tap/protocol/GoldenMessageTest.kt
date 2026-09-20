package com.company.tap.protocol

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Golden wire fixtures under `src/test/resources/golden`: one request per [Command], one response
 * per [CommandResult] kind and one per [ErrorCode]. A fixture that stops decoding, or whose
 * canonical re-encoding changes, means the wire contract changed; regenerate with
 * `-Dtap.golden.update=true` only alongside the matching `.docs/protocol-contract.md` edit.
 */
class GoldenMessageTest {
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val update = System.getProperty("tap.golden.update") == "true"
    private val goldenDir = Path.of(System.getProperty("tap.golden.dir") ?: "src/test/resources/golden")

    private val commands: Map<String, Command> = mapOf(
        "health" to Health,
        "device-info" to DeviceInfoQuery,
        "press-key" to PressKey(KEYCODE_BACK),
        "screenshot" to Screenshot,
        "dump-hierarchy" to DumpHierarchy,
        "exists" to Exists(Selector.text("Sign in")),
        "count" to Count(Selector.text("Row", MatchMode.STARTS_WITH)),
        "snapshot" to Snapshot(Selector.androidResource(AUT, "status")),
        "wait-visible" to WaitVisible(Selector.text("Welcome", MatchMode.STARTS_WITH)),
        "wait-gone" to WaitGone(Selector.rawResource("spinner")),
        "wait-app-visible" to WaitAppVisible(AUT),
        "wait-screen-stable" to WaitScreenStable(AUT, stableForMs = 750, signal = StabilitySignal.PIXELS),
        "tap" to Tap(Selector.androidResource(AUT, "login")),
        "long-tap" to LongTap(Selector.contentDescription("More")),
        "set-text" to SetText(Selector.androidResource(AUT, "email"), "user@example.com"),
        "type-text" to TypeText(Selector.androidResource(AUT, "email"), "typed"),
        "clear-text" to ClearText(Selector.androidResource(AUT, "email")),
        "swipe" to Swipe(Selector.androidResource(AUT, "pager"), Direction.LEFT, distancePercent = 60),
        "scroll" to Scroll(Selector.androidResource(AUT, "list"), Direction.DOWN),
        "scroll-until" to ScrollUntil(
            selector = Selector.text("Row 40"),
            container = Selector.androidResource(AUT, "list"),
            maxScrolls = 10,
        ),
        "sync-bootstrap" to SyncBootstrap(observedPid = 4242, observedStartToken = "1234567"),
        "sync-poll" to SyncPoll(
            observedPid = 4242,
            observedStartToken = "1234567",
            expectedProcessStartUuid = "5b4c2f4e-0a8d-4d2a-9d63-7a0c9f5f6f01",
            expectedSessionIdentity = "3e0f7d3d-6c1e-4b1f-9a1c-2d3e4f5a6b7c",
        ),
        "selector-first-in-system" to Tap(
            Selector.text("Allow", MatchMode.CONTAINS)
                .first()
                .inSystemPackage("com.google.android.permissioncontroller"),
        ),
        "selector-relational" to Tap(
            Selector(
                Node.allOf(
                    Node.className("android.widget.Button"),
                    Node.Flag(NodeFlag.CLICKABLE),
                    Node.ancestor(Node.Resource("row", AUT)),
                    Node.child(Node.text("Row \\d+", MatchMode.REGEX)),
                ),
            ).at(1),
        ),
        "selector-any-of" to Exists(
            Selector(
                (Node.text("Allow") or Node.text("Allow only while using the app") or Node.contentDescription("Allow"))
                    and Node.Flag(NodeFlag.ENABLED),
            ).inSystemPackage("com.google.android.permissioncontroller"),
        ),
    )

    private val requests: Map<String, Request> = commands.entries.associate { (name, command) ->
        "request-$name" to Request(sessionId = SESSION, generation = 3, timeoutMs = 10_000, command = command)
    }

    private val responses: Map<String, Response> = buildMap {
        put("response-ok-done", Response.ok(Done, durationMs = 12))
        put("response-ok-bool", Response.ok(BoolResult(true), durationMs = 12))
        put("response-ok-moved", Response.ok(Moved(false), durationMs = 40))
        put("response-ok-text", Response.ok(TextResult("<hierarchy/>"), durationMs = 120))
        put("response-ok-count", Response.ok(CountResult(3), durationMs = 20))
        put(
            "response-ok-snapshot",
            Response.ok(
                SnapshotResult(
                    ElementSnapshot(
                        className = "android.widget.Button",
                        packageName = AUT,
                        resourceName = "$AUT:id/login",
                        text = "Sign in",
                        bounds = Bounds(84, 1200, 996, 1320),
                        checkable = false,
                        checked = false,
                        clickable = true,
                        enabled = true,
                        focusable = true,
                        focused = false,
                        longClickable = false,
                        scrollable = false,
                        selected = false,
                        childCount = 0,
                    ),
                ),
                durationMs = 15,
            ),
        )
        put(
            "response-ok-device-info",
            Response.ok(
                DeviceInfoResult(
                    DeviceInfo(
                        apiLevel = 34,
                        manufacturer = "Google",
                        model = "sdk_gphone64_x86_64",
                        product = "sdk_gphone64_x86_64",
                        displayWidth = 1080,
                        displayHeight = 2400,
                        displayRotation = 0,
                        currentPackage = AUT,
                    ),
                ),
                durationMs = 3,
            ),
        )
        put(
            "response-ok-sync",
            Response.ok(
                SyncResult(
                    SyncState(
                        initialized = true,
                        processId = 4242,
                        processStartUuid = "5b4c2f4e-0a8d-4d2a-9d63-7a0c9f5f6f01",
                        sessionIdentity = "3e0f7d3d-6c1e-4b1f-9a1c-2d3e4f5a6b7c",
                        generation = 6,
                        busyCount = 0,
                        lastTransitionElapsedMs = 987_654,
                    ),
                ),
                durationMs = 8,
            ),
        )
        put(
            "response-ok-artifact",
            Response.ok(
                ArtifactResult(
                    ArtifactInfo(
                        blobId = "0f6c9b3a-4d9e-4c1a-8f4c-2a9d8e7f6b5c",
                        mediaType = "image/png",
                        byteCount = 125_454,
                        sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                        width = 1080,
                        height = 2400,
                    ),
                ),
                durationMs = 210,
            ),
        )
        // UNKNOWN is the host-side decode fallback for codes newer than the host; never on the wire.
        (ErrorCode.entries - ErrorCode.UNKNOWN).forEach { code ->
            put(
                "response-error-${code.name.lowercase().replace('_', '-')}",
                Response.failure(code, durationMs = 5, detail = exampleDetail[code], message = "example"),
            )
        }
    }

    @Test
    fun everyCommandHasARequestFixture() {
        assertEquals(Command.names.toSet(), commands.values.map { it.op }.toSet())
    }

    @Test
    fun everyResultKindHasAResponseFixture() {
        val kinds = CommandResult.serializer().descriptor.getElementDescriptor(1)
        val expected = (0 until kinds.elementsCount).map(kinds::getElementName).toSet()
        val covered = responses.values.mapNotNull { it.result }.map { pretty.encodeToJsonElement(it).jsonObject.getValue("kind").jsonPrimitive.content }.toSet()
        assertEquals(expected, covered)
    }

    @Test
    fun everyErrorCodeHasAResponseFixture() {
        val covered = responses.values.mapNotNull { it.errorCode }.toSet()
        assertEquals(ErrorCode.entries.toSet() - ErrorCode.UNKNOWN, covered)
    }

    @Test
    fun requestFixturesMatchGolden() = requests.forEach { (name, value) -> check(name, value) }

    @Test
    fun responseFixturesMatchGolden() = responses.forEach { (name, value) -> check(name, value) }

    @Test
    fun goldenDirectoryHasNoStrayFiles() {
        val expected = (requests.keys + responses.keys).map { "$it.json" }.toSet()
        val actual = Files.list(goldenDir).use { stream -> stream.map { it.fileName.toString() }.toList() }.toSet()
        if (update) return
        assertEquals(expected, actual, "Golden directory is out of sync with the test's fixture list")
    }

    private inline fun <reified T> check(name: String, value: T) {
        val encoded = pretty.encodeToString(value) + "\n"
        val file = goldenDir.resolve("$name.json")
        if (update) {
            Files.createDirectories(goldenDir)
            Files.writeString(file, encoded)
            return
        }
        assertTrue(Files.exists(file), "Missing golden fixture $file; run with -Dtap.golden.update=true")
        val golden = Files.readString(file)
        // Decoding must reproduce the in-code example and encoding must reproduce the file.
        val decoded = try {
            CanonicalJson.codec.decodeFromString<T>(golden)
        } catch (e: Exception) {
            fail("Golden fixture $file no longer decodes: ${e.message}")
        }
        assertEquals(value, decoded, "Golden fixture $file decodes to a different value")
        assertEquals(golden, encoded, "Golden fixture $file encoding drifted")
    }

    private companion object {
        const val AUT = "com.example.app"
        const val SESSION = "0d1c2b3a-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

        /** A representative [ErrorDetail] for codes that carry one. */
        val exampleDetail: Map<ErrorCode, String> = mapOf(
            ErrorCode.INVALID_SELECTOR to ErrorDetail.INVALID_REGEX,
            ErrorCode.INVALID_REQUEST to ErrorDetail.UNSUPPORTED_CHARACTERS,
            ErrorCode.NOT_FOUND to ErrorDetail.END_REACHED,
            ErrorCode.WAIT_TIMEOUT to ErrorDetail.SCREEN_CHANGING,
            ErrorCode.NOT_INTERACTABLE to ErrorDetail.FOCUS_TIMEOUT,
            ErrorCode.STALE_DURING_COMMAND to ErrorDetail.TARGET_GONE,
            ErrorCode.ACTION_REJECTED to ErrorDetail.TEXT_MISMATCH,
            ErrorCode.DEADLINE_EXCEEDED to ErrorDetail.EXPIRED_IN_QUEUE,
            ErrorCode.CANCELLED to ErrorDetail.CANCELLED_IN_QUEUE,
            ErrorCode.AUT_CRASHED to ErrorDetail.PROCESS_RESTARTED,
            ErrorCode.AUT_MISMATCH to ErrorDetail.PROCESS_MISMATCH,
            ErrorCode.SYNC_PROVIDER_UNAVAILABLE to ErrorDetail.PROVIDER_TIMEOUT,
            ErrorCode.ARTIFACT_TRANSFER_FAILED to ErrorDetail.BLOB_CHECKSUM_MISMATCH,
            ErrorCode.PAYLOAD_TOO_LARGE to ErrorDetail.ARTIFACT_TOO_LARGE,
            ErrorCode.DRIVER_UNHEALTHY to ErrorDetail.HEARTBEAT_EXPIRED,
            ErrorCode.TRANSPORT_LOST to ErrorDetail.TRANSPORT_CLOSED,
        )
    }
}
