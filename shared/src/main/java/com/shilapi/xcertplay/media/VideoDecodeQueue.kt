package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    /** receivedNs keeps the existing age-limit origin; trace is diagnostic metadata only. */
    data class Frame(val nalus: ByteArray, val receivedNs: Long = System.nanoTime(),
        val trace: VideoFrameTrace? = null) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    /** Ordered reference-chain break; the next dependent frame must wait for a new keyframe. */
    data class Resync(val reason: String = "video queue overflow") : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Wi-Fi delivers frames in bursts after a radio gap; the decoder's 250 ms age check bounds latency.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
    // C reserves bounded control capacity: two configs, one latest surface, one reference break.
    // The total is therefore maxFrames + 4 jobs and maxBytes of Frame + Config payloads.
    private val boundedControls: Boolean = false,
    private val onTraceDrop: (VideoFrameTrace, String) -> Unit = { _, _ -> },
) {
    private val jobs = LinkedBlockingQueue<VideoJob>()
    private var closed = false

    @Synchronized fun offer(job: VideoJob) {
        if (closed) { traceDrop(job, "queue_closed"); return }
        if (boundedControls) {
            offerBounded(job)
            return
        }
        if (job is VideoJob.Resync) {
            // A receive-side loss invalidates the queued reference chain too. Preserve controls
            // and deliver the break before any subsequent frame, including a later IDR.
            discardFrames()
        }
        if (job is VideoJob.Frame) {
            val frames = jobs.filterIsInstance<VideoJob.Frame>()
            if (frames.size >= maxFrames || frames.sumOf { it.nalus.size.toLong() } + job.nalus.size > maxBytes) {
                discardFrames()
                jobs.offer(VideoJob.Resync())
            }
            // A single oversized frame is also a lost reference chain.
            if (job.nalus.size > maxBytes) { traceDrop(job, "oversized"); return }
        }
        jobs.offer(job)
    }

    /** Config overflow is terminal for the receive worker; it must never lose CSD silently. */
    private fun offerBounded(job: VideoJob) {
        when (job) {
            is VideoJob.Config -> {
                val usage = usage()
                if (usage.configs >= 2 || usage.bytes + job.codecData.size > maxBytes) {
                    throw IOException("Video decode configuration queue exceeded its control/payload budget")
                }
            }
            is VideoJob.SurfaceChanged -> {
                // UI updates are latest-state controls. Coalesce only pending surface messages;
                // never throw on the UI thread or remove an ordered config/reference break.
                val iterator = jobs.iterator()
                while (iterator.hasNext()) if (iterator.next() is VideoJob.SurfaceChanged) iterator.remove()
            }
            is VideoJob.Resync -> discardFrames()
            is VideoJob.Frame -> {
                val usage = usage()
                if (usage.frames >= maxFrames || usage.bytes + job.nalus.size > maxBytes) {
                    discardFrames()
                    jobs.offer(VideoJob.Resync())
                }
                // Config bytes share the same budget. A frame still too large after clearing
                // frames is a declared gap, not permission to exceed the queue's memory cap.
                if (usage().bytes + job.nalus.size > maxBytes) { traceDrop(job, "oversized"); return }
            }
        }
        jobs.offer(job)
    }

    private data class Usage(val frames: Int, val configs: Int, val bytes: Long)

    private fun usage(): Usage {
        var frames = 0
        var configs = 0
        var bytes = 0L
        // All producers hold this queue's monitor; a concurrent consumer can only decrease
        // occupancy. A conservative snapshot may reject early but cannot overfill the budget.
        for (job in jobs) when (job) {
            is VideoJob.Frame -> { frames++; bytes += job.nalus.size }
            is VideoJob.Config -> { configs++; bytes += job.codecData.size }
            else -> Unit
        }
        return Usage(frames, configs, bytes)
    }

    @Synchronized fun discardFrames() {
        // Collection.removeIf is a Java default method added in API 24; use the iterator on 4.3.
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            when (val job = iterator.next()) {
                is VideoJob.Frame, is VideoJob.Resync -> { traceDrop(job, "queue_discard"); iterator.remove() }
                else -> Unit
            }
        }
    }

    /** Selected IDR is removed too; the sole decoder consumer must feed it before polling again. */
    data class CatchUp(val frame: VideoJob.Frame, val discarded: Int)

    /**
     * Skip only a contiguous frame prefix ending at a fresh H264 IDR. Never cross an ordered
     * control or retain dependent pictures from before that IDR. Caller is the sole consumer;
     * the monitor excludes producers and close while selecting/removing the prefix.
     */
    @Synchronized fun takeFreshH264KeyFrame(nowNs: Long, maxAgeNs: Long): CatchUp? {
        if (closed) return null
        var selected: VideoJob.Frame? = null
        var prefixSize = 0
        var selectedSize = 0
        for (job in jobs) {
            if (job !is VideoJob.Frame) break
            prefixSize++
            val ageNs = nowNs - job.receivedNs
            if (ageNs in 0..maxAgeNs && MediaCodecSupport.isH264KeyFrame(job.nalus)) {
                selected = job
                selectedSize = prefixSize
            }
        }
        val frame = selected ?: return null
        repeat(selectedSize) { index ->
            val removed = jobs.poll()
            if (index < selectedSize - 1 && removed != null) traceDrop(removed, "idr_catchup")
        }
        return CatchUp(frame, selectedSize - 1)
    }

    fun poll(timeoutMillis: Long): VideoJob? = jobs.poll(timeoutMillis, TimeUnit.MILLISECONDS)

    /** Releases retained buffers and makes any late producer callback harmless after shutdown. */
    @Synchronized fun close() {
        closed = true
        jobs.forEach { traceDrop(it, "queue_close") }
        jobs.clear()
    }

    private fun traceDrop(job: VideoJob, reason: String) {
        if (job is VideoJob.Frame) job.trace?.let { runCatching { onTraceDrop(it, reason) } }
    }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
