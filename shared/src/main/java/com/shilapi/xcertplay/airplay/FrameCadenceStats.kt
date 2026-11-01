package com.shilapi.xcertplay.airplay

/** Single-worker, fixed-size frame timing counters. Long gaps are reported, never labelled as stalls. */
internal class FrameCadenceStats(startedNs: Long) {
    private var windowStartNs = startedNs
    private var lastFrameNs: Long? = null
    private val perSecond = LongArray(5)
    private val gapBuckets = LongArray(8)
    private var late = 0L
    private var frames = 0L

    fun frame(nowNs: Long) {
        frames++
        val second = (nowNs - windowStartNs).coerceAtLeast(0) / 1_000_000_000L
        if (second < perSecond.size) perSecond[second.toInt()]++ else late++
        lastFrameNs?.let { previous ->
            val gap = nowNs - previous
            if (gap >= 0) {
                val bucket = LIMITS_NS.indexOfFirst { gap <= it }.let { if (it < 0) 7 else it }
                gapBuckets[bucket]++
            }
        }
        lastFrameNs = nowNs
    }

    /** Window reset preserves inter-frame continuity; replacement retires only the interval baseline. */
    fun reportAndReset(nowNs: Long): String {
        val result = "frames=$frames windowMs=${(nowNs - windowStartNs).coerceAtLeast(0) / 1_000_000} " +
            "perSec=[${perSecond.joinToString(",")}] late=$late " +
            "gapBins5_17_34_50_100_250_1000_over=[${gapBuckets.joinToString(",")}]"
        windowStartNs = nowNs
        frames = 0
        late = 0
        perSecond.fill(0)
        gapBuckets.fill(0)
        return result
    }

    /** Preserve evidence already accumulated in this window when the decoder is recreated. */
    fun resetContinuity() {
        lastFrameNs = null
    }

    private companion object {
        val LIMITS_NS = longArrayOf(5, 17, 34, 50, 100, 250, 1000).map { it * 1_000_000 }.toLongArray()
    }
}
