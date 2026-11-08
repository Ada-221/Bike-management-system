package com.cebp.rider.model

import java.time.Instant

// Provisional models: align these with the OpenAPI contract once Person 1 publishes it.

enum class VehicleType { E_BIKE, SCOOTER }

enum class VehicleStatus { AVAILABLE, RENTED, MAINTENANCE }

data class Vehicle(
    val id: String,
    val type: VehicleType,
    val batteryPercent: Int,
    val status: VehicleStatus,
    val area: String,
)

data class Rental(
    val id: String,
    val vehicleId: String,
    val startedAt: Instant,
    val endedAt: Instant? = null,
)
