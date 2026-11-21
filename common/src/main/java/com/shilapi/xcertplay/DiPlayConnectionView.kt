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

internal val DiPlayBackground = Color.rgb(11, 20, 29)
internal val DiPlaySurface = Color.rgb(21, 35, 46)
internal val DiPlayBorder = Color.rgb(64, 85, 99)
internal val DiPlayAccent = Color.rgb(130, 237, 219)
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
    val wide = diPlayLayoutWidth() >= 1024
    val inset = diPlayDp(if (wide) 48 else if (compact) 12 else 28)
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
        addView(diPlayLabel(getString(R.string.diplay), if (wide) 33f else 23f, DiPlayText, true))
    }
    val navigation = diPlayRow()
    listOf(
        Triple(R.string.diplay_connection_tab, connectionSelected, onConnection),
        Triple(R.string.settings, settingsSelected, onSettings),
    ).forEachIndexed { index, (title, selected, click) ->
        navigation.addView(
            diPlayButton(getString(title), if (selected) DiPlayButtonStyle.SELECTED else DiPlayButtonStyle.NORMAL, click).apply {
                if (wide) textSize = 22f
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = diPlayDp(8) },
        )
    }
    if (compact) {
        toolbar.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
    } else {
        toolbar.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(navigation, LinearLayout.LayoutParams(diPlayDp(if (wide) 320 else 260), -2))
        toolbar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
    }
    toolbar.addView(diPlayButton(getString(R.string.car_home), click = onCarHome).apply {
        if (wide) textSize = 20f
    })
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
    private val inlineActions = wide || context.diPlayLayoutWidth() >= 515
    private val roomy = context.diPlayLayoutWidth() >= 1150
    var phonePickerVisible: Boolean = false
        private set
    private val rail = DiPlayTransportRail(context, wide, selectTransport)
    private val statusSection = context.diPlayColumn()

    init {
        orientation = if (wide) HORIZONTAL else VERTICAL
        if (wide) {
            // The viewport supplies the remaining height after the content's minimum size.
            val railWeight = if (roomy) .25f else .32f
            addView(rail, LayoutParams(0, -1, railWeight))
            addView(statusSection, LayoutParams(0, -1, 1f - railWeight).apply { marginStart = context.diPlayDp(24) })
        } else {
            addView(rail, LayoutParams(-1, -2))
            addView(statusSection, LayoutParams(-1, -2, 1f).apply { topMargin = context.diPlayDp(16) })
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
        rail.show(wireless, transportEnabled)
        statusSection.removeAllViews()
        val pad = context.diPlayDp(if (wide) 32 else 14)
        val card = context.diPlayColumn().apply {
            background = context.diPlayCard(DiPlaySurface, DiPlayBorder)
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
            addView(context.diPlayLabel(status, if (wide) 22f else 15f, tone, true).apply {
                if (Build.VERSION.SDK_INT >= 19) accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, LayoutParams(0, -2, 1f))
        })
        val detail = context.diPlayRow().apply {
            gravity = Gravity.CENTER_VERTICAL
            if (title != null || guidance != null) {
                val size = context.diPlayDp(if (wide) 100 else 44)
                val icon = when {
                    title != null -> R.drawable.ic_dp_phone
                    wireless == false -> R.drawable.ic_dp_usb
                    else -> R.drawable.ic_dp_connection
                }
                addView(context.diPlayIconTile(icon, if (wide) 28 else 10),
                    LayoutParams(size, size).apply { marginEnd = context.diPlayDp(if (wide) 28 else 12) })
                addView(context.diPlayColumn().apply {
                    title?.let { addView(context.diPlayLabel(it, if (wide) 49f else 24f, DiPlayText, true)) }
                    guidance?.let {
                        addView(context.diPlayLabel(it, if (wide) 24f else 15f, DiPlayMuted), LayoutParams(-1, -2).apply {
                            if (title != null) topMargin = context.diPlayDp(4)
                        })
                    }
                }, LayoutParams(0, -2, 1f))
            }
        }
        card.addView(detail, LayoutParams(-1, -2, 1f).apply {
            if (title != null || guidance != null) topMargin = context.diPlayDp(12)
        })
        if (actions.isNotEmpty()) {
            val row = if (inlineActions) context.diPlayRow() else context.diPlayColumn()
            actions.forEachIndexed { index, action ->
                val button = context.diPlayButton(context.getString(action.title), action.style, action.click)
                if (wide) {
                    button.textSize = 23f
                    button.minimumHeight = context.diPlayDp(68)
                    if (roomy && action.style == DiPlayButtonStyle.PRIMARY) button.minWidth = context.diPlayDp(236)
                    if (roomy && action.style == DiPlayButtonStyle.DISCONNECT && index > 0) {
                        row.addView(View(context), LayoutParams(0, 1, 1f))
                    }
                }
                row.addView(button, when {
                    roomy -> LayoutParams(-2, -2).apply {
                        if (index > 0) marginStart = context.diPlayDp(12)
                    }
                    inlineActions -> LayoutParams(0, -2, 1f).apply {
                        if (index > 0) marginStart = context.diPlayDp(8)
                    }
                    else -> LayoutParams(-1, -2).apply { if (index > 0) topMargin = context.diPlayDp(8) }
                })
            }
            card.addView(row, LayoutParams(-1, -2).apply { topMargin = context.diPlayDp(if (wide) 18 else 14) })
        }
        statusSection.addView(card, LayoutParams(-1, -2, 1f))
    }

    fun showPhonePicker(phones: View, onCancel: () -> Unit, onPair: () -> Unit) {
        phonePickerVisible = true
        statusSection.removeAllViews()
        val panel = context.diPlayColumn().apply {
            val inset = context.diPlayDp(26)
            setPadding(inset, inset, inset, inset)
            background = context.diPlayCard(DiPlaySurface, DiPlayBorder)
        }
        panel.addView(context.diPlayLabel(
            context.getString(R.string.choose_your_iphone), 36f, DiPlayText, true,
        ))
        panel.addView(phones, LayoutParams(-1, -2, 1f))
        val actions = context.diPlayRow()
        actions.addView(context.diPlayButton(context.getString(R.string.pair_another), click = onPair))
        actions.addView(context.diPlayButton(context.getString(R.string.cancel), click = onCancel),
            LayoutParams(-2, -2).apply { marginStart = context.diPlayDp(12) })
        panel.addView(actions)
        statusSection.addView(panel, LayoutParams(-1, -2, 1f))
    }
}

