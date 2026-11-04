package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.UUID

/** Only declared in this test candidate's geelyUsb manifest, protected by android.permission.DUMP. */
class GeelyPhoneNavigationProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || Build.VERSION.SDK_INT != 18 || Build.BOARD != "C3ALFUS" ||
            Build.DISPLAY != "06.03.08600.H53.00060" || !intent.getBooleanExtra("parked", false)) {
            reject("action_platform_or_parked_guard")
            return
        }
        val kind = intent.getStringExtra("kind") ?: return reject("missing_kind")
        val latitude = intent.getStringExtra("latitude")?.toDoubleOrNull()
        val longitude = intent.getStringExtra("longitude")?.toDoubleOrNull()
        val coordinates = intent.getStringExtra("coordinates")
        try {
            com.shilapi.xcertplay.airplay.GeelyPhoneNavigationProbe.command(kind, latitude, longitude, coordinates)
        } catch (_: IllegalArgumentException) {
            return reject("invalid_probe_or_coordinates")
        }
        val session = CarPlayBackgroundSession.snapshot() ?: return reject("no_existing_carplay_session")
        val now = SystemClock.elapsedRealtime()
        if (lastProbeAt != null && now - lastProbeAt!! < 3_000) return reject("rate_limited")
        lastProbeAt = now
        val id = UUID.randomUUID().toString()
        val admitted = session.controller.requestPhoneNavigationProbe(id, kind, latitude, longitude, coordinates)
        Log.i(TAG, "id=$id kind=$kind admitted=$admitted navigationExecuted=unknown")
        resultCode = if (admitted) 1 else 0
        resultData = "id=$id admitted=$admitted; observe phone and vehicle; not execution confirmation"
    }

    private fun reject(reason: String) {
        Log.i(TAG, "rejected=$reason")
        resultCode = 0
        resultData = reason
    }

    companion object {
        const val ACTION = "com.shihab.diplay.geely.PHONE_NAVIGATION_PROBE"
        private const val TAG = "GeelyPhoneProbe"
        // onReceive runs on the application's main thread; no extra polling thread is created.
        private var lastProbeAt: Long? = null
    }
}
