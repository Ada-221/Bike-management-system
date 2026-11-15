package com.cebp.rider.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.cebp.rider.data.RiderException
import com.cebp.rider.data.RiderLocation
import com.cebp.rider.data.RiderRepository
import com.cebp.rider.ui.components.CenteredMessage
import com.cebp.rider.ui.components.LoadContent
import com.cebp.rider.ui.components.RiderScaffold
import com.cebp.rider.ui.components.messageRes
import com.cebp.rider.ui.components.rememberLoad
import com.cebp.rider.ui.components.toLocalTimeText
import kotlinx.coroutines.launch

@Composable
fun ActiveRentalRoute(
    rentalId: String,
    repository: RiderRepository,
    onBack: () -> Unit,
    onRentalEnded: (rentalId: String) -> Unit,
) {
    val load = rememberLoad(rentalId) { repository.getRentalWithVehicleLabel(rentalId) }
    val scope = rememberCoroutineScope()
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<RiderException?>(null) }

    RiderScaffold(
        title = stringResource(R.string.rental_active_title),
        onBack = onBack,
    ) { padding ->
        LoadContent(load, padding) { loaded ->
            if (loaded == null) {
                CenteredMessage(stringResource(R.string.rental_not_found), padding)
            } else {
                val rental = loaded.rental
                Column(
                    modifier = Modifier.padding(padding).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.rental_vehicle, loaded.vehicleLabel),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        stringResource(
                            R.string.rental_started_at,
                            rental.startedAt.toLocalTimeText(),
                        ),
                    )
                    Text(stringResource(R.string.vehicle_battery, rental.startBatteryPercent))
                    error?.let {
                        Text(stringResource(it.messageRes), color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = {
                            submitting = true
                            error = null
                            scope.launch {
                                try {
                                    // Returning is idempotent, so a retry after a network failure is safe.
                                    onRentalEnded(
                                        repository.endRental(rental.id, RiderLocation.current).id,
                                    )
                                } catch (e: RiderException) {
                                    error = e
                                    submitting = false
                                }
                            }
                        },
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.rental_end))
                    }
                }
            }
        }
    }
}
