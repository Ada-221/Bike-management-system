package com.cebp.rider

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cebp.rider.data.FakeRiderRepository
import com.cebp.rider.data.RiderException
import com.cebp.rider.data.RiderRepository
import com.cebp.rider.model.GeoPoint
import com.cebp.rider.model.Vehicle
import com.cebp.rider.navigation.RiderApp
import java.io.IOException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RiderFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private fun launch(repository: RiderRepository = FakeRiderRepository()) {
        compose.setContent {
            MaterialTheme {
                RiderApp(repository = repository)
            }
        }
        compose.waitForIdle()
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

    private fun pressBack() {
        Espresso.pressBack()
        compose.waitForIdle()
    }

    @Test
    fun riderCompletesTheFullRentalFlow() {
        launch()
        assertShown("Available vehicles")
        click("EB-001")

        assertShown("Vehicle details")
        assertShown("Battery: 92%")
        click("Start rental")

        assertShown("Active ride")
        assertShown("Vehicle: EB-001")
        click("Return vehicle")

        assertShown("Ride summary")
        assertShown("Vehicle: EB-001")
        assertShown("Battery: 92% → 92%")
        click("Done")

        assertShown("Available vehicles")
        assertShown("EB-001")
        assertGone("Ride in progress on EB-001")
    }

    @Test
    fun backFromDetailsReturnsToTheList() {
        launch()
        click("EB-001")

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        assertShown("Available vehicles")
    }

    @Test
    fun backDuringARideSkipsDetailsAndTheRideCanBeResumed() {
        launch()
        click("EB-001")
        click("Start rental")

        pressBack()

        assertShown("Available vehicles")
        assertShown("Ride in progress on EB-001")

        click("Resume ride")
        assertShown("Active ride")
    }

    @Test
    fun backFromTheSummaryDoesNotReturnToTheEndedRide() {
        launch()
        click("EB-001")
        click("Start rental")
        click("Return vehicle")

        pressBack()

        assertShown("Available vehicles")
    }

    @Test
    fun aSecondRentalIsRefusedDuringARide() {
        launch()
        click("EB-001")
        click("Start rental")
        pressBack()

        click("EB-002")
        click("Start rental")

        assertShown("You already have a ride in progress. Return that vehicle first.")
    }

    @Test
    fun rentedVehicleLeavesTheListUntilItIsReturned() {
        launch()
        click("ES-001")
        click("Start rental")
        pressBack()

        assertGone("ES-001")

        click("Resume ride")
        click("Return vehicle")
        click("Done")

        assertShown("ES-001")
    }

    @Test
    fun onlyRentableNearbyVehiclesAreListed() {
        launch()
        assertShown("EB-001")
        assertGone("ES-002") // battery 18%
        assertGone("ES-003") // needs maintenance
        assertGone("EB-003") // outside the search radius
    }

    @Test
    fun aVehicleThatCannotBeRentedCannotBeStarted() {
        val repository = FakeRiderRepository()
        // Nearby never lists ES-002 (18% battery), so this list includes it to reach its details.
        launch(object : RiderRepository by repository {
            override suspend fun getNearbyVehicles(position: GeoPoint): List<Vehicle> =
                listOfNotNull(repository.getVehicle("veh_004"))
        })
        click("ES-002")

        assertShown(
            "This vehicle can't be rented right now. " +
                "Vehicles need to be available with at least 20% battery.",
        )
        compose.onNodeWithText("Start rental").assertIsNotEnabled()
    }

    @Test
    fun aNetworkFailureShowsAnErrorAndRetryRecovers() {
        val repository = FakeRiderRepository()
        var failuresLeft = 1
        launch(object : RiderRepository by repository {
            override suspend fun getNearbyVehicles(position: GeoPoint): List<Vehicle> {
                if (failuresLeft-- > 0) throw RiderException.Unreachable(IOException("offline"))
                return repository.getNearbyVehicles(position)
            }
        })

        assertShown("Can't reach the server. Check your connection and try again.")
        click("Try again")

        assertShown("EB-001")
    }
}
