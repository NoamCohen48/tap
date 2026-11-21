package io.github.noamcohen48.tap.daemon.core

import io.github.noamcohen48.tap.api.v1.LogEntry
import io.github.noamcohen48.tap.daemon.storage.StoredEntry

/** A device log is one payload in private retention, never an envelope for SDK calls. */
internal fun LogEntry.stored(): StoredEntry {
    val result =
        StoredEntry
            .newBuilder()
            .setCursor(cursor)
            .setHostTimeMs(hostTimeMs)
            .setHostMonotonicNs(hostMonotonicNs)
    when {
        hasLine() -> result.setLine(line)
        hasAction() -> result.setAction(action)
        hasMarker() -> result.setMarker(marker)
        hasGap() -> result.setGap(gap)
        else -> error("not a device-log entry")
    }
    return result.build()
}

/** Connection calls/deaths cannot accidentally escape through the live device-log RPC. */
internal fun StoredEntry.deviceLog(): LogEntry? {
    val result =
        LogEntry
            .newBuilder()
            .setCursor(cursor)
            .setHostTimeMs(hostTimeMs)
            .setHostMonotonicNs(hostMonotonicNs)
    when {
        hasLine() -> result.setLine(line)
        hasAction() -> result.setAction(action)
        hasMarker() -> result.setMarker(marker)
        hasGap() -> result.setGap(gap)
        else -> return null
    }
    return result.build()
}
