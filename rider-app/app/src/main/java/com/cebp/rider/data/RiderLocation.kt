package com.cebp.rider.data

import com.cebp.rider.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the rider is. Week 2 uses a fixed synthetic point: the centre of the "Campus" zone from
 * the API contract examples. Device location and area selection are planned for Week 4.
 */
object RiderLocation {
    val current = GeoPoint(latitude = 48.2100, longitude = 16.3600)

    /** Search radius choices offered to the rider. The contract allows up to 5000 m. */
    val radiusOptionsMeters = listOf(500, 1000, 2000)

    /** The contract's default radius for `GET /vehicles/nearby`. */
    const val DEFAULT_RADIUS_METERS = 1000
}

/** Straight-line (Haversine) distance in meters, as the backend computes `distanceMeters`. */
internal fun GeoPoint.distanceMetersTo(other: GeoPoint): Int {
    val earthRadiusMeters = 6_371_000.0
    val dLat = Math.toRadians(other.latitude - latitude)
    val dLon = Math.toRadians(other.longitude - longitude)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) * sin(dLon / 2).pow(2)
    return (2 * earthRadiusMeters * asin(sqrt(a))).toInt()
}
