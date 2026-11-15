package com.cebp.rider.data.remote

import com.cebp.rider.data.ErrorCodes
import com.cebp.rider.data.RiderException
import com.cebp.rider.data.RiderRepository
import com.cebp.rider.model.GeoPoint
import com.cebp.rider.model.Rental
import com.cebp.rider.model.RentalStatus
import com.cebp.rider.model.Vehicle
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** [RiderRepository] backed by the fleet-management service's REST API. */
class HttpRiderRepository(
    private val api: FleetApi,
    private val riderId: String,
) : RiderRepository {

    override suspend fun getNearbyVehicles(position: GeoPoint): List<Vehicle> = call {
        api.listNearbyVehicles(position.latitude, position.longitude)
    }

    override suspend fun getVehicle(vehicleId: String): Vehicle? = callOrNullIfMissing {
        api.getVehicle(vehicleId)
    }

    override suspend fun getActiveRental(): Rental? = call {
        api.listRentals(riderId, RentalStatus.ACTIVE).firstOrNull()
    }

    override suspend fun getRental(rentalId: String): Rental? = callOrNullIfMissing {
        api.getRental(rentalId)
    }

    override suspend fun startRental(vehicleId: String, idempotencyKey: String): Rental = call {
        api.startRental(idempotencyKey, RentalStart(vehicleId = vehicleId, riderId = riderId))
    }

    override suspend fun endRental(rentalId: String, position: GeoPoint): Rental = call {
        api.returnRental(rentalId, RentalReturn(position.latitude, position.longitude))
    }

    private suspend fun <T> callOrNullIfMissing(request: suspend () -> T): T? = try {
        call(request)
    } catch (e: RiderException.Rejected) {
        if (e.code == ErrorCodes.NOT_FOUND) null else throw e
    }

    /** Runs [request] and turns every failure into a [RiderException]. */
    private suspend fun <T> call(request: suspend () -> T): T = try {
        request()
    } catch (e: HttpException) {
        throw e.toRiderException()
    } catch (e: IOException) {
        throw RiderException.Unreachable(e)
    } catch (e: SerializationException) {
        throw RiderException.Unexpected("The server sent data the app cannot read.", e)
    }

    private fun HttpException.toRiderException(): RiderException {
        val status = code()
        val error = response()?.errorBody()?.string()?.let { body ->
            runCatching { json.decodeFromString(ApiError.serializer(), body) }.getOrNull()
        }
        return when {
            status in 400..499 && error != null -> RiderException.Rejected(error.code, error.message)
            status == 404 -> RiderException.Rejected(ErrorCodes.NOT_FOUND, "Not found.")
            else -> RiderException.Unexpected(
                "HTTP $status" + (error?.correlationId?.let { " (correlation ID $it)" } ?: ""),
                this,
            )
        }
    }

    companion object {
        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /** Builds the repository for the API at [baseUrl], for example `http://10.0.2.2:8080/api/v1/`. */
        fun create(baseUrl: String, riderId: String): HttpRiderRepository {
            val client = OkHttpClient.Builder()
                .callTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    // Optional in the contract; lets the backend team trace this request in their logs.
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("X-Correlation-Id", UUID.randomUUID().toString())
                            .build(),
                    )
                }
                .build()
            val api = Retrofit.Builder()
                .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(FleetApi::class.java)
            return HttpRiderRepository(api, riderId)
        }
    }
}
