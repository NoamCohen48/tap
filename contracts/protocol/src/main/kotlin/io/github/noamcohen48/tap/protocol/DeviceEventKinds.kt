package io.github.noamcohen48.tap.protocol

import io.github.noamcohen48.tap.api.v1.DeviceEvent
import io.github.noamcohen48.tap.api.v1.DeviceEventType

/** The subscription type an event's `kind` case belongs to; UNSPECIFIED for an event with no kind. */
val DeviceEvent.eventType: DeviceEventType
    get() =
        when (kindCase) {
            DeviceEvent.KindCase.TOAST_SHOWN -> DeviceEventType.DEVICE_EVENT_TOAST
            DeviceEvent.KindCase.WINDOW_CHANGED -> DeviceEventType.DEVICE_EVENT_WINDOW_CHANGED
            DeviceEvent.KindCase.CONTENT_CHANGED -> DeviceEventType.DEVICE_EVENT_CONTENT_CHANGED
            DeviceEvent.KindCase.VIEW_CLICKED -> DeviceEventType.DEVICE_EVENT_VIEW_CLICKED
            DeviceEvent.KindCase.VIEW_SCROLLED -> DeviceEventType.DEVICE_EVENT_VIEW_SCROLLED
            DeviceEvent.KindCase.TEXT_CHANGED -> DeviceEventType.DEVICE_EVENT_TEXT_CHANGED
            DeviceEvent.KindCase.KIND_NOT_SET, null -> DeviceEventType.DEVICE_EVENT_TYPE_UNSPECIFIED
        }

/** Longest text a device event field carries; longer text is cut. */
const val DEVICE_EVENT_TEXT_CHARS = 512

/** Longest class name a device event carries. */
const val DEVICE_EVENT_CLASS_CHARS = 256
