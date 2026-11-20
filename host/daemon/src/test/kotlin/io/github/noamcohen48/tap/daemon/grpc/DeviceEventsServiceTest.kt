package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.ContentChanged
import io.github.noamcohen48.tap.api.v1.DeviceEvent
import io.github.noamcohen48.tap.api.v1.DeviceEventBatch
import io.github.noamcohen48.tap.api.v1.DeviceEventType
import io.github.noamcohen48.tap.api.v1.ToastShown
import io.github.noamcohen48.tap.api.v1.WatchDeviceEventsRequest
import io.github.noamcohen48.tap.api.v1.WatchDeviceEventsResponse
import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.protocol.Frame
import io.github.noamcohen48.tap.protocol.FrameType
import io.github.noamcohen48.tap.wire.v1.DeviceEventFilter
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeviceEventsServiceTest {
    private val adb = object : Adb("fake-adb") {}

    private fun AttachedHarness.request(
        vararg types: DeviceEventType,
        packageName: String? = null,
    ): WatchDeviceEventsRequest =
        WatchDeviceEventsRequest
            .newBuilder()
            .setClientConnectionId(connectionId)
            .setAttachedDeviceId(attached.id)
            .addAllTypes(types.toList())
            .apply { if (packageName != null) setPackageName(packageName) }
            .build()

    private suspend fun AttachedHarness.nextFilter(): Set<DeviceEventType> {
        val frame = withContext(Dispatchers.IO) { driver.nextFrame() }
        assertEquals(FrameType.EVENT_FILTER, frame.type)
        assertEquals(0L, frame.requestId)
        return DeviceEventFilter.parseFrom(frame.payload).typesList.toSet()
    }

    private fun AttachedHarness.send(
        vararg events: DeviceEvent,
        dropped: Long = 0,
    ) = driver.write(
        Frame(
            FrameType.DEVICE_EVENTS,
            0,
            DeviceEventBatch
                .newBuilder()
                .addAllEvents(events.toList())
                .setDropped(dropped)
                .build()
                .toByteArray(),
        ),
    )

    private fun event(
        type: DeviceEventType,
        sequence: Long,
        packageName: String = "com.shop",
        text: String = "",
    ): DeviceEvent =
        DeviceEvent
            .newBuilder()
            .setSequence(sequence)
            .setPackageName(packageName)
            .apply {
                when (type) {
                    DeviceEventType.DEVICE_EVENT_TOAST -> setToastShown(ToastShown.newBuilder().setText(text))
                    DeviceEventType.DEVICE_EVENT_CONTENT_CHANGED -> setContentChanged(ContentChanged.getDefaultInstance())
                    else -> error("no fixture for $type")
                }
            }.build()

    @Test
    fun deliversMatchingEventsAfterReadyAndClearsTheFilterOnCancel() =
        runBlocking {
            AttachedHarness.with(adb) {
                coroutineScope {
                    val responses = Channel<WatchDeviceEventsResponse>(Channel.UNLIMITED)
                    val watching =
                        launch {
                            devices
                                .watchDeviceEvents(request(DeviceEventType.DEVICE_EVENT_TOAST, packageName = "com.shop"))
                                .collect { responses.send(it) }
                        }
                    assertEquals(setOf(DeviceEventType.DEVICE_EVENT_TOAST), withTimeout(2_000) { nextFilter() })
                    assertTrue(withTimeout(2_000) { responses.receive() }.ready)

                    send(
                        event(DeviceEventType.DEVICE_EVENT_TOAST, 1, text = "Saved"),
                        event(DeviceEventType.DEVICE_EVENT_TOAST, 2, packageName = "com.other"),
                        event(DeviceEventType.DEVICE_EVENT_CONTENT_CHANGED, 3),
                        event(DeviceEventType.DEVICE_EVENT_TOAST, 4, text = "Sent"),
                    )
                    val received = mutableListOf<DeviceEvent>()
                    while (received.size < 2) received += withTimeout(2_000) { responses.receive() }.batch.eventsList
                    assertEquals(listOf(1L, 4L), received.map { it.sequence })
                    assertEquals(listOf("Saved", "Sent"), received.map { it.toastShown.text })

                    watching.cancel()
                    assertEquals(emptySet(), withTimeout(2_000) { nextFilter() })
                }
            }
        }

    @Test
    fun theDriverIsToldTheUnionOfAllSubscriptions() =
        runBlocking {
            AttachedHarness.with(adb) {
                coroutineScope {
                    val toasts = launch { devices.watchDeviceEvents(request(DeviceEventType.DEVICE_EVENT_TOAST)).collect {} }
                    assertEquals(setOf(DeviceEventType.DEVICE_EVENT_TOAST), withTimeout(2_000) { nextFilter() })
                    val windows = launch { devices.watchDeviceEvents(request(DeviceEventType.DEVICE_EVENT_WINDOW_CHANGED)).collect {} }
                    assertEquals(
                        setOf(DeviceEventType.DEVICE_EVENT_TOAST, DeviceEventType.DEVICE_EVENT_WINDOW_CHANGED),
                        withTimeout(2_000) { nextFilter() },
                    )
                    toasts.cancel()
                    assertEquals(setOf(DeviceEventType.DEVICE_EVENT_WINDOW_CHANGED), withTimeout(2_000) { nextFilter() })
                    windows.cancel()
                    assertEquals(emptySet(), withTimeout(2_000) { nextFilter() })
                }
            }
        }

    @Test
    fun eventsTheDriverDroppedEndTheStreamAsResourceExhausted() =
        runBlocking {
            AttachedHarness.with(adb) {
                coroutineScope {
                    val ready = Channel<Unit>(1)
                    val outcome =
                        async {
                            assertFailsWith<StatusException> {
                                devices.watchDeviceEvents(request(DeviceEventType.DEVICE_EVENT_TOAST)).collect { if (it.ready) ready.send(Unit) }
                            }
                        }
                    withTimeout(2_000) { nextFilter() }
                    withTimeout(2_000) { ready.receive() }
                    send(event(DeviceEventType.DEVICE_EVENT_TOAST, 9), dropped = 3)
                    assertEquals(Status.Code.RESOURCE_EXHAUSTED, withTimeout(2_000) { outcome.await() }.status.code)
                }
            }
        }

    @Test
    fun closingTheDriverConnectionCompletesTheStream() =
        runBlocking {
            AttachedHarness.with(adb) {
                coroutineScope {
                    val ready = Channel<Unit>(1)
                    val collected =
                        async {
                            devices.watchDeviceEvents(request(DeviceEventType.DEVICE_EVENT_TOAST)).toListAfter(ready)
                        }
                    withTimeout(2_000) { nextFilter() }
                    withTimeout(2_000) { ready.receive() }
                    // Detaching closes the driver connection (the harness's fake session does not, so
                    // the test closes it): the stream then ends without an error.
                    attached.deviceSession.client.close()
                    assertEquals(1, withTimeout(5_000) { collected.await() }.size)
                }
            }
        }

    @Test
    fun rejectsNoTypesAndUnknownTypes() =
        runBlocking {
            AttachedHarness.with(adb) {
                for (bad in listOf(request(), request(DeviceEventType.DEVICE_EVENT_TYPE_UNSPECIFIED), request(DeviceEventType.DEVICE_EVENT_TOAST, packageName = " "))) {
                    val error = assertFailsWith<StatusException> { devices.watchDeviceEvents(bad).toList() }
                    assertEquals(Status.Code.INVALID_ARGUMENT, error.status.code)
                }
            }
        }

    private suspend fun kotlinx.coroutines.flow.Flow<WatchDeviceEventsResponse>.toListAfter(ready: Channel<Unit>): List<WatchDeviceEventsResponse> {
        val all = mutableListOf<WatchDeviceEventsResponse>()
        collect {
            all += it
            if (it.ready) ready.send(Unit)
        }
        return all
    }
}
