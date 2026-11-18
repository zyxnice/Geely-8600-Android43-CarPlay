package com.shilapi.xcertplay.network

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** The Wi-Fi trial opts into the H53 factory SoftAP on this exact head unit. */
object GeelyFactoryHotspot {
    private const val META_DATA = "com.shilapi.xcertplay.GEELY_WIFI_TRIAL"

    fun enabled(context: Context): Boolean {
        if (!matches(Build.VERSION.SDK_INT, Build.BOARD, Build.DISPLAY, true)) return false
        return runCatching {
            val app = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            app.metaData?.getBoolean(META_DATA, false) == true
        }.getOrDefault(false)
    }

    internal fun matches(api: Int, board: String?, display: String?, trial: Boolean): Boolean =
        trial && api == 18 && board == "C3ALFUS" && display == "06.03.08600.H53.00060"
}
