package com.company.tap.junit5

import com.company.tap.sdk.Connection
import com.company.tap.sdk.TapClient
import com.company.tap.sdk.TapServiceProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * rollback failures are suppressed into the creation failure. Cancellation is owned the same
 * way: once a creator holds its single-flight (or a shutdown holds its teardown gate), every
 * state clear, resource rollback, gate completion and mutex transition runs under
 * [NonCancellable] and the original [CancellationException] is rethrown afterwards, so a
 * cancelled handshake never leaks a client, a stop, a flight, or a gate.
 *
 * Test seams: [closeClient]/[closeConnection] default to the real closes and let suppression
 * tests inject failures; [onTeardownPark] is invoked immediately before parking on the
 * teardown gate so the managed-generation test can prove the second generation is parked
 * without timing assumptions.
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
    private val closeClient: suspend (TapClient) -> Unit = { it.close() },
    private val closeConnection: suspend (Connection) -> Unit = { it.close() },
    private val onTeardownPark: () -> Unit = {},
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
                    onTeardownPark()
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
            // client is published (or rolled back) below. Once the flight is owned, every
            // state clear, rollback, gate completion and mutex transition below runs under
            // NonCancellable so a cancelled handshake never leaks a stop, a client, the
            // flight, or a gate; the original cancellation is rethrown afterwards.
            var started = false
            var created: TapClient? = null
            try {
                started = if (manageService()) startService() else false
                created = createClient()
            } catch (createFailed: Throwable) {
                withContext(NonCancellable) {
                    if (started) {
                        try {
                            stopService()
                        } catch (stopFailed: Throwable) {
                            createFailed.addSuppressed(stopFailed)
                        }
                    }
                    mutex.withLock { if (clientFlight === myFlight) clientFlight = null }
                    if (createFailed is CancellationException) {
                        if (!myFlight.isCompleted) myFlight.completeExceptionally(GenerationLostException())
                    } else {
                        if (!myFlight.isCompleted) myFlight.completeExceptionally(createFailed)
                    }
                }
                throw createFailed
            }
            // I/O succeeded; a cancellation that landed after the create must still roll
            // the just-built client back instead of leaking it.
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancelledAfterIo: CancellationException) {
                withContext(NonCancellable) {
                    try {
                        closeClient(created!!)
                    } catch (closeFailed: Throwable) {
                        cancelledAfterIo.addSuppressed(closeFailed)
                    }
                    if (started) {
                        try {
                            stopService()
                        } catch (stopFailed: Throwable) {
                            cancelledAfterIo.addSuppressed(stopFailed)
                        }
                    }
                    mutex.withLock { if (clientFlight === myFlight) clientFlight = null }
                    if (!myFlight.isCompleted) myFlight.completeExceptionally(GenerationLostException())
                }
                throw cancelledAfterIo
            }
            var published = false
            var staleClient: TapClient? = null
            var staleStop = false
            try {
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
            } catch (publishCancelled: CancellationException) {
                withContext(NonCancellable) {
                    try {
                        closeClient(created!!)
                    } catch (closeFailed: Throwable) {
                        publishCancelled.addSuppressed(closeFailed)
                    }
                    if (started) {
                        try {
                            stopService()
                        } catch (stopFailed: Throwable) {
                            publishCancelled.addSuppressed(stopFailed)
                        }
                    }
                    mutex.withLock { if (clientFlight === myFlight) clientFlight = null }
                    if (!myFlight.isCompleted) myFlight.completeExceptionally(GenerationLostException())
                }
                throw publishCancelled
            }
            if (!published) {
                // A shutdown (or a newer generation) won the race while our I/O was
                // in flight: never publish the stale client, close it and stop the
                // service we started, then retry on the new generation. Both attempts run
                // independently under NonCancellable; a stale discard has no primary to
                // suppress into.
                withContext(NonCancellable) {
                    try {
                        closeClient(staleClient!!)
                    } catch (_: Throwable) {
                        // No primary; the stop below still runs.
                    }
                    if (staleStop) {
                        try {
                            stopService()
                        } catch (_: Throwable) {
                            // No primary; nothing to report.
                        }
                    }
                }
                if (!myFlight.isCompleted) myFlight.completeExceptionally(GenerationLostException())
                continue
            }
            try {
                installHook(created!!)
                myFlight.complete(created!!)
            } catch (hookFailed: Throwable) {
                // The client is already published for the next accessor; only the flight
                // still needs a terminal state so sharers never park forever.
                if (!myFlight.isCompleted) myFlight.completeExceptionally(hookFailed)
                throw hookFailed
            }
            return created!!
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
                    onTeardownPark()
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
            // client close until the attach below finishes (no mid-connect close). Once the
            // flight is owned, every state clear, rollback, gate completion and mutex
            // transition below runs under NonCancellable with the original cancellation
            // rethrown afterwards.
            val connected: Connection
            try {
                connected = connectClient(owner)
            } catch (connectFailed: Throwable) {
                // Decide rollback vs generation loss vs shared-client keep under the lock,
                // then run the owned close + stop independently under NonCancellable with
                // every cleanup failure suppressed into the primary.
                var rollbackClient: TapClient? = null
                var rollbackStop = false
                var rollbackGate: CompletableDeferred<Unit>? = null
                var generationLost = false
                var ownedRollback = false
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (seenGen != generation || teardown != null) {
                            generationLost = true
                        } else if (clientBefore == null && connectionValue == null) {
                            rollbackClient = clientValue.also { clientValue = null }
                            rollbackStop = startedService.also { startedService = false }
                            closed = true
                            generation += 1
                            rollbackGate = CompletableDeferred<Unit>().also { teardown = it }
                            ownedRollback = true
                        }
                        if (connectionFlight === myFlight) connectionFlight = null
                    }
                    if (generationLost) {
                        // A shutdown raced the attach and owns the published client half;
                        // drop nothing here (nothing was published by this connect) so the
                        // shutdown never double-closes. Cancellation still propagates to
                        // the caller; a non-cancellation failure retries on the new
                        // generation.
                        if (!myFlight.isCompleted) myFlight.completeExceptionally(GenerationLostException())
                    } else if (ownedRollback) {
                        try {
                            if (rollbackClient != null) {
                                try {
                                    closeClient(rollbackClient)
                                } catch (closeFailed: Throwable) {
                                    connectFailed.addSuppressed(closeFailed)
                                }
                            }
                            if (rollbackStop) {
                                try {
                                    stopService()
                                } catch (stopFailed: Throwable) {
                                    connectFailed.addSuppressed(stopFailed)
                                }
                            }
                        } finally {
                            mutex.withLock { if (teardown === rollbackGate) teardown = null }
                            rollbackGate?.complete(Unit)
                        }
                        if (!myFlight.isCompleted) {
                            if (connectFailed is CancellationException) {
                                myFlight.completeExceptionally(GenerationLostException())
                            } else {
                                myFlight.completeExceptionally(connectFailed)
                            }
                        }
                    } else {
                        // A pre-existing shared client stays up for the next connect retry;
                        // only the flight needs a terminal state. Cancellation lets
                        // sharers retry via GenerationLost; a failure propagates.
                        if (!myFlight.isCompleted) {
                            if (connectFailed is CancellationException) {
                                myFlight.completeExceptionally(GenerationLostException())
                            } else {
                                myFlight.completeExceptionally(connectFailed)
                            }
                        }
                    }
                }
                if (generationLost && connectFailed !is CancellationException) continue
                throw connectFailed
            }
            try {
                currentCoroutineContext().ensureActive()
            } catch (cancelledAfterIo: CancellationException) {
                withContext(NonCancellable) {
                    try {
                        closeConnection(connected)
                    } catch (closeFailed: Throwable) {
                        cancelledAfterIo.addSuppressed(closeFailed)
                    }
                    mutex.withLock { if (connectionFlight === myFlight) connectionFlight = null }
                    if (!myFlight.isCompleted) myFlight.completeExceptionally(GenerationLostException())
                }
                throw cancelledAfterIo
            }
            var published = false
            var stale: Connection? = null
            try {
                mutex.withLock {
                    if (seenGen != generation || teardown != null || connectionValue != null) {
                        stale = connected
                    } else {
                        connectionValue = connected
                        published = true
                    }
                    if (connectionFlight === myFlight) connectionFlight = null
                }
            } catch (publishCancelled: CancellationException) {
                withContext(NonCancellable) {
                    try {
                        closeConnection(connected)
                    } catch (closeFailed: Throwable) {
                        publishCancelled.addSuppressed(closeFailed)
                    }
                    mutex.withLock { if (connectionFlight === myFlight) connectionFlight = null }
                    if (!myFlight.isCompleted) myFlight.completeExceptionally(GenerationLostException())
                }
                throw publishCancelled
            }
            if (!published) {
                // Shutdown won the race during the attach: drop the stale connection
                // (the client half was accounted by the shutdown) and retry.
                withContext(NonCancellable) {
                    try {
                        closeConnection(stale!!)
                    } catch (_: Throwable) {
                        // Stale discard has no primary; the shutdown owns the client half.
                    }
                }
                if (!myFlight.isCompleted) myFlight.completeExceptionally(GenerationLostException())
                continue
            }
            try {
                myFlight.complete(connected)
            } catch (completeFailed: Throwable) {
                if (!myFlight.isCompleted) myFlight.completeExceptionally(completeFailed)
                throw completeFailed
            }
            return connected
        }
    }

    /**
     * Closes the connection (explicit `Close`, then the attach scope) and the channel, then
     * stops a service this generation started. Idempotent per generation: the snapshot nuls
     * both refs and installs the teardown gate under the mutex, so concurrent shutdowns join
     * the in-flight teardown instead of double-closing, and a creation that arrives after the
     * snapshot awaits the gate (close + owned stop complete) before starting the next
     * generation. Teardown work runs outside the mutex. Once the teardown gate is owned, every
     * close, stop, state clear, gate completion and mutex transition runs under
     * [NonCancellable]; a caller cancelled while owning the teardown still completes it and
     * then rethrows the original cancellation. Failures are swallowed here
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
            // Owns myTeardown from here: join in-flight creations (cancellable capture),
            // then every close, state clear, gate completion and mutex transition under
            // NonCancellable, rethrowing the original cancellation afterwards so the gate
            // is never left incomplete.
            // Join in-flight creations before closing: they hold no teardown park while
            // doing I/O (client is ensured before the connection flight is installed),
            // so this cannot deadlock, and it keeps a close from landing mid-connect.
            // Newcomers park on the teardown gate above until it completes below.
            var joinCancelled: CancellationException? = null
            try {
                try {
                    awaitClient?.await()
                } catch (cancelled: CancellationException) {
                    // Only the shutdown caller's own cancellation surfaces here: flights
                    // complete with values, GenerationLost, or non-cancellation failures,
                    // never with CancellationException.
                    joinCancelled = cancelled
                } catch (_: Throwable) {
                    // GenerationLost or a creation failure: the creator already rolled its
                    // own generation back; the shutdown still closes what it snapshotted.
                }
                if (joinCancelled == null) {
                    try {
                        awaitConnection?.await()
                    } catch (cancelled: CancellationException) {
                        joinCancelled = cancelled
                    } catch (_: Throwable) {
                    }
                } else {
                    // Already cancelled: still drain the second flight without suspending
                    // on cancellation — its result is irrelevant, but never leave an
                    // unobserved failure to surface later.
                    try {
                        awaitConnection?.await()
                    } catch (_: Throwable) {
                    }
                }
            } catch (joinFailed: CancellationException) {
                // Defensive: await above only throws the caller's cancellation.
                joinCancelled = joinFailed
            }
            withContext(NonCancellable) {
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
                    try {
                        if (connection != null) closeConnection(connection)
                    } catch (_: Throwable) {
                    }
                    try {
                        if (client != null) closeClient(client)
                    } catch (_: Throwable) {
                    }
                    try {
                        if (stop) stopService()
                    } catch (_: Throwable) {
                    }
                    try {
                        if (lateConnection != null) closeConnection(lateConnection)
                    } catch (_: Throwable) {
                    }
                    try {
                        if (lateClient != null) closeClient(lateClient)
                    } catch (_: Throwable) {
                    }
                    try {
                        if (lateStop) stopService()
                    } catch (_: Throwable) {
                    }
                } finally {
                    mutex.withLock { if (teardown === myTeardown) teardown = null }
                    myTeardown!!.complete(Unit)
                }
            }
            // The teardown gate is complete; now preserve the caller's cancellation, if any,
            // instead of swallowing it. Prefer the join-time original; otherwise surface a
            // cancellation that landed during the NonCancellable closes via ensureActive.
            if (joinCancelled != null) throw joinCancelled
            currentCoroutineContext().ensureActive()
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
