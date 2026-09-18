package com.company.tap.protocol

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Golden wire fixtures under `src/test/resources/golden`: one request per [Operation] and one
 * response per [ErrorCode] (plus the success shapes). A fixture that stops decoding, or whose
 * canonical re-encoding changes, means the wire contract changed; regenerate with
 * `-Dtap.golden.update=true` only alongside the matching `.docs/protocol-contract.md` edit.
 */
class GoldenMessageTest {
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val update = System.getProperty("tap.golden.update") == "true"
    private val goldenDir = Path.of(System.getProperty("tap.golden.dir") ?: "src/test/resources/golden")

    private val requests: Map<String, Request> = mapOf(
        "request-health" to request(Operation.HEALTH, timeoutMs = 5_000),
        "request-exists" to request(Operation.EXISTS, selector = Selector.text("Sign in")),
        "request-tap" to request(Operation.TAP, selector = Selector.androidResource(AUT, "login")),
        "request-long-tap" to request(Operation.LONG_TAP, selector = Selector.contentDescription("More")),
        "request-wait-visible" to request(
            Operation.WAIT_VISIBLE,
            selector = Selector.text("Welcome", MatchMode.STARTS_WITH),
            timeoutMs = 15_000,
        ),
        "request-dump-hierarchy" to request(Operation.DUMP_HIERARCHY, timeoutMs = 15_000),
        "request-set-text" to request(
            Operation.SET_TEXT,
            selector = Selector.androidResource(AUT, "email"),
            inputText = "user@example.com",
        ),
        "request-type-text" to request(
            Operation.TYPE_TEXT,
            selector = Selector.androidResource(AUT, "email"),
            inputText = "typed",
        ),
        "request-clear-text" to request(Operation.CLEAR_TEXT, selector = Selector.androidResource(AUT, "email")),
        "request-swipe" to request(
            Operation.SWIPE,
            selector = Selector.androidResource(AUT, "pager"),
            direction = Direction.LEFT,
            distancePercent = 60,
        ),
        "request-scroll" to request(
            Operation.SCROLL,
            selector = Selector.androidResource(AUT, "list"),
            direction = Direction.DOWN,
        ),
        "request-scroll-until" to request(
            Operation.SCROLL_UNTIL,
            selector = Selector.text("Row 40"),
            containerSelector = Selector.androidResource(AUT, "list"),
            direction = Direction.DOWN,
            maxScrolls = 10,
        ),
        "request-screenshot" to request(Operation.SCREENSHOT, timeoutMs = 30_000),
        "request-sync-bootstrap" to request(Operation.SYNC_BOOTSTRAP, observedPid = 4242, observedStartToken = "1234567"),
        "request-sync-state" to request(
            Operation.SYNC_STATE,
            observedPid = 4242,
            observedStartToken = "1234567",
            expectedProcessStartUuid = "5b4c2f4e-0a8d-4d2a-9d63-7a0c9f5f6f01",
            expectedSessionIdentity = "3e0f7d3d-6c1e-4b1f-9a1c-2d3e4f5a6b7c",
        ),
        "request-selector-first-in-system" to request(
            Operation.TAP,
            selector = Selector.text("Allow", MatchMode.CONTAINS)
                .first()
                .inSystemPackage("com.google.android.permissioncontroller"),
        ),
        "request-selector-relational" to request(
            Operation.TAP,
            selector = Selector(
                NodeSelector(
                    className = StringMatch("android.widget.Button"),
                    clickable = true,
                    ancestor = NodeSelector(resource = ResourceId("row", AUT)),
                    child = NodeSelector(text = StringMatch("Row \\d+", MatchMode.REGEX)),
                ),
            ).at(1),
        ),
    )

    private val responses: Map<String, Response> = buildMap {
        put("response-ok", Response(ok = true, durationMs = 12))
        put("response-ok-value", Response(ok = true, value = true, durationMs = 12))
        put("response-ok-text", Response(ok = true, text = "<hierarchy/>", durationMs = 120))
        put(
            "response-ok-sync-state",
            Response(
                ok = true,
                durationMs = 8,
                syncState = SyncState(
                    initialized = true,
                    processId = 4242,
                    processStartUuid = "5b4c2f4e-0a8d-4d2a-9d63-7a0c9f5f6f01",
                    sessionIdentity = "3e0f7d3d-6c1e-4b1f-9a1c-2d3e4f5a6b7c",
                    generation = 6,
                    busyCount = 0,
                    lastTransitionElapsedMs = 987_654,
                ),
            ),
        )
        put(
            "response-ok-artifact",
            Response(
                ok = true,
                durationMs = 210,
                artifact = ArtifactInfo(
                    blobId = "0f6c9b3a-4d9e-4c1a-8f4c-2a9d8e7f6b5c",
                    mediaType = "image/png",
                    byteCount = 125_454,
                    sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    width = 1080,
                    height = 2400,
                ),
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
    fun everyOperationHasARequestFixture() {
        val covered = requests.values.map { it.operation }.toSet()
        assertEquals(Operation.entries.toSet(), covered)
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

    private fun request(
        operation: Operation,
        timeoutMs: Long = 10_000,
        selector: Selector? = null,
        containerSelector: Selector? = null,
        inputText: String? = null,
        direction: Direction? = null,
        distancePercent: Int = DEFAULT_GESTURE_PERCENT,
        maxScrolls: Int = 20,
        observedPid: Int? = null,
        observedStartToken: String? = null,
        expectedProcessStartUuid: String? = null,
        expectedSessionIdentity: String? = null,
    ) = Request(
        sessionId = SESSION,
        sessionGeneration = 3,
        operation = operation,
        timeoutMs = timeoutMs,
        selector = selector,
        containerSelector = containerSelector,
        inputText = inputText,
        direction = direction,
        distancePercent = distancePercent,
        maxScrolls = maxScrolls,
        observedPid = observedPid,
        observedStartToken = observedStartToken,
        expectedProcessStartUuid = expectedProcessStartUuid,
        expectedSessionIdentity = expectedSessionIdentity,
    )

    private companion object {
        const val AUT = "com.example.app"
        const val SESSION = "0d1c2b3a-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

        /** A representative [ErrorDetail] for codes that carry one. */
        val exampleDetail: Map<ErrorCode, String> = mapOf(
            ErrorCode.INVALID_SELECTOR to ErrorDetail.INVALID_REGEX,
            ErrorCode.INVALID_REQUEST to ErrorDetail.UNSUPPORTED_CHARACTERS,
            ErrorCode.NOT_FOUND to ErrorDetail.END_REACHED,
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
