package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * H53 experiment through the stock exported receiver, never the USB/root bridge or a raw vehicle
 * transport. A successful Android dispatch is not a receiver acknowledgement or HUD acceptance.
 */
internal class GeelyHudTrialOutput(
    context: Context,
    private val log: (String) -> Unit,
    private val currentGeneration: (Long) -> Boolean,
) {
    private val app = context.applicationContext
    private var factoryVerified: Boolean? = null
    private var closed = false
    private val ownerToken = Any()
    private var channel = -1L
    private val policy = GeelyHudTrialPolicy(
        object : GeelyHudTrialPort {
            override fun begin() = dispatch(mapOf("KEY_TYPE" to 10019, "EXTRA_STATE" to 8), true)
            override fun guidance(frame: GeelyNavigationFrame) = dispatch(frame.candidateFields(), true)
            override fun end() = dispatch(mapOf("KEY_TYPE" to 10019, "EXTRA_STATE" to 9), false)
        },
        enabled = { GeelyHudTrialSettings.acquire(app, ownerToken) },
        revoke = { GeelyHudTrialSettings.revoke(ownerToken) },
        receiverReady = ::receiverReady,
        log = log,
        status = { GeelyHudTrialSettings.report(ownerToken, it) },
    )

    /** Called on the existing bounded navigation worker, including its one-second freshness tick. */
    @Synchronized fun update(generation: Long, frame: GeelyNavigationFrame?) {
        if (!closed && currentGeneration(generation)) {
            channel = generation
            policy.update(frame)
        }
    }

    @Synchronized fun close() {
        if (closed) return
        try { policy.close() } catch (error: Exception) {
            GeelyHudTrialSettings.report(ownerToken, GeelyHudTrialStatus.CLEAR_FAILED)
            log("geely HUD trial close failed: ${error.javaClass.simpleName}; physical clear unverified")
        } finally {
            closed = true
            GeelyHudTrialSettings.release(ownerToken)
        }
    }

    private fun dispatch(fields: Map<String, Any>, requireConsent: Boolean) {
        check(!closed && currentGeneration(channel) && GeelyHudTrialSettings.owns(ownerToken) &&
            (!requireConsent || GeelyHudTrialSettings.acquire(app, ownerToken)) &&
            receiverReady()) {
            "factory_receiver_unavailable"
        }
        val intent = Intent(ACTION).setComponent(RECEIVER)
        for ((key, value) in fields) when (value) {
            is Int -> intent.putExtra(key, value)
            is String -> intent.putExtra(key, value)
            else -> error("unsupported_oem_field")
        }
        // Channel replacement can race the preceding identity checks. Recheck immediately
        // before Binder dispatch; a Binder call already in progress cannot be recalled.
        check(currentGeneration(channel)) { "stale_navigation_channel" }
        app.sendBroadcast(intent)
    }

    private fun receiverReady(): Boolean {
        if (!GeelyHudTrialSettings.available(app)) return false
        // Verify once on the worker, not on the UI thread or for every distance update.
        if (factoryVerified == null) factoryVerified = verifyFactory()
        if (factoryVerified != true) {
            GeelyHudTrialSettings.report(ownerToken, GeelyHudTrialStatus.RECEIVER_UNVERIFIED)
            return false
        }
        // User explicitly selected CarPlay as the sole navigation source. Do not query, infer or
        // wait for factory navigation activity; this check protects only receiver identity.
        return true
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
        log("geely HUD receiver verification=$available; factory navigation state ignored by user setting")
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
