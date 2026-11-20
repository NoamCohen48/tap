package io.github.noamcohen48.tap.driver

import android.app.Notification
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import io.github.noamcohen48.tap.api.v1.ContentChange
import io.github.noamcohen48.tap.api.v1.ContentChanged
import io.github.noamcohen48.tap.api.v1.DeviceEvent
import io.github.noamcohen48.tap.api.v1.DeviceEventType
import io.github.noamcohen48.tap.api.v1.TextChanged
import io.github.noamcohen48.tap.api.v1.ToastShown
import io.github.noamcohen48.tap.api.v1.ViewClicked
import io.github.noamcohen48.tap.api.v1.ViewScrolled
import io.github.noamcohen48.tap.api.v1.WindowChange
import io.github.noamcohen48.tap.api.v1.WindowChanged
import io.github.noamcohen48.tap.protocol.DEVICE_EVENT_CLASS_CHARS
import io.github.noamcohen48.tap.protocol.DEVICE_EVENT_TEXT_CHARS

/**
 * Turns UiAutomation's accessibility events into typed `tap.v1.DeviceEvent`s for the
 * connection's [sink], only for the types the host's filter names ([forwarded]; none until it
 * sends one). Copies scalar data only: never retains an AccessibilityEvent or its source node.
 */
internal class AccessibilityEvents {
    @Volatile private var sink: ((DeviceEvent) -> Unit)? = null

    @Volatile var forwarded: Set<DeviceEventType> = emptySet()

    private var sequence = 0L

    /** Starts a connection's delivery with nothing forwarded; null stops it. */
    fun attach(target: ((DeviceEvent) -> Unit)?) {
        forwarded = emptySet()
        sink = target
    }

    @Synchronized
    fun onEvent(event: AccessibilityEvent) {
        val target = sink ?: return
        val type = typeOf(event) ?: return
        if (type !in forwarded) return
        val built = DeviceEvent.newBuilder()
        when (type) {
            DeviceEventType.DEVICE_EVENT_TOAST -> built.setToastShown(ToastShown.newBuilder().setText(event.joinedText("")))
            DeviceEventType.DEVICE_EVENT_WINDOW_CHANGED -> built.setWindowChanged(windowChanged(event) ?: return)
            DeviceEventType.DEVICE_EVENT_CONTENT_CHANGED -> built.setContentChanged(contentChanged(event))
            DeviceEventType.DEVICE_EVENT_VIEW_CLICKED -> built.setViewClicked(viewClicked(event))
            DeviceEventType.DEVICE_EVENT_VIEW_SCROLLED -> built.setViewScrolled(viewScrolled(event))
            DeviceEventType.DEVICE_EVENT_TEXT_CHANGED -> built.setTextChanged(textChanged(event))
            DeviceEventType.DEVICE_EVENT_TYPE_UNSPECIFIED, DeviceEventType.UNRECOGNIZED -> return
        }
        target(
            built
                .setSequence(++sequence)
                .setUptimeMs(event.eventTime)
                .setPackageName(event.packageName.cut(DEVICE_EVENT_CLASS_CHARS))
                .build(),
        )
    }

    private fun typeOf(event: AccessibilityEvent): DeviceEventType? =
        when (event.eventType) {
            // A notification raises the same type with its Notification as parcelable data.
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (event.parcelableData is Notification) null else DeviceEventType.DEVICE_EVENT_TOAST
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                DeviceEventType.DEVICE_EVENT_WINDOW_CHANGED
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> DeviceEventType.DEVICE_EVENT_CONTENT_CHANGED
            AccessibilityEvent.TYPE_VIEW_CLICKED -> DeviceEventType.DEVICE_EVENT_VIEW_CLICKED
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> DeviceEventType.DEVICE_EVENT_VIEW_SCROLLED
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> DeviceEventType.DEVICE_EVENT_TEXT_CHANGED
            else -> null
        }

    /** Null for a TYPE_WINDOWS_CHANGED below API 28, which says nothing about what changed. */
    private fun windowChanged(event: AccessibilityEvent): WindowChanged? {
        val changes =
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                listOf(WindowChange.WINDOW_CHANGE_STATE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                flags(event.windowChanges, WINDOW_CHANGES)
            } else {
                return null
            }
        if (changes.isEmpty()) return null
        return WindowChanged
            .newBuilder()
            .setClassName(event.className.cut(DEVICE_EVENT_CLASS_CHARS))
            .setTitle(event.joinedText(" "))
            .setWindowId(event.windowId)
            .addAllChanges(changes)
            .build()
    }

    private fun contentChanged(event: AccessibilityEvent): ContentChanged =
        ContentChanged
            .newBuilder()
            .setClassName(event.className.cut(DEVICE_EVENT_CLASS_CHARS))
            .setWindowId(event.windowId)
            .addAllChanges(flags(event.contentChangeTypes, CONTENT_CHANGES))
            .build()

    private fun viewClicked(event: AccessibilityEvent): ViewClicked =
        ViewClicked
            .newBuilder()
            .setClassName(event.className.cut(DEVICE_EVENT_CLASS_CHARS))
            .setLabel(event.joinedText(" "))
            .setWindowId(event.windowId)
            .build()

