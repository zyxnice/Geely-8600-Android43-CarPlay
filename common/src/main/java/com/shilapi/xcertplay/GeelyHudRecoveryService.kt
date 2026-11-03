package com.shilapi.xcertplay

import android.app.IntentService
import android.content.Intent
import com.shilapi.xcertplay.compat.GeelyHudExclusiveControl

/** API18 boot recovery runs independently of the user's CarPlay auto-launch preference. */
@Suppress("DEPRECATION")
class GeelyHudRecoveryService : IntentService("geely-hud-boot-recovery") {
    override fun onHandleIntent(intent: Intent?) {
        GeelyHudExclusiveControl.recoverAndWait(applicationContext)
    }
}
