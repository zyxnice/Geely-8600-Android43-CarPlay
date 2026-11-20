// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R

internal val DiPlayBackground = Color.rgb(16, 26, 36)
internal val DiPlaySurface = Color.rgb(25, 41, 54)
internal val DiPlayBorder = Color.rgb(64, 85, 99)
internal val DiPlayAccent = Color.rgb(121, 232, 221)
internal val DiPlayText = Color.rgb(239, 246, 251)
internal val DiPlayMuted = Color.rgb(174, 194, 209)

internal fun Context.diPlayDp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()

// Let larger text move to the compact layout before a label runs out of room.
internal fun Context.diPlayLayoutWidth(): Float =
    resources.configuration.screenWidthDp / resources.configuration.fontScale.coerceAtLeast(1f)

internal fun Context.diPlayColumn() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
}

internal fun Context.diPlayRow() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
}

internal fun Context.diPlayCard(color: Int, border: Int) = GradientDrawable().apply {
    setColor(color)
    setStroke(diPlayDp(1), border)
    cornerRadius = diPlayDp(14).toFloat()
}

internal fun Context.diPlayLabel(value: CharSequence, size: Float, color: Int, bold: Boolean = false) =
    TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

internal enum class DiPlayButtonStyle { NORMAL, SELECTED, PRIMARY, DISCONNECT }

internal fun Context.diPlayButton(
    title: CharSequence,
    style: DiPlayButtonStyle = DiPlayButtonStyle.NORMAL,
    click: () -> Unit,
) = Button(this).apply {
    val (fill, border, foreground) = when (style) {
        DiPlayButtonStyle.NORMAL -> Triple(DiPlaySurface, DiPlayBorder, DiPlayText)
        DiPlayButtonStyle.SELECTED -> Triple(Color.rgb(35, 65, 73), DiPlayAccent, DiPlayAccent)
        DiPlayButtonStyle.PRIMARY -> Triple(DiPlayAccent, DiPlayAccent, DiPlayBackground)
        DiPlayButtonStyle.DISCONNECT -> Triple(Color.rgb(56, 39, 46), Color.rgb(124, 77, 85), Color.rgb(255, 192, 177))
    }
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), diPlayCard(Color.rgb(44, 83, 88), DiPlayAccent))
        addState(intArrayOf(android.R.attr.state_focused), diPlayCard(fill, DiPlayAccent))
        addState(intArrayOf(), diPlayCard(fill, border))
    }
    setTextColor(ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(if (style == DiPlayButtonStyle.PRIMARY) foreground else DiPlayMuted, DiPlayText, foreground),
    ))
    text = title
    isAllCaps = false
    textSize = 17f
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    minHeight = diPlayDp(52)
    minimumHeight = diPlayDp(52)
    minWidth = 0
    minimumWidth = 0
    setPadding(diPlayDp(12), diPlayDp(6), diPlayDp(12), diPlayDp(6))
    if (Build.VERSION.SDK_INT >= 21) stateListAnimator = null
    isSelected = style == DiPlayButtonStyle.SELECTED
    setOnClickListener { click() }
}

