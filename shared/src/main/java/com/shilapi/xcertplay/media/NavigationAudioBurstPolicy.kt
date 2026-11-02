package com.shilapi.xcertplay.media

/**
 * Keeps one navigation focus attempt until PCM has drained and the input has been quiet.
 * A denied request still belongs to the current burst: only a later burst may request again.
 * Times are monotonic nanoseconds; queued PCM bytes come from the AudioTrack playback head.
 * The audio worker owns this state. It does not stop the CarPlay stream or discard packets.
 */
internal class NavigationAudioBurstPolicy(private val quietWindowNs: Long = 500_000_000L) {
    private var focusAttempted = false
    private var lastPcmNs = 0L
    private var drainedSinceNs: Long? = null

    /** Returns true once per burst, before its first nonempty PCM is written. */
    fun onPcm(nowNs: Long): Boolean {
        lastPcmNs = nowNs
        drainedSinceNs = null
        if (focusAttempted) return false
        focusAttempted = true
        return true
    }

    /**
     * Ends a burst only after hardware drain plus a full quiet window. A gap in UDP packets
     * alone cannot end speech still queued in AudioTrack or awaiting decoder output.
     */
    fun releaseIfIdle(nowNs: Long, queuedPcmBytes: Long, inputQueueEmpty: Boolean,
        lastArrivalNs: Long): Boolean {
        if (!focusAttempted) return false
        if (queuedPcmBytes > 0 || !inputQueueEmpty) {
            drainedSinceNs = null
            return false
        }
        val drainedSince = drainedSinceNs ?: nowNs.also { drainedSinceNs = it }
        val lastActivity = maxOf(drainedSince, lastPcmNs, lastArrivalNs)
        if (nowNs - lastActivity < quietWindowNs) return false
        focusAttempted = false
        drainedSinceNs = null
        return true
    }
}
