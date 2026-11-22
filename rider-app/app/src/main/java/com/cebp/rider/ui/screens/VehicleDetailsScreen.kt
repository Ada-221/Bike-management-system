package com.cebp.rider.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cebp.rider.R
import com.cebp.rider.data.ErrorCodes
import com.cebp.rider.data.RiderException
import com.cebp.rider.data.RiderRepository
import com.cebp.rider.model.Vehicle
import com.cebp.rider.ui.components.BatteryFreshness
import com.cebp.rider.ui.components.BatteryLevel
import com.cebp.rider.ui.components.LoadContent
import com.cebp.rider.ui.components.RiderScaffold
import com.cebp.rider.ui.components.labelRes
import com.cebp.rider.ui.components.messageRes
import com.cebp.rider.ui.components.notRentableReasonRes
import com.cebp.rider.ui.components.rememberLoad
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Refusals after which the vehicle shown on screen is probably out of date. */
private val VEHICLE_CHANGED_CODES = setOf(
    ErrorCodes.VEHICLE_NOT_AVAILABLE,
    ErrorCodes.BATTERY_TOO_LOW,
    ErrorCodes.NOT_FOUND,
)

@Composable
fun VehicleDetailsRoute(
    vehicleId: String,
    repository: RiderRepository,
    onBack: () -> Unit,
    onRentalStarted: (rentalId: String) -> Unit,
    onOpenActiveRental: (rentalId: String) -> Unit,
) {
    val load = rememberLoad(vehicleId) { repository.getVehicle(vehicleId) }
    val scope = rememberCoroutineScope()
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<RiderException?>(null) }
    // One key per attempt to rent. A retry after a network failure reuses it, so the backend
    // returns the rental it may already have created instead of starting a second one. After a
    // refusal nothing was created, so the next tap is a new request with a new key.
    var idempotencyKey by rememberSaveable(vehicleId) { mutableStateOf(UUID.randomUUID().toString()) }

    RiderScaffold(
        title = stringResource(R.string.vehicle_details_title),
        onBack = onBack,
    ) { padding ->
        LoadContent(load, padding) { vehicle ->
            if (vehicle == null) {
                DetailsMessage(stringResource(R.string.vehicle_not_found))
            } else {
                VehicleDetailsContent(
                    vehicle = vehicle,
                    startEnabled = vehicle.rentable && !submitting,
                    error = error,
                    onStartRental = {
                        submitting = true
                        error = null
                        // Main thread explicitly: navigating after the network reply must not run on the
                        // HTTP client's thread, whatever dispatcher the composition provides.
                        scope.launch(Dispatchers.Main.immediate) {
                            try {
                                onRentalStarted(repository.startRental(vehicle.id, idempotencyKey).id)
                            } catch (e: RiderException) {
                                error = e
                                submitting = false
                                if (e is RiderException.Rejected) {
                                    idempotencyKey = UUID.randomUUID().toString()
                                    // Show the vehicle's current status next to the refusal.
                                    if (e.code in VEHICLE_CHANGED_CODES) load.reload()
                                }
                            }
                        }
                    },
                    onGoToRide = {
                        scope.launch(Dispatchers.Main.immediate) {
                            try {
                                repository.getActiveRental()?.let { onOpenActiveRental(it.id) }
                            } catch (e: RiderException) {
                                error = e
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun VehicleDetailsContent(
    vehicle: Vehicle,
    startEnabled: Boolean,
    error: RiderException?,
    onStartRental: () -> Unit,
    onGoToRide: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(vehicle.label, style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(vehicle.type.labelRes))
        BatteryLevel(vehicle.batteryPercent)
        BatteryFreshness(vehicle.lastBatteryAt)
        Text(stringResource(R.string.vehicle_status, stringResource(vehicle.status.labelRes)))
        if (!vehicle.rentable) {
            Text(stringResource(vehicle.notRentableReasonRes))
        }
        if (error != null) {
            Text(stringResource(error.messageRes), color = MaterialTheme.colorScheme.error)
            if (error is RiderException.Rejected && error.code == ErrorCodes.RIDER_HAS_ACTIVE_RENTAL) {
                OutlinedButton(onClick = onGoToRide, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.go_to_my_ride))
                }
            }
        }
        Button(
            onClick = onStartRental,
            enabled = startEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.rental_start))
        }
    }
}

/** A message in a scrollable column, so pull-to-refresh still works. */
@Composable
internal fun DetailsMessage(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(message)
    }
}
