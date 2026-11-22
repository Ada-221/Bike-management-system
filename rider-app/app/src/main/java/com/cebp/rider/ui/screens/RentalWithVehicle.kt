package com.cebp.rider.ui.screens

import com.cebp.rider.data.RiderRepository
import com.cebp.rider.model.Rental
import com.cebp.rider.model.Vehicle

/** A rental, plus its vehicle, which the `Rental` schema only references by ID. */
internal data class RentalWithVehicle(val rental: Rental, val vehicle: Vehicle?) {
    /** The vehicle's label, falling back to its ID if the vehicle is missing. */
    val vehicleLabel: String get() = vehicle?.label ?: rental.vehicleId
}

/** Null if the rental does not exist. */
internal suspend fun RiderRepository.getRentalWithVehicle(rentalId: String): RentalWithVehicle? {
    val rental = getRental(rentalId) ?: return null
    return RentalWithVehicle(rental, getVehicle(rental.vehicleId))
}
