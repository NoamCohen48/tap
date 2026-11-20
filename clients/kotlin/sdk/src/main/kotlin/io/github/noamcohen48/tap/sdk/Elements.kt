package io.github.noamcohen48.tap.sdk

import io.github.noamcohen48.tap.api.v1.Snapshots
import kotlin.time.Duration

/** Lazy collection reads, not live nodes or enumeration of a virtualized dataset. */
class Elements internal constructor(
    val device: Device,
    val selector: Selector,
) {
    init {
        require(!selector.hasPick) { "elements does not accept first()/at()" }
    }

    /**
     * One read of current matches in accessibility order (window order, then children before
     * parents). Empty is valid. Not an atomic UI transaction. Staleness fails the whole read;
     * over 1000 matches / 1 MiB fails with PAYLOAD_TOO_LARGE, never silent truncation.
     */
    suspend fun snapshots(timeout: Duration = device.timeouts.action): List<ElementSnapshot> =
        device
            .executeOrThrow(timeout, selector) {
                snapshots = Snapshots.newBuilder().setSelector(selector.proto).build()
            }.snapshots.elementsList
            .map { it.toModel() }

    /** Raw texts from one [snapshots] call; retains nulls, duplicates and empty strings. */
    suspend fun texts(timeout: Duration = device.timeouts.action): List<String?> = snapshots(timeout).map { it.text }
}
