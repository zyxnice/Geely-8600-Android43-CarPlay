package com.shilapi.xcertplay.hud

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * H53 experiment through the stock exported receiver, never the USB/root bridge or a raw vehicle
 * transport. A successful Android dispatch is not a receiver acknowledgement or HUD acceptance.
 */
// API18 has no receiver-flags overload; the exact SDK guard rejects every other platform.
// External active-navigation evidence revokes this trial. Do not upgrade the shared
// API18 AndroidX baseline solely for a newer registration helper (added in core 1.9).
@SuppressLint("UnspecifiedRegisterReceiverFlag")
internal class GeelyHudTrialOutput(
    context: Context,
    private val log: (String) -> Unit,
    private val currentGeneration: (Long) -> Boolean,
) {
    private val app = context.applicationContext
    private var factoryVerified: Boolean? = null
    @Volatile private var registered = false
    private val ownerToken = Any()
    private var channel = -1L
    private val factoryNavigation = GeelyFactoryNavigationState()
    private var reportedFactoryState: GeelyFactoryNavigationState.Activity? = null
    private val policy = GeelyHudTrialPolicy(
        object : GeelyHudTrialPort {
            override fun begin() = dispatch(mapOf("KEY_TYPE" to 10019, "EXTRA_STATE" to 8), true)
            override fun guidance(frame: GeelyNavigationFrame) = dispatch(frame.candidateFields(), true)
            override fun end() = dispatch(mapOf("KEY_TYPE" to 10019, "EXTRA_STATE" to 9), false)
        },
        enabled = { GeelyHudTrialSettings.acquire(app, ownerToken) },
        revoke = { GeelyHudTrialSettings.revoke(ownerToken) },
        factoryReadiness = ::factoryReadiness,
        log = log,
        status = { GeelyHudTrialSettings.report(ownerToken, it) },
    )
    private val foreignReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Our commands are explicit to another package, so they never loop back here.
            if (registered && intent.action == ACTION) {
                val keyType = intent.getIntExtra("KEY_TYPE", -1)
                if (keyType != 10001 && keyType != 10019) return
                fun optionalInt(key: String) = if (intent.hasExtra(key)) intent.getIntExtra(key, -1) else null
                val state = factoryNavigation.observe(keyType, optionalInt("EXTRA_STATE"),
                    optionalInt("ICON"), optionalInt("SEG_REMAIN_DIS"), intent.getStringExtra("NEXT_ROAD_NAME"),
                    optionalInt("ROUTE_REMAIN_DIS"), optionalInt("ROUTE_REMAIN_TIME"))
                if (state != reportedFactoryState) {
                    reportedFactoryState = state
                    log("geely HUD factory navigation evidence=$state keyType=$keyType state=${optionalInt("EXTRA_STATE")} " +
                        "icon=${optionalInt("ICON")} segmentMeters=${optionalInt("SEG_REMAIN_DIS")} " +
                        "remainingMeters=${optionalInt("ROUTE_REMAIN_DIS")} remainingSeconds=${optionalInt("ROUTE_REMAIN_TIME")} " +
                        "roadPresent=${!intent.getStringExtra("NEXT_ROAD_NAME").isNullOrBlank()}")
                }
                if (state == GeelyFactoryNavigationState.Activity.ACTIVE) {
                    policy.foreignNavigation()
                } else if (keyType == 10019 && optionalInt("EXTRA_STATE") in setOf(9, 12)) {
                    policy.foreignNavigationEnded()
                }
            }
        }
    }

    init {
        try {
            if (Build.VERSION.SDK_INT == 18) {
                app.registerReceiver(foreignReceiver, IntentFilter(ACTION))
                registered = true
            } else {
                error("unsupported_trial_platform")
            }
        } catch (error: Exception) {
            GeelyHudTrialSettings.revoke(ownerToken)
            log("geely HUD trial unavailable: source observer registration failed ${error.javaClass.simpleName}")
        }
    }

    /** Called on the existing bounded navigation worker, including its one-second freshness tick. */
    @Synchronized fun update(generation: Long, frame: GeelyNavigationFrame?) {
        if (registered && currentGeneration(generation)) {
            channel = generation
            policy.update(frame)
        } else if (!registered && GeelyHudTrialSettings.acquire(app, ownerToken)) {
            GeelyHudTrialSettings.report(ownerToken, GeelyHudTrialStatus.RECEIVER_UNVERIFIED)
            GeelyHudTrialSettings.revoke(ownerToken)
        }
    }

    @Synchronized fun close() {
        try { policy.close() } catch (error: Exception) {
            GeelyHudTrialSettings.report(ownerToken, GeelyHudTrialStatus.CLEAR_FAILED)
            log("geely HUD trial close failed: ${error.javaClass.simpleName}; physical clear unverified")
        } finally {
            if (registered) {
                registered = false
                try { app.unregisterReceiver(foreignReceiver) }
                catch (error: Exception) { log("geely HUD source observer unregister failed: ${error.javaClass.simpleName}") }
                finally { GeelyHudTrialSettings.release(ownerToken) }
            } else {
                GeelyHudTrialSettings.release(ownerToken)
            }
        }
    }

    private fun dispatch(fields: Map<String, Any>, requireConsent: Boolean) {
        check(registered && currentGeneration(channel) && GeelyHudTrialSettings.owns(ownerToken) &&
            (!requireConsent || GeelyHudTrialSettings.acquire(app, ownerToken)) &&
            factoryReadiness() == GeelyHudFactoryReadiness.READY) {
            "factory_receiver_not_exclusive"
        }
        val intent = Intent(ACTION).setComponent(RECEIVER)
        for ((key, value) in fields) when (value) {
            is Int -> intent.putExtra(key, value)
            is String -> intent.putExtra(key, value)
            else -> error("unsupported_oem_field")
        }
        // Channel replacement can race the preceding identity/navigation checks. Recheck immediately
        // before Binder dispatch; a Binder call already in progress cannot be recalled.
        check(currentGeneration(channel)) { "stale_navigation_channel" }
        app.sendBroadcast(intent)
    }

    private fun factoryReadiness(): GeelyHudFactoryReadiness {
        if (!GeelyHudTrialSettings.available(app)) return GeelyHudFactoryReadiness.BLOCKED
        // Verify once on the worker, not on the UI thread or for every distance update.
        if (factoryVerified == null) factoryVerified = verifyFactory()
        if (factoryVerified != true) {
            GeelyHudTrialSettings.report(ownerToken, GeelyHudTrialStatus.RECEIVER_UNVERIFIED)
            return GeelyHudFactoryReadiness.BLOCKED
        }
        // Voice binding and AutoBackgroundService start AMap without a navigation route.
        // A process list cannot establish route ownership. Default ON is a preference, not proof
        // of an idle factory route. UNKNOWN waits for an end event or this owner's explicit check.
        return when (factoryNavigation.activity) {
            GeelyFactoryNavigationState.Activity.ACTIVE -> {
                GeelyHudTrialSettings.report(ownerToken, GeelyHudTrialStatus.FACTORY_RUNNING)
                GeelyHudFactoryReadiness.BLOCKED
            }
            GeelyFactoryNavigationState.Activity.INACTIVE -> GeelyHudFactoryReadiness.READY
            GeelyFactoryNavigationState.Activity.UNKNOWN ->
                if (GeelyHudTrialSettings.factoryIdleConfirmed(ownerToken)) GeelyHudFactoryReadiness.READY
                else GeelyHudFactoryReadiness.WAITING
        }
    }

    private fun verifyFactory(): Boolean = try {
        val info = app.packageManager.getReceiverInfo(RECEIVER, 0)
        val pkg = app.packageManager.getPackageInfo(RECEIVER.packageName, 0)
        val permission = info.permission
        val permitted = permission == null ||
            app.packageManager.checkPermission(permission, app.packageName) == PackageManager.PERMISSION_GRANTED
        val enabledState = app.packageManager.getComponentEnabledSetting(RECEIVER)
        val source = info.applicationInfo.sourceDir
        val componentEnabled = enabledState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
            enabledState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val available = info.enabled && info.exported && info.applicationInfo.enabled && permitted &&
            componentEnabled &&
            pkg.versionCode == 6 && pkg.versionName == "0.6800" &&
            source.endsWith(".apk") && sha256(source) == FACTORY_APK_SHA256 &&
            sha256(source.removeSuffix(".apk") + ".odex") == FACTORY_ODEX_SHA256
        log("geely HUD trial factory verification=$available; vehicle output requires session consent")
        available
    } catch (error: Exception) {
        log("geely HUD trial factory verification failed: ${error.javaClass.simpleName}")
        false
    }

    private fun sha256(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(path).use { input ->
            val buffer = ByteArray(32768)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
        val RECEIVER = ComponentName("com.neusoft.extraservice", "com.neusoft.extraservice.receiver.AutoNaviReceiver")
        // Original H53 APK is pre-optimized (ODEX). Pin the shipped artifact, not a repacked DEX.
        const val FACTORY_APK_SHA256 = "39c5da198ec14a1995772d8920dba7ad6859a4350bdb3c4b913db9d2a650322e"
        const val FACTORY_ODEX_SHA256 = "ce1779e14124d3d55a8300d847b110a97910bceedcd2c517828d5420956ee154"
    }
}
