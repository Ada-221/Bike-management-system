package com.cebp.rider.model

import java.time.Instant
import kotlinx.serialization.Serializable

// Field names and enum values follow api/fleet-api.yaml. IDs are prefixed strings such as veh_001
// and rnt_001; the app treats them as opaque and never relies on their format.

/** Fleet rule: a vehicle needs at least this much battery to be rented. */
const val MIN_RENTABLE_BATTERY_PERCENT = 20

@Serializable
enum class VehicleType { E_BIKE, E_SCOOTER }

@Serializable
enum class VehicleStatus { AVAILABLE, RENTED, MAINTENANCE_REQUIRED }

@Serializable
enum class RentalStatus { ACTIVE, COMPLETED }

/** A point on the map, in decimal degrees. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** Contract schemas `Vehicle` and `NearbyVehicle`. Fields the app does not use are ignored. */
@Serializable
data class Vehicle(
    val id: String,
    val label: String,
    val type: VehicleType,
    val status: VehicleStatus,
    val batteryPercent: Int,
    val latitude: Double,
    val longitude: Double,
    val zoneId: String? = null,
    /** Set by the backend: AVAILABLE with at least [MIN_RENTABLE_BATTERY_PERCENT] battery. */
    val rentable: Boolean,
    /** When [batteryPercent] was measured. Null if the vehicle never reported it. */
    @Serializable(with = InstantSerializer::class)
    val lastBatteryAt: Instant? = null,
    /** When [latitude] and [longitude] were measured. */
    @Serializable(with = InstantSerializer::class)
    val lastLocationAt: Instant? = null,
    /** Only present in `GET /vehicles/nearby` results. */
    val distanceMeters: Int? = null,
)

/** Contract schema `Rental`. */
@Serializable
data class Rental(
    val id: String,
    val vehicleId: String,
    val riderId: String,
    val status: RentalStatus,
    @Serializable(with = InstantSerializer::class)
    val startedAt: Instant,
    @Serializable(with = InstantSerializer::class)
    val endedAt: Instant? = null,
    val startLatitude: Double,
    val startLongitude: Double,
    val endLatitude: Double? = null,
    val endLongitude: Double? = null,
    val startBatteryPercent: Int,
    val endBatteryPercent: Int? = null,
)
