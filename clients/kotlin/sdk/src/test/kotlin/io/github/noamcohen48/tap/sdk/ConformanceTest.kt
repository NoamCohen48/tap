package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.Failure
import io.github.noamcohen48.tap.api.v1.FailureReason
import io.grpc.Metadata
import io.grpc.Status
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The shared client conformance table (`contracts/conformance/client-conformance.json`): the
 * Python unit suite loads the same file, so both clients keep the same defaults and map daemon
 * failures to the same exception kinds.
 */
class ConformanceTest {
    private val table: JsonObject =
        Json.parseToJsonElement(File(checkNotNull(System.getProperty("tap.conformance"))).readText()).jsonObject

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.content

    @Test
    fun `defaults match the table`() {
        val defaults = table.getValue("defaults").jsonObject
        val timeouts = Timeouts()
        assertEquals(defaults.long("action_timeout_ms"), timeouts.action.inWholeMilliseconds)
        assertEquals(defaults.long("wait_timeout_ms"), timeouts.wait.inWholeMilliseconds)
        assertEquals(defaults.long("lifecycle_timeout_ms"), timeouts.lifecycle.inWholeMilliseconds)
        assertEquals(defaults.long("idle_stable_ms"), Timeouts.IDLE_STABLE_FOR.inWholeMilliseconds)
        assertEquals(defaults.long("acquire_timeout_ms"), Timeouts.ACQUIRE.inWholeMilliseconds)
    }

    @Test
    fun `failures map by reason, never by message`() {
        for (element in table.getValue("failures").jsonArray) {
            val case = element.jsonObject
            val status = Status.fromCode(Status.Code.valueOf(case.string("status")!!)).withDescription(case.string("details"))
            val trailers = Metadata()
            val reason = case.string("reason")?.let(FailureReason::valueOf) ?: FailureReason.FAILURE_REASON_UNSPECIFIED
            if (case.string("reason") != null) {
                val failure =
                    Failure
                        .newBuilder()
                        .setReason(reason)
                        .setSerial(case.string("serial").orEmpty())
                        .setWaitedMs(case["waited_ms"]?.jsonPrimitive?.long ?: 0)
                        .build()
                trailers.put(FAILURE_TRAILER, failure)
            }
            val error = mapStatus(status, "fallback", status.asRuntimeException(trailers))
            val label = case.toString()
            when (case.string("raises")) {
                "wait_timeout" -> {
                    val timeout = assertIs<WaitTimeoutException>(error, label)
                    assertEquals(case.string("serial"), timeout.serial, label)
                    assertEquals(case.long("waited_ms"), timeout.elapsedMs, label)
                }
                "device_busy" -> assertIs<DeviceBusyException>(error, label)
                "device_quarantined" -> assertEquals(case.string("serial"), assertIs<DeviceQuarantinedException>(error, label).serial, label)
                "app_lifecycle" -> assertIs<AppLifecycleException>(error, label)
                "server" -> {
                    val server = assertIs<ServerException>(error, label)
                    assertEquals(case.string("status"), server.status, label)
                    assertEquals(reason.toModel(), server.reason, label)
                }
                else -> error("unknown raises in $label")
            }
        }
    }
}
