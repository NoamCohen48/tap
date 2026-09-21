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
 * One client and one service [Connection] per test JVM, with explicit sequential generations.
 * With `tap.manageService=true` the service is started (`tap start`) before the client connects
 * and, if that start created it, stopped (`tap stop`) when the JUnit launcher session ends — a
 * service that was already running is left alone.
 *
 * The attach stream's scope lives in the [Connection] itself (owned, JVM-lifetime): it
 * survives across tests and is closed exactly once per generation here. All lifecycle methods
 * are `suspend`; the only `runBlocking` boundaries are the true edges — the launcher listener
 * and the JVM shutdown hook — never inside SDK or framework types. If the JVM dies without
 * those, the service notices the dropped liveness stream and closes its sessions.
 *
 * Linearization: one [Mutex] guards the closed flag, both resource refs and the
 * started-service flag. `shutdown` snapshots all three under that lock and closes outside it,
 * so a concurrent `client`/`connection` either lands fully before the snapshot (and is closed
 * by it) or fully after (a new generation); no resource is created between the snapshot's
 * halves, and every created resource is closed exactly once. After a shutdown the next access
 * explicitly opens a new generation (new client + connection, service start/stop accounted
 * again) instead of reusing cleared refs — launcher sessions are sequential, never overlapping.
 */
internal class TapConnectionState(
    private val manageService: () -> Boolean = { TapConfig.current.manageService },
    private val startService: suspend () -> Boolean = { TapServiceProcess.start().started },
    private val stopService: suspend () -> Unit = { TapServiceProcess.stop() },
    private val createClient: suspend () -> TapClient = { TapClient.create() },
    private val connectClient: suspend (TapClient) -> Connection = { client ->
        client.connect("junit ${ProcessHandle.current().pid()} ${System.getProperty("user.dir")}")
    },
    private val onFirstClient: (TapClient) -> Unit = { Runtime.getRuntime().addShutdownHook(Thread({ shutdownHook() }, "tap-shutdown")) },
) {
    private val mutex = Mutex()
    private var clientValue: TapClient? = null
    private var connectionValue: Connection? = null
    private var closed = false
    private var startedService = false
    private val hookInstalled = AtomicBoolean(false)

    private fun installHook(client: TapClient) {
        if (hookInstalled.compareAndSet(false, true)) onFirstClient(client)
    }

    /** The shared client, connecting (and starting the service when configured) on first use. */
    suspend fun client(): TapClient =
        mutex.withLock {
            clientValue?.let { return@withLock it }
            // First use of this generation performs I/O under the lock, so concurrent
            // callers share exactly one client and shutdown cannot slip between the
            // service start and the ref store. First use is rare; steady-state hits the
            // cached ref above.
            val started = if (manageService()) startService() else false
            val created = createClient()
            clientValue = created
            startedService = started
            closed = false
            installHook(created)
            created
        }

    /** The shared connection (attach established before first use). */
    suspend fun connection(): Connection =
        mutex.withLock {
            connectionValue?.let { return@withLock it }
            // Same lock from client creation through the connect: shutdown waits outside,
            // so the stored pair is always consistent — never a connection on a closed
            // client, never a create slipping between snapshot halves.
            val owner = clientLocked()
            connectClient(owner).also { connectionValue = it }
        }

    private suspend fun clientLocked(): TapClient {
        // Caller already holds [mutex].
        clientValue?.let { return it }
        val started = if (manageService()) startService() else false
        val created = createClient()
        clientValue = created
        startedService = started
        closed = false
        installHook(created)
        return created
    }

    /**
     * Closes the connection (explicit `Close`, then the attach scope) and the channel, then
     * stops a service this generation started. Idempotent per generation: the snapshot nuls
     * both refs under the mutex, so concurrent shutdowns close each resource exactly once,
     * and a later `client`/`connection` opens a new generation. Failures are swallowed here
     * (the edge has nowhere to report them) — per-test teardown in `TapExtension` is the
     * place that preserves failures.
     */
    suspend fun shutdown() {
        val connection: Connection?
        val client: TapClient?
        val stop: Boolean
        mutex.withLock {
            if (closed && clientValue == null && connectionValue == null) return
            closed = true
            connection = connectionValue.also { connectionValue = null }
            client = clientValue.also { clientValue = null }
            stop = startedService.also { startedService = false }
        }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        if (stop) runCatching { stopService() }
    }

    companion object {
        /** Edge boundary for the shutdown hook (installed once per JVM). */
        private fun shutdownHook() = TapConnection.shutdownBlocking()
    }
}

/** The JVM-wide state with production factories. */
internal object TapConnection {
    private val state = TapConnectionState()

    suspend fun client(): TapClient = state.client()

    suspend fun connection(): Connection = state.connection()

    suspend fun shutdown() = state.shutdown()

    /** Edge boundary for the launcher listener and the shutdown hook. */
    fun shutdownBlocking() = runBlocking { shutdown() }
}

/** Registered through `META-INF/services`; closes [TapConnection] once all tests have run. */
class TapLauncherSessionListener : LauncherSessionListener {
    override fun launcherSessionClosed(session: LauncherSession) {
        TapConnection.shutdownBlocking()
    }
}
