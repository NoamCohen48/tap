package io.github.noamcohen48.tap.driver

import io.github.noamcohen48.tap.api.v1.DeviceNotification
import io.github.noamcohen48.tap.api.v1.MatchMode
import io.github.noamcohen48.tap.protocol.Commands
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMatcherTest {
    private val message = DeviceNotification.newBuilder().setPackageName("com.example").setTitle("New message").setText("from Ada").build()
    private val untitled = DeviceNotification.newBuilder().setPackageName("com.example").build()

    @Test
    fun anEmptyMatchTakesEveryNotification() {
        val any = NotificationCommands.Matcher(Commands.notificationMatch())
        assertTrue(any.matches(message))
        assertTrue(any.matches(untitled))
    }

    @Test
    fun everyGivenFieldMustMatch() {
        assertTrue(NotificationCommands.Matcher(Commands.notificationMatch("com.example", "New message")).matches(message))
        assertFalse(NotificationCommands.Matcher(Commands.notificationMatch("com.other", "New message")).matches(message))
        assertFalse(NotificationCommands.Matcher(Commands.notificationMatch(title = "New")).matches(message))
        assertTrue(NotificationCommands.Matcher(Commands.notificationMatch(title = "New", text = "Ada", mode = MatchMode.MATCH_CONTAINS)).matches(message))
        assertFalse(NotificationCommands.Matcher(Commands.notificationMatch(title = "New", text = "Bob", mode = MatchMode.MATCH_CONTAINS)).matches(message))
    }

    @Test
    fun aMissingTitleOrTextMatchesNothing() {
        assertFalse(NotificationCommands.Matcher(Commands.notificationMatch(title = ".*", mode = MatchMode.MATCH_REGEX)).matches(untitled))
        assertFalse(NotificationCommands.Matcher(Commands.notificationMatch(text = "")).matches(untitled))
    }
}
