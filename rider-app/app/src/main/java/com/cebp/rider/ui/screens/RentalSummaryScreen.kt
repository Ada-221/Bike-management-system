package com.cebp.rider.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cebp.rider.R
import com.cebp.rider.data.RiderRepository
import com.cebp.rider.ui.components.LoadContent
import com.cebp.rider.ui.components.RiderScaffold
import com.cebp.rider.ui.components.durationText
import com.cebp.rider.ui.components.rememberLoad
import com.cebp.rider.ui.components.toLocalTimeText
import java.time.Duration

@Composable
fun RentalSummaryRoute(
    rentalId: String,
    repository: RiderRepository,
    onDone: () -> Unit,
) {
    val load = rememberLoad(rentalId) { repository.getRentalWithVehicleLabel(rentalId) }

    RiderScaffold(title = stringResource(R.string.rental_summary_title)) { padding ->
        LoadContent(load, padding) { loaded ->
            Column(
                modifier = Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (loaded?.rental?.endedAt == null) {
                    Text(stringResource(R.string.rental_not_found))
                } else {
                    RentalDetails(loaded)
                }
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.rental_done))
                }
            }
        }
    }
}

@Composable
private fun RentalDetails(loaded: RentalWithVehicle) {
    val rental = loaded.rental
    val endedAt = requireNotNull(rental.endedAt)
    Text(
        text = stringResource(R.string.rental_vehicle, loaded.vehicleLabel),
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(stringResource(R.string.rental_started_at, rental.startedAt.toLocalTimeText()))
    Text(stringResource(R.string.rental_ended_at, endedAt.toLocalTimeText()))
    Text(
        stringResource(
            R.string.rental_duration,
            durationText(Duration.between(rental.startedAt, endedAt)),
        ),
    )
    rental.endBatteryPercent?.let { end ->
        Text(stringResource(R.string.rental_battery_used, rental.startBatteryPercent, end))
    }
}
