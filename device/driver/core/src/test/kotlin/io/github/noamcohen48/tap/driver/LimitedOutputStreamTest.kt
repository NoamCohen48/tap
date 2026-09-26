package io.github.noamcohen48.tap.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LimitedOutputStreamTest {
    @Test
    fun keepsContentUpToTheLimit() {
        val output = LimitedOutputStream(4)
        output.write("ab".encodeToByteArray())
        output.write('c'.code)
        output.write('d'.code)

        assertEquals("abcd", output.content())
    }

    @Test
    fun refusesToGrowPastTheLimit() {
        val output = LimitedOutputStream(4)
        output.write("abc".encodeToByteArray())

        assertThrows(OutputLimitExceeded::class.java) { output.write("de".encodeToByteArray()) }
    }
}