internal fun Context.diPlayPageHeader(
    connectionSelected: Boolean,
    settingsSelected: Boolean,
    onConnection: () -> Unit,
    onSettings: () -> Unit,
    onCarHome: () -> Unit,
): View = diPlayColumn().apply {
    val compact = diPlayLayoutWidth() < 620
    val inset = diPlayDp(if (compact) 12 else 28)
    val toolbar = diPlayRow().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(inset, diPlayDp(8), inset, diPlayDp(if (compact) 4 else 8))
    }
    setBackgroundColor(Color.rgb(18, 31, 42))
    val name = diPlayRow().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_carplay)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(diPlayDp(28), diPlayDp(28)).apply { marginEnd = diPlayDp(8) })
        addView(diPlayLabel(getString(R.string.diplay), 23f, DiPlayText, true))
    }
    val navigation = diPlayRow()
    listOf(
        Triple(R.string.diplay_connection_tab, connectionSelected, onConnection),
        Triple(R.string.settings, settingsSelected, onSettings),
    ).forEachIndexed { index, (title, selected, click) ->
        navigation.addView(
            diPlayButton(getString(title), if (selected) DiPlayButtonStyle.SELECTED else DiPlayButtonStyle.NORMAL, click),
            LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = diPlayDp(8) },
        )
    }
    if (compact) {
        toolbar.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
    } else {
        toolbar.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(navigation, LinearLayout.LayoutParams(0, -2, 1.35f))
        toolbar.addView(View(context), LinearLayout.LayoutParams(0, 1, .4f))
    }
    toolbar.addView(diPlayButton(getString(R.string.car_home), click = onCarHome))
    addView(toolbar, LinearLayout.LayoutParams(-1, -2))
    if (compact) {
        addView(navigation, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = inset
            marginEnd = inset
            bottomMargin = diPlayDp(8)
        })
    }
    addView(View(context).apply { setBackgroundColor(DiPlayBorder) }, LinearLayout.LayoutParams(-1, diPlayDp(1)))
}

