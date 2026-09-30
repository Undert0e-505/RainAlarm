package com.rainalarm.app.data

import android.util.Log
import com.rainalarm.app.BuildConfig
import com.rainalarm.app.domain.RadarTravelTransitionReason

/** Debug-only transition evidence. It intentionally records no location values. */
object TravelModeDiagnostics {
    private const val TAG = "RainTravelState"

    fun record(old: Boolean, new: Boolean, reason: RadarTravelTransitionReason) {
        if (BuildConfig.DEBUG) Log.d(TAG, "follow=$old->$new reason=${reason.name}")
    }
}
