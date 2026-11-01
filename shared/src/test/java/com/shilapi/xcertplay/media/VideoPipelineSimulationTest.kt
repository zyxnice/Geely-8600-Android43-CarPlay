package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Virtual-time fault lab around the REAL queue/reference/recovery/output-batch components.
 * The worker schedule and codec service time below are explicit models, NOT AndroidMediaSink,
 * MediaCodec, Dalvik GC, physical display, or measured iPhone behaviour. No wall-clock FPS claims.
 */
class VideoPipelineSimulationTest {
    @Test fun repeatablePressureMatrixDistinguishesArrivalBurstsFromConsumerStalls() {
        val scenarios = listOf(
            Scenario("steady30", fps = 30),
            Scenario("steady60", fps = 60),
            Scenario("arrival_burst120ms", burstMs = 120),
            Scenario("worker_pause27ms_every500ms", pauseMs = 27, pausePeriodMs = 500),
            Scenario("worker_pause400ms", pauseMs = 400),
            Scenario("worker_pause400ms_idr1500ms", pauseMs = 400, keyFrameDelayMs = 1500),
            Scenario("codec45ms", decodeMs = 45),
            Scenario("codec45ms_pause400ms", decodeMs = 45, pauseMs = 400),
            Scenario("static_then_resume", staticFromMs = 1500, staticToMs = 3000),
        )
        val results = scenarios.map { scenario ->
            val first = run(scenario)
            assertEquals("Scenario must be deterministic: ${scenario.name}", first, run(scenario))
            first
        }
        assertEquals(0, results[0].recoveries)
        assertTrue(results[0].maxOutputGapMs <= 34)
        assertEquals(0, results[1].recoveries)
        assertTrue(results[1].maxOutputGapMs <= 17)
        assertTrue(results[2].maxOutputGapMs > results[0].maxOutputGapMs)
        assertTrue(results[3].maxOutputGapMs > results[0].maxOutputGapMs)
        assertTrue(results[4].recoveries > 0)
        assertTrue(results[5].maxOutputGapMs > results[4].maxOutputGapMs)
        assertTrue(results[6].recoveries > 0)
        // A long output gap alone is not backlog: a static sender produces no fresh frames.
        assertTrue(results[7].superseded > 0)
        assertEquals(0, results[8].recoveries)
        assertTrue(results[8].maxOutputGapMs > 1000)
        val report = File("build/reports/carplay-simulation/virtual-pressure.csv")
        requireNotNull(report.parentFile).mkdirs()
        report.writeText("scenario,sourceFrames,rendered,superseded,recoveries,queuePeakFrames,queuePeakBytes,maxQueueAgeMs,maxReceiveToSubmitMs,maxOutputGapMs\n" +
            results.joinToString("\n") { it.csv() } + "\n")
    }

    @Test fun lateKeyframeDoesNotTurnSenderWaitIntoRepeatedDecoderFailures() {
        val policy = VideoRecoveryPolicy(enabled = true, monitorProgress = true)
        policy.onRendered()
        assertEquals(VideoRecoveryPolicy.Action.KEEP_DECODER, policy.begin(true, true))
        // Phone withholds IDR; a codec-output timeout must not begin before accepted IDR input.
        for (second in 1..15) assertFalse(policy.disableIfOutputTimedOut(second * 1_000_000_000L))
        policy.onKeyFrameQueued(20, 16_000_000_000L)
        assertFalse(policy.canRender(19))
        assertTrue(policy.canRender(20))
        assertTrue(policy.disableIfOutputTimedOut(17_000_000_000L))
    }

    private data class Scenario(
        val name: String,
        val fps: Int = 30,
        val decodeMs: Int = 10,
        val burstMs: Int = 1,
        val pauseMs: Int = 0,
        val pausePeriodMs: Int = 0,
        val keyFrameDelayMs: Int = 100,
        val staticFromMs: Int = -1,
        val staticToMs: Int = -1,
    )

    private data class Result(
        val name: String, var sourceFrames: Int = 0, var rendered: Int = 0,
        var superseded: Int = 0, var recoveries: Int = 0,
        var queuePeakFrames: Int = 0, var queuePeakBytes: Long = 0,
        var maxQueueAgeMs: Long = 0, var maxReceiveToSubmitMs: Long = 0,
        var maxOutputGapMs: Long = 0,
    ) {
        fun csv() = listOf(name, sourceFrames, rendered, superseded, recoveries,
            queuePeakFrames, queuePeakBytes, maxQueueAgeMs, maxReceiveToSubmitMs, maxOutputGapMs).joinToString(",")
    }

    private data class Output(val readyMs: Int, val ptsUs: Long, val receivedNs: Long)

