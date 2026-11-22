package com.cebp.rider.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cebp.rider.R
import com.cebp.rider.data.RiderException
import com.cebp.rider.data.RiderLocation
import com.cebp.rider.data.RiderRepository
import com.cebp.rider.model.RentalStatus
import com.cebp.rider.model.VehicleStatus
import com.cebp.rider.ui.components.AUTO_REFRESH_MILLIS
import com.cebp.rider.ui.components.BatteryFreshness
import com.cebp.rider.ui.components.BatteryLevel
import com.cebp.rider.ui.components.LoadContent
import com.cebp.rider.ui.components.RiderScaffold
import com.cebp.rider.ui.components.durationText
import com.cebp.rider.ui.components.messageRes
import com.cebp.rider.ui.components.rememberLoad
import com.cebp.rider.ui.components.toLocalTimeText
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ActiveRentalRoute(
    rentalId: String,
    repository: RiderRepository,
    onBack: () -> Unit,
    onRentalEnded: (rentalId: String) -> Unit,
) {
    // Refreshes on its own, so the vehicle's battery and any fault stay current during the ride.
    val load = rememberLoad(rentalId, autoRefreshMillis = AUTO_REFRESH_MILLIS) {
        repository.getRentalWithVehicle(rentalId)
    }
    val scope = rememberCoroutineScope()
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<RiderException?>(null) }

    RiderScaffold(
        title = stringResource(R.string.rental_active_title),
        onBack = onBack,
    ) { padding ->
        LoadContent(load, padding) { loaded ->
            when {
                loaded == null -> DetailsMessage(stringResource(R.string.rental_not_found))
                // For example returned from another device, or the screen was out of date.
                loaded.rental.status == RentalStatus.COMPLETED -> RideAlreadyEnded(
                    onSeeSummary = { onRentalEnded(loaded.rental.id) },
                )
                else -> ActiveRideContent(
                    loaded = loaded,
                    submitting = submitting,
                    error = error,
                    onReturn = {
                        submitting = true
                        error = null
                        // Main thread explicitly, as in VehicleDetailsScreen: navigation follows the reply.
                        scope.launch(Dispatchers.Main.immediate) {
                            try {
                                // Returning is idempotent, so a retry after a network failure is safe.
                                onRentalEnded(
                                    repository.endRental(loaded.rental.id, RiderLocation.current).id,
                                )
                            } catch (e: RiderException) {
                                error = e
                                submitting = false
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ActiveRideContent(
    loaded: RentalWithVehicle,
    submitting: Boolean,
    error: RiderException?,
    onReturn: () -> Unit,
) {
    val rental = loaded.rental
    val vehicle = loaded.vehicle
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.rental_vehicle, loaded.vehicleLabel),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(stringResource(R.string.rental_started_at, rental.startedAt.toLocalTimeText()))
        RideTime(rental.startedAt)
        if (vehicle != null) {
            BatteryLevel(vehicle.batteryPercent)
            BatteryFreshness(vehicle.lastBatteryAt)
        } else {
            BatteryLevel(rental.startBatteryPercent)
        }
        // A fault sets the vehicle to maintenance during the ride; the ride stays returnable.
        if (vehicle?.status == VehicleStatus.MAINTENANCE_REQUIRED) {
            FaultWarning()
        }
        error?.let {
            Text(stringResource(it.messageRes), color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = onReturn,
            enabled = !submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.rental_end))
        }
    }
}

/** "Ride time: 2 minutes and 3 seconds", updated every second. */
@Composable
private fun RideTime(startedAt: Instant) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(startedAt) {
        // Main dispatcher explicitly, so the one-second wait is real time.
        withContext(Dispatchers.Main) {
            while (true) {
                now = Instant.now()
                delay(1_000)
            }
        }
    }
    Text(stringResource(R.string.rental_ride_time, durationText(Duration.between(startedAt, now))))
}

@Composable
private fun FaultWarning() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.rental_fault_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.rental_fault_body))
        }
    }
}

@Composable
private fun RideAlreadyEnded(onSeeSummary: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.rental_already_ended))
        Button(onClick = onSeeSummary, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.rental_see_summary))
        }
    }
}
