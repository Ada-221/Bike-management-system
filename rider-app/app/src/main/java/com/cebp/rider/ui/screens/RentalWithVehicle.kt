package com.cebp.rider.ui.screens

import com.cebp.rider.data.RiderRepository
import com.cebp.rider.model.Rental

/** A rental, plus the label of its vehicle, which the `Rental` schema does not include. */
internal data class RentalWithVehicle(val rental: Rental, val vehicleLabel: String)

/** Null if the rental does not exist. Falls back to the vehicle ID if the vehicle is missing. */
internal suspend fun RiderRepository.getRentalWithVehicleLabel(rentalId: String): RentalWithVehicle? {
    val rental = getRental(rentalId) ?: return null
    val label = getVehicle(rental.vehicleId)?.label ?: rental.vehicleId
    return RentalWithVehicle(rental, label)
}
