package com.cebp.rider.ui.components

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgeTest {

    @Test
    fun `under a minute is just now`() {
        assertEquals(Age.JustNow, ageOf(Duration.ofSeconds(59)))
    }

    @Test
    fun `a negative age, from clocks that disagree, is just now`() {
        assertEquals(Age.JustNow, ageOf(Duration.ofSeconds(-30)))
    }

    @Test
    fun `minutes are rounded down`() {
        assertEquals(Age.Minutes(3), ageOf(Duration.ofSeconds(3 * 60 + 59)))
    }

    @Test
    fun `hours and days`() {
        assertEquals(Age.Hours(2), ageOf(Duration.ofMinutes(150)))
        assertEquals(Age.Days(1), ageOf(Duration.ofHours(30)))
    }

    @Test
    fun `a battery reading becomes stale after 15 minutes`() {
        assertFalse(Duration.ofMinutes(15) > STALE_BATTERY_AFTER)
        assertTrue(Duration.ofMinutes(16) > STALE_BATTERY_AFTER)
    }
}
