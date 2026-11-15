package com.cebp.rider.data.remote

import com.cebp.rider.data.ErrorCodes
import com.cebp.rider.data.RiderException
import com.cebp.rider.model.GeoPoint
import com.cebp.rider.model.RentalStatus
import com.cebp.rider.model.VehicleStatus
import com.cebp.rider.model.VehicleType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Checks the requests the app sends and how it reads responses, against api/fleet-api.yaml v0.1.
 * The JSON bodies below are the examples from that contract.
 */
class HttpRiderRepositoryTest {

    private val server = MockWebServer()
    private lateinit var repository: HttpRiderRepository

    @Before
    fun setUp() {
        server.start()
        repository = HttpRiderRepository.create(server.url("/api/v1/").toString(), RIDER_ID)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun respond(status: Int, body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    private fun RecordedRequest.bodyJson() = Json.parseToJsonElement(body.readUtf8()).jsonObject

    private suspend fun failure(block: suspend () -> Unit): RiderException =
        runCatching { block() }.exceptionOrNull() as RiderException

    @Test
    fun `nearby sends the position and reads NearbyVehicle`() = runTest {
        respond(200, "[$NEARBY_VEHICLE]")

        val vehicles = repository.getNearbyVehicles(GeoPoint(48.21, 16.36))

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/vehicles/nearby", request.requestUrl!!.encodedPath)
        assertEquals("48.21", request.requestUrl!!.queryParameter("lat"))
        assertEquals("16.36", request.requestUrl!!.queryParameter("lon"))
        assertNotNull(request.getHeader("X-Correlation-Id"))

        val vehicle = vehicles.single()
        assertEquals("b0000000-0000-0000-0000-000000000001", vehicle.id)
        assertEquals("EB-001", vehicle.label)
        assertEquals(VehicleType.E_BIKE, vehicle.type)
        assertEquals(VehicleStatus.AVAILABLE, vehicle.status)
        assertEquals(92, vehicle.batteryPercent)
        assertTrue(vehicle.rentable)
        assertEquals(28, vehicle.distanceMeters)
    }

    @Test
    fun `prefixed vehicle IDs are sent unchanged in the path`() = runTest {
        respond(200, VEHICLE)

        repository.getVehicle("veh_001")

        assertEquals("/api/v1/vehicles/veh_001", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `a missing vehicle is null`() = runTest {
        respond(404, NOT_FOUND_ERROR)

        assertNull(repository.getVehicle("b0000000-0000-0000-0000-0000000000ff"))
    }

    @Test
    fun `the active rental is looked up by rider and status`() = runTest {
        respond(200, "[$ACTIVE_RENTAL]")

        val rental = repository.getActiveRental()!!

        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/v1/rentals", url.encodedPath)
        assertEquals(RIDER_ID, url.queryParameter("riderId"))
        assertEquals("ACTIVE", url.queryParameter("status"))
        assertEquals("c0000000-0000-0000-0000-000000000001", rental.id)
        assertEquals(RentalStatus.ACTIVE, rental.status)
        assertEquals(Instant.parse("2026-11-09T08:20:00Z"), rental.startedAt)
        assertNull(rental.endedAt)
        assertEquals(70, rental.startBatteryPercent)
    }

    @Test
    fun `no active rental is null`() = runTest {
        respond(200, "[]")

        assertNull(repository.getActiveRental())
    }

    @Test
    fun `start sends the idempotency key, vehicle and rider`() = runTest {
        respond(201, ACTIVE_RENTAL)

        repository.startRental("b0000000-0000-0000-0000-000000000005", "3b241101-e2bb-4255-8caf-4136c566a962")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/rentals", request.requestUrl!!.encodedPath)
        assertEquals("3b241101-e2bb-4255-8caf-4136c566a962", request.getHeader("Idempotency-Key"))
        val body = request.bodyJson()
        assertEquals("b0000000-0000-0000-0000-000000000005", body["vehicleId"]!!.jsonPrimitive.content)
        assertEquals(RIDER_ID, body["riderId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a repeated start answered with 200 returns the original rental`() = runTest {
        respond(200, ACTIVE_RENTAL)

        assertEquals("c0000000-0000-0000-0000-000000000001", repository.startRental("v", "key-1").id)
    }

    @Test
    fun `a business-rule conflict becomes Rejected with the contract code`() = runTest {
        respond(
            409,
            """{"code":"BATTERY_TOO_LOW","message":"Vehicle ES-002 battery is 18 percent (minimum 20).",
               "timestamp":"2026-11-09T08:30:00Z","correlationId":"7f3c1d2e-9a41-4c55-8d0b-1f2e3a4b5c6d"}""",
        )

        val error = failure { repository.startRental("v", "key-1") }

        assertTrue(error is RiderException.Rejected)
        assertEquals(ErrorCodes.BATTERY_TOO_LOW, (error as RiderException.Rejected).code)
    }

    @Test
    fun `return sends where the vehicle was parked and reads the completed rental`() = runTest {
        respond(200, COMPLETED_RENTAL)

        val rental = repository.endRental("c0000000-0000-0000-0000-000000000001", GeoPoint(48.2099, 16.3605))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/rentals/c0000000-0000-0000-0000-000000000001/return", request.requestUrl!!.encodedPath)
        val body = request.bodyJson()
        assertEquals(48.2099, body["latitude"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(16.3605, body["longitude"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(RentalStatus.COMPLETED, rental.status)
        assertEquals(Instant.parse("2026-11-09T08:41:00Z"), rental.endedAt)
        assertEquals(64, rental.endBatteryPercent)
    }

    @Test
    fun `timestamps with a numeric UTC offset are accepted`() = runTest {
        respond(200, ACTIVE_RENTAL.replace("2026-11-09T08:20:00Z", "2026-11-09T08:20:00.123+00:00"))

        assertEquals(
            Instant.parse("2026-11-09T08:20:00.123Z"),
            repository.getRental("c0000000-0000-0000-0000-000000000001")!!.startedAt,
        )
    }

    @Test
    fun `a server error becomes Unexpected`() = runTest {
        respond(500, """{"code":"INTERNAL_ERROR","message":"boom","timestamp":"2026-11-09T08:30:00Z"}""")

        assertTrue(failure { repository.getNearbyVehicles(GeoPoint(48.21, 16.36)) } is RiderException.Unexpected)
    }

    @Test
    fun `a response the app cannot read becomes Unexpected`() = runTest {
        respond(200, """{"not":"a list"}""")

        assertTrue(failure { repository.getNearbyVehicles(GeoPoint(48.21, 16.36)) } is RiderException.Unexpected)
    }

    @Test
    fun `a broken connection becomes Unreachable`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertTrue(failure { repository.getNearbyVehicles(GeoPoint(48.21, 16.36)) } is RiderException.Unreachable)
    }

    private companion object {
        const val RIDER_ID = "rider-demo-1"

        const val VEHICLE = """{"id":"b0000000-0000-0000-0000-000000000001","label":"EB-001","type":"E_BIKE",
            "status":"AVAILABLE","batteryPercent":92,"latitude":48.2102,"longitude":16.3598,
            "zoneId":"a0000000-0000-0000-0000-000000000001","rentable":true,
            "lastTelemetryAt":"2026-11-09T08:29:50Z","createdAt":"2026-11-01T10:00:00Z",
            "updatedAt":"2026-11-09T08:29:50Z"}"""

        val NEARBY_VEHICLE = VEHICLE.trimEnd('}') + ""","distanceMeters":28}"""

        const val ACTIVE_RENTAL = """{"id":"c0000000-0000-0000-0000-000000000001",
            "vehicleId":"b0000000-0000-0000-0000-000000000005","riderId":"rider-demo-1",
            "status":"ACTIVE","startedAt":"2026-11-09T08:20:00Z","endedAt":null,
            "startLatitude":48.1858,"startLongitude":16.3758,"endLatitude":null,"endLongitude":null,
            "startBatteryPercent":70,"endBatteryPercent":null}"""

        val COMPLETED_RENTAL = ACTIVE_RENTAL
            .replace("\"ACTIVE\"", "\"COMPLETED\"")
            .replace("\"endedAt\":null", "\"endedAt\":\"2026-11-09T08:41:00Z\"")
            .replace("\"endLatitude\":null", "\"endLatitude\":48.2099")
            .replace("\"endLongitude\":null", "\"endLongitude\":16.3605")
            .replace("\"endBatteryPercent\":null", "\"endBatteryPercent\":64")

        const val NOT_FOUND_ERROR = """{"code":"NOT_FOUND",
            "message":"Vehicle b0000000-0000-0000-0000-0000000000ff not found.",
            "timestamp":"2026-11-09T08:30:00Z","correlationId":"7f3c1d2e-9a41-4c55-8d0b-1f2e3a4b5c6d"}"""
    }
}
