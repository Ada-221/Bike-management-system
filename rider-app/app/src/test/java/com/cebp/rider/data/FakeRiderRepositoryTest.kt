package com.cebp.rider.data

import com.cebp.rider.model.VehicleStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeRiderRepositoryTest {

    private val repository = FakeRiderRepository()

    @Test
    fun `available vehicles exclude vehicles in maintenance`() = runTest {
        val available = repository.getAvailableVehicles().map { it.id }

        assertFalse("SCOOTER-003" in available)
        assertTrue("BIKE-001" in available)
    }

    @Test
    fun `starting a rental removes the vehicle from the available list`() = runTest {
        val rental = repository.startRental("BIKE-001")

        assertEquals("BIKE-001", rental.vehicleId)
        assertEquals(rental, repository.getActiveRental())
        assertEquals(VehicleStatus.RENTED, repository.getVehicle("BIKE-001")?.status)
        assertFalse(repository.getAvailableVehicles().any { it.id == "BIKE-001" })
    }

    @Test
    fun `repeating start for the same vehicle returns the same rental`() = runTest {
        assertEquals(repository.startRental("BIKE-001"), repository.startRental("BIKE-001"))
    }

    @Test
    fun `a rider cannot start a second rental during a ride`() = runTest {
        repository.startRental("BIKE-001")

        val failure = runCatching { repository.startRental("BIKE-002") }.exceptionOrNull()

        assertTrue(failure is RentalException)
        assertEquals(VehicleStatus.AVAILABLE, repository.getVehicle("BIKE-002")?.status)
    }

    @Test
    fun `a vehicle in maintenance cannot be rented`() = runTest {
        val failure = runCatching { repository.startRental("SCOOTER-003") }.exceptionOrNull()

        assertTrue(failure is RentalException)
        assertNull(repository.getActiveRental())
    }

    @Test
    fun `an unknown vehicle cannot be rented`() = runTest {
        val failure = runCatching { repository.startRental("BIKE-404") }.exceptionOrNull()

        assertTrue(failure is RentalException)
    }

    @Test
    fun `ending a rental makes the vehicle available again`() = runTest {
        val rental = repository.startRental("BIKE-001")

        val ended = repository.endRental(rental.id)

        assertNotNull(ended.endedAt)
        assertNull(repository.getActiveRental())
        assertEquals(VehicleStatus.AVAILABLE, repository.getVehicle("BIKE-001")?.status)
    }

    @Test
    fun `repeating end returns the same rental`() = runTest {
        val rental = repository.startRental("BIKE-001")

        assertEquals(repository.endRental(rental.id), repository.endRental(rental.id))
    }

    @Test
    fun `a returned vehicle can be rented again under a new rental`() = runTest {
        val first = repository.startRental("BIKE-001")
        repository.endRental(first.id)

        val second = repository.startRental("BIKE-001")

        assertTrue(first.id != second.id)
    }

    @Test
    fun `an unknown rental cannot be ended`() = runTest {
        val failure = runCatching { repository.endRental("rental-404") }.exceptionOrNull()

        assertTrue(failure is RentalException)
    }
}
