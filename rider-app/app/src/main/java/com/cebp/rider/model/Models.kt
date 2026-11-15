package com.cebp.rider.model

import java.time.Instant
import kotlinx.serialization.Serializable

// Field names and enum values follow api/fleet-api.yaml (v0.1). IDs are opaque strings: the team
// agreed on prefixed IDs such as veh_001, while the v0.1 contract still says UUID, so the app never
// relies on their format.

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
    /** Set by the backend: AVAILABLE with at least 20 percent battery. */
    val rentable: Boolean,
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
