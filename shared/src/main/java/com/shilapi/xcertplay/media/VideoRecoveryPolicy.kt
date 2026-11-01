package com.shilapi.xcertplay.media

/**
 * Decoder-worker-owned recovery state. Only a lost reference chain after a real output may keep
 * the codec; format, surface and hardware failures must still rebuild it. No Android calls live
 * here, so the first-output, stale-output and bounded-fallback decisions can be tested directly.
 */
internal class VideoRecoveryPolicy(
    private val enabled: Boolean,
    private val outputTimeoutNs: Long = 1_000_000_000L,
    private val monitorProgress: Boolean = false,
) {
    enum class Action { REBUILD, KEEP_DECODER, ALREADY_WAITING }

    private var hasRenderedOutput = false
    private var disabledForSession = false
    private var recovering = false
    private var keyFramePtsUs: Long? = null
    private var keyFrameQueuedNs: Long? = null
    private var minimumOutputPtsUs: Long? = null
    private var pendingInputSinceNs: Long? = null
    private var pendingInputs = 0
    private var rejectedSinceNs: Long? = null

    /** B-output2: only accepted codec input is evidence of decoder work, not socket receipt. */
    fun onInputQueued(nowNs: Long) {
        rejectedSinceNs = null
        if (pendingInputSinceNs == null) pendingInputSinceNs = nowNs
        pendingInputs++
    }

    /** Require both sustained input and elapsed time; static scenes cannot trigger this watchdog. */
    fun consumeOutputStall(nowNs: Long): Boolean {
        val since = pendingInputSinceNs ?: return false
        if (!monitorProgress || recovering || pendingInputs < 30 || nowNs - since < 1_500_000_000L) return false
        clearInputProgress()
        return true
    }

    /** Missing random-access input is a sender/reference-chain wait, never proof of codec failure. */
    fun reportKeyFrameWaitIfDue(nowNs: Long): Boolean {
        if (!monitorProgress) return false
        val since = rejectedSinceNs
        if (since == null) { rejectedSinceNs = nowNs; return false }
        if (nowNs - since < 5_000_000_000L) return false
        rejectedSinceNs = nowNs
        return true
    }

    private fun clearInputProgress() {
        pendingInputSinceNs = null
        pendingInputs = 0
    }

    /** Queue/age gaps and B's accepted-input stall probe may try a fresh IDR on a healthy codec. */
    fun begin(referenceGap: Boolean, decoderAvailable: Boolean): Action {
        if (!enabled || disabledForSession || !referenceGap || !decoderAvailable || !hasRenderedOutput) {
            if (recovering && (!referenceGap || !decoderAvailable)) disabledForSession = true
            return Action.REBUILD
        }
        if (recovering) {
            // A second loss invalidates the previous recovery IDR, but must not extend the
            // already-started fallback deadline indefinitely under recurring queue pressure.
            keyFramePtsUs = null
            return Action.ALREADY_WAITING
        }
        recovering = true
        clearInputProgress()
        keyFramePtsUs = null
        keyFrameQueuedNs = null
        return Action.KEEP_DECODER
    }

    /** Called only after MediaCodec accepted a new random-access frame, never on receipt alone. */
    fun onKeyFrameQueued(presentationTimeUs: Long, nowNs: Long) {
        if (!recovering || keyFramePtsUs != null) return
        keyFramePtsUs = presentationTimeUs
        minimumOutputPtsUs = presentationTimeUs
        if (keyFrameQueuedNs == null) keyFrameQueuedNs = nowNs
    }

    /** Old codec output is discarded until output for the newly submitted keyframe arrives. */
    fun canRender(presentationTimeUs: Long): Boolean {
        val boundary = if (recovering) keyFramePtsUs ?: return false else minimumOutputPtsUs
        return boundary == null || presentationTimeUs >= boundary
    }

    fun onRendered() {
        clearInputProgress()
        rejectedSinceNs = null
        hasRenderedOutput = true
        recovering = false
        keyFramePtsUs = null
        keyFrameQueuedNs = null
    }

    /**
     * A static screen or a phone still withholding an IDR is not a failed decoder. The timeout
     * starts with accepted keyframe input; failure disables this strategy for the stream lifetime.
     */
    fun disableIfOutputTimedOut(nowNs: Long): Boolean {
        val submitted = keyFrameQueuedNs ?: return false
        if (!recovering || nowNs - submitted < outputTimeoutNs) return false
        disabledForSession = true
        onDecoderChanged()
        return true
    }

    /** A new codec/format/surface needs its own first output; a failed strategy stays disabled. */
    fun onDecoderChanged() {
        clearInputProgress()
        rejectedSinceNs = null
        hasRenderedOutput = false
        recovering = false
        keyFramePtsUs = null
        keyFrameQueuedNs = null
        minimumOutputPtsUs = null
    }

    /** A hardware/input exception during an attempted soft recovery must not restart that loop. */
    fun onDecoderFailure() {
        if (recovering) disabledForSession = true
        onDecoderChanged()
    }
}
