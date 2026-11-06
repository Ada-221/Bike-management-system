package com.cebp.rider

import android.app.Application
import com.cebp.rider.data.FakeRiderRepository
import com.cebp.rider.data.RiderRepository

class RiderApplication : Application() {
    /** Held here so the fake's in-memory state survives activity recreation. */
    val repository: RiderRepository by lazy { FakeRiderRepository() }
}