internal class DiPlayTransportRail(
    context: Context,
    private val wide: Boolean,
    private val selectTransport: (Boolean) -> Unit,
) : LinearLayout(context) {
    private val inline = !wide && context.diPlayLayoutWidth() >= 340
    private val tileMinimumHeight = if (wide) 106 else 116
    private val usb = TransportTile(false)
    private val wifi = TransportTile(true)

    init {
        orientation = VERTICAL
        addView(context.diPlayLabel(
            context.getString(R.string.diplay_connection_mode), if (wide) 23f else 17f, DiPlayText, true,
        ).apply { setPadding(0, 0, 0, context.diPlayDp(10)) })
        val modes = if (inline) context.diPlayRow() else context.diPlayColumn()
        listOf(usb, wifi).forEachIndexed { index, tile ->
            modes.addView(tile, if (inline) {
                LayoutParams(0, -1, 1f).apply { if (index > 0) marginStart = context.diPlayDp(10) }
            } else {
                LayoutParams(-1, -2, if (wide) 1f else 0f).apply {
                    if (index > 0) topMargin = context.diPlayDp(12)
                }
            })
        }
        addView(modes, LayoutParams(-1, -2, if (wide) 1f else 0f))
    }

    fun show(wireless: Boolean?, enabled: Boolean = true) {
        usb.update(wireless == false, enabled)
        wifi.update(wireless == true, enabled)
    }

    private inner class TransportTile(private val wireless: Boolean) : FrameLayout(this@DiPlayTransportRail.context) {
        private val button = context.diPlayButton("") { selectTransport(wireless) }.apply {
            val title = if (wireless) "Wi-Fi" else "USB"
            val description = context.getString(if (wireless) R.string.diplay_wireless_mode else R.string.diplay_usb_mode)
            text = SpannableString("$title\n$description").apply {
                setSpan(RelativeSizeSpan(.67f), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            gravity = (if (wide) Gravity.CENTER_VERTICAL else Gravity.BOTTOM) or Gravity.START
            textSize = if (wide) 32f else 20f
            minimumHeight = context.diPlayDp(tileMinimumHeight)
            val inset = context.diPlayDp(if (wide) 20 else 14)
            if (wide) setPadding(context.diPlayDp(107), inset, context.diPlayDp(38), inset)
            else setPadding(inset, context.diPlayDp(52), inset, inset)
        }
        private val glyph = context.diPlayIconTile(
            if (wireless) R.drawable.ic_dp_connection else R.drawable.ic_dp_usb, if (wide) 12 else 8,
        )
        private val check = ImageView(context).apply {
            setImageResource(R.drawable.ic_dp_check)
            setColorFilter(DiPlayBackground)
            val padding = context.diPlayDp(5)
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(DiPlayAccent)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        init {
            addView(button, FrameLayout.LayoutParams(-1, -1))
            val iconSize = context.diPlayDp(if (wide) 68 else 36)
            val inset = context.diPlayDp(if (wide) 20 else 14)
            addView(glyph, FrameLayout.LayoutParams(iconSize, iconSize,
                (if (wide) Gravity.CENTER_VERTICAL else Gravity.TOP) or Gravity.START).apply {
                marginStart = inset
                if (!wide) topMargin = inset
            })
            val checkSize = context.diPlayDp(if (wide) 26 else 22)
            addView(check, FrameLayout.LayoutParams(checkSize, checkSize, Gravity.TOP or Gravity.END).apply {
                marginEnd = inset
                topMargin = inset
            })
        }

        private var renderedState: Pair<Boolean, Boolean>? = null

        fun update(selected: Boolean, enabled: Boolean) {
            val state = selected to enabled
            if (renderedState == state) return
            renderedState = state
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

internal fun Context.diPlayIconTile(resource: Int, inset: Int) = ImageView(this).apply {
    setImageResource(resource)
    setColorFilter(DiPlayAccent)
    val padding = diPlayDp(inset)
    setPadding(padding, padding, padding, padding)
    background = diPlayCard(Color.rgb(37, 60, 73), DiPlayBorder)
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}
