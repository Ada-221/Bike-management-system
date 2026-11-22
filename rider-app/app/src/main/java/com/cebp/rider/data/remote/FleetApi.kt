package com.cebp.rider.data.remote

import com.cebp.rider.model.Rental
import com.cebp.rider.model.RentalStatus
import com.cebp.rider.model.Vehicle
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** The fleet-management endpoints the rider app uses, as defined in api/fleet-api.yaml. */
interface FleetApi {
    @GET("vehicles/nearby")
    suspend fun listNearbyVehicles(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
        @Query("radiusMeters") radiusMeters: Int,
    ): List<Vehicle>

    @GET("vehicles/{vehicleId}")
    suspend fun getVehicle(@Path("vehicleId") vehicleId: String): Vehicle

    @GET("rentals")
    suspend fun listRentals(
        @Query("riderId") riderId: String,
        @Query("status") status: RentalStatus,
    ): List<Rental>

    @GET("rentals/{rentalId}")
    suspend fun getRental(@Path("rentalId") rentalId: String): Rental

    @POST("rentals")
    suspend fun startRental(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: RentalStart,
    ): Rental

    @POST("rentals/{rentalId}/return")
    suspend fun returnRental(
        @Path("rentalId") rentalId: String,
        @Body body: RentalReturn,
    ): Rental
}

/** Contract schema `RentalStart`. */
@Serializable
data class RentalStart(val vehicleId: String, val riderId: String)

/** Contract schema `RentalReturn`: where the rider parked the vehicle. */
@Serializable
data class RentalReturn(val latitude: Double, val longitude: Double)

/** Contract schema `Error`. */
@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val correlationId: String? = null,
)
