package com.cebp.rider.data

import android.content.Context
import java.util.UUID

/**
 * The contract identifies a rider by a free-text `riderId` (no accounts are in scope). Each
 * installation generates one and keeps it, so a ride in progress is found again after a restart
 * and two emulators act as two different riders.
 */
class RiderIdStore(context: Context) {
    private val preferences = context.getSharedPreferences("rider", Context.MODE_PRIVATE)

    fun riderId(): String = preferences.getString(KEY, null)
        ?: "rider-${UUID.randomUUID()}".also { preferences.edit().putString(KEY, it).apply() }

    private companion object {
        const val KEY = "rider_id"
    }
}
