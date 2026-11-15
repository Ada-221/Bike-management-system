package com.cebp.rider.data

import com.cebp.rider.model.GeoPoint
import com.cebp.rider.model.Rental
import com.cebp.rider.model.Vehicle

/**
 * The rider's view of the fleet backend. Screens only depend on this interface.
 *
 * Every function throws [RiderException] when the request fails.
 */
interface RiderRepository {
    /** Rentable vehicles near [position], closest first (`GET /vehicles/nearby`). */
    suspend fun getNearbyVehicles(position: GeoPoint): List<Vehicle>

    /** The vehicle, or null if it does not exist. */
    suspend fun getVehicle(vehicleId: String): Vehicle?

    /** This rider's ride in progress, if any. */
    suspend fun getActiveRental(): Rental?

    /** The rental, or null if it does not exist. */
    suspend fun getRental(rentalId: String): Rental?

    /**
     * Starts a rental. Use one [idempotencyKey] per user intention and reuse it when retrying
     * after [RiderException.Unreachable]: a repeated request then returns the original rental.
     */
    suspend fun startRental(vehicleId: String, idempotencyKey: String): Rental

    /** Ends the rental with the vehicle parked at [position]. Repeating it returns the same rental. */
    suspend fun endRental(rentalId: String, position: GeoPoint): Rental
}
