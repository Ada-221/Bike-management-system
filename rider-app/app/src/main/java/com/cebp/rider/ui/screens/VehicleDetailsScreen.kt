package com.cebp.rider.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cebp.rider.R
import com.cebp.rider.data.RentalException
import com.cebp.rider.data.RiderRepository
import com.cebp.rider.model.Vehicle
import com.cebp.rider.model.VehicleStatus
import com.cebp.rider.ui.components.CenteredMessage
import com.cebp.rider.ui.components.LoadState
import com.cebp.rider.ui.components.LoadingIndicator
import com.cebp.rider.ui.components.RiderScaffold
import com.cebp.rider.ui.components.labelRes
import com.cebp.rider.ui.components.rememberLoad
import kotlinx.coroutines.launch

@Composable
fun VehicleDetailsRoute(
    vehicleId: String,
    repository: RiderRepository,
    onBack: () -> Unit,
    onRentalStarted: (rentalId: String) -> Unit,
) {
    val state = rememberLoad(vehicleId) { repository.getVehicle(vehicleId) }
    val scope = rememberCoroutineScope()
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    RiderScaffold(
        title = stringResource(R.string.vehicle_details_title),
        onBack = onBack,
    ) { padding ->
        when (state) {
            LoadState.Loading -> LoadingIndicator(padding)
            is LoadState.Ready -> {
                val vehicle = state.value
                if (vehicle == null) {
                    CenteredMessage(stringResource(R.string.vehicle_not_found), padding)
                } else {
                    VehicleDetailsContent(
                        vehicle = vehicle,
                        padding = padding,
                        startEnabled = vehicle.status == VehicleStatus.AVAILABLE && !submitting,
                        error = error,
                        onStartRental = {
                            submitting = true
                            error = null
                            scope.launch {
                                try {
                                    onRentalStarted(repository.startRental(vehicle.id).id)
                                } catch (e: RentalException) {
                                    error = e.message
                                    submitting = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VehicleDetailsContent(
    vehicle: Vehicle,
    padding: PaddingValues,
    startEnabled: Boolean,
    error: String?,
    onStartRental: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(vehicle.id, style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(vehicle.type.labelRes))
        Text(stringResource(R.string.vehicle_battery, vehicle.batteryPercent))
        Text(stringResource(R.string.vehicle_area, vehicle.area))
        Text(stringResource(R.string.vehicle_status, stringResource(vehicle.status.labelRes)))
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error)
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
