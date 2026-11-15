package com.cebp.rider.data

import com.cebp.rider.model.GeoPoint
import com.cebp.rider.model.Rental
import com.cebp.rider.model.RentalStatus
import com.cebp.rider.model.Vehicle
import com.cebp.rider.model.VehicleStatus
import com.cebp.rider.model.VehicleType
import java.time.Clock
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory stand-in for the fleet-management service, with synthetic data. It follows the
 * business rules and error codes of api/fleet-api.yaml, so the app behaves the same against
 * either. State is lost when the app process ends.
 */
class FakeRiderRepository(
    private val riderId: String = "rider-demo-1",
    private val clock: Clock = Clock.systemUTC(),
) : RiderRepository {

    private val mutex = Mutex()
    private val vehicles = seedVehicles().associateBy { it.id }.toMutableMap()
    private val rentals = mutableMapOf<String, Rental>()
    private val rentalIdsByKey = mutableMapOf<String, String>()

    override suspend fun getNearbyVehicles(position: GeoPoint): List<Vehicle> = mutex.withLock {
        vehicles.values
            .filter { it.rentable }
            .map { it.copy(distanceMeters = position.distanceMetersTo(it.position)) }
            .filter { it.distanceMeters!! <= NEARBY_RADIUS_METERS }
            .sortedBy { it.distanceMeters }
    }

    override suspend fun getVehicle(vehicleId: String): Vehicle? = mutex.withLock {
        vehicles[vehicleId]
    }

    override suspend fun getActiveRental(): Rental? = mutex.withLock { activeRental() }

    override suspend fun getRental(rentalId: String): Rental? = mutex.withLock {
        rentals[rentalId]
    }

    override suspend fun startRental(vehicleId: String, idempotencyKey: String): Rental =
        mutex.withLock {
            rentalIdsByKey[idempotencyKey]?.let { rentalId ->
                val earlier = rentals.getValue(rentalId)
                if (earlier.vehicleId == vehicleId) return@withLock earlier
                reject(ErrorCodes.IDEMPOTENCY_KEY_REUSED, "This Idempotency-Key was already used.")
            }

            val vehicle = vehicles[vehicleId]
                ?: reject(ErrorCodes.NOT_FOUND, "Vehicle $vehicleId not found.")
            if (activeRental() != null) {
                reject(ErrorCodes.RIDER_HAS_ACTIVE_RENTAL, "Rider $riderId already has an active rental.")
            }
            if (vehicle.status != VehicleStatus.AVAILABLE) {
                reject(ErrorCodes.VEHICLE_NOT_AVAILABLE, "Vehicle ${vehicle.label} is not available.")
            }
            if (vehicle.batteryPercent < MIN_RENTABLE_BATTERY_PERCENT) {
                reject(ErrorCodes.BATTERY_TOO_LOW, "Vehicle ${vehicle.label} battery is too low.")
            }

            val rental = Rental(
                id = "rental_%03d".format(Locale.ROOT, rentals.size + 1),
                vehicleId = vehicleId,
                riderId = riderId,
                status = RentalStatus.ACTIVE,
                startedAt = clock.instant(),
                startLatitude = vehicle.latitude,
                startLongitude = vehicle.longitude,
                startBatteryPercent = vehicle.batteryPercent,
            )
            rentals[rental.id] = rental
            rentalIdsByKey[idempotencyKey] = rental.id
            vehicles[vehicleId] = vehicle.withStatus(VehicleStatus.RENTED)
            rental
        }

    override suspend fun endRental(rentalId: String, position: GeoPoint): Rental = mutex.withLock {
        val rental = rentals[rentalId]
            ?: reject(ErrorCodes.NOT_FOUND, "Rental $rentalId not found.")
        if (rental.status == RentalStatus.COMPLETED) return@withLock rental

        val vehicle = vehicles.getValue(rental.vehicleId)
        val ended = rental.copy(
            status = RentalStatus.COMPLETED,
            endedAt = clock.instant(),
            endLatitude = position.latitude,
            endLongitude = position.longitude,
            endBatteryPercent = vehicle.batteryPercent,
        )
        rentals[rentalId] = ended
        // In the real system telemetry reports the new location; the fake moves the vehicle itself.
        vehicles[vehicle.id] = vehicle
            .copy(latitude = position.latitude, longitude = position.longitude)
            .withStatus(VehicleStatus.AVAILABLE)
        ended
    }

    private fun activeRental(): Rental? = rentals.values.firstOrNull {
        it.riderId == riderId && it.status == RentalStatus.ACTIVE
    }

    private fun reject(code: String, message: String): Nothing =
        throw RiderException.Rejected(code, message)

    private companion object {
        const val MIN_RENTABLE_BATTERY_PERCENT = 20
        const val NEARBY_RADIUS_METERS = 1000

        val Vehicle.position get() = GeoPoint(latitude, longitude)

        fun Vehicle.withStatus(status: VehicleStatus) = copy(
            status = status,
            rentable = status == VehicleStatus.AVAILABLE &&
                batteryPercent >= MIN_RENTABLE_BATTERY_PERCENT,
        )

        fun vehicle(
            id: String,
            label: String,
            type: VehicleType,
            battery: Int,
            status: VehicleStatus,
            latitude: Double,
            longitude: Double,
        ) = Vehicle(
            id = id,
            label = label,
            type = type,
            status = status,
            batteryPercent = battery,
            latitude = latitude,
            longitude = longitude,
            rentable = false,
        ).withStatus(status)

        /** Near the Campus point in [RiderLocation], except EB-003, which is outside the radius. */
        fun seedVehicles() = listOf(
            vehicle("veh_001", "EB-001", VehicleType.E_BIKE, 92, VehicleStatus.AVAILABLE, 48.2102, 16.3598),
            vehicle("veh_002", "EB-002", VehicleType.E_BIKE, 47, VehicleStatus.AVAILABLE, 48.2131, 16.3640),
            vehicle("veh_003", "ES-001", VehicleType.E_SCOOTER, 78, VehicleStatus.AVAILABLE, 48.2085, 16.3571),
            vehicle("veh_004", "ES-002", VehicleType.E_SCOOTER, 18, VehicleStatus.AVAILABLE, 48.2095, 16.3612),
            vehicle("veh_005", "ES-003", VehicleType.E_SCOOTER, 64, VehicleStatus.MAINTENANCE_REQUIRED, 48.2110, 16.3590),
            vehicle("veh_006", "EB-003", VehicleType.E_BIKE, 85, VehicleStatus.AVAILABLE, 48.2300, 16.4000),
        )
    }
}
