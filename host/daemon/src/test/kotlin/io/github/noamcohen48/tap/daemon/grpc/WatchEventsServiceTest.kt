package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpcKt
import io.github.noamcohen48.tap.api.v1.LoggedEvent
import io.github.noamcohen48.tap.api.v1.WatchEventsRequest
import io.github.noamcohen48.tap.daemon.core.DaemonConfig
import io.github.noamcohen48.tap.daemon.core.TapDaemon
import io.github.noamcohen48.tap.host.Adb
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchEventsServiceTest {
    private suspend fun withServer(block: suspend (TapDaemon, ClientConnectionServiceGrpcKt.ClientConnectionServiceCoroutineStub) -> Unit) {
        val daemon = TapDaemon(DaemonConfig(Adb("fake-adb"), Files.createTempDirectory("tap-watch-events"), driver = null, log = {}))
        val name = InProcessServerBuilder.generateName()
        val server =
            InProcessServerBuilder
                .forName(name)
                .directExecutor()
                .addService(ClientConnectionService(daemon))
                .build()
                .start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        try {
            withTimeout(5_000) { block(daemon, ClientConnectionServiceGrpcKt.ClientConnectionServiceCoroutineStub(channel)) }
        } finally {
            daemon.close()
            channel.shutdownNow()
            server.shutdownNow()
            channel.awaitTermination(2, TimeUnit.SECONDS)
            server.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `reader replays backlog then live events and closes on owner disconnect`(): Unit =
        runBlocking {
            withServer { daemon, service ->
                val owner = daemon.connectClient("owner", holdIdleMs = 10_000)
                repeat(3) { owner.events.append(LoggedEvent.newBuilder()) }
                val ready = CompletableDeferred<Unit>()
                val watching =
                    async {
                        service
                            .watchEvents(
                                WatchEventsRequest
                                    .newBuilder()
                                    .setObservedConnectionId(owner.id)
                                    .setAfterSeq(1)
                                    .build(),
                            ).onEach { ready.complete(Unit) }
                            .toList()
                    }
                ready.await()
                owner.events.append(LoggedEvent.newBuilder())
                daemon.disconnectClient(owner.id, "owner finished")
                val updates = watching.await()
                assertEquals(listOf(2L, 3L, 4L), updates.flatMap { it.events.eventsList }.map { it.seq })
                assertEquals("owner finished", updates.last().closing.reason)
            }
        }

    @Test
    fun `watching never renews a held connection or claims its Observe stream`(): Unit =
        runBlocking {
            withServer { daemon, service ->
                val owner = daemon.connectClient("short-lived", holdIdleMs = 150)
                val started = owner.lastUsedNanos
                val updates = service.watchEvents(WatchEventsRequest.newBuilder().setObservedConnectionId(owner.id).build()).toList()
                assertEquals(started, owner.lastUsedNanos)
                assertEquals(0, owner.inFlight)
                assertFalse(daemon.clientConnectionExists(owner.id))
                assertTrue(
                    updates
                        .last()
                        .closing.reason
                        .startsWith("idle for"),
                )
            }
        }

    @Test
    fun `reader cancellation leaves owner alive and rejects invalid cursor and unknown connection`(): Unit =
        runBlocking {
            withServer { daemon, service ->
                val owner = daemon.connectClient("owner", holdIdleMs = 10_000)
                val request = WatchEventsRequest.newBuilder().setObservedConnectionId(owner.id).build()
                service.watchEvents(request).take(1).toList()
                assertTrue(daemon.clientConnectionExists(owner.id))
                assertFalse(daemon.observeOwnerPresent(owner.id))
                assertEquals(
                    Status.Code.INVALID_ARGUMENT,
                    Status
                        .fromThrowable(
                            assertFails {
                                service.watchEvents(request.toBuilder().setAfterSeq(-1).build()).toList()
                            },
                        ).code,
                )
                assertEquals(
                    Status.Code.NOT_FOUND,
                    Status
                        .fromThrowable(
                            assertFails {
                                service.watchEvents(request.toBuilder().setObservedConnectionId("unknown").build()).toList()
                            },
                        ).code,
                )
            }
        }
}
