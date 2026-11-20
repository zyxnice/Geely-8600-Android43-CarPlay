// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1280dp-h480dp-mdpi")
class DiPlayConnectionViewTest {
    @Test fun widescreenKeepsBothModesAndSessionActionsTogether() {
        val context = RuntimeEnvironment.getApplication()
        val chosen = mutableListOf<Boolean>()
        val view = DiPlayConnectionView(context, chosen::add)
        var disconnects = 0
        view.show(true, "Connected", "iPhone", "Wi-Fi", DiPlayAccent, listOf(
            DiPlayConnectionView.Action(R.string.diplay_continue_carplay) {},
            DiPlayConnectionView.Action(R.string.diplay_switch_phone) {},
            DiPlayConnectionView.Action(R.string.disconnect) { disconnects++ },
        ))
        layout(view, 1280)

        val usb = view.buttons().first { it.text.startsWith("USB") }
        val wifi = view.buttons().first { it.text.startsWith("Wi-Fi") }
        val disconnect = view.buttons().first { it.text == context.getString(R.string.disconnect) }
        assertEquals(LinearLayout.HORIZONTAL, view.orientation)
        assertEquals(usb.top, wifi.top)
        assertEquals(usb.width, wifi.width)
        assertTrue(wifi.isSelected)
        assertFalse(usb.isSelected)
        usb.performClick()
        wifi.performClick()
        disconnect.performClick()
        assertEquals(listOf(false, true), chosen)
        assertEquals(1, disconnects)
    }

    @Test fun compactAndLargeTextKeepActionsReachableAndAllowWrapping() {
        val application = RuntimeEnvironment.getApplication()
        val context: Context = application.createConfigurationContext(Configuration(application.resources.configuration).apply {
            screenWidthDp = 480
            screenHeightDp = 800
            fontScale = 1.5f
        })
        val view = DiPlayConnectionView(context) {}
        view.show(false, "Waiting for device", "User’s exceptionally long iPhone name", "Connect the data cable.",
            DiPlayAccent, listOf(
                DiPlayConnectionView.Action(R.string.diplay_cancel_wait) {},
                DiPlayConnectionView.Action(R.string.diagnostics) {},
            ))
        layout(view, 480)

        val buttons = view.buttons().toList()
        assertEquals(LinearLayout.VERTICAL, view.orientation)
        assertEquals(4, buttons.size)
        val usb = Rect(0, 0, buttons[0].width, buttons[0].height)
        val wifi = Rect(0, 0, buttons[1].width, buttons[1].height)
        view.offsetDescendantRectToMyCoords(buttons[0], usb)
        view.offsetDescendantRectToMyCoords(buttons[1], wifi)
        assertTrue(usb.bottom <= wifi.top)
        assertTrue(buttons[2].bottom <= buttons[3].top)
        assertTrue(buttons.all { it.height >= context.diPlayDp(52) && it.width <= 480 })
    }

    private fun layout(view: View, width: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, width, view.measuredHeight)
    }

    private fun View.buttons(): Sequence<Button> = when (this) {
        is Button -> sequenceOf(this)
        is ViewGroup -> (0 until childCount).asSequence().flatMap { getChildAt(it).buttons() }
        else -> emptySequence()
    }
}
