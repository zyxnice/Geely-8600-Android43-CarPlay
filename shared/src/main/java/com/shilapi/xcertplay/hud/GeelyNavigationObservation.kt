package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** API18/8600 observation with a separate manual trial owner. Decoding never runs on CSM. */
internal class GeelyNavigationObservation internal constructor(log: (String) -> Unit) {
    private val diagnostic: (String) -> Unit = { message ->
        try { log(message) } catch (error: Exception) {
            warn("navigation diagnostic callback failed", error)
        }
    }
    private val observer = GeelyNavigationObserver(log = diagnostic)
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(128), { task ->
            Thread(task, "diplay-geely-navigation-observer").apply { isDaemon = true }
        })
    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "diplay-geely-navigation-clock").apply { isDaemon = true }
    }
    private var generation = 0L
    private var ownerRun = Int.MIN_VALUE
    private var closed = false
    private var failed = false
    private var clockStarted = false
    private var output: GeelyHudTrialOutput? = null

    private fun attachOutput(trial: GeelyHudTrialOutput) {
        output = trial
        bindCandidateSink(trial::update)
        observer.outputMode = "manual-trial-owner"
    }

    /** Bind before the first channel. Stale decoder/timer work cannot deliver a pending candidate. */
    @Synchronized internal fun bindCandidateSink(sink: (Long, GeelyNavigationFrame?) -> Unit) {
        check(!clockStarted && !closed)
        observer.onCandidate = { channel, frame -> if (isCurrent(channel)) sink(channel, frame) }
    }

    /** Cleanup may run after close; only a newer channel invalidates an already owned trial. */
    @Synchronized private fun currentGeneration(channel: Long): Boolean = channel == generation

    /** Wireless connection generations are monotonic; an old queued tunnel cannot become owner. */
    @Synchronized fun beginChannel(connectionGeneration: Int = 0): Long {
        if (closed || connectionGeneration < ownerRun) return -1L
        if (!clockStarted && !failed) {
            clockStarted = true
            timer.scheduleWithFixedDelay({ submit { observer.tick() } }, 1, 1, TimeUnit.SECONDS)
        }
        ownerRun = connectionGeneration
        val token = ++generation
        submit { observer.beginChannel(token) }
        return token
    }

    @Synchronized fun accept(channel: Long, frame: Iap2Frame): Boolean {
        if (closed || channel < 0 || channel != generation) return false
        // Other CSM consumers keep running even if optional observation has failed closed.
        if (frame.messageId != Iap2RouteState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != Iap2RouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) return true
        submit {
            if (isCurrent(channel)) observer.accept(channel, frame)
        }
        return true
    }

    fun pause() = submit { observer.pause() }
    fun resume() = submit { observer.resume() }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        timer.shutdownNow()
        if (!failed) {
            worker.queue.clear()
            worker.execute { try { output?.close() } finally { observer.close() } }
            worker.shutdown()
        }
    }

    @Synchronized private fun isCurrent(channel: Long): Boolean = !closed && !failed && channel == generation

    @Synchronized private fun submit(action: () -> Unit) {
        if (closed || failed) return
        try {
            worker.execute {
                try { action() } catch (error: Exception) { disable(error) }
            }
        } catch (error: RejectedExecutionException) {
            disable(error)
        }
    }

    @Synchronized private fun disable(error: Exception) {
        if (failed || closed) return
        failed = true
        timer.shutdownNow()
        // Incremental messages cannot be dropped and then treated as a complete route.
        worker.queue.clear()
        worker.execute {
            try { output?.close() } finally { observer.close("disabled-queue-or-decoder-failure") }
        }
        worker.shutdown()
        warn("navigation observation disabled until next controller; trial cleanup attempted", error)
    }

    private fun warn(message: String, error: Exception) {
        try { Log.w("DiPlay-Geely-Nav", message, error) } catch (_: RuntimeException) {
            // Optional diagnostics cannot fail the phone transport even if the log backend fails.
        }
    }

    companion object {
        private const val METADATA = "com.shilapi.xcertplay.GEELY_NAVIGATION_OBSERVE"

        fun isEnabled(context: Context): Boolean {
            if (Build.VERSION.SDK_INT != 18 || Build.BOARD != "C3ALFUS" ||
                !Build.DISPLAY.orEmpty().startsWith("06.03.08600")) return false
            return try {
                context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                    .metaData?.getBoolean(METADATA, false) == true
            } catch (error: PackageManager.NameNotFoundException) {
                Log.w("DiPlay-Geely-Nav", "navigation observation metadata unavailable", error)
                false
            }
        }

        fun create(context: Context, log: (String) -> Unit): GeelyNavigationObservation? =
            if (isEnabled(context)) GeelyNavigationObservation(log).also {
                it.attachOutput(GeelyHudTrialOutput(context, it.diagnostic, it::currentGeneration))
            } else null
    }
}
