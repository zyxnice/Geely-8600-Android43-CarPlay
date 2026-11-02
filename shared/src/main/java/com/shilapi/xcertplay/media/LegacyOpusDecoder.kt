package com.shilapi.xcertplay.media

import io.github.jaredmdobson.concentus.OpusDecoder

/**
 * Per-renderer Opus state for pre-21 Android, where the platform Opus decoder is unavailable.
 * The renderer's worker owns this instance. Output is signed 16-bit little-endian PCM at the
 * negotiated sample rate/channel count; consume [pcm] before the next [decode] call.
 */
internal class LegacyOpusDecoder(
    sampleRate: Int,
    private val channels: Int,
    private val log: (String) -> Unit,
) {
    private val decoder = OpusDecoder(sampleRate, channels)
    // Opus packets carry at most 120 ms. Size for 48 kHz, including lower negotiated rates.
    private val samples = ShortArray(5760 * channels)
    val pcm = ByteArray(samples.size * 2)
    private var rejectedPackets = 0L
    private var decodedPackets = 0L

    /** Returns the valid byte count in [pcm]; empty or invalid packets never replay old PCM. */
    fun decode(packet: ByteArray): Int {
        if (packet.isEmpty()) return 0
        val frames = try {
            decoder.decode(packet, 0, packet.size, samples, 0, 5760, false)
        } catch (error: Exception) {
            rejectedPackets++
            if (rejectedPackets <= 3) {
                log("Audio: software Opus packet rejected count=$rejectedPackets bytes=${packet.size} " +
                    "error=${error.javaClass.simpleName}")
            }
            return 0
        }
        val sampleCount = frames * channels
        for (index in 0 until sampleCount) {
            val sample = samples[index].toInt()
            pcm[index * 2] = sample.toByte()
            pcm[index * 2 + 1] = (sample ushr 8).toByte()
        }
        decodedPackets++
        if (decodedPackets == 1L) log("Audio: software Opus first output frames=$frames bytes=${sampleCount * 2}")
        return sampleCount * 2
    }
}
