package com.shilapi.xcertplay.media

/** Renderer-thread counters only. No audio is retained or changed. Snapshots reset window counts. */
internal class AacDecodeDiagnostics(
    private val inspectSamples: Boolean = true,
    private val nowNs: () -> Long = System::nanoTime,
) {
    val streamId = java.lang.Long.toHexString(nowNs())
    var phase = "created"
    private val pending = LinkedHashMap<Long, Long>()
    private var accessUnits = 0L
    private var auBytes = 0L
    private var auMin = Int.MAX_VALUE
    private var auMax = 0
    private var tinyUnits = 0L
    private var queueAgeMaxUs = 0L
    private var inputWaitMaxUs = 0L
    private var inputUnavailable = 0L
    private var inputBufferMissing = 0L
    private var inputOversize = 0L
    private var codecMissing = 0L
    private var queueCallMaxUs = 0L
    private var submitted = 0L
    private var lastInputNs = -1L
    private var lastOutputNs = -1L
    private var outputPollMaxUs = 0L
    private var outputNotReady = 0L
    private var emptyOutput = 0L
    private var outputBufferMissing = 0L
    private var outputCount = 0L
    private var outputBytes = 0L
    private var outputPtsUs = -1L
    private var submitToOutputMaxUs = 0L
    private var unmatchedOutput = 0L
    private var timingEvictions = 0L
    private var pcmSamples = 0L
    private var nonZeroSamples = 0L
    private var zeroPcmBuffers = 0L
    private var peak = 0
    private var windowStartedNs = nowNs()
    private var windowIndex = 0L
    private var inputNoPcmWindows = 0L
    private var outputGapMaxNs = 0L
    private var firstReceiveNs = 0L
    private var firstPcmNs = 0L
    private var pcmInspectTotalNs = 0L
    private var pcmInspectMaxNs = 0L
    private var pcmInspectCalls = 0L
    private var logEmitCalls = 0L
    private var logEmitTotalNs = 0L
    private var logEmitMaxNs = 0L

    fun accessUnit(bytes: Int, receivedNs: Long) {
        accessUnits++
        auBytes += bytes
        auMin = minOf(auMin, bytes)
        auMax = maxOf(auMax, bytes)
        if (bytes <= 8) tinyUnits++ // A small AAC access unit can be valid silence, not corruption.
        if (firstReceiveNs == 0L && receivedNs > 0) firstReceiveNs = receivedNs
        if (receivedNs > 0) queueAgeMaxUs = maxOf(queueAgeMaxUs, (nowNs() - receivedNs).coerceAtLeast(0) / 1000)
    }

    fun inputDequeued(index: Int, elapsedNs: Long) {
        inputWaitMaxUs = maxOf(inputWaitMaxUs, elapsedNs / 1000)
        if (index < 0) inputUnavailable++
    }

    fun inputMissing() { inputBufferMissing++ }
    fun oversize() { inputOversize++ }
    fun noCodec() { codecMissing++ }

    fun queued(ptsUs: Long, elapsedNs: Long) {
        val now = nowNs()
        submitted++
        lastInputNs = now
        queueCallMaxUs = maxOf(queueCallMaxUs, elapsedNs / 1000)
        pending[ptsUs] = now
        // Diagnostics must remain bounded even when a decoder produces no matching output.
        if (pending.size > 128) {
            pending.remove(pending.keys.first())
            timingEvictions++
        }
    }

    fun outputPolled(noOutput: Boolean, elapsedNs: Long) {
        outputPollMaxUs = maxOf(outputPollMaxUs, elapsedNs / 1000)
        if (noOutput) outputNotReady++
    }

    fun emptyOutput() { emptyOutput++ }
    fun outputMissing() { outputBufferMissing++ }

    /** S16LE PCM before fade/volume. Exact zero detection
        not an assertion of audible output. */
    fun pcm(data: ByteArray, offset: Int, length: Int, ptsUs: Long) {
        val now = nowNs()
        if (firstPcmNs == 0L) firstPcmNs = now
        if (lastOutputNs >= 0) outputGapMaxNs = maxOf(outputGapMaxNs, (now - lastOutputNs).coerceAtLeast(0))
        lastOutputNs = now
        outputPtsUs = ptsUs
        outputCount++
        outputBytes += length
        val start = pending.remove(ptsUs)
        if (start == null) unmatchedOutput++
        else submitToOutputMaxUs = maxOf(submitToOutputMaxUs, (now - start) / 1000)
        if (!inspectSamples) return
        // Keep the per-sample loop in local Int values on the API18/Dalvik hot path.
        // Exact full-buffer statistics and existing log meanings are preserved.
        var nonZero = 0
        var bufferPeak = 0
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            val value = (data[index].toInt() and 0xff) or (data[index + 1].toInt() shl 8)
            if (value != 0) nonZero++
            bufferPeak = maxOf(bufferPeak, kotlin.math.abs(value))
            index += 2
        }
        pcmSamples += length / 2
        nonZeroSamples += nonZero
        peak = maxOf(peak, bufferPeak)
        if (nonZero == 0 && length >= 2) zeroPcmBuffers++
        // Measure the existing full PCM inspection, including timing lookup. This is wall time
        // on the renderer thread (may include preemption), not decoder CPU time.
        val inspectNs = (nowNs() - now).coerceAtLeast(0)
        pcmInspectCalls++
        pcmInspectTotalNs += inspectNs
        pcmInspectMaxNs = maxOf(pcmInspectMaxNs, inspectNs)
    }

    fun startupTiming(): String {
        val now = nowNs()
        val firstReceiveToPcmMs = if (firstReceiveNs == 0L || firstPcmNs == 0L) -1
            else (firstPcmNs - firstReceiveNs) / 1_000_000L
        val firstReceiveToPlayCallMs = if (firstReceiveNs == 0L) -1
            else (now - firstReceiveNs) / 1_000_000L
        return "firstReceiveToPcmMs=$firstReceiveToPcmMs firstReceiveToPlayCallMs=$firstReceiveToPlayCallMs"
    }

    /** Completed AAC log calls only: formatting + logcat + report callback, not async disk I/O.
     * Session totals include earlier windows; the current emission appears in the next snapshot.
     */
    fun logEmitted(elapsedNs: Long) {
        val elapsed = elapsedNs.coerceAtLeast(0)
        logEmitCalls++
        logEmitTotalNs += elapsed
        logEmitMaxNs = maxOf(logEmitMaxNs, elapsed)
    }

    /** Submission-to-output includes scheduling/backpressure, not just AAC CPU time. */
    fun snapshot(): String {
        val now = nowNs()
        val windowMs = (now - windowStartedNs).coerceAtLeast(0) / 1_000_000L
        windowIndex++
        // This is evidence, not a stall verdict: startup and scheduling can span windows.
        if (submitted > 0 && outputCount == 0L) inputNoPcmWindows++
        fun age(value: Long) = if (value < 0) -1 else (now - value).coerceAtLeast(0) / 1_000_000
        val line = "phase=$phase au=$accessUnits auBytes=$auBytes " +
            "auMin=${if (accessUnits == 0L) 0 else auMin} auMax=$auMax tinyAu=$tinyUnits " +
            "renderQueueAgeMaxUs=$queueAgeMaxUs inputWaitMaxUs=$inputWaitMaxUs " +
            "inputUnavailable=$inputUnavailable inputBufferMissing=$inputBufferMissing " +
            "inputOversize=$inputOversize codecMissing=$codecMissing submitted=$submitted " +
            "queueCallMaxUs=$queueCallMaxUs outputPollMaxUs=$outputPollMaxUs outputNotReady=$outputNotReady " +
            "emptyOutput=$emptyOutput outputBufferMissing=$outputBufferMissing " +
            "pcmBuffers=$outputCount pcmBytes=$outputBytes pcmSamples=$pcmSamples " +
            "pcmNonZeroSamples=$nonZeroSamples pcmZeroBuffers=$zeroPcmBuffers pcmPeak=$peak " +
            "lastOutputPtsUs=$outputPtsUs submitToOutputMaxUs=$submitToOutputMaxUs " +
            "unmatchedOutput=$unmatchedOutput pendingTimings=${pending.size} timingEvictions=$timingEvictions " +
            "oldestPendingMs=${pending.values.firstOrNull()?.let(::age) ?: -1} " +
            "sinceInputMs=${age(lastInputNs)} sinceOutputMs=${age(lastOutputNs)} " +
            "window=$windowIndex windowMs=$windowMs inputNoPcmWindowsTotal=$inputNoPcmWindows " +
            "outputGapMaxMs=${outputGapMaxNs / 1_000_000L} " +
            "pcmInspectCalls=$pcmInspectCalls pcmInspectTotalUs=${pcmInspectTotalNs / 1000} " +
            "pcmInspectMaxUs=${pcmInspectMaxNs / 1000} " +
            "logEmitCallsTotal=$logEmitCalls logEmitTotalUs=${logEmitTotalNs / 1000} " +
            "logEmitMaxUs=${logEmitMaxNs / 1000}"
        accessUnits = 0
        auBytes = 0
        auMin = Int.MAX_VALUE
        auMax = 0
        tinyUnits = 0
        queueAgeMaxUs = 0
        inputWaitMaxUs = 0
        inputUnavailable = 0
        inputBufferMissing = 0
        inputOversize = 0
        codecMissing = 0
        queueCallMaxUs = 0
        submitted = 0
        outputPollMaxUs = 0
        outputNotReady = 0
        emptyOutput = 0
        outputBufferMissing = 0
        outputCount = 0
        outputBytes = 0
        submitToOutputMaxUs = 0
        unmatchedOutput = 0
        timingEvictions = 0
        pcmSamples = 0
        nonZeroSamples = 0
        zeroPcmBuffers = 0
        peak = 0
        windowStartedNs = now
        outputGapMaxNs = 0
        pcmInspectCalls = 0
        pcmInspectTotalNs = 0
        pcmInspectMaxNs = 0
        return line
    }
}
