package com.cebp.rider.data

import com.cebp.rider.model.Rental
import com.cebp.rider.model.Vehicle
import com.cebp.rider.model.VehicleStatus
import com.cebp.rider.model.VehicleType
import java.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory stand-in for the fleet-management API, using synthetic data.
 * State is lost when the app process ends.
 */
class FakeRiderRepository(
    private val clock: Clock = Clock.systemUTC(),
) : RiderRepository {

    private val mutex = Mutex()
    private val vehicles = seedVehicles().associateBy { it.id }.toMutableMap()
    private val rentals = mutableMapOf<String, Rental>()

    override suspend fun getAvailableVehicles(): List<Vehicle> = mutex.withLock {
        vehicles.values.filter { it.status == VehicleStatus.AVAILABLE }
    }

    override suspend fun getVehicle(vehicleId: String): Vehicle? = mutex.withLock {
        vehicles[vehicleId]
    }

    override suspend fun getActiveRental(): Rental? = mutex.withLock { activeRental() }

    override suspend fun getRental(rentalId: String): Rental? = mutex.withLock {
        rentals[rentalId]
    }

    override suspend fun startRental(vehicleId: String): Rental = mutex.withLock {
        val active = activeRental()
        if (active != null) {
            if (active.vehicleId == vehicleId) return@withLock active
            throw RentalException("Return ${active.vehicleId} before starting another ride.")
        }

        val vehicle = vehicles[vehicleId]
            ?: throw RentalException("Vehicle $vehicleId does not exist.")
        if (vehicle.status != VehicleStatus.AVAILABLE) {
            throw RentalException("Vehicle $vehicleId is not available.")
        }

        val rental = Rental(
            id = "rental-${rentals.size + 1}",
            vehicleId = vehicleId,
            startedAt = clock.instant(),
        )
        rentals[rental.id] = rental
        vehicles[vehicleId] = vehicle.copy(status = VehicleStatus.RENTED)
        rental
    }

    override suspend fun endRental(rentalId: String): Rental = mutex.withLock {
        val rental = rentals[rentalId]
            ?: throw RentalException("Rental $rentalId does not exist.")
        if (rental.endedAt != null) return@withLock rental

        val ended = rental.copy(endedAt = clock.instant())
        rentals[rentalId] = ended
        vehicles.getValue(rental.vehicleId).let {
            vehicles[it.id] = it.copy(status = VehicleStatus.AVAILABLE)
        }
        ended
    }

    private fun activeRental(): Rental? = rentals.values.firstOrNull { it.endedAt == null }

    private fun seedVehicles() = listOf(
        Vehicle("BIKE-001", VehicleType.E_BIKE, 92, VehicleStatus.AVAILABLE, "Central"),
        Vehicle("BIKE-002", VehicleType.E_BIKE, 47, VehicleStatus.AVAILABLE, "University"),
        Vehicle("SCOOTER-001", VehicleType.SCOOTER, 78, VehicleStatus.AVAILABLE, "Central"),
        Vehicle("SCOOTER-002", VehicleType.SCOOTER, 18, VehicleStatus.AVAILABLE, "Station"),
        Vehicle("SCOOTER-003", VehicleType.SCOOTER, 64, VehicleStatus.MAINTENANCE, "Station"),
    )
}
