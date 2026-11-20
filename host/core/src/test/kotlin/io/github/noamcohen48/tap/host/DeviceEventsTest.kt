package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.api.v1.DeviceEvent
import io.github.noamcohen48.tap.api.v1.DeviceEventBatch
import io.github.noamcohen48.tap.api.v1.DeviceEventType
import io.github.noamcohen48.tap.protocol.Frame
import io.github.noamcohen48.tap.protocol.FrameType
import io.github.noamcohen48.tap.protocol.ProtocolException
import io.github.noamcohen48.tap.protocol.protocolVersion
import io.github.noamcohen48.tap.wire.v1.DeviceEventFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceEventsTest {
    private val secret = ByteArray(32).also(SecureRandom()::nextBytes)

    /** Returns Unit on purpose: JUnit never runs a test method that returns a value. */
    private fun withClient(
        driver: FakeDriverServer = FakeDriverServer("session-events", 2, secret),
        block: suspend (FakeDriverServer, DriverClient) -> Unit,
    ) =
        runBlocking {
            driver.use {
                val client = DriverClient.connect(driver.port, "session-events", 2, secret, heartbeatIntervalMs = 0)
                try {
                    block(driver, client)
                } finally {
                    client.close()
                }
            }
        }

    private fun FakeDriverServer.events(vararg events: DeviceEvent) =
        write(Frame(FrameType.DEVICE_EVENTS, 0, DeviceEventBatch.newBuilder().addAllEvents(events.toList()).build().toByteArray()))

    private fun toast(sequence: Long) =
        DeviceEvent
            .newBuilder()
            .setToastShown(io.github.noamcohen48.tap.api.v1.ToastShown.newBuilder().setText("toast"))
            .setSequence(sequence)
            .build()

    private fun FakeDriverServer.nextFilter(): Set<DeviceEventType> {
        val frame = nextFrame()
        assertEquals(FrameType.EVENT_FILTER, frame.type)
        return DeviceEventFilter.parseFrom(frame.payload).typesList.toSet()
    }

    @Test
    fun aLostTransportEndsSubscriptionsWithItsCause() =
        withClient { driver, client ->
            val subscription = client.events.subscribe(setOf(DeviceEventType.DEVICE_EVENT_TOAST))
            assertEquals(setOf(DeviceEventType.DEVICE_EVENT_TOAST), driver.nextFilter())
            driver.events(toast(1))
            assertEquals(1L, withTimeout(2_000) { subscription.events.receive() }.sequence)

            driver.dropConnection()
            val ended = withTimeout(2_000) { subscription.events.receiveCatching() }
            assertTrue(ended.isClosed)
            assertTrue(ended.exceptionOrNull() != null, "a lost transport is not a clean end")
            assertFailsWith<Throwable> { client.events.subscribe(setOf(DeviceEventType.DEVICE_EVENT_TOAST)) }
        }

    @Test
    fun closingTheClientEndsSubscriptionsCleanly() =
        withClient { driver, client ->
            val subscription = client.events.subscribe(setOf(DeviceEventType.DEVICE_EVENT_WINDOW_CHANGED))
            driver.nextFilter()
            client.close()
            val ended = withTimeout(2_000) { subscription.events.receiveCatching() }
            assertTrue(ended.isClosed)
            assertNull(ended.exceptionOrNull())
        }

    @Test
    fun eventsOnARequestIdPoisonTheConnection() =
        withClient { driver, client ->
            driver.write(Frame(FrameType.DEVICE_EVENTS, 5, DeviceEventBatch.getDefaultInstance().toByteArray()))
            withTimeout(5_000) { while (!client.isPoisoned) delay(10) }
            assertTrue(client.poisonCause is ProtocolException)
        }

    @Test
    fun aSubscriberThatFallsBehindIsEndedAlone() =
        withClient { driver, client ->
            val slow = client.events.subscribe(setOf(DeviceEventType.DEVICE_EVENT_TOAST))
            driver.nextFilter()
            val other = client.events.subscribe(setOf(DeviceEventType.DEVICE_EVENT_TOAST))
            // `other` reads each event as it arrives; `slow` reads nothing until the end.
            repeat(DEVICE_EVENT_SUBSCRIBER_QUEUE + 1) {
                driver.events(toast(it.toLong()))
                withTimeout(2_000) { other.events.receive() }
            }
            repeat(DEVICE_EVENT_SUBSCRIBER_QUEUE) { slow.events.receive() }
            assertTrue(slow.events.receiveCatching().exceptionOrNull() is DeviceEventOverflowException)
            driver.events(toast(9_999))
            assertEquals(9_999L, withTimeout(2_000) { other.events.receive() }.sequence)
        }

    @Test
    fun aDriverBeforeProtocol53HasNoEvents() =
        withClient(FakeDriverServer("session-events", 2, secret, supportedVersions = listOf(protocolVersion(5, 2)))) { _, client ->
            assertFailsWith<DeviceEventsUnsupportedException> { client.events }
            assertTrue(!client.isPoisoned)
        }
}
