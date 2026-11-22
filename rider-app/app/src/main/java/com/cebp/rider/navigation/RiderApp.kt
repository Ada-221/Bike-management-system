package com.cebp.rider.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cebp.rider.data.RiderRepository
import com.cebp.rider.ui.screens.ActiveRentalRoute
import com.cebp.rider.ui.screens.RentalSummaryRoute
import com.cebp.rider.ui.screens.VehicleDetailsRoute
import com.cebp.rider.ui.screens.VehicleListRoute

private object Routes {
    const val VEHICLE_ID = "vehicleId"
    const val RENTAL_ID = "rentalId"

    const val VEHICLE_LIST = "vehicles"
    const val VEHICLE_DETAILS = "vehicles/{$VEHICLE_ID}"
    const val ACTIVE_RENTAL = "rentals/{$RENTAL_ID}/active"
    const val RENTAL_SUMMARY = "rentals/{$RENTAL_ID}/summary"

    fun vehicleDetails(vehicleId: String) = "vehicles/$vehicleId"
    fun activeRental(rentalId: String) = "rentals/$rentalId/active"
    fun rentalSummary(rentalId: String) = "rentals/$rentalId/summary"
}

/**
 * Rider flow: list -> details -> active ride -> summary -> list.
 *
 * The vehicle list stays at the bottom of the back stack, and each rental step replaces the
 * previous one, so Back never returns to a step that no longer applies, such as "Start rental"
 * during a ride. A ride left with Back is reopened from the banner on the vehicle list.
 */
@Composable
fun RiderApp(repository: RiderRepository) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.VEHICLE_LIST) {
        composable(Routes.VEHICLE_LIST) {
            VehicleListRoute(
                repository = repository,
                onVehicleClick = { navController.navigate(Routes.vehicleDetails(it)) },
                onResumeRental = { navController.navigate(Routes.activeRental(it)) },
            )
        }

        composable(
            route = Routes.VEHICLE_DETAILS,
            arguments = listOf(navArgument(Routes.VEHICLE_ID) { type = NavType.StringType }),
        ) { entry ->
            VehicleDetailsRoute(
                vehicleId = entry.arguments?.getString(Routes.VEHICLE_ID).orEmpty(),
                repository = repository,
                onBack = navController::popBackStack,
                onRentalStarted = { navController.replaceAboveList(Routes.activeRental(it)) },
                onOpenActiveRental = { navController.replaceAboveList(Routes.activeRental(it)) },
            )
        }

        composable(
            route = Routes.ACTIVE_RENTAL,
            arguments = listOf(navArgument(Routes.RENTAL_ID) { type = NavType.StringType }),
        ) { entry ->
            ActiveRentalRoute(
                rentalId = entry.arguments?.getString(Routes.RENTAL_ID).orEmpty(),
                repository = repository,
                onBack = navController::popBackStack,
                onRentalEnded = { navController.replaceAboveList(Routes.rentalSummary(it)) },
            )
        }

        composable(
            route = Routes.RENTAL_SUMMARY,
            arguments = listOf(navArgument(Routes.RENTAL_ID) { type = NavType.StringType }),
        ) { entry ->
            RentalSummaryRoute(
                rentalId = entry.arguments?.getString(Routes.RENTAL_ID).orEmpty(),
                repository = repository,
                onDone = navController::popBackStack,
            )
        }
    }
}

private fun NavHostController.replaceAboveList(route: String) {
    navigate(route) { popUpTo(Routes.VEHICLE_LIST) }
}
