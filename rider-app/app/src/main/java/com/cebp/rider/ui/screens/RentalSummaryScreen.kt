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
import com.cebp.rider.model.Rental
import com.cebp.rider.ui.components.LoadState
import com.cebp.rider.ui.components.LoadingIndicator
import com.cebp.rider.ui.components.RiderScaffold
import com.cebp.rider.ui.components.rememberLoad
import com.cebp.rider.ui.components.toLocalTimeText
import java.time.Duration

@Composable
fun RentalSummaryRoute(
    rentalId: String,
    repository: RiderRepository,
    onDone: () -> Unit,
) {
    val state = rememberLoad(rentalId) { repository.getRental(rentalId) }

    RiderScaffold(title = stringResource(R.string.rental_summary_title)) { padding ->
        when (state) {
            LoadState.Loading -> LoadingIndicator(padding)
            is LoadState.Ready -> {
                val rental = state.value
                Column(
                    modifier = Modifier.padding(padding).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (rental?.endedAt == null) {
                        Text(stringResource(R.string.rental_not_found))
                    } else {
                        RentalDetails(rental)
                    }
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.rental_done))
                    }
                }
            }
        }
    }
}

@Composable
private fun RentalDetails(rental: Rental) {
    val endedAt = requireNotNull(rental.endedAt)
    Text(
        text = stringResource(R.string.rental_vehicle, rental.vehicleId),
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(stringResource(R.string.rental_started_at, rental.startedAt.toLocalTimeText()))
    Text(stringResource(R.string.rental_ended_at, endedAt.toLocalTimeText()))
    Text(
        stringResource(
            R.string.rental_duration_minutes,
            Duration.between(rental.startedAt, endedAt).toMinutes(),
        ),
    )
}