    private fun viewScrolled(event: AccessibilityEvent): ViewScrolled =
        ViewScrolled
            .newBuilder()
            .setClassName(event.className.cut(DEVICE_EVENT_CLASS_CHARS))
            .setWindowId(event.windowId)
            .apply {
                event.scrollX.defined()?.let(::setScrollX)
                event.scrollY.defined()?.let(::setScrollY)
                event.maxScrollX.defined()?.let(::setMaxScrollX)
                event.maxScrollY.defined()?.let(::setMaxScrollY)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    event.scrollDeltaX.defined()?.let(::setDeltaX)
                    event.scrollDeltaY.defined()?.let(::setDeltaY)
                }
                event.fromIndex.defined()?.let(::setFromIndex)
                event.toIndex.defined()?.let(::setToIndex)
                event.itemCount.defined()?.let(::setItemCount)
            }.build()

    private fun textChanged(event: AccessibilityEvent): TextChanged =
        TextChanged
            .newBuilder()
            .setClassName(event.className.cut(DEVICE_EVENT_CLASS_CHARS))
            .setText(event.joinedText(""))
            .setBeforeText(event.beforeText.cut(DEVICE_EVENT_TEXT_CHARS))
            .setPassword(event.isPassword)
            .setWindowId(event.windowId)
            .apply {
                event.fromIndex.defined()?.let(::setFromIndex)
                event.addedCount.defined()?.let(::setAddedCount)
                event.removedCount.defined()?.let(::setRemovedCount)
            }.build()

    private companion object {
        /** AccessibilityRecord's "not set" value for indices, counts and deltas. */
        const val UNDEFINED = -1

        val WINDOW_CHANGES =
            listOf(
                AccessibilityEvent.WINDOWS_CHANGE_ADDED to WindowChange.WINDOW_CHANGE_ADDED,
                AccessibilityEvent.WINDOWS_CHANGE_REMOVED to WindowChange.WINDOW_CHANGE_REMOVED,
                AccessibilityEvent.WINDOWS_CHANGE_TITLE to WindowChange.WINDOW_CHANGE_TITLE,
                AccessibilityEvent.WINDOWS_CHANGE_BOUNDS to WindowChange.WINDOW_CHANGE_BOUNDS,
                AccessibilityEvent.WINDOWS_CHANGE_LAYER to WindowChange.WINDOW_CHANGE_LAYER,
                AccessibilityEvent.WINDOWS_CHANGE_ACTIVE to WindowChange.WINDOW_CHANGE_ACTIVE,
                AccessibilityEvent.WINDOWS_CHANGE_FOCUSED to WindowChange.WINDOW_CHANGE_FOCUSED,
                AccessibilityEvent.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED to WindowChange.WINDOW_CHANGE_ACCESSIBILITY_FOCUSED,
                AccessibilityEvent.WINDOWS_CHANGE_PARENT to WindowChange.WINDOW_CHANGE_PARENT,
                AccessibilityEvent.WINDOWS_CHANGE_CHILDREN to WindowChange.WINDOW_CHANGE_CHILDREN,
                AccessibilityEvent.WINDOWS_CHANGE_PIP to WindowChange.WINDOW_CHANGE_PIP,
            )

        // The constants are compile-time ints: reading them on an older device is safe, and a
        // flag that device never sets simply never matches.
        val CONTENT_CHANGES =
            listOf(
                AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE to ContentChange.CONTENT_CHANGE_SUBTREE,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT to ContentChange.CONTENT_CHANGE_TEXT,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION to ContentChange.CONTENT_CHANGE_CONTENT_DESCRIPTION,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION to ContentChange.CONTENT_CHANGE_STATE_DESCRIPTION,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE to ContentChange.CONTENT_CHANGE_PANE_TITLE,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED to ContentChange.CONTENT_CHANGE_PANE_APPEARED,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED to ContentChange.CONTENT_CHANGE_PANE_DISAPPEARED,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_DRAG_STARTED to ContentChange.CONTENT_CHANGE_DRAG_STARTED,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_DRAG_DROPPED to ContentChange.CONTENT_CHANGE_DRAG_DROPPED,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_DRAG_CANCELLED to ContentChange.CONTENT_CHANGE_DRAG_CANCELLED,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_INVALID to ContentChange.CONTENT_CHANGE_CONTENT_INVALID,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_ERROR to ContentChange.CONTENT_CHANGE_ERROR,
                AccessibilityEvent.CONTENT_CHANGE_TYPE_ENABLED to ContentChange.CONTENT_CHANGE_ENABLED,
            )

        fun <T> flags(
            bits: Int,
            table: List<Pair<Int, T>>,
        ): List<T> = table.filter { (bit, _) -> bits and bit != 0 }.map { it.second }

        fun Int.defined(): Int? = takeIf { it != UNDEFINED }

        fun CharSequence?.cut(chars: Int): String = this?.toString().orEmpty().take(chars)

        fun AccessibilityEvent.joinedText(separator: String): String =
            text.joinToString(separator).take(DEVICE_EVENT_TEXT_CHARS)
    }
}
