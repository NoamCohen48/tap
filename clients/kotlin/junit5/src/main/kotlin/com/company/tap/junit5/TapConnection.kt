package com.company.tap.junit5

import com.company.tap.sdk.Connection
import com.company.tap.sdk.TapClient
import com.company.tap.sdk.TapServiceProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
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
 * Generations: the [Mutex] is held only for short state transitions — never across the
 * service start/stop RPCs, channel/client creation, or the attach `connect`. A `shutdown`
 * snapshots the refs under the lock, bumps the generation, installs a teardown gate
 * ([CompletableDeferred]) covering its closes plus the owned service stop, and completes that
 * gate only after the teardown work finishes. A `client`/`connection` creation that arrives
 * after the snapshot awaits the gate before its own start/connect, so generation N+1 can
 * never connect to generation N's service before N's stop completes (no ABA). Creations that
 * raced the snapshot discard their just-built resources (close + owned stop, never published)
 * and retry on the new generation. Concurrent creators share one result through per-resource
 * single-flight deferreds; concurrent shutdowns join the in-flight teardown instead of
 * double-closing. After a shutdown the next access explicitly opens a new generation (new
 * client + connection, service start/stop accounted again) instead of reusing cleared refs —
 * launcher sessions are sequential, never overlapping.
 *
 * Creation rollback: when this generation's service start reported `started=true` and the
 * subsequent client create (or the attach connect for a freshly created client) fails, the
 * partial client is closed and the owned service is stopped before the failure propagates;
 * rollback failures are suppressed into the creation failure.
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
    private var generation: Long = 0
    private var teardown: CompletableDeferred<Unit>? = null
    private var clientFlight: CompletableDeferred<TapClient>? = null
    private var connectionFlight: CompletableDeferred<Connection>? = null
    private val hookInstalled = AtomicBoolean(false)

    private class GenerationLostException : Exception("tap generation lost; retry")

    private fun installHook(client: TapClient) {
        if (hookInstalled.compareAndSet(false, true)) onFirstClient(client)
    }

    /** The shared client, starting the managed service (when configured) on first use. */
    suspend fun client(): TapClient {
        while (true) {
            mutex.withLock { clientValue?.let { return it } }
            val gate: Deferred<Unit>?
            val flight: Deferred<TapClient>?
            mutex.withLock {
                clientValue?.let { return it }
                gate = teardown
                flight = clientFlight
            }
            if (gate != null) {
                try {
                    gate.await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                }
                continue
            }
            if (flight != null) {
                try {
                    return flight.await()
                } catch (lost: GenerationLostException) {
                    continue
                } catch (cancelled: CancellationException) {
                    throw cancelled
                }
            }
            val myFlight = CompletableDeferred<TapClient>()
            var seenGen = 0L
            var isCreator = false
            mutex.withLock {
                if (clientValue != null || teardown != null || clientFlight != null) {
                    // Lost the race; loop back to the fast path / gate / flight above.
                } else {
                    clientFlight = myFlight
                    seenGen = generation
                    isCreator = true
                }
            }
            if (!isCreator) continue
            // Service start, client creation and the publish below all run outside the
            // mutex; the flight holds the gate for concurrent creators until the new
            // client is published (or rolled back) below.
            var started = false
            var created: TapClient? = null
            try {
                started = if (manageService()) startService() else false
                try {
                    created = createClient()
                } catch (createFailed: Throwable) {
                    if (createFailed is CancellationException) {
                        // Our own cancellation: roll the owned start back without
                        // poisoning sharers, then propagate the cancellation.
                        if (started) runCatching { stopService() }
                        mutex.withLock { if (clientFlight === myFlight) clientFlight = null }
                        if (!myFlight.isCompleted) myFlight.completeExceptionally(GenerationLostException())
                        throw createFailed
                    }
                    if (started) {
                        try {
                            stopService()
                        } catch (stopFailed: Throwable) {
                            createFailed.addSuppressed(stopFailed)
                        }
                    }
                    throw createFailed
                }
                var published = false
                var staleClient: TapClient? = null
                var staleStop = false
                mutex.withLock {
                    if (seenGen != generation || teardown != null || clientValue != null) {
                        staleClient = created
                        staleStop = started
                    } else {
                        clientValue = created
                        startedService = started
                        closed = false
                        published = true
                    }
                    if (clientFlight === myFlight) clientFlight = null
                }
                if (!published) {
                    // A shutdown (or a newer generation) won the race while our I/O was
                    // in flight: never publish the stale client, close it and stop the
                    // service we started, then retry on the new generation.
                    runCatching { staleClient?.close() }
                    if (staleStop) runCatching { stopService() }
                    if (!myFlight.isCompleted) myFlight.completeExceptionally(GenerationLostException())
                    continue
                }
                installHook(created!!)
                myFlight.complete(created!!)
                return created!!
            } catch (failure: Throwable) {
                if (failure is GenerationLostException) continue
                if (failure is CancellationException) {
                    mutex.withLock { if (clientFlight === myFlight) clientFlight = null }
                    if (!myFlight.isCompleted) myFlight.completeExceptionally(GenerationLostException())
                    throw failure
                }
                mutex.withLock { if (clientFlight === myFlight) clientFlight = null }
                if (!myFlight.isCompleted) myFlight.completeExceptionally(failure)
                throw failure
            }
        }
    }

    /** The shared connection (attach established before first use). */
    suspend fun connection(): Connection {
        while (true) {
            mutex.withLock { connectionValue?.let { return it } }
            // Ensure the client first, holding no connection flight: creators never park
            // on the teardown gate while a shutdown may be awaiting their flight.
            val clientBefore: TapClient?
            mutex.withLock {
                if (connectionValue != null) return connectionValue!!
                clientBefore = clientValue
            }
            val owner: TapClient
            try {
                owner = client()
            } catch (failure: Throwable) {
                if (failure is GenerationLostException) continue
                throw failure
            }
            // Re-check under the gate before becoming the single connector.
            mutex.withLock { connectionValue?.let { return it } }
            val gate: Deferred<Unit>?
            val flight: Deferred<Connection>?
            mutex.withLock {
                connectionValue?.let { return it }
                gate = teardown
                flight = connectionFlight
            }
            if (gate != null) {
                try {
                    gate.await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                }
                continue
            }
            if (flight != null) {
                try {
                    return flight.await()
                } catch (lost: GenerationLostException) {
                    continue
                } catch (cancelled: CancellationException) {
                    throw cancelled
                }
            }
            val myFlight = CompletableDeferred<Connection>()
            var seenGen = 0L
            var isCreator = false
            mutex.withLock {
                if (connectionValue != null || teardown != null || connectionFlight != null) {
                    // Lost the race; loop back above.
                } else {
                    connectionFlight = myFlight
                    seenGen = generation
                    isCreator = true
                }
            }
            if (!isCreator) continue
            // The attach connect runs outside the mutex; the connection flight gates
            // concurrent connectors, and a shutdown joining this flight defers its
            // client close until the attach below finishes (no mid-connect close).
            val connected: Connection
            try {
                connected = connectClient(owner)
            } catch (connectFailed: Throwable) {
                if (connectFailed is CancellationException) {
                    // Cancelled attach: the shared client stays usable; sharers retry.
                    mutex.withLock { if (connectionFlight === myFlight) connectionFlight = null }
                    if (!myFlight.isCompleted) myFlight.completeExceptionally(GenerationLostException())
                    throw connectFailed
                }
                // Roll the whole fresh generation back only when this connect caused
                // the client creation (no shared client to preserve). A pre-existing
                // shared client stays up for the next connect retry. A shutdown that
                // raced the attach is a generation loss, not a creation failure:
                // drop nothing (nothing was published) and retry on the new generation.
                var rollbackClient: TapClient? = null
                var rollbackStop = false
                var rollbackGate: CompletableDeferred<Unit>? = null
                var generationLost = false
                mutex.withLock {
                    if (seenGen != generation || teardown != null) {
                        generationLost = true
                    } else if (clientBefore == null && connectionValue == null) {
                        rollbackClient = clientValue.also { clientValue = null }
                        rollbackStop = startedService.also { startedService = false }
                        closed = true
                        generation += 1
                        rollbackGate = CompletableDeferred<Unit>().also { teardown = it }
                    }
                    if (connectionFlight === myFlight) connectionFlight = null
                }
                if (generationLost) {
                    if (!myFlight.isCompleted) myFlight.completeExceptionally(GenerationLostException())
                    continue
                }
                if (rollbackClient != null) {
                    try {
                        runCatching { rollbackClient?.close() }
                        if (rollbackStop) stopService()
                    } catch (rollbackFailed: Throwable) {
                        connectFailed.addSuppressed(rollbackFailed)
                    } finally {
                        mutex.withLock { if (teardown === rollbackGate) teardown = null }
                        rollbackGate?.complete(Unit)
                    }
                }
                if (!myFlight.isCompleted) myFlight.completeExceptionally(connectFailed)
                throw connectFailed
            }
            var published = false
            var stale: Connection? = null
            mutex.withLock {
                if (seenGen != generation || teardown != null || connectionValue != null) {
                    stale = connected
                } else {
                    connectionValue = connected
                    published = true
                }
                if (connectionFlight === myFlight) connectionFlight = null
            }
            if (!published) {
                // Shutdown won the race during the attach: drop the stale connection
                // (the client half was accounted by the shutdown) and retry.
                runCatching { stale?.close() }
                if (!myFlight.isCompleted) myFlight.completeExceptionally(GenerationLostException())
                continue
            }
            myFlight.complete(connected)
            return connected
        }
    }

    /**
     * Closes the connection (explicit `Close`, then the attach scope) and the channel, then
     * stops a service this generation started. Idempotent per generation: the snapshot nuls
     * both refs and installs the teardown gate under the mutex, so concurrent shutdowns join
     * the in-flight teardown instead of double-closing, and a creation that arrives after the
     * snapshot awaits the gate (close + owned stop complete) before starting the next
     * generation. Teardown work runs outside the mutex. Failures are swallowed here
     * (the edge has nowhere to report them) — per-test teardown in `TapExtension` is the
     * place that preserves failures. A later `client`/`connection` explicitly opens a new
     * generation — launcher sessions are sequential, never overlapping.
     */
    suspend fun shutdown() {
        while (true) {
            var connection: Connection? = null
            var client: TapClient? = null
            var stop = false
            var join: Deferred<Unit>? = null
            var myTeardown: CompletableDeferred<Unit>? = null
            var awaitClient: Deferred<TapClient>? = null
            var awaitConnection: Deferred<Connection>? = null
            var noOp = false
            mutex.withLock {
                if (clientValue == null && connectionValue == null && !startedService) {
                    val inFlight = teardown
                    if (inFlight != null) {
                        join = inFlight
                    } else if (clientFlight == null && connectionFlight == null) {
                        noOp = true
                    } else {
                        // No published resources, but creations are in flight: bump the
                        // generation so they discard and retry, and gate newcomers until
                        // their cleanup finishes. Nothing to close ourselves.
                        generation += 1
                        myTeardown = CompletableDeferred<Unit>().also { teardown = it }
                        awaitClient = clientFlight
                        awaitConnection = connectionFlight
                    }
                } else {
                    connection = connectionValue.also { connectionValue = null }
                    client = clientValue.also { clientValue = null }
                    stop = startedService.also { startedService = false }
                    closed = true
                    generation += 1
                    myTeardown = CompletableDeferred<Unit>().also { teardown = it }
                    awaitClient = clientFlight
                    awaitConnection = connectionFlight
                }
            }
            if (noOp) return
            if (join != null) {
                try {
                    join.await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Teardown gates never fail (shutdown swallows its closes); retry the
                    // snapshot in case a new generation published while joining.
                }
                return
            }
            // Join in-flight creations before closing: they hold no teardown park while
            // doing I/O (client is ensured before the connection flight is installed),
            // so this cannot deadlock, and it keeps a close from landing mid-connect.
            // Newcomers park on the teardown gate above until it completes below.
            runCatching { awaitClient?.await() }
            runCatching { awaitConnection?.await() }
            // Reclaim anything an in-flight creation published while joining (it saw the
            // new generation and should have discarded, but never leak a publish).
            var lateConnection: Connection? = null
            var lateClient: TapClient? = null
            var lateStop = false
            mutex.withLock {
                lateConnection = connectionValue.also { connectionValue = null }
                lateClient = clientValue.also { clientValue = null }
                lateStop = startedService.also { startedService = false }
            }
            try {
                runCatching { connection?.close() }
                runCatching { client?.close() }
                if (stop) runCatching { stopService() }
                runCatching { lateConnection?.close() }
                runCatching { lateClient?.close() }
                if (lateStop) runCatching { stopService() }
            } finally {
                mutex.withLock { if (teardown === myTeardown) teardown = null }
                myTeardown!!.complete(Unit)
            }
            return
        }
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
