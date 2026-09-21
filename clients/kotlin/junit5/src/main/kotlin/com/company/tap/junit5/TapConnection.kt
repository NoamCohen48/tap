package com.company.tap.junit5

import com.company.tap.sdk.Connection
import com.company.tap.sdk.TapClient
import com.company.tap.sdk.TapServiceProcess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One client and one service [Connection] per test JVM. With `tap.manageService=true` the
 * service is started (`tap start`) before the client connects and, if that start created it,
 * stopped (`tap stop`) when the JUnit launcher session ends — a service that was already
 * running is left alone.
 *
 * The attach stream's scope lives in the [Connection] itself (owned, JVM-lifetime): it
 * survives across tests and is closed exactly once here. All lifecycle methods are `suspend`;
 * the only `runBlocking` boundaries are the true edges — the launcher listener and the JVM
 * shutdown hook — never inside SDK or framework types. If the JVM dies without those, the
 * service notices the dropped liveness stream and closes its sessions.
 */
internal object TapConnection {
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()
    private var clientValue: TapClient? = null
    private var connectionValue: Connection? = null

    /** The shared client, connecting (and starting the service when configured) on first use. */
    suspend fun client(): TapClient =
        mutex.withLock {
            clientValue ?: run {
                if (TapConfig.current.manageService) started.set(TapServiceProcess.start().started)
                TapClient.create().also {
                    clientValue = it
                    Runtime.getRuntime().addShutdownHook(Thread({ shutdownBlocking() }, "tap-shutdown"))
                }
            }
        }

    /** The shared connection (attach established before first use). */
    suspend fun connection(): Connection =
        mutex.withLock {
            connectionValue ?: run {
                val owner = clientValue ?: clientLocked()
                owner.connect("junit ${ProcessHandle.current().pid()} ${System.getProperty("user.dir")}").also {
                    connectionValue = it
                }
            }
        }

    private suspend fun clientLocked(): TapClient {
        // Caller already holds [mutex].
        clientValue?.let { return it }
        if (TapConfig.current.manageService) started.set(TapServiceProcess.start().started)
        return TapClient.create().also {
            clientValue = it
            Runtime.getRuntime().addShutdownHook(Thread({ shutdownBlocking() }, "tap-shutdown"))
        }
    }

    /**
     * Closes the connection (explicit `Close`, then the attach scope) and the channel, then
     * stops a service this JVM started. Idempotent: the attach scope closes exactly once.
     * Runs teardown under bounded non-cancellable contexts in the SDK; failures are swallowed
     * here (the edge has nowhere to report them) — per-test teardown in `TapExtension` is the
     * place that preserves failures.
     */
    suspend fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        val connection = mutex.withLock { connectionValue.also { connectionValue = null } }
        val client = mutex.withLock { clientValue.also { clientValue = null } }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        if (started.get()) runCatching { TapServiceProcess.stop() }
    }

    /** Edge boundary for the launcher listener and the shutdown hook. */
    fun shutdownBlocking() = runBlocking { shutdown() }
}

/** Registered through `META-INF/services`; closes [TapConnection] once all tests have run. */
class TapLauncherSessionListener : LauncherSessionListener {
    override fun launcherSessionClosed(session: LauncherSession) {
        TapConnection.shutdownBlocking()
    }
}