internal class DiPlayConnectionView(
    context: Context,
    private val selectTransport: (wireless: Boolean) -> Unit,
) : LinearLayout(context) {
    data class Action(val title: Int, val style: DiPlayButtonStyle = DiPlayButtonStyle.NORMAL, val click: () -> Unit)

    private val wide = context.diPlayLayoutWidth() >= 860
    private val inlineModes = context.diPlayLayoutWidth() >= 340
    private val inlineActions = wide || context.diPlayLayoutWidth() >= 515
    private val wideCardHeight = if (context.resources.configuration.screenHeightDp >= 420) 246 else 174
    private val usb = TransportTile(false)
    private val wifi = TransportTile(true)
    private val statusSection = context.diPlayColumn()

    init {
        orientation = if (wide) HORIZONTAL else VERTICAL
        val modes = if (inlineModes) context.diPlayRow() else context.diPlayColumn()
        listOf(usb, wifi).forEachIndexed { index, mode ->
            modes.addView(mode, if (inlineModes) LayoutParams(0, -1, 1f).apply {
                if (index > 0) marginStart = context.diPlayDp(10)
            } else LayoutParams(-1, -2).apply { if (index > 0) topMargin = context.diPlayDp(8) })
        }
        val modeSection = context.diPlayColumn().apply {
            addView(heading(R.string.diplay_connection_mode))
            addView(modes, LayoutParams(-1, -2))
        }
        if (wide) {
            addView(modeSection, LayoutParams(0, -2, .40f))
            addView(statusSection, LayoutParams(0, -2, .60f).apply { marginStart = context.diPlayDp(18) })
        } else {
            addView(modeSection, LayoutParams(-1, -2))
            addView(statusSection, LayoutParams(-1, -2).apply { topMargin = context.diPlayDp(16) })
        }
    }

    fun show(
        wireless: Boolean?,
        status: String,
        title: String?,
        guidance: String?,
        tone: Int,
        actions: List<Action>,
        transportEnabled: Boolean = true,
    ) {
        usb.update(wireless == false, transportEnabled)
        wifi.update(wireless == true, transportEnabled)
        statusSection.removeAllViews()
        statusSection.addView(heading(R.string.diplay_connection_status))
        val pad = context.diPlayDp(if (wide) 18 else 14)
        val card = context.diPlayColumn().apply {
            background = context.diPlayCard(DiPlaySurface, DiPlayBorder)
            if (wide) minimumHeight = context.diPlayDp(wideCardHeight)
            setPadding(pad, pad, pad, pad)
        }
        card.addView(context.diPlayRow().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(tone)
                }
            }, LayoutParams(context.diPlayDp(8), context.diPlayDp(8)).apply {
                marginEnd = context.diPlayDp(8)
            })
            addView(context.diPlayLabel(status, 15f, tone, true).apply {
                if (Build.VERSION.SDK_INT >= 19) accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, LayoutParams(0, -2, 1f))
        })
        title?.let {
            card.addView(context.diPlayLabel(it, if (wide) 29f else 24f, DiPlayText, true),
                LayoutParams(-1, -2).apply { topMargin = context.diPlayDp(8) })
        }
        guidance?.let {
            card.addView(context.diPlayLabel(it, 15f, DiPlayMuted),
                LayoutParams(-1, -2).apply { topMargin = context.diPlayDp(6) })
        }
        if (actions.isNotEmpty()) {
            val row = if (inlineActions) context.diPlayRow() else context.diPlayColumn()
            actions.forEachIndexed { index, action ->
                val button = context.diPlayButton(context.getString(action.title), action.style, action.click)
                row.addView(button, if (inlineActions) LayoutParams(0, -2, 1f).apply {
                    if (index > 0) marginStart = context.diPlayDp(8)
                } else LayoutParams(-1, -2).apply { if (index > 0) topMargin = context.diPlayDp(8) })
            }
            card.addView(row, LayoutParams(-1, -2).apply { topMargin = context.diPlayDp(if (wide) 24 else 14) })
        }
        statusSection.addView(card, LayoutParams(-1, -2))
    }

    private fun heading(title: Int) = context.diPlayLabel(context.getString(title), 17f, DiPlayText, true).apply {
        setPadding(0, 0, 0, context.diPlayDp(10))
    }

    private inner class TransportTile(private val wireless: Boolean) : FrameLayout(this@DiPlayConnectionView.context) {
        private val button = context.diPlayButton("") { selectTransport(wireless) }.apply {
            val title = if (wireless) "Wi-Fi" else "USB"
            val description = context.getString(if (wireless) R.string.diplay_wireless_mode else R.string.diplay_usb_mode)
            text = SpannableString("$title\n$description").apply {
                setSpan(RelativeSizeSpan(.67f), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            gravity = Gravity.BOTTOM or Gravity.START
            textSize = if (wide) 24f else 20f
            minHeight = context.diPlayDp(if (wide) wideCardHeight else 116)
            setPadding(context.diPlayDp(14), context.diPlayDp(46), context.diPlayDp(12), context.diPlayDp(12))
        }
        private val glyph = ImageView(context).apply {
            setImageResource(if (wireless) R.drawable.ic_dp_connection else R.drawable.ic_dp_usb)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val check = context.diPlayLabel("✓", 20f, DiPlayAccent, true).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        init {
            addView(button, FrameLayout.LayoutParams(-1, -2))
            addView(glyph, FrameLayout.LayoutParams(context.diPlayDp(28), context.diPlayDp(28),
                Gravity.TOP or Gravity.START).apply {
                marginStart = context.diPlayDp(14)
                topMargin = context.diPlayDp(12)
            })
            addView(check, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                marginEnd = context.diPlayDp(14)
                topMargin = context.diPlayDp(10)
            })
        }

        fun update(selected: Boolean, enabled: Boolean) {
            button.isSelected = selected
            button.isEnabled = enabled
            check.visibility = if (selected) View.VISIBLE else View.GONE
            glyph.setColorFilter(if (selected) DiPlayAccent else DiPlayMuted)
            button.background = StateListDrawable().apply {
                val active = context.diPlayCard(Color.rgb(35, 69, 75), DiPlayAccent)
                addState(intArrayOf(android.R.attr.state_pressed), active)
                addState(intArrayOf(android.R.attr.state_focused), context.diPlayCard(DiPlaySurface, DiPlayAccent))
                addState(intArrayOf(), if (selected) active else context.diPlayCard(DiPlaySurface, DiPlayBorder))
            }
        }
    }
}
