package com.cebp.rider.data

import com.cebp.rider.model.Rental
import com.cebp.rider.model.Vehicle

/** The rider's view of the backend. Screens only depend on this interface. */
interface RiderRepository {
    suspend fun getAvailableVehicles(): List<Vehicle>

    suspend fun getVehicle(vehicleId: String): Vehicle?

    suspend fun getActiveRental(): Rental?

    suspend fun getRental(rentalId: String): Rental?

    /** Repeating the call for a vehicle this rider already has returns the same rental. */
    suspend fun startRental(vehicleId: String): Rental

    /** Repeating the call for a rental that has ended returns the same rental. */
    suspend fun endRental(rentalId: String): Rental
}

/** A rental request the backend refused. [message] is shown to the rider. */
class RentalException(message: String) : Exception(message)
