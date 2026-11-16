package io.github.noamcohen48.tap.driver

import io.github.noamcohen48.tap.api.v1.ElementSnapshot
import io.github.noamcohen48.tap.api.v1.ErrorCode
import io.github.noamcohen48.tap.protocol.CommandFailure
import io.github.noamcohen48.tap.protocol.MAX_SNAPSHOT_LIST_BYTES
import io.github.noamcohen48.tap.protocol.MAX_SNAPSHOT_MATCHES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BulkSnapshotsTest {
    @Test fun valuesKeepOrderAndEveryObjectIsRecycled() {
        val recycled = mutableListOf<Int>()
        val reads = mutableListOf<Int>()
        val result =
            bulkSnapshots(listOf(3, 1, 2), {
                reads += it
                ElementSnapshot
                    .newBuilder()
                    .setText("$it")
                    .setCheckable(true)
                    .setChecked(it == 1)
                    .build()
            }, recycled::add, {})
        assertEquals(listOf(3, 1, 2), reads)
        assertEquals(listOf("3", "1", "2"), result.elementsList.map { it.text })
        assertEquals(listOf(false, true, false), result.elementsList.map { it.checked })
        assertEquals(reads, recycled)
        assertEquals(0, bulkSnapshots(emptyList<Int>(), { error("no reads") }, {}, {}).elementsCount)
    }

    @Test fun aFailedReadReturnsNothingAndRecyclesEvenUnreadObjects() {
        val recycled = mutableListOf<Int>()
        val stale = IllegalStateException("stale node")
        val thrown =
            assertThrows(IllegalStateException::class.java) {
                bulkSnapshots(listOf(1, 2, 3), {
                    if (it == 2) throw stale
                    ElementSnapshot.getDefaultInstance()
                }, recycled::add, {})
            }
        assertEquals(stale, thrown)
        assertEquals(listOf(1, 2, 3), recycled)
    }

    @Test fun cancellationRecyclesTheWholeMatchSet() {
        val recycled = mutableListOf<Int>()
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            bulkSnapshots(listOf(1, 2, 3), { ElementSnapshot.getDefaultInstance() }, recycled::add, {
                if (++checks == 3) error("cancelled")
            })
        }
        assertEquals(listOf(1, 2, 3), recycled)
    }

    @Test fun matchOverflowFailsInsteadOfTruncating() {
        val recycled = mutableListOf<Int>()
        val error =
            assertThrows(CommandFailure::class.java) {
                bulkSnapshots((0..MAX_SNAPSHOT_MATCHES).toList(), { error("must not read overflowing set") }, recycled::add, {})
            }
        assertEquals(ErrorCode.ERR_PAYLOAD_TOO_LARGE, error.code)
        assertEquals(MAX_SNAPSHOT_MATCHES + 1, recycled.size)
        assertEquals(
            MAX_SNAPSHOT_MATCHES,
            bulkSnapshots((1..MAX_SNAPSHOT_MATCHES).toList(), {
                ElementSnapshot.getDefaultInstance()
            }, {}, {}).elementsCount,
        )
    }

    @Test fun byteLimitIncludesRepeatedFieldFramingAndRecyclesOnOverflow() {
        // Three bytes for the text length, one tag; three for the enclosing length and one tag.
        val exact = ElementSnapshot.newBuilder().setText("a".repeat(MAX_SNAPSHOT_LIST_BYTES - 8)).build()
        val result = bulkSnapshots(listOf(exact), { it }, {}, {})
        assertEquals(MAX_SNAPSHOT_LIST_BYTES, result.serializedSize)
        val recycled = mutableListOf<ElementSnapshot>()
        val error =
            assertThrows(CommandFailure::class.java) {
                bulkSnapshots(listOf(exact, ElementSnapshot.getDefaultInstance()), { it }, recycled::add, {})
            }
        assertEquals(ErrorCode.ERR_PAYLOAD_TOO_LARGE, error.code)
        assertEquals(2, recycled.size)
    }
}
