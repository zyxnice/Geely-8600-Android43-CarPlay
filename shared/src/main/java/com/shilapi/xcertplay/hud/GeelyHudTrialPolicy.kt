package com.shilapi.xcertplay.hud

/** Fixed OEM navigation operations. Implementations must target only the verified factory receiver. */
internal interface GeelyHudTrialPort {
    fun begin()
    fun guidance(frame: GeelyNavigationFrame)
    fun end()
}

/**
 * One enabled controller's output. All calls are serialized; the user selects CarPlay as the sole
 * navigation source. Factory navigation state is intentionally outside this policy.
 */
internal class GeelyHudTrialPolicy(
    private val port: GeelyHudTrialPort,
    private val enabled: () -> Boolean,
    private val revoke: () -> Unit,
    private val receiverReady: () -> Boolean,
    private val log: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val status: (GeelyHudTrialStatus) -> Unit = {},
) {
    private var ownsOutput = false
    private var lastFrame: GeelyNavigationFrame? = null
    private var failed = false
    private var closed = false
    private var clearAttempts = 0
    private var guidanceDispatches = 0L
    private var lastGuidanceReportNs: Long? = null

    @Synchronized fun update(frame: GeelyNavigationFrame?) {
        if (closed) return
        if (!ownsOutput && !enabled()) return
        if (failed && enabled()) {
            revoke()
            status(GeelyHudTrialStatus.RECONNECT_REQUIRED)
            log("geely HUD trial remains disabled until a new controller after receiver or output failure")
        }
        if (!receiverReady()) {
            abandon("receiver-unverified")
            return
        }
        if (failed || !enabled() || !publishable(frame)) {
            if (!failed && enabled() && ownsOutput) status(GeelyHudTrialStatus.STALE)
            clearOwned(if (failed) "output-failed" else if (!enabled()) "disabled" else "route-unavailable-or-invalid")
            return
        }
        try {
            if (!ownsOutput) {
                // Mark before dispatch: a thrown send does not prove the receiver saw nothing.
                ownsOutput = true
                port.begin()
                log("geely HUD trial navigation begin; receiver acknowledgement unavailable")
            }
            if (frame != lastFrame) {
                port.guidance(requireNotNull(frame))
                lastFrame = frame
                status(GeelyHudTrialStatus.SENT)
                guidanceDispatches++
                val now = nanoTime()
                if (lastGuidanceReportNs?.let { now - it >= 5_000_000_000L } != false) {
                    lastGuidanceReportNs = now
                    log("geely HUD trial guidance dispatched count=$guidanceDispatches nowNs=$now " +
                        "distanceUpdatedNs=${frame.distanceUpdatedNanos} fields=${frame.candidateFields()}; " +
                        "receiver acknowledgement unavailable")
                }
            }
        } catch (error: Exception) {
            failed = true
            revoke()
            status(GeelyHudTrialStatus.SEND_FAILED)
            log("geely HUD trial output failed: ${error.javaClass.simpleName}")
            clearOwned("send-failure")
        }
    }

    @Synchronized fun close() {
        if (closed) return
        revoke()
        if (ownsOutput) {
            if (receiverReady()) clearOwned("controller-closed") else abandon("receiver-unverified-on-close")
        }
        closed = true
    }

    private fun publishable(frame: GeelyNavigationFrame?): Boolean {
        val updated = frame?.distanceUpdatedNanos ?: return false
        val age = nanoTime() - updated
        // The decoder owns route validity, maneuver changes and whole-stream timeout. A stale
        // distance alone must not end and restart a still-valid navigation session.
        return age >= 0 &&
            frame.road.toByteArray(charset("GB2312")).size <= 80
    }

    private fun clearOwned(reason: String) {
        if (!ownsOutput || clearAttempts >= 3) return
        // Identity must still match before an end broadcast, including retries after send failure.
        if (!receiverReady()) { abandon("receiver-unverified-before-clear"); return }
        clearAttempts++
        try {
            port.end()
            ownsOutput = false
            lastFrame = null
            clearAttempts = 0
            guidanceDispatches = 0
            lastGuidanceReportNs = null
            log("geely HUD trial end dispatched reason=$reason; physical clear unverified")
        } catch (error: Exception) {
            failed = true
            revoke()
            status(GeelyHudTrialStatus.CLEAR_FAILED)
            log("geely HUD trial clear failed attempt=$clearAttempts reason=$reason error=${error.javaClass.simpleName}")
        }
    }

    private fun abandon(reason: String) {
        if (ownsOutput || enabled()) log("geely HUD trial withdrawn reason=$reason; no end sent to shared receiver")
        ownsOutput = false
        lastFrame = null
        failed = true
        revoke()
    }
}
