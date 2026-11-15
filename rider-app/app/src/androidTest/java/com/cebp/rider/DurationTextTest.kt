package com.cebp.rider

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cebp.rider.ui.components.durationText
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the wording, which comes from the app's string and plural resources. */
@RunWith(AndroidJUnit4::class)
class DurationTextTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun formatsRideLengths() {
        val cases = mapOf(
            0L to "0 seconds",
            1L to "1 second",
            42L to "42 seconds",
            60L to "1 minute",
            96L to "1 minute and 36 seconds",
            125L to "2 minutes and 5 seconds",
            3600L to "1 hour",
            3660L to "1 hour and 1 minute",
            3903L to "1 hour, 5 minutes and 3 seconds",
            7322L to "2 hours, 2 minutes and 2 seconds",
        )
        compose.setContent {
            Column {
                cases.keys.forEach { seconds -> Text(durationText(Duration.ofSeconds(seconds))) }
            }
        }
        cases.values.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }
}
