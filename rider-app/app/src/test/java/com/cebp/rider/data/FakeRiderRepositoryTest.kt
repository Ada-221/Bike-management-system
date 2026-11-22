package com.cebp.rider.data

import com.cebp.rider.model.GeoPoint
import com.cebp.rider.model.RentalStatus
import com.cebp.rider.model.VehicleStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeRiderRepositoryTest {

    private val now = Instant.parse("2026-11-22T10:00:00Z")
    private val repository = FakeRiderRepository(
        riderId = "rider-test",
        clock = Clock.fixed(now, ZoneOffset.UTC),
    )
    private val here = RiderLocation.current
    private val parkedAt = GeoPoint(48.2099, 16.3605)

    private suspend fun nearbyIds(radiusMeters: Int = 1000) =
        repository.getNearbyVehicles(here, radiusMeters).map { it.id }

    private suspend fun rejectionCode(block: suspend () -> Unit): String? =
        (runCatching { block() }.exceptionOrNull() as? RiderException.Rejected)?.code

    @Test
    fun `nearby lists only rentable vehicles within the radius, closest first`() = runTest {
        val nearby = repository.getNearbyVehicles(here, 1000)

        // ES-002 (18%) and ES-003 (fault) need maintenance; EB-003 is about 1.5 km away.
        assertEquals(listOf("veh_001", "veh_003", "veh_002"), nearby.map { it.id })
        assertTrue(nearby.all { it.rentable })
        assertEquals(nearby.map { it.distanceMeters }, nearby.map { it.distanceMeters }.sortedBy { it })
    }

    @Test
    fun `a larger radius also finds farther vehicles`() = runTest {
        assertFalse("veh_006" in nearbyIds(1000))
        assertEquals("veh_006", nearbyIds(2000).last())
    }

    @Test
    fun `a smaller radius leaves out vehicles beyond it`() = runTest {
        // EB-002 is about 450 m away and ES-001 about 270 m.
        assertEquals(listOf("veh_001", "veh_003"), nearbyIds(300))
    }

    @Test
    fun `vehicles report when their battery and location were measured`() = runTest {
        val vehicle = repository.getVehicle("veh_002")!!

        assertEquals(now.minus(Duration.ofMinutes(40)), vehicle.lastBatteryAt)
        assertNotNull(vehicle.lastLocationAt)
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
    fun `the vehicle's state is checked before the rider's, as in the backend`() = runTest {
        repository.startRental("veh_001", "key-1")

        assertEquals(
            ErrorCodes.VEHICLE_NOT_AVAILABLE,
            rejectionCode { repository.startRental("veh_005", "key-2") },
        )
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
    fun `a vehicle below 20 percent waits in maintenance and cannot be rented`() = runTest {
        val vehicle = repository.getVehicle("veh_004")!!

        assertEquals(VehicleStatus.MAINTENANCE_REQUIRED, vehicle.status)
        assertFalse(vehicle.rentable)
        assertEquals(
            ErrorCodes.VEHICLE_NOT_AVAILABLE,
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
        assertEquals(now, ended.endedAt)
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
    fun `a fault during a ride puts the vehicle in maintenance but the ride can still be returned`() =
        runTest {
            val rental = repository.startRental("veh_001", "key-1")

            repository.reportFault("veh_001")

            assertEquals(VehicleStatus.MAINTENANCE_REQUIRED, repository.getVehicle("veh_001")?.status)
            assertEquals(RentalStatus.ACTIVE, repository.getActiveRental()?.status)

            val ended = repository.endRental(rental.id, parkedAt)

            assertEquals(RentalStatus.COMPLETED, ended.status)
            assertEquals(VehicleStatus.MAINTENANCE_REQUIRED, repository.getVehicle("veh_001")?.status)
            assertFalse("veh_001" in nearbyIds())
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
        assertTrue(second.id.startsWith("rnt_"))
    }

    @Test
    fun `an unknown rental cannot be returned and is not found`() = runTest {
        assertEquals(ErrorCodes.NOT_FOUND, rejectionCode { repository.endRental("rnt_404", here) })
        assertNull(repository.getRental("rnt_404"))
    }
}
