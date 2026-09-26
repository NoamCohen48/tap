package com.company.tap.sdk

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

/** The server's wording for a held per-serial lock (`DeviceBusyException` in `:host:core`). */
internal const val DEVICE_BUSY_MARKER = "is in use by another session"

/**
 * Ownership marker for suspending device calls. Installed by [tapScope] (scripts) and by the
 * JUnit 5 `tapTest` bridge (which uses the same element with a `junit:<method>` owner); every
 * suspending `Device`/`App`/`Element`/`ElementWait` call requires it. `TapClient`,
 * `ClientConnection`, `DaemonDiscovery` and `TapDaemonProcess` never require it: they set up the
 * channel, the connection and the daemon process outside any test scope.
 */
class TapContext(
    val owner: String,
) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<TapContext>

    override val key: CoroutineContext.Key<*> get() = Key
}

/**
 * Requires an owning [TapContext]: `tapScope { ... }` in scripts, `tapTest { ... }` in JUnit.
 * A coroutine that does not inherit the scope (for example `GlobalScope.async`) fails here
 * instead of running uncancelled beside the test.
 */
suspend fun ensureTapBound(operation: String) {
    if (currentCoroutineContext()[TapContext] == null) {
        throw TapUsageException(
            "$operation must be called inside tapTest { ... } (JUnit @TapTest) or tapScope { ... } (scripts); " +
                "suspension launched outside the bound scope is rejected so timeouts and sibling failures can cancel it",
        )
    }
}

/**
 * Runs [block] with a [TapContext] owner, for scripts and other non-JUnit callers.
 * JUnit tests must use `tapTest { ... }` instead (same marker, plus the extension binding).
 * Nesting (`tapScope` inside `tapScope`/`tapTest`) fails immediately.
 */
suspend fun <T> tapScope(
    owner: String = "script",
    block: suspend CoroutineScope.() -> T,
): T {
    if (currentCoroutineContext()[TapContext] != null) {
        throw TapUsageException(
            "nested tapScope ($owner): suspending device calls already belong to '${currentCoroutineContext()[TapContext]?.owner}'",
        )
    }
    return coroutineScope {
        kotlinx.coroutines.withContext(TapContext(owner)) { block() }
    }
}

/**
 * Converts gRPC failures into the client's exceptions. Driver outcomes never arrive this way
 * (they are `CommandResult` data); this covers server refusals and host-side failures.
 * Caller cancellation ([CancellationException]) is never mapped: it propagates so deadlines
 * and sibling failures cancel the RPC promptly.
 */
internal suspend fun <T> mapped(
    serial: String? = null,
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: StatusException) {
        throw mapStatus(error.status, serial, error)
    } catch (error: StatusRuntimeException) {
        throw mapStatus(error.status, serial, error)
    }

private fun mapStatus(
    status: Status,
    serial: String?,
    cause: Throwable,
): TapException {
    val details = status.description.orEmpty()
    return when {
        status.code == Status.Code.UNAUTHENTICATED -> {
            ServerException(
                status.code.name,
                "wrong or missing daemon token (read from daemon.json in the state dir, or " +
                    "tap.token / TAP_TOKEN with an explicit address): $details",
                cause,
            )
        }

        status.code == Status.Code.PERMISSION_DENIED -> {
            ServerException(
                status.code.name,
                "device ${serial ?: "?"} is attached by another client connection; only the connection " +
                    "that attached it may use or detach it: $details",
                cause,
            )
        }

        status.code == Status.Code.DEADLINE_EXCEEDED && details.startsWith("Timed out") -> {
            WaitTimeoutException(details, serial ?: "?", 0, cause = cause)
        }

        details.contains(DEVICE_BUSY_MARKER) -> {
            DeviceBusyException(details, cause)
        }

        status.code == Status.Code.FAILED_PRECONDITION -> {
            AppLifecycleException(details, cause)
        }

        else -> {
            ServerException(status.code.name, details, cause)
        }
    }
}
