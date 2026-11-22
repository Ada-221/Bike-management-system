package com.cebp.rider.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cebp.rider.R
import com.cebp.rider.data.RiderLocation
import com.cebp.rider.data.RiderRepository
import com.cebp.rider.model.Rental
import com.cebp.rider.model.Vehicle
import com.cebp.rider.model.VehicleType
import com.cebp.rider.ui.components.AUTO_REFRESH_MILLIS
import com.cebp.rider.ui.components.BatteryLevel
import com.cebp.rider.ui.components.LoadContent
import com.cebp.rider.ui.components.RiderScaffold
import com.cebp.rider.ui.components.distanceText
import com.cebp.rider.ui.components.labelRes
import com.cebp.rider.ui.components.rememberLoad

private data class VehicleListContent(
    val vehicles: List<Vehicle>,
    val activeRental: Rental?,
    /** Label of the vehicle in [activeRental], falling back to its ID. */
    val activeVehicleLabel: String?,
)

@Composable
fun VehicleListRoute(
    repository: RiderRepository,
    onVehicleClick: (vehicleId: String) -> Unit,
    onResumeRental: (rentalId: String) -> Unit,
) {
    // The type filter is applied in the app; the radius is sent to the backend.
    var typeFilter by rememberSaveable { mutableStateOf<VehicleType?>(null) }
    var radiusMeters by rememberSaveable { mutableIntStateOf(RiderLocation.DEFAULT_RADIUS_METERS) }

    val load = rememberLoad(radiusMeters, autoRefreshMillis = AUTO_REFRESH_MILLIS) {
        val activeRental = repository.getActiveRental()
        VehicleListContent(
            vehicles = repository.getNearbyVehicles(RiderLocation.current, radiusMeters),
            activeRental = activeRental,
            activeVehicleLabel = activeRental?.let {
                repository.getVehicle(it.vehicleId)?.label ?: it.vehicleId
            },
        )
    }

    RiderScaffold(title = stringResource(R.string.vehicle_list_title)) { padding ->
        LoadContent(load, padding) { content ->
            VehicleList(
                content = content,
                typeFilter = typeFilter,
                radiusMeters = radiusMeters,
                onTypeFilterChange = { typeFilter = it },
                onRadiusChange = { radiusMeters = it },
                onVehicleClick = onVehicleClick,
                onResumeRental = onResumeRental,
            )
        }
    }
}

@Composable
private fun VehicleList(
    content: VehicleListContent,
    typeFilter: VehicleType?,
    radiusMeters: Int,
    onTypeFilterChange: (VehicleType?) -> Unit,
    onRadiusChange: (Int) -> Unit,
    onVehicleClick: (vehicleId: String) -> Unit,
    onResumeRental: (rentalId: String) -> Unit,
) {
    val shown = content.vehicles.filter { typeFilter == null || it.type == typeFilter }

    // One scrolling list, so pull-to-refresh also works when it is empty.
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        content.activeRental?.let { rental ->
            item(key = "banner") {
                ActiveRentalBanner(
                    vehicleLabel = content.activeVehicleLabel ?: rental.vehicleId,
                    onResume = { onResumeRental(rental.id) },
                )
            }
        }
        item(key = "filters") {
            Filters(typeFilter, radiusMeters, onTypeFilterChange, onRadiusChange)
        }
        if (shown.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.vehicle_list_empty, distanceText(radiusMeters)),
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            items(shown, key = { it.id }) { vehicle ->
                VehicleRow(vehicle = vehicle, onClick = { onVehicleClick(vehicle.id) })
            }
        }
    }
}

@Composable
private fun Filters(
    typeFilter: VehicleType?,
    radiusMeters: Int,
    onTypeFilterChange: (VehicleType?) -> Unit,
    onRadiusChange: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                null to R.string.filter_all,
                VehicleType.E_BIKE to R.string.filter_e_bikes,
                VehicleType.E_SCOOTER to R.string.filter_e_scooters,
            ).forEach { (type, label) ->
                FilterChip(
                    selected = typeFilter == type,
                    onClick = { onTypeFilterChange(type) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.filter_within))
            RiderLocation.radiusOptionsMeters.forEach { option ->
                FilterChip(
                    selected = radiusMeters == option,
                    onClick = { onRadiusChange(option) },
                    label = { Text(distanceText(option)) },
                )
            }
        }
    }
}

@Composable
private fun ActiveRentalBanner(vehicleLabel: String, onResume: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.rental_in_progress, vehicleLabel),
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = onResume) {
                Text(stringResource(R.string.rental_resume))
            }
        }
    }
}

@Composable
private fun VehicleRow(vehicle: Vehicle, onClick: () -> Unit) {
    val type = stringResource(vehicle.type.labelRes)
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(vehicle.label) },
        supportingContent = {
            Text(
                vehicle.distanceMeters
                    ?.let { stringResource(R.string.vehicle_list_row, type, distanceText(it)) }
                    ?: type,
            )
        },
        trailingContent = { BatteryLevel(vehicle.batteryPercent) },
    )
}
