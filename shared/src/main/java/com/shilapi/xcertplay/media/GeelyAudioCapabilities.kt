package com.shilapi.xcertplay.media

import android.media.AudioManager

/** H52 extends AudioManager; discover its public constants rather than assuming stream numbers. */
data class GeelyAudioCapabilities(val carPlay: Int, val navigationSpeech: Int, val navigationAlert: Int?) {
    /** Only CarPlay's alert stream may opt into the questionable vendor alert route. */
    fun navigationStream(audioType: String, separateAlerts: Boolean): Int =
        if (separateAlerts && audioType.equals("alert", ignoreCase = true)) {
            navigationAlert ?: navigationSpeech
        } else {
            navigationSpeech
        }

    companion object {
        /** Default only the verified variant/firmware; saved user choices remain authoritative. */
        fun defaultsToOemRouting(packageName: String, sdk: Int, board: String, display: String): Boolean =
            packageName == "com.shihab.diplay.repair1.geely8600" && sdk == 18 &&
                board == "C3ALFUS" && display == "06.03.08600.H53.00060"

        fun detect(): GeelyAudioCapabilities? = fromFields { name ->
            runCatching { AudioManager::class.java.getField(name).getInt(null) }.getOrNull()
        }

        internal fun fromFields(read: (String) -> Int?): GeelyAudioCapabilities? {
            val media = read("STREAM_CARPLAYAUDIO") ?: return null
            val speech = read("STREAM_NAVI_TTS") ?: return null
            // H41 has NAVI_TTS but no CARPLAYAUDIO. Do not activate a guessed H52 profile there.
            if (media != 23 || speech != 11 || read("MODE_CARPLAYAUDIO") != 14) return null
            return GeelyAudioCapabilities(media, speech, read("STREAM_NAVI_ALERT")?.takeIf { it == 25 })
        }
    }
}
