package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.Build

/** Persistent user preference, separate from the current controller's revocable output lease. */
object GeelyHudTrialSettings {
    private val lease = GeelyHudTrialLease()
    @Volatile private var currentStatus = GeelyHudTrialStatus.OFF
    private var initialized = false
    private var requestedEnabled = true

    fun available(context: Context): Boolean =
        context.packageName == "com.shihab.diplay.repair1.geely8600" &&
            Build.VERSION.SDK_INT == 18 && Build.BOARD == "C3ALFUS" &&
            Build.DISPLAY == "06.03.08600.H53.00060" && GeelyNavigationObservation.isEnabled(context)

    @Synchronized fun enabled(context: Context): Boolean {
        initialize(context)
        return available(context) && requestedEnabled
    }

    private fun initialize(context: Context) {
        if (initialized) return
        requestedEnabled = GeelyHudPreferences.enabled(context)
        lease.request(requestedEnabled && available(context))
        currentStatus = if (requestedEnabled && available(context)) GeelyHudTrialStatus.WAITING else GeelyHudTrialStatus.OFF
        initialized = true
    }

    /** Save manual OFF as well as ON; runtime failures must not change this user preference. */
    @Synchronized fun setEnabled(context: Context, enabled: Boolean) {
        initialize(context)
        val requested = enabled && available(context)
        requestedEnabled = requested
        GeelyHudPreferences.setEnabled(context, requested)
        lease.request(requested)
        currentStatus = if (requested) GeelyHudTrialStatus.WAITING else GeelyHudTrialStatus.OFF
    }

    fun status(): GeelyHudTrialStatus = currentStatus

    @Synchronized internal fun report(token: Any, status: GeelyHudTrialStatus) {
        if (!lease.owns(token)) return
        // A Binder send may return after the user switched off. Keep OFF authoritative;
        // errors remain visible because they can require physical HUD recovery.
        if (status in setOf(GeelyHudTrialStatus.SENT, GeelyHudTrialStatus.STALE, GeelyHudTrialStatus.WAITING) &&
            !lease.acquire(token)) return
        currentStatus = status
    }

    /** A second controller cannot adopt a live owner's consent during asynchronous teardown. */
    @Synchronized internal fun acquire(context: Context, token: Any): Boolean {
        initialize(context)
        return available(context) && lease.acquire(token)
    }

    @Synchronized internal fun revoke(token: Any) = lease.revoke(token)
    internal fun owns(token: Any): Boolean = lease.owns(token)
    @Synchronized internal fun release(token: Any) {
        if (!lease.owns(token)) return
        lease.release(token)
        // Only a completed controller teardown can re-arm the saved preference. A failed owner
        // retains its revoked lease through all later ticks and cannot resurrect itself.
        lease.request(requestedEnabled)
        if (currentStatus in setOf(GeelyHudTrialStatus.OFF, GeelyHudTrialStatus.WAITING,
                GeelyHudTrialStatus.SENT, GeelyHudTrialStatus.STALE)) {
            currentStatus = if (requestedEnabled) GeelyHudTrialStatus.WAITING else GeelyHudTrialStatus.OFF
        }
    }
}

/** Same SharedPreferences pattern as BydOutputSettings; absent preference defaults to enabled. */
internal object GeelyHudPreferences {
    private const val PREFS = "diplay_geely_hud"
    private const val KEY_ENABLED = "navigation_enabled"

    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}

/** UI state for the current process. SENT means Android dispatch only, never physical HUD acceptance. */
enum class GeelyHudTrialStatus {
    OFF, WAITING, SENT, RECEIVER_UNVERIFIED, STALE, SEND_FAILED, CLEAR_FAILED, RECONNECT_REQUIRED,
}

/** Retain ownership through asynchronous clearing; revocation must not grant a second writer. */
internal class GeelyHudTrialLease {
    private var pending = false
    private var owner: Any? = null
    private var ownerEnabled = false

    @Synchronized fun request(enabled: Boolean) {
        pending = enabled && owner == null
        if (owner != null) ownerEnabled = enabled
    }
    @Synchronized fun enabled(): Boolean = pending || ownerEnabled
    @Synchronized fun acquire(token: Any): Boolean {
        if (owner === token) return ownerEnabled
        if (owner != null || !pending) return false
        pending = false
        owner = token
        ownerEnabled = true
        return true
    }
    @Synchronized fun revoke(token: Any) { if (owner === token) ownerEnabled = false }
    @Synchronized fun owns(token: Any): Boolean = owner === token
    @Synchronized fun release(token: Any) {
        if (owner === token) { owner = null; ownerEnabled = false }
    }
}
