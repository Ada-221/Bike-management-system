package com.cebp.rider.data

import com.cebp.rider.model.GeoPoint
import com.cebp.rider.model.MIN_RENTABLE_BATTERY_PERCENT
import com.cebp.rider.model.Rental
import com.cebp.rider.model.RentalStatus
import com.cebp.rider.model.Vehicle
import com.cebp.rider.model.VehicleStatus
import com.cebp.rider.model.VehicleType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory stand-in for the fleet-management service, with synthetic data. It follows the
 * business rules, check order and error codes of fleet-management-service (api/fleet-api.yaml),
 * so the app behaves the same against either. State is lost when the app process ends.
 */
class FakeRiderRepository(
    private val riderId: String = "rider-demo-1",
    private val clock: Clock = Clock.systemUTC(),
) : RiderRepository {

    private val mutex = Mutex()
    private val vehicles = seedVehicles(clock.instant()).associateBy { it.id }.toMutableMap()
    private val rentals = mutableMapOf<String, Rental>()
    private val rentalIdsByKey = mutableMapOf<String, String>()
    private val faultyVehicleIds = mutableSetOf<String>()

    override suspend fun getNearbyVehicles(position: GeoPoint, radiusMeters: Int): List<Vehicle> =
        mutex.withLock {
            vehicles.values
                .filter { it.rentable }
                .map { it.copy(distanceMeters = position.distanceMetersTo(it.position)) }
                .filter { it.distanceMeters!! <= radiusMeters }
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

            // Same order as the backend: the vehicle's state first, then the rider's.
            val vehicle = vehicles[vehicleId]
                ?: reject(ErrorCodes.NOT_FOUND, "Vehicle $vehicleId not found.")
            if (vehicle.status != VehicleStatus.AVAILABLE) {
                reject(ErrorCodes.VEHICLE_NOT_AVAILABLE, "Vehicle ${vehicle.label} is ${vehicle.status}.")
            }
            if (vehicle.batteryPercent < MIN_RENTABLE_BATTERY_PERCENT) {
                reject(ErrorCodes.BATTERY_TOO_LOW, "Vehicle ${vehicle.label} battery is too low.")
            }
            if (activeRental() != null) {
                reject(ErrorCodes.RIDER_HAS_ACTIVE_RENTAL, "Rider $riderId already has an active rental.")
            }

            val rental = Rental(
                id = "rnt_%03d".format(Locale.ROOT, rentals.size + 1),
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

        val now = clock.instant()
        val vehicle = vehicles.getValue(rental.vehicleId)
        val ended = rental.copy(
            status = RentalStatus.COMPLETED,
            endedAt = now,
            endLatitude = position.latitude,
            endLongitude = position.longitude,
            endBatteryPercent = vehicle.batteryPercent,
        )
        rentals[rentalId] = ended
        // A vehicle with a fault or too little battery goes to maintenance instead of the list.
        val nextStatus = if (vehicle.id in faultyVehicleIds ||
            vehicle.batteryPercent < MIN_RENTABLE_BATTERY_PERCENT
        ) {
            VehicleStatus.MAINTENANCE_REQUIRED
        } else {
            VehicleStatus.AVAILABLE
        }
        // In the real system telemetry reports the new location; the fake moves the vehicle itself.
        vehicles[vehicle.id] = vehicle
            .copy(latitude = position.latitude, longitude = position.longitude, lastLocationAt = now)
            .withStatus(nextStatus)
        ended
    }

    /**
     * Simulates a vehicle reporting a fault, as fleet-management-service applies it: the vehicle
     * becomes MAINTENANCE_REQUIRED at once, even during a ride, and the ride stays returnable.
     * Used by tests; the backend learns about faults from telemetry or operators.
     */
    suspend fun reportFault(vehicleId: String) = mutex.withLock {
        val vehicle = vehicles.getValue(vehicleId)
        faultyVehicleIds += vehicleId
        vehicles[vehicleId] = vehicle.withStatus(VehicleStatus.MAINTENANCE_REQUIRED)
    }

    private fun activeRental(): Rental? = rentals.values.firstOrNull {
        it.riderId == riderId && it.status == RentalStatus.ACTIVE
    }

    private fun reject(code: String, message: String): Nothing =
        throw RiderException.Rejected(code, message)

    private companion object {
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
            measuredAt: Instant,
        ) = Vehicle(
            id = id,
            label = label,
            type = type,
            status = status,
            batteryPercent = battery,
            latitude = latitude,
            longitude = longitude,
            rentable = false,
            lastBatteryAt = measuredAt,
            lastLocationAt = measuredAt,
        ).withStatus(status)

        /**
         * Around the Campus point in [RiderLocation]. ES-002 has too little battery, so, as in the
         * backend, it waits in maintenance; ES-003 has a fault; EB-003 is about 1.5 km away, so it
         * only appears with the 2 km radius; EB-002's battery reading is 40 minutes old.
         */
        fun seedVehicles(now: Instant): List<Vehicle> {
            fun ago(minutes: Long) = now.minus(Duration.ofMinutes(minutes))
            return listOf(
                vehicle("veh_001", "EB-001", VehicleType.E_BIKE, 92, VehicleStatus.AVAILABLE, 48.2102, 16.3598, ago(2)),
                vehicle("veh_002", "EB-002", VehicleType.E_BIKE, 47, VehicleStatus.AVAILABLE, 48.2131, 16.3640, ago(40)),
                vehicle("veh_003", "ES-001", VehicleType.E_SCOOTER, 78, VehicleStatus.AVAILABLE, 48.2085, 16.3571, ago(0)),
                vehicle("veh_004", "ES-002", VehicleType.E_SCOOTER, 18, VehicleStatus.MAINTENANCE_REQUIRED, 48.2095, 16.3612, ago(5)),
                vehicle("veh_005", "ES-003", VehicleType.E_SCOOTER, 64, VehicleStatus.MAINTENANCE_REQUIRED, 48.2110, 16.3590, ago(10)),
                vehicle("veh_006", "EB-003", VehicleType.E_BIKE, 85, VehicleStatus.AVAILABLE, 48.2235, 16.3600, ago(1)),
            )
        }
    }
}