    private fun run(scenario: Scenario): Result {
        val queue = VideoDecodeQueue()
        val chain = VideoReferenceChain()
        val policy = VideoRecoveryPolicy(enabled = true, monitorProgress = true)
        val codec = ArrayDeque<Output>()
        val result = Result(scenario.name)
        var nextSource = 0
        var requestedKeyFrameAt: Int? = null
        var lastRenderedMs: Int? = null
        var lastCodecReadyMs = 0
        fun requestKeyFrame(nowMs: Int) {
            if (requestedKeyFrameAt == null) requestedKeyFrameAt = nowMs + scenario.keyFrameDelayMs
        }
        fun recover(nowMs: Int) {
            result.recoveries++
            if (policy.begin(true, true) == VideoRecoveryPolicy.Action.REBUILD) {
                codec.clear()
                lastCodecReadyMs = nowMs
                policy.onDecoderChanged()
            }
            chain.reset()
            requestKeyFrame(nowMs)
        }
        fun feed(frame: VideoJob.Frame, nowMs: Int) {
            if (!chain.accepts(frame.nalus, VideoCodec.H264)) { requestKeyFrame(nowMs); return }
            val ptsUs = nowMs * 1000L
            codec.addLast(Output(maxOf(nowMs, lastCodecReadyMs) + scenario.decodeMs, ptsUs, frame.receivedNs))
            lastCodecReadyMs = codec.last().readyMs
            policy.onInputQueued(nowMs * 1_000_000L)
            if (chain.needsKeyFrame) policy.onKeyFrameQueued(ptsUs, nowMs * 1_000_000L)
            chain.onQueued()
        }
        // Six seconds of source traffic followed by two seconds to drain the modeled pipeline.
        for (nowMs in 0..8000) {
            while (nextSource * 1000 / scenario.fps < 6000) {
                val producedMs = nextSource * 1000 / scenario.fps
                val arrivalMs = ((producedMs + scenario.burstMs - 1) / scenario.burstMs) * scenario.burstMs
                if (arrivalMs > nowMs) break
                nextSource++
                if (producedMs in scenario.staticFromMs until scenario.staticToMs) continue
                val requested = requestedKeyFrameAt?.let { producedMs >= it } == true
                val keyFrame = nextSource == 1 || requested
                if (requested) requestedKeyFrameAt = null
                // Fixed 4096-byte synthetic access unit: queue bytes are model input, not bitrate evidence.
                val payload = ByteArray(4096).apply { this[3] = 1; this[4] = if (keyFrame) 0x65 else 0x41 }
                queue.offer(VideoJob.Frame(payload, nowMs * 1_000_000L))
                result.sourceFrames++
            }
            val snapshot = queue.snapshot(nowMs * 1_000_000L)
            result.queuePeakFrames = maxOf(result.queuePeakFrames, snapshot.frames)
            result.queuePeakBytes = maxOf(result.queuePeakBytes, snapshot.bytes)
            result.maxQueueAgeMs = maxOf(result.maxQueueAgeMs, snapshot.oldestAgeNs / 1_000_000)
            val paused = if (scenario.pausePeriodMs > 0) {
                nowMs > 0 && nowMs % scenario.pausePeriodMs < scenario.pauseMs
            } else nowMs in 1000 until 1000 + scenario.pauseMs
            if (paused) continue

            // A synchronous modeled drain uses the production latest-output ownership rule.
            val released = mutableMapOf<Int, Output>()
            val batch = VideoOutputBatch { index, pts, _, _, render ->
                val output = released.remove(index)!!
                if (render && policy.canRender(pts)) {
                    result.rendered++
                    lastRenderedMs?.let { result.maxOutputGapMs = maxOf(result.maxOutputGapMs, (nowMs - it).toLong()) }
                    lastRenderedMs = nowMs
                    result.maxReceiveToSubmitMs = maxOf(result.maxReceiveToSubmitMs, nowMs - output.receivedNs / 1_000_000)
                    policy.onRendered()
                }
            }
            var drained = 0
            while (codec.isNotEmpty() && codec.first().readyMs <= nowMs && drained < 8) {
                val output = codec.removeFirst()
                released[drained] = output
                if (batch.offer(drained, output.ptsUs, 0, 4096)) result.superseded++
                drained++
            }
            batch.finish(true)
            // Four input slots are an assumption, not a measurement of the Freescale codec.
            if (codec.size < 4) when (val job = queue.poll(0)) {
                is VideoJob.Frame -> {
                    if (nowMs * 1_000_000L - job.receivedNs > 250_000_000L) {
                        val catchUp = queue.takeFreshH264KeyFrame(nowMs * 1_000_000L, 250_000_000L)
                        if (catchUp == null) queue.discardFrames()
                        recover(nowMs)
                        if (catchUp != null) feed(catchUp.frame, nowMs)
                    } else feed(job, nowMs)
                }
                is VideoJob.Resync -> recover(nowMs)
                else -> Unit
            }
            if (policy.disableIfOutputTimedOut(nowMs * 1_000_000L) || policy.consumeOutputStall(nowMs * 1_000_000L)) {
                codec.clear()
                policy.onDecoderChanged()
                chain.reset()
                requestKeyFrame(nowMs)
            }
        }
        queue.close()
        return result
    }
}
