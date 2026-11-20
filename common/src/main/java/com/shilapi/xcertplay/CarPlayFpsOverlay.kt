// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import kotlin.math.roundToInt

/** UI-only sample of the decoder's main Surface submissions, including on API 18. */
internal class CarPlayFpsOverlay(context: Context) : TextView(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var source: AndroidMediaSink? = null
    private var lastCount = 0L
    private var sampledAt = 0L
    private val update = object : Runnable {
        override fun run() {
            val sink = source ?: return
            val now = SystemClock.elapsedRealtime()
            val count = sink.mainScreenSubmittedFrameCount()
            val elapsed = now - sampledAt
            if (elapsed > 0) {
                val fps = ((count - lastCount) * 1000.0 / elapsed).roundToInt()
                val reading = context.getString(R.string.carplay_fps_value, fps)
                if (!reading.contentEquals(text)) text = reading
                lastCount = count
                sampledAt = now
            }
            handler.postDelayed(this, 1000L)
        }
    }

    init {
        visibility = View.GONE
        isClickable = false
        isFocusable = false
        textSize = 14f
        typeface = Typeface.MONOSPACE
        setTextColor(Color.WHITE)
        setPadding(context.diPlayDp(9), context.diPlayDp(5), context.diPlayDp(9), context.diPlayDp(5))
        background = GradientDrawable().apply {
            setColor(Color.argb(192, 0, 0, 0))
            cornerRadius = context.diPlayDp(6).toFloat()
        }
    }

    fun showFrom(sink: AndroidMediaSink?) {
        if (source === sink) return
        source = sink
        handler.removeCallbacks(update)
        if (sink == null) {
            visibility = View.GONE
            return
        }
        lastCount = sink.mainScreenSubmittedFrameCount()
        sampledAt = SystemClock.elapsedRealtime()
        setText(R.string.carplay_fps_sampling)
        visibility = View.VISIBLE
        handler.postDelayed(update, 1000L)
    }

    override fun onDetachedFromWindow() {
        showFrom(null)
        super.onDetachedFromWindow()
    }
}
