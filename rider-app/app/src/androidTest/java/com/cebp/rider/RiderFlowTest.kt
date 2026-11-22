package com.cebp.rider

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cebp.rider.data.FakeRiderRepository
import com.cebp.rider.data.RiderException
import com.cebp.rider.data.RiderRepository
import com.cebp.rider.model.GeoPoint
import com.cebp.rider.model.Vehicle
import com.cebp.rider.navigation.RiderApp
import java.io.IOException
import kotlinx.coroutines.runBlocking
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

    /**
     * Waits up to 5 seconds for [text], then checks it is on screen. A refresh the rider asks for
     * shows its result after at least half a second, so it is not there immediately.
     */
    private fun assertShown(text: String) {
        runCatching {
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun assertGone(text: String) {
        compose.onNodeWithText(text).assertDoesNotExist()
    }

    private fun pressBack() {
        Espresso.pressBack()
        compose.waitForIdle()
    }

    // ---------------------------------------------------------------- Rental and return flow

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
    fun aFaultDuringARideShowsAWarningAndTheRideCanStillBeReturned() {
        val repository = FakeRiderRepository()
        launch(repository)
        click("EB-001")
        click("Start rental")
        pressBack()

        runBlocking { repository.reportFault("veh_001") }
        click("Resume ride")

        assertShown("This vehicle reported a problem")
        click("Return vehicle")
        assertShown("Ride summary")
    }

    // ---------------------------------------------------------------- Nearby discovery

    @Test
    fun onlyRentableNearbyVehiclesAreListed() {
        launch()
        assertShown("EB-001")
        assertGone("ES-002") // battery 18%, waiting in maintenance
        assertGone("ES-003") // fault
        assertGone("EB-003") // about 1.5 km away, outside the default 1 km
    }

    @Test
    fun theTypeFilterShowsOnlyThatType() {
        launch()
        click("E-scooters")

        assertShown("ES-001")
        assertGone("EB-001")
        assertGone("EB-002")

        click("All")
        assertShown("EB-001")
    }

    @Test
    fun aLargerRadiusFindsFartherVehicles() {
        launch()
        assertGone("EB-003")

        click("2 km")

        assertShown("EB-003")
    }

    @Test
    fun aSmallRadiusWithNoMatchExplainsWhatToTry() {
        launch()
        click("500 m")
        click("E-scooters")
        assertShown("ES-001")

        // ES-001 is the only scooter; once it is rented, nothing matches.
        click("ES-001")
        click("Start rental")
        pressBack()

        assertShown(
            "No matching vehicles within 500 m. Try a larger radius or another vehicle type.",
        )
    }

    // ---------------------------------------------------------------- Battery and status

    @Test
    fun detailsShowWhenTheBatteryWasMeasured() {
        launch()
        click("EB-001")

        assertShown("Battery measured 2 minutes ago")
    }

    @Test
    fun anOldBatteryReadingIsFlagged() {
        launch()
        click("EB-002")

        assertShown("Battery measured 40 minutes ago, so it may be out of date")
    }

    @Test
    fun aVehicleThatCannotBeRentedExplainsWhyAndCannotBeStarted() {
        val repository = FakeRiderRepository()
        // Nearby never lists ES-002 (18% battery), so this list includes it to reach its details.
        launch(object : RiderRepository by repository {
            override suspend fun getNearbyVehicles(position: GeoPoint, radiusMeters: Int): List<Vehicle> =
                listOfNotNull(repository.getVehicle("veh_004"))
        })
        click("ES-002")

        assertShown("Status: Needs maintenance")
        assertShown("The battery is below 20%, so this vehicle can't be rented until it's charged.")
        compose.onNodeWithText("Start rental").assertIsNotEnabled()
    }

    // ---------------------------------------------------------------- Rental errors

    @Test
    fun aSecondRentalIsRefusedAndOffersTheRideInProgress() {
        launch()
        click("EB-001")
        click("Start rental")
        pressBack()

        click("EB-002")
        click("Start rental")

        assertShown("You already have a ride in progress. Return that vehicle first.")
        click("Go to my ride")
        assertShown("Active ride")
        assertShown("Vehicle: EB-001")
    }

    @Test
    fun aVehicleThatChangedMeanwhileIsRefusedAndShownAsItIsNow() {
        val repository = FakeRiderRepository()
        launch(repository)
        click("EB-002")
        assertShown("Status: Available")

        // The vehicle reports a fault after the rider opened its details.
        runBlocking { repository.reportFault("veh_002") }
        click("Start rental")

        assertShown("This vehicle was just taken or needs maintenance. Pick another one.")
        assertShown("Status: Needs maintenance")
        compose.onNodeWithText("Start rental").assertIsNotEnabled()
    }

    @Test
    fun aNetworkFailureShowsAnErrorAndRetryRecovers() {
        val repository = FakeRiderRepository()
        var failuresLeft = 1
        launch(object : RiderRepository by repository {
            override suspend fun getNearbyVehicles(position: GeoPoint, radiusMeters: Int): List<Vehicle> {
                if (failuresLeft-- > 0) throw RiderException.Unreachable(IOException("offline"))
                return repository.getNearbyVehicles(position, radiusMeters)
            }
        })

        assertShown("Can't reach the server. Check your connection and try again.")
        click("Try again")

        assertShown("EB-001")
    }

    @Test
    fun aFailedRefreshKeepsTheListAndSaysSo() {
        val repository = FakeRiderRepository()
        var calls = 0
        launch(object : RiderRepository by repository {
            override suspend fun getNearbyVehicles(position: GeoPoint, radiusMeters: Int): List<Vehicle> {
                if (calls++ > 0) throw RiderException.Unreachable(IOException("offline"))
                return repository.getNearbyVehicles(position, radiusMeters)
            }
        })
        assertShown("EB-001")

        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertShown("Couldn't update. Can't reach the server. Check your connection and try again.")
        assertShown("EB-001")
    }
}
