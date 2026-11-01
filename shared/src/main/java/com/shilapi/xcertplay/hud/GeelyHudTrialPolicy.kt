package com.shilapi.xcertplay.hud

/** Fixed OEM navigation operations. Implementations must target only the verified factory receiver. */
internal interface GeelyHudTrialPort {
    fun begin()
    fun guidance(frame: GeelyNavigationFrame)
    fun end()
}

/** WAITING preserves the requested switch without granting ownership of unknown factory state. */
internal enum class GeelyHudFactoryReadiness { READY, WAITING, BLOCKED }

/**
 * One explicitly enabled controller's experimental output. All calls are serialized; no queued
 * guidance survives a source handoff. Observed navigation evidence is not atomic ownership.
 */
internal class GeelyHudTrialPolicy(
    private val port: GeelyHudTrialPort,
    private val enabled: () -> Boolean,
    private val revoke: () -> Unit,
    private val factoryReadiness: () -> GeelyHudFactoryReadiness,
    private val log: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val status: (GeelyHudTrialStatus) -> Unit = {},
) {
    private var ownsOutput = false
    private var lastFrame: GeelyNavigationFrame? = null
    private var failed = false
    private var closed = false
    private var clearAttempts = 0

    @Synchronized fun update(frame: GeelyNavigationFrame?) {
        if (closed) return
        if (!ownsOutput && !enabled()) return
        if (failed && enabled()) {
            revoke()
            status(GeelyHudTrialStatus.RECONNECT_REQUIRED)
            log("geely HUD trial remains disabled until a new controller after conflict or output failure")
        }
        when (factoryReadiness()) {
            GeelyHudFactoryReadiness.WAITING -> {
                status(GeelyHudTrialStatus.NAVIGATION_STATE_UNKNOWN)
                return
            }
            GeelyHudFactoryReadiness.BLOCKED -> {
                abandon("factory-active-or-unverified")
                return
            }
            GeelyHudFactoryReadiness.READY -> Unit
        }
        if (failed || !enabled() || !fresh(frame)) {
            if (!failed && enabled() && ownsOutput) status(GeelyHudTrialStatus.STALE)
            clearOwned(if (failed) "output-failed" else if (!enabled()) "disabled" else "no-fresh-guidance")
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
            }
        } catch (error: Exception) {
            failed = true
            revoke()
            status(GeelyHudTrialStatus.SEND_FAILED)
            log("geely HUD trial output failed: ${error.javaClass.simpleName}")
            clearOwned("send-failure")
        }
    }

    /** Another broadcast source takes precedence. Never clear a newly adopted factory route. */
    @Synchronized fun foreignNavigation() {
        if (!closed && (ownsOutput || enabled())) {
            abandon("other-navigation-source")
            status(GeelyHudTrialStatus.SOURCE_CHANGED)
        }
    }

    /** The shared receiver was cleared by an external end; rebuild only a still-consented trial. */
    @Synchronized fun foreignNavigationEnded() {
        if (closed || !ownsOutput) return
        ownsOutput = false
        lastFrame = null
        clearAttempts = 0
        // Do not grant consent or erase a prior conflict/failure. update() still checks all guards.
        log("geely HUD factory navigation ended; owned output will require a fresh begin")
    }

    @Synchronized fun close() {
        if (closed) return
        revoke()
        if (ownsOutput) {
            if (factoryReadiness() == GeelyHudFactoryReadiness.READY) clearOwned("controller-closed") else abandon("factory-active-on-close")
        }
        closed = true
    }

    private fun fresh(frame: GeelyNavigationFrame?): Boolean {
        val updated = frame?.distanceUpdatedNanos ?: return false
        val age = nanoTime() - updated
        // This is an application-side expiry, not an assertion about the physical HUD's TTL.
        return age in 0 until 5_000_000_000L &&
            frame.road.toByteArray(charset("GB2312")).size <= 80
    }

    private fun clearOwned(reason: String) {
        if (!ownsOutput || clearAttempts >= 3) return
        // Recheck immediately before a clear; do not overwrite a known factory takeover.
        if (factoryReadiness() != GeelyHudFactoryReadiness.READY) { abandon("factory-active-before-clear"); return }
        clearAttempts++
        try {
            port.end()
            ownsOutput = false
            lastFrame = null
            clearAttempts = 0
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
