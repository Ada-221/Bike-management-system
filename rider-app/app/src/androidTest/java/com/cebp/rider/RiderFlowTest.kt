package com.cebp.rider

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cebp.rider.data.FakeRiderRepository
import com.cebp.rider.navigation.RiderApp
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RiderFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeRiderRepository()

    @Before
    fun setUp() {
        compose.setContent {
            MaterialTheme {
                RiderApp(repository = repository)
            }
        }
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
    }

    private fun assertShown(text: String) {
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun assertGone(text: String) {
        compose.onNodeWithText(text).assertDoesNotExist()
    }

    @Test
    fun riderCompletesTheFullRentalFlow() {
        assertShown("Available vehicles")
        click("BIKE-001")

        assertShown("Vehicle details")
        assertShown("Battery: 92%")
        click("Start rental")

        assertShown("Active ride")
        click("Return vehicle")

        assertShown("Ride summary")
        assertShown("Vehicle: BIKE-001")
        click("Done")

        assertShown("Available vehicles")
        assertShown("BIKE-001")
        assertGone("Ride in progress on BIKE-001")
    }

    @Test
    fun backFromDetailsReturnsToTheList() {
        click("BIKE-001")

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        assertShown("Available vehicles")
    }

    @Test
    fun backDuringARideSkipsDetailsAndTheRideCanBeResumed() {
        click("BIKE-001")
        click("Start rental")

        Espresso.pressBack()
        compose.waitForIdle()

        assertShown("Available vehicles")
        assertShown("Ride in progress on BIKE-001")

        click("Resume ride")
        assertShown("Active ride")
    }

    @Test
    fun backFromTheSummaryDoesNotReturnToTheEndedRide() {
        click("BIKE-001")
        click("Start rental")
        click("Return vehicle")

        Espresso.pressBack()
        compose.waitForIdle()

        assertShown("Available vehicles")
    }

    @Test
    fun aSecondRentalIsRefusedDuringARide() {
        click("BIKE-001")
        click("Start rental")
        Espresso.pressBack()
        compose.waitForIdle()

        click("BIKE-002")
        click("Start rental")

        assertShown("Return BIKE-001 before starting another ride.")
    }

    @Test
    fun rentedVehicleLeavesTheListUntilItIsReturned() {
        click("SCOOTER-001")
        click("Start rental")
        Espresso.pressBack()
        compose.waitForIdle()

        assertGone("SCOOTER-001")

        click("Resume ride")
        click("Return vehicle")
        click("Done")

        assertShown("SCOOTER-001")
    }

    @Test
    fun vehiclesInMaintenanceAreNotListed() {
        assertShown("BIKE-001")
        assertGone("SCOOTER-003")
    }
}
