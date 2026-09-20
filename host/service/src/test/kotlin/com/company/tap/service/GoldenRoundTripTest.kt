package com.company.tap.service

import com.company.tap.protocol.CanonicalJson
import com.company.tap.protocol.Request
import com.company.tap.protocol.Response
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every golden protocol fixture must survive protocol → proto → protocol unchanged, so a
 * binding that speaks the proto can express exactly what the Kotlin host can.
 */
class GoldenRoundTripTest {
    private val golden: Path = Path.of(System.getProperty("tap.goldenDir"))

    @Test
    fun `golden requests round-trip through Command`() {
        val files = golden.listDirectoryEntries("request-*.json")
        assertTrue(files.isNotEmpty(), "no golden requests under $golden")
        files.forEach { file ->
            val original = CanonicalJson.codec.decodeFromString<Request>(Files.readString(file))
            val proto = Conversions.command(original.command, original.timeoutMs)
            val back = Conversions.command(proto, defaultTimeoutMs = -1)
            assertEquals(original.command, back.command, "request fixture ${file.name} changed through the proto round trip")
            assertEquals(original.timeoutMs, back.timeoutMs)
        }
    }

    @Test
    fun `golden responses round-trip through CommandResult`() {
        val files = golden.listDirectoryEntries("response-*.json")
        assertTrue(files.isNotEmpty(), "no golden responses under $golden")
        files.forEach { file ->
            val original = CanonicalJson.codec.decodeFromString<Response>(Files.readString(file))
            val proto = Conversions.result(original, requestId = 7, generation = 3)
            assertEquals(original, Conversions.response(proto), "response fixture ${file.name} changed through the proto round trip")
        }
    }
}
