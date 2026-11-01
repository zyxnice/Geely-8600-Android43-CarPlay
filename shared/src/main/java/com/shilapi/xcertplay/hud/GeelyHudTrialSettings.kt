package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.Build

/** Process-local consent only; neither an APK update nor an ignition restart restores output. */
object GeelyHudTrialSettings {
    private val lease = GeelyHudTrialLease()
    @Volatile private var currentStatus = GeelyHudTrialStatus.OFF

    fun available(context: Context): Boolean =
        context.packageName == "com.shihab.diplay.repair1.geely8600" &&
            Build.VERSION.SDK_INT == 18 && Build.BOARD == "C3ALFUS" &&
            Build.DISPLAY == "06.03.08600.H53.00060" && GeelyNavigationObservation.isEnabled(context)

    fun enabled(context: Context): Boolean = available(context) && lease.enabled()

    /** Caller obtains explicit trial consent first; the output owner verifies the OEM before sending. */
    @Synchronized fun setEnabled(context: Context, enabled: Boolean) {
        val requested = enabled && available(context)
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
    internal fun acquire(context: Context, token: Any): Boolean = available(context) && lease.acquire(token)

    @Synchronized internal fun revoke(token: Any) = lease.revoke(token)
    internal fun owns(token: Any): Boolean = lease.owns(token)
    @Synchronized internal fun release(token: Any) {
        if (lease.owns(token) && currentStatus in setOf(GeelyHudTrialStatus.WAITING, GeelyHudTrialStatus.SENT)) {
            currentStatus = GeelyHudTrialStatus.OFF
        }
        lease.release(token)
    }
}

/** UI state for the current process. SENT means Android dispatch only, never physical HUD acceptance. */
enum class GeelyHudTrialStatus {
    OFF, WAITING, SENT, RECEIVER_UNVERIFIED, FACTORY_RUNNING, NAVIGATION_STATE_UNKNOWN,
    SOURCE_CHANGED, STALE, SEND_FAILED, CLEAR_FAILED, RECONNECT_REQUIRED,
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
