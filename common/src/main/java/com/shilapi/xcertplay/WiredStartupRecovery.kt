package com.shilapi.xcertplay

/** Only two initial wired media-start failures may bypass the normal reconnect backoff. */
internal object WiredStartupRecovery {
    fun fastRetry(wired: Boolean, mediaActive: Boolean, attempts: Int, reason: String): Boolean {
        if (!wired || mediaActive || attempts !in 0..1) return false
        return reason.startsWith("CarPlay transport error: NCM bulk OUT did not become ready within ") ||
            reason == "CarPlay transport error: Wired media TCP did not connect after StartSession"
    }
}
