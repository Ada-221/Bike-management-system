package com.cebp.rider

import android.app.Application
import com.cebp.rider.data.FakeRiderRepository
import com.cebp.rider.data.RiderIdStore
import com.cebp.rider.data.RiderRepository
import com.cebp.rider.data.remote.HttpRiderRepository

class RiderApplication : Application() {
    /**
     * Uses the fleet API when the build sets `rider.backendUrl`, and in-memory demo data otherwise.
     * Held here so its state survives activity recreation.
     */
    val repository: RiderRepository by lazy {
        val backendUrl = BuildConfig.BACKEND_URL
        if (backendUrl.isBlank()) {
            FakeRiderRepository()
        } else {
            HttpRiderRepository.create(backendUrl, RiderIdStore(this).riderId())
        }
    }
}
