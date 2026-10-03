package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.Activity
import io.github.noamcohen48.tap.api.v1.LoggedEvent
import io.github.noamcohen48.tap.api.v1.WatchRequest
import io.github.noamcohen48.tap.api.v1.WatchServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.WatchVideoRequest
import io.github.noamcohen48.tap.daemon.core.DaemonConfig
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.host.Adb
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class WatchServiceTest {
    private suspend fun withServer(block: suspend (TapDaemon, WatchServiceGrpcKt.WatchServiceCoroutineStub) -> Unit) {
        val daemon = TapDaemon(DaemonConfig(Adb("fake-adb"), Files.createTempDirectory("tap-watch"), driver = null, log = {}))
        val name = InProcessServerBuilder.generateName()
        val server =
            InProcessServerBuilder
                .forName(name)
                .directExecutor()
                .addService(WatchService(daemon))
                .build()
                .start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        try {
            withTimeout(5_000) { block(daemon, WatchServiceGrpcKt.WatchServiceCoroutineStub(channel)) }
        } finally {
            daemon.close()
            channel.shutdownNow()
            server.shutdownNow()
            channel.awaitTermination(2, TimeUnit.SECONDS)
            server.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private fun Activity.label(): String =
        when (kindCase) {
            Activity.KindCase.CONNECTION_OPENED -> "opened ${connectionOpened.name}"
            Activity.KindCase.CONNECTION_CLOSED -> "closed ${connectionClosed.reason}"
            Activity.KindCase.EVENT -> "event ${event.seq}"
            else -> kindCase.name
        }

    @Test
    fun `first response is the connection snapshot and backlog, then live activity follows`(): Unit =
        runBlocking {
            withServer { daemon, service ->
                val before = daemon.connectClient("junit fixture-tests", holdIdleMs = 10_000)
                before.events.append(LoggedEvent.newBuilder())
                val ready = CompletableDeferred<Unit>()
                val watching =
                    async {
                        service
                            .watch(WatchRequest.getDefaultInstance())
                            .onEach { ready.complete(Unit) }
                            // Up to and including the response that closes `later` (it may also
                            // carry `later`'s event).
                            .transformWhile { response ->
                                emit(response)
                                response.activitiesList.none { it.label() == "closed done" }
                            }.toList()
                    }
                ready.await()
                val later = daemon.connectClient("pytest", holdIdleMs = 10_000)
                later.events.append(LoggedEvent.newBuilder())
                daemon.disconnectClient(later.id, "done")
                val responses = watching.await()
                val first = responses.first()
                assertEquals(listOf("junit fixture-tests"), first.connectionsList.map { it.name })
                assertEquals(listOf("opened junit fixture-tests", "event 1"), first.activitiesList.map { it.label() })
                assertEquals(0, first.dropped)
                val live = responses.drop(1).flatMap { it.activitiesList }
                assertEquals(listOf("opened pytest", "event 1", "closed done"), live.map { it.label() })
                assertTrue(live.all { it.clientConnectionId == later.id })
                assertEquals(listOf(3L, 4L, 5L), live.map { it.seq })
            }
        }

    @Test
    fun `watching resumes after a seq and never renews a held connection`(): Unit =
        runBlocking {
            withServer { daemon, service ->
                val owner = daemon.connectClient("owner", holdIdleMs = 10_000)
                repeat(2) { owner.events.append(LoggedEvent.newBuilder()) }
                val started = owner.lastUsedNanos
                val first = service.watch(WatchRequest.newBuilder().setAfterSeq(2).build()).first()
                assertEquals(listOf(3L), first.activitiesList.map { it.seq })
                assertEquals(started, owner.lastUsedNanos)
                assertEquals(0, owner.inFlight)
                assertTrue(daemon.clientConnectionExists(owner.id))
            }
        }

    @Test
    fun `invalid cursor and unconfigured video are rejected`(): Unit =
        runBlocking {
            withServer { _, service ->
                assertEquals(
                    Status.Code.INVALID_ARGUMENT,
                    Status.fromThrowable(assertFails { service.watch(WatchRequest.newBuilder().setAfterSeq(-1).build()).toList() }).code,
                )
                assertEquals(
                    Status.Code.INVALID_ARGUMENT,
                    Status.fromThrowable(assertFails { service.watchVideo(WatchVideoRequest.getDefaultInstance()).toList() }).code,
                )
                assertEquals(
                    Status.Code.FAILED_PRECONDITION,
                    Status.fromThrowable(assertFails { service.watchVideo(WatchVideoRequest.newBuilder().setSerial("emulator-5554").build()).toList() }).code,
                )
            }
        }
}
