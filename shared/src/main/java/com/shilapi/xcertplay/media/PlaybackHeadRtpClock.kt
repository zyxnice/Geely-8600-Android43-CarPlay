package com.shilapi.xcertplay.media

import java.util.ArrayDeque

/** Maps the frames accepted by one AudioTrack back to their RTP positions. Guarded by its caller. */
internal class PlaybackHeadRtpClock {
    private class Span(val firstFrame: Long, val firstSample: Long, var endFrame: Long)

    private val spans = ArrayDeque<Span>()

    var playedFrames = 0L
        private set

    /** Adjacent PCM and RTP ranges merge. A discontinuity needs a fresh head read to prune history. */
    fun recordWrite(firstFrame: Long, frameCount: Int, firstSample: Long): Boolean {
        val last = spans.peekLast()
        if (last != null && last.endFrame == firstFrame &&
            ((last.firstSample + firstFrame - last.firstFrame) and RTP_MASK) == (firstSample and RTP_MASK)
        ) {
            last.endFrame += frameCount
            return false
        }
        spans.addLast(Span(firstFrame, firstSample, firstFrame + frameCount))
        return last != null
    }

    fun advance(rawHead: Int) {
        val written = spans.peekLast()?.endFrame ?: return
        // The unsigned head and the committed write count are within one AudioTrack buffer.
        // Signed subtraction across the 32-bit rollover recovers their small difference.
        val head = written + (rawHead - written.toInt()).toLong()
        playedFrames = head.coerceIn(playedFrames, written)
        // At the boundary the last old frame has played, even if a new range is being prebuffered.
        while (spans.size > 1 && playedFrames > spans.first.endFrame) spans.removeFirst()
    }

    fun rtpSample(): Long? {
        val span = spans.peekFirst() ?: return null
        if (playedFrames <= span.firstFrame) return null
        return (span.firstSample + playedFrames - span.firstFrame) and RTP_MASK
    }

    companion object {
        private const val RTP_MASK = 0xffff_ffffL

        /** Reverse sampleTimestampUs, which truncates to whole microseconds on codec input. */
        fun sampleFromPts(ptsUs: Long, sampleRate: Int): Long =
            (ptsUs * sampleRate + 500_000L) / 1_000_000L
    }
}
