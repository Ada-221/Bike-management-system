package com.cebp.rider.ui.components

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

class DurationPartsTest {

    @Test
    fun `seconds only`() {
        assertEquals(DurationParts(0, 0, 42), durationParts(Duration.ofSeconds(42)))
    }

    @Test
    fun `minutes and seconds`() {
        assertEquals(DurationParts(0, 1, 36), durationParts(Duration.ofSeconds(96)))
    }

    @Test
    fun `hours, minutes and seconds`() {
        assertEquals(DurationParts(1, 5, 3), durationParts(Duration.ofSeconds(3903)))
    }

    @Test
    fun `fractions of a second are dropped`() {
        assertEquals(DurationParts(0, 0, 4), durationParts(Duration.ofMillis(4_999)))
    }

    @Test
    fun `a negative duration counts as zero`() {
        // Possible if the phone's and server's clocks disagree.
        assertEquals(DurationParts(0, 0, 0), durationParts(Duration.ofSeconds(-5)))
    }
}
