package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/**
 * Read-only observation of one controller's route channels. A channel token prevents a replaced
 * Bluetooth/tunnel/USB receiver from mutating the current route. Optional output has its own owner.
 */
internal class GeelyNavigationObserver(
    private val log: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val route = Iap2RouteState(nanoTime)
    private var generation = 0L
    private var closed = false
    private var suspended = false
    private var routeFrames = 0L
    private var maneuverFrames = 0L
    private var rejectedFrames = 0L
    private var lastReportNs: Long? = null
    private var lastAvailability: String? = null
    /** Optional trial sink runs on the bounded navigation worker, independently of log throttling. */
    var onCandidate: (Long, GeelyNavigationFrame?) -> Unit = { _, _ -> }
    var outputMode: String = "blocked"

    @Synchronized fun beginChannel(channel: Long = generation + 1): Long {
        if (closed) return -1L
        generation = channel
        suspended = false
        route.clear()
        onCandidate(generation, null)
        routeFrames = 0
        maneuverFrames = 0
        rejectedFrames = 0
        lastReportNs = null
        lastAvailability = null
        val mode = if (outputMode == "blocked") "observe-only" else "trial-monitor"
        log("geely navigation $mode channel=$generation vehicleOutput=$outputMode")
        return generation
    }

    /** Returns false for an old/closed channel, including non-navigation callbacks. */
    @Synchronized fun accept(channel: Long, frame: Iap2Frame): Boolean {
        if (closed || channel != generation) return false
        if (suspended) return true
        when (frame.messageId) {
            Iap2RouteState.ROUTE_GUIDANCE_UPDATE -> routeFrames++
            Iap2RouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE -> maneuverFrames++
            else -> return true
        }
        val change = route.accept(frame.messageId, frame.payload)
        if (change == Iap2RouteChange.NONE) rejectedFrames++
        report()
        return true
    }

    /** This checks local decoder freshness only; it does not clear a physical HUD. */
    @Synchronized fun tick() {
        if (!closed && !suspended) report()
    }

    @Synchronized fun pause() {
        if (closed) return
        route.clear()
        suspended = true
        onCandidate(generation, null)
        lastAvailability = null
        log("geely navigation observation paused channel=$generation vehicleOutput=$outputMode")
    }

    @Synchronized fun resume() {
        if (closed || !suspended) return
        route.clear()
        suspended = false
        lastAvailability = null
    }

    @Synchronized fun close(reason: String = "closed") {
        if (closed) return
        closed = true
        route.clear()
        onCandidate(generation, null)
        log("geely navigation observation $reason channel=$generation routeFrames=$routeFrames " +
            "maneuverFrames=$maneuverFrames noGuidanceFrames=$rejectedFrames vehicleOutput=$outputMode")
    }

    private fun report() {
        if (routeFrames == 0L && maneuverFrames == 0L) { onCandidate(generation, null); return }
        val snapshot = route.current()
        val candidate = snapshot?.let(GeelyNavigationFrame::from)
        onCandidate(generation, candidate)
        val availability = when {
            snapshot == null -> "incomplete-or-expired"
            !snapshot.distanceKnown -> "missing-or-out-of-range-distance"
            !snapshot.drivingSideKnown -> "missing-driving-side"
            candidate == null -> "unsupported-maneuver-or-road"
            else -> "candidate-only"
        }
        val now = nanoTime()
        val previous = lastReportNs
        // Emit transitions immediately and one periodic summary; do not log every distance tick.
        if (availability == lastAvailability && previous != null && now - previous < REPORT_INTERVAL_NS) return
        lastReportNs = now
        lastAvailability = availability
        log("geely navigation observation channel=$generation status=$availability routeFrames=$routeFrames " +
            "maneuverFrames=$maneuverFrames noGuidanceFrames=$rejectedFrames apple=$snapshot " +
            "candidate=${candidate?.candidateFields()} vehicleOutput=$outputMode")
    }

    private companion object { const val REPORT_INTERVAL_NS = 5_000_000_000L }
}
