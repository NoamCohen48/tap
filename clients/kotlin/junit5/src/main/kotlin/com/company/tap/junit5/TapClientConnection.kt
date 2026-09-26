package com.company.tap.junit5

import com.company.tap.sdk.ClientConnection
import com.company.tap.sdk.TapClient
import com.company.tap.sdk.TapDaemonProcess
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The test JVM's one [TapClient] + [ClientConnection], created on first use and shared by every
 * test. Every [connection] call re-validates the cached connection under the [Mutex]: a closed
 * one, or one whose liveness stream ended (daemon restart, network loss), is dropped together
 * with its client and a fresh pair is created, so one dead connection never fails every later
 * test in the JVM. Creation re-resolves the daemon address, so a daemon restarted on a new port
 * is found again.
 *
 * With `tap.manageDaemon=true` each creation runs `tap start` first (a no-op when the daemon is
 * running); if any of them started it, [shutdown] runs `tap stop` — a daemon that was already
 * running is left alone. [shutdown] runs from the launcher session listener and, as a fallback,
 * a JVM shutdown hook; if the JVM dies without either, the server notices the dropped liveness
 * stream and detaches the connection's devices.
 */
internal class ConnectionMemo(
    private val manageDaemon: () -> Boolean = { TapConfig.current.manageDaemon },
    private val startDaemon: suspend () -> Boolean = { TapDaemonProcess.start().started },
    private val stopDaemon: suspend () -> Unit = { TapDaemonProcess.stop() },
    private val createClient: suspend () -> TapClient = { TapClient.create() },
    private val connect: suspend (TapClient) -> ClientConnection = { client ->
        client.connect("junit ${ProcessHandle.current().pid()} ${System.getProperty("user.dir")}")
    },
    private val isUsable: (ClientConnection) -> Boolean = { it.isUsable },
    private val closeConnection: suspend (ClientConnection) -> Unit = { it.close() },
    private val closeClient: suspend (TapClient) -> Unit = { it.close() },
    private val onFirstCreate: () -> Unit = {},
) {
    private val mutex = Mutex()
    private var client: TapClient? = null
    private var connection: ClientConnection? = null
    private var startedDaemon = false
    private val created = AtomicBoolean(false)

    /** The cached connection if it is still usable, else a newly created one. */
    suspend fun connection(): ClientConnection =
        mutex.withLock {
            connection?.let { if (isUsable(it)) return@withLock it }
            withContext(NonCancellable) { discard() }
            if (created.compareAndSet(false, true)) onFirstCreate()
            if (manageDaemon() && startDaemon()) startedDaemon = true
            val newClient = createClient()
            val newConnection =
                try {
                    connect(newClient)
                } catch (failure: Throwable) {
                    withContext(NonCancellable) { runCatching { closeClient(newClient) } }.exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                }
            client = newClient
            connection = newConnection
            newConnection
        }

    /**
     * Closes the connection and client and stops a daemon this memo started. Safe to call more
     * than once; the next [connection] creates a new pair. The first failure is thrown with
     * later ones suppressed into it.
     */
    suspend fun shutdown() {
        withContext(NonCancellable) {
            mutex.withLock {
                val errors = discard().toMutableList()
                if (startedDaemon) {
                    startedDaemon = false
                    runCatching { stopDaemon() }.exceptionOrNull()?.let(errors::add)
                }
                errors.firstOrNull()?.let { first ->
                    errors.drop(1).forEach(first::addSuppressed)
                    throw first
                }
            }
        }
    }

    /** Closes and forgets the current pair (caller holds [mutex]); returns the close failures. */
    private suspend fun discard(): List<Throwable> {
        val errors = listOfNotNull(
            connection?.let { runCatching { closeConnection(it) }.exceptionOrNull() },
            client?.let { runCatching { closeClient(it) }.exceptionOrNull() },
        )
        connection = null
        client = null
        return errors
    }
}

/** The JVM-wide memo with production factories. */
internal object TapClientConnection {
    private val memo =
        ConnectionMemo(
            onFirstCreate = { Runtime.getRuntime().addShutdownHook(Thread({ shutdownBlocking() }, "tap-shutdown")) },
        )

    suspend fun connection(): ClientConnection = memo.connection()

    suspend fun shutdown(): Unit = memo.shutdown()

    /** Edge boundary for the launcher listener and the shutdown hook. */
    fun shutdownBlocking(): Unit = runBlocking { shutdown() }
}

/** Registered through `META-INF/services`; closes [TapClientConnection] once all tests have run. */
class TapLauncherSessionListener : LauncherSessionListener {
    override fun launcherSessionClosed(session: LauncherSession) {
        TapClientConnection.shutdownBlocking()
    }
}
