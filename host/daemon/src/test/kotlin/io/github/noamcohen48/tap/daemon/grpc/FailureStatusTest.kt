package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.api.v1.Failure
import io.github.noamcohen48.tap.api.v1.FailureReason
import io.github.noamcohen48.tap.daemon.core.DaemonClosingException
import io.github.noamcohen48.tap.daemon.core.NotOwnerException
import io.github.noamcohen48.tap.daemon.core.UnknownAttachedDeviceException
import io.github.noamcohen48.tap.daemon.core.UnknownClientConnectionException
import io.github.noamcohen48.tap.host.AdbCommandException
import io.github.noamcohen48.tap.host.AppLifecycleException
import io.github.noamcohen48.tap.host.DeviceBusyException
import io.github.noamcohen48.tap.host.DeviceQuarantinedException
import io.github.noamcohen48.tap.host.DriverBuildMismatchException
import io.github.noamcohen48.tap.host.DriverStartException
import io.github.noamcohen48.tap.host.HostWaitTimeoutException
import io.github.noamcohen48.tap.host.SessionClosingException
import io.github.noamcohen48.tap.protocol.InvalidCommandException
import io.grpc.Status
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every mapped failure carries a `tap-failure-bin` trailer whose reason clients switch on. */
class FailureStatusTest {
    private fun failureOf(error: Throwable): Pair<Status.Code, Failure> {
        val status = error.toStatus()
        return status.status.code to checkNotNull(status.trailers?.get(FAILURE_TRAILER)) { "no failure trailer for $error" }
    }

    @Test
    fun `known exceptions map to a status code and reason`() {
        val cases =
            listOf(
                UnknownClientConnectionException("c") to (Status.Code.NOT_FOUND to FailureReason.FAILURE_REASON_UNKNOWN_CLIENT_CONNECTION),
                UnknownAttachedDeviceException("d") to (Status.Code.NOT_FOUND to FailureReason.FAILURE_REASON_UNKNOWN_ATTACHED_DEVICE),
                NotOwnerException("d", "c") to (Status.Code.PERMISSION_DENIED to FailureReason.FAILURE_REASON_NOT_OWNER),
                InvalidArgumentException("x") to (Status.Code.INVALID_ARGUMENT to FailureReason.FAILURE_REASON_INVALID_ARGUMENT),
                InvalidCommandException(ErrorCode.ERR_INVALID_SELECTOR, "EMPTY", "x") to
                    (Status.Code.INVALID_ARGUMENT to FailureReason.FAILURE_REASON_INVALID_ARGUMENT),
                DeviceBusyException("s", 0) to (Status.Code.FAILED_PRECONDITION to FailureReason.FAILURE_REASON_DEVICE_BUSY),
                DeviceBusyException("s", 5) to (Status.Code.DEADLINE_EXCEEDED to FailureReason.FAILURE_REASON_DEVICE_BUSY),
                HostWaitTimeoutException("idle", "s", 7, 1, null) to (Status.Code.DEADLINE_EXCEEDED to FailureReason.FAILURE_REASON_HOST_WAIT_TIMEOUT),
                DeviceQuarantinedException("s", "q") to (Status.Code.FAILED_PRECONDITION to FailureReason.FAILURE_REASON_DEVICE_QUARANTINED),
                AppLifecycleException("x") to (Status.Code.FAILED_PRECONDITION to FailureReason.FAILURE_REASON_APP_LIFECYCLE),
                DriverBuildMismatchException("a", "b", "c", "s") to
                    (Status.Code.FAILED_PRECONDITION to FailureReason.FAILURE_REASON_DRIVER_BUILD_MISMATCH),
                DriverStartException("x") to (Status.Code.UNAVAILABLE to FailureReason.FAILURE_REASON_DRIVER_START_FAILED),
                AdbCommandException("s", listOf("shell"), 1, "") to (Status.Code.UNAVAILABLE to FailureReason.FAILURE_REASON_ADB_FAILED),
                SessionClosingException("s") to (Status.Code.ABORTED to FailureReason.FAILURE_REASON_SESSION_UNUSABLE),
                DaemonClosingException() to (Status.Code.FAILED_PRECONDITION to FailureReason.FAILURE_REASON_DAEMON_PRECONDITION),
            )
        for ((error, expected) in cases) {
            val (code, failure) = failureOf(error)
            assertEquals(expected, code to failure.reason, error.toString())
        }
    }

    @Test
    fun `device failures name the serial and the wait`() {
        val (_, busy) = failureOf(DeviceBusyException("emulator-5554", 1_200))
        assertEquals("emulator-5554", busy.serial)
        assertEquals(1_200, busy.waitedMs)
        val (_, timeout) = failureOf(HostWaitTimeoutException("window", "85e49002", 3_000, 4, "none"))
        assertEquals("85e49002", timeout.serial)
        assertEquals(3_000, timeout.waitedMs)
    }

    @Test
    fun `the defaults echoed in Info match the client conformance table`() {
        // Gradle runs tests in the module directory.
        val table = File("../../contracts/conformance/client-conformance.json")
        val defaults = Json.parseToJsonElement(table.readText()).jsonObject.getValue("defaults").jsonObject
        val expected = defaults.mapValues { it.value.jsonPrimitive.long }
        val message = Defaults.message
        assertEquals(
            expected,
            mapOf(
                "action_timeout_ms" to message.actionTimeoutMs,
                "wait_timeout_ms" to message.waitTimeoutMs,
                "lifecycle_timeout_ms" to message.lifecycleTimeoutMs,
                "idle_stable_ms" to message.idleStableMs,
                "acquire_timeout_ms" to message.acquireTimeoutMs,
            ),
        )
    }

    @Test
    fun `a bare require or check failure is INTERNAL, not caller input`() {
        for (error in listOf(IllegalArgumentException("bug"), IllegalStateException("bug"), NoSuchElementException("bug"))) {
            val (code, failure) = failureOf(error)
            assertEquals(Status.Code.INTERNAL, code, error.toString())
            assertEquals(FailureReason.FAILURE_REASON_INTERNAL, failure.reason)
        }
    }
}
