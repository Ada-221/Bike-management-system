package com.cebp.rider.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cebp.rider.R
import com.cebp.rider.data.RiderRepository
import com.cebp.rider.model.Rental
import com.cebp.rider.model.Vehicle
import com.cebp.rider.ui.components.CenteredMessage
import com.cebp.rider.ui.components.LoadState
import com.cebp.rider.ui.components.LoadingIndicator
import com.cebp.rider.ui.components.RiderScaffold
import com.cebp.rider.ui.components.labelRes
import com.cebp.rider.ui.components.rememberLoad

private data class VehicleListContent(
    val vehicles: List<Vehicle>,
    val activeRental: Rental?,
)

@Composable
fun VehicleListRoute(
    repository: RiderRepository,
    onVehicleClick: (vehicleId: String) -> Unit,
    onResumeRental: (rentalId: String) -> Unit,
) {
    val state = rememberLoad(repository) {
        VehicleListContent(repository.getAvailableVehicles(), repository.getActiveRental())
    }

    RiderScaffold(title = stringResource(R.string.vehicle_list_title)) { padding ->
        when (state) {
            LoadState.Loading -> LoadingIndicator(padding)
            is LoadState.Ready -> VehicleList(
                content = state.value,
                padding = padding,
                onVehicleClick = onVehicleClick,
                onResumeRental = onResumeRental,
            )
        }
    }
}

@Composable
private fun VehicleList(
    content: VehicleListContent,
    padding: PaddingValues,
    onVehicleClick: (vehicleId: String) -> Unit,
    onResumeRental: (rentalId: String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        content.activeRental?.let { rental ->
            ActiveRentalBanner(rental = rental, onResumeRental = onResumeRental)
        }
        if (content.vehicles.isEmpty()) {
            CenteredMessage(
                message = stringResource(R.string.vehicle_list_empty),
                padding = PaddingValues(),
            )
        } else {
            LazyColumn {
                items(content.vehicles, key = { it.id }) { vehicle ->
                    VehicleRow(vehicle = vehicle, onClick = { onVehicleClick(vehicle.id) })
                }
            }
        }
    }
}

@Composable
private fun ActiveRentalBanner(rental: Rental, onResumeRental: (rentalId: String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.rental_in_progress, rental.vehicleId),
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = { onResumeRental(rental.id) }) {
                Text(stringResource(R.string.rental_resume))
            }
        }
    }
}

@Composable
private fun VehicleRow(vehicle: Vehicle, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(vehicle.id) },
        supportingContent = {
            Text("${stringResource(vehicle.type.labelRes)} · ${vehicle.area}")
        },
        trailingContent = {
            Text(stringResource(R.string.vehicle_battery, vehicle.batteryPercent))
        },
    )
}
