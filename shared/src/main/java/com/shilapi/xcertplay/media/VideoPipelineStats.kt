package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.FrameCadenceStats

/** Worker-owned timings. Output age ends at dequeue, never at physical display presentation. */
internal class VideoPipelineStats(
    private val report: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    enum class Stage {
        QUEUE_AGE, INPUT_WAIT, INPUT_COPY, INPUT_SUBMIT, OUTPUT_POLL, CODEC_AGE, OUTPUT_RELEASE,
        RECEIVE_TO_SUBMIT, SUBMIT_INTERVAL,
        INPUT_DEQUEUE,
        INPUT_PREPARE,
    }
    private val counts = LongArray(Stage.entries.size)
    private val totals = LongArray(Stage.entries.size)
    private val maxima = LongArray(Stage.entries.size)
    private var started = nanoTime()
    private var decoded = 0L
    private var rendered = 0L
    private var superseded = 0L
    // Bounded metadata only; codec may omit outputs. Never retain access-unit payloads here.
    private val receivedByPts = LinkedHashMap<Long, Long>()
    private var lastSubmitNs: Long? = null
    private val cadence = FrameCadenceStats(started)
    private var queueSamples = 0
    private var queuePeakFrames = 0
    private var queuePeakBytes = 0L
    private var queuePeakAgeNs = 0L
    private var trackedInputPeak = 0
    private var metadataEvictions = 0
    private var copiedBytes = 0L
    private var intermediateArrayBytes = 0L
    private var copiedFrames = 0

    /**
     * Successful codec-buffer copy paths only: bytes written and new Annex-B array payload.
     * Excludes frames rejected before copying, crypto/network copies and other heap allocations.
     */
    fun copy(bytes: Int, intermediateBytes: Int) {
        copiedFrames++
        copiedBytes += bytes
        intermediateArrayBytes += intermediateBytes
    }

    /** Sampled queue maxima, not a guarantee to capture peaks during a blocked codec call. */
    fun queue(snapshot: VideoDecodeQueue.Snapshot) {
        queueSamples++
        queuePeakFrames = maxOf(queuePeakFrames, snapshot.frames)
        queuePeakBytes = maxOf(queuePeakBytes, snapshot.bytes)
        queuePeakAgeNs = maxOf(queuePeakAgeNs, snapshot.oldestAgeNs)
    }

    fun input(presentationTimeUs: Long, receivedNs: Long) {
        receivedByPts[presentationTimeUs] = receivedNs
        if (receivedByPts.size > 128) {
            val iterator = receivedByPts.entries.iterator()
            iterator.next()
            iterator.remove()
            metadataEvictions++
        }
        trackedInputPeak = maxOf(trackedInputPeak, receivedByPts.size)
    }

    /** Successful release to Surface, not physical presentation or end-to-end touch latency. */
    fun released(presentationTimeUs: Long, render: Boolean, submittedNs: Long) {
        val receivedNs = receivedByPts.remove(presentationTimeUs)
        if (!render) return
        if (receivedNs != null) record(Stage.RECEIVE_TO_SUBMIT, submittedNs - receivedNs)
        lastSubmitNs?.let { record(Stage.SUBMIT_INTERVAL, submittedNs - it) }
        lastSubmitNs = submittedNs
        cadence.frame(submittedNs)
    }

    fun decoderChanged() {
        receivedByPts.clear()
        lastSubmitNs = null
        cadence.resetContinuity()
    }

    fun record(stage: Stage, elapsedNs: Long) {
        if (elapsedNs < 0) return
        val index = stage.ordinal
        counts[index]++
        totals[index] += elapsedNs
        maxima[index] = maxOf(maxima[index], elapsedNs)
    }

    fun output(render: Boolean) {
        decoded++
        if (render) rendered++
    }

    fun superseded() { superseded++ }

    fun flush(ended: Boolean = false) {
        val now = nanoTime()
        if (!ended && now - started < 5_000_000_000L) return
        val hasSamples = counts.any { it != 0L } || decoded != 0L || queueSamples != 0 || copiedFrames != 0
        // Process-wide heap occupancy, not allocated bytes or a GC counter. Do not enable
        // Dalvik's global allocation tracker: its overhead would contaminate the comparison.
        val runtime = Runtime.getRuntime()
        val heapUsedBytes = runtime.totalMemory() - runtime.freeMemory()
        val fields = Stage.entries.joinToString(" ") { stage ->
            val index = stage.ordinal
            val average = if (counts[index] == 0L) 0L else totals[index] / counts[index]
            "${stage.name}Us=${average / 1000}/${maxima[index] / 1000}"
        }
        if (hasSamples) runCatching { report("pipeline avg/max $fields decoded=$decoded rendered=$rendered " +
            "superseded=$superseded copiedFrames=$copiedFrames copiedBytes=$copiedBytes " +
            "annexBArrayBytes=$intermediateArrayBytes heapUsedBytes=$heapUsedBytes ended=$ended") }
        val outputCadence = cadence.reportAndReset(now)
        if (hasSamples) runCatching { report("Cadence: submit $outputCadence queueSamples=$queueSamples " +
            "queuePeakFrames=$queuePeakFrames queuePeakBytes=$queuePeakBytes " +
            "queuePeakAgeMs=${queuePeakAgeNs / 1_000_000} " +
            "trackedInputs=${receivedByPts.size}/$trackedInputPeak metadataEvictions=$metadataEvictions") }
        counts.fill(0)
        totals.fill(0)
        maxima.fill(0)
        decoded = 0
        rendered = 0
        superseded = 0
        queueSamples = 0
        queuePeakFrames = 0
        queuePeakBytes = 0
        queuePeakAgeNs = 0
        trackedInputPeak = receivedByPts.size
        metadataEvictions = 0
        copiedFrames = 0
        copiedBytes = 0
        intermediateArrayBytes = 0
        started = now
    }
}
