package com.cebp.rider.data

import com.cebp.rider.model.GeoPoint
import com.cebp.rider.model.RentalStatus
import com.cebp.rider.model.VehicleStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeRiderRepositoryTest {

    private val repository = FakeRiderRepository(riderId = "rider-test")
    private val here = RiderLocation.current
    private val parkedAt = GeoPoint(48.2099, 16.3605)

    private suspend fun nearbyIds() = repository.getNearbyVehicles(here).map { it.id }

    private suspend fun rejectionCode(block: suspend () -> Unit): String? =
        (runCatching { block() }.exceptionOrNull() as? RiderException.Rejected)?.code

    @Test
    fun `nearby lists only rentable vehicles within the radius, closest first`() = runTest {
        val nearby = repository.getNearbyVehicles(here)

        // ES-002 has 18% battery, ES-003 needs maintenance, EB-003 is about 3 km away.
        assertEquals(listOf("veh_001", "veh_003", "veh_002"), nearby.map { it.id })
        assertTrue(nearby.all { it.rentable })
        assertEquals(nearby.map { it.distanceMeters }, nearby.map { it.distanceMeters }.sortedBy { it })
    }

    @Test
    fun `starting a rental records it and removes the vehicle from nearby`() = runTest {
        val rental = repository.startRental("veh_001", "key-1")

        assertEquals("veh_001", rental.vehicleId)
        assertEquals("rider-test", rental.riderId)
        assertEquals(RentalStatus.ACTIVE, rental.status)
        assertEquals(92, rental.startBatteryPercent)
        assertEquals(rental, repository.getActiveRental())
        assertEquals(VehicleStatus.RENTED, repository.getVehicle("veh_001")?.status)
        assertFalse("veh_001" in nearbyIds())
    }

    @Test
    fun `repeating start with the same idempotency key returns the original rental`() = runTest {
        assertEquals(
            repository.startRental("veh_001", "key-1"),
            repository.startRental("veh_001", "key-1"),
        )
    }

    @Test
    fun `reusing an idempotency key for another vehicle is refused`() = runTest {
        repository.startRental("veh_001", "key-1")

        assertEquals(
            ErrorCodes.IDEMPOTENCY_KEY_REUSED,
            rejectionCode { repository.startRental("veh_002", "key-1") },
        )
    }

    @Test
    fun `a rider cannot start a second rental during a ride`() = runTest {
        repository.startRental("veh_001", "key-1")

        assertEquals(
            ErrorCodes.RIDER_HAS_ACTIVE_RENTAL,
            rejectionCode { repository.startRental("veh_002", "key-2") },
        )
        assertEquals(VehicleStatus.AVAILABLE, repository.getVehicle("veh_002")?.status)
    }

    @Test
    fun `a vehicle that needs maintenance cannot be rented`() = runTest {
        assertEquals(
            ErrorCodes.VEHICLE_NOT_AVAILABLE,
            rejectionCode { repository.startRental("veh_005", "key-1") },
        )
        assertNull(repository.getActiveRental())
    }

    @Test
    fun `a vehicle with battery below 20 percent cannot be rented`() = runTest {
        assertFalse(repository.getVehicle("veh_004")!!.rentable)
        assertEquals(
            ErrorCodes.BATTERY_TOO_LOW,
            rejectionCode { repository.startRental("veh_004", "key-1") },
        )
    }

    @Test
    fun `an unknown vehicle cannot be rented and is not found`() = runTest {
        assertEquals(ErrorCodes.NOT_FOUND, rejectionCode { repository.startRental("veh_404", "key-1") })
        assertNull(repository.getVehicle("veh_404"))
    }

    @Test
    fun `returning completes the rental and frees the vehicle where it was parked`() = runTest {
        val rental = repository.startRental("veh_001", "key-1")

        val ended = repository.endRental(rental.id, parkedAt)

        assertEquals(RentalStatus.COMPLETED, ended.status)
        assertNotNull(ended.endedAt)
        assertEquals(parkedAt.latitude, ended.endLatitude!!, 0.0)
        assertEquals(parkedAt.longitude, ended.endLongitude!!, 0.0)
        assertEquals(92, ended.endBatteryPercent)
        assertNull(repository.getActiveRental())
        val vehicle = repository.getVehicle("veh_001")!!
        assertEquals(VehicleStatus.AVAILABLE, vehicle.status)
        assertEquals(parkedAt.latitude, vehicle.latitude, 0.0)
        assertTrue("veh_001" in nearbyIds())
    }

    @Test
    fun `repeating return gives the same rental`() = runTest {
        val rental = repository.startRental("veh_001", "key-1")

        assertEquals(repository.endRental(rental.id, parkedAt), repository.endRental(rental.id, here))
    }

    @Test
    fun `a returned vehicle can be rented again under a new rental`() = runTest {
        val first = repository.startRental("veh_001", "key-1")
        repository.endRental(first.id, parkedAt)

        val second = repository.startRental("veh_001", "key-2")

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `an unknown rental cannot be returned and is not found`() = runTest {
        assertEquals(ErrorCodes.NOT_FOUND, rejectionCode { repository.endRental("rental_404", here) })
        assertNull(repository.getRental("rental_404"))
    }
}
