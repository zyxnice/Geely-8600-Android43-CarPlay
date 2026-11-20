package com.shilapi.xcertplay.orchestration

import android.os.SystemClock
import com.shilapi.xcertplay.airplay.AirPlayDisconnectEvidence
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.network.WirelessHotspotInfo
import java.net.InetAddress
import java.util.UUID

class WirelessHotspotSnapshot internal constructor(info: WirelessHotspotInfo) {
    val backend = info.backend
    val channel = info.channel.takeIf { it > 0 }
    val frequencyMHz = info.frequencyMHz
    val interfaceName = info.interfaceName
    val expectedAddress: InetAddress? = info.hostAddress
}

/** Controller state captured before the caller starts teardown or retries. */
data class WirelessDisconnectObservation internal constructor(
    val sessionId: String,
    val generation: Int,
    val observedAtMillis: Long,
    val observedElapsedMillis: Long,
    val connectedMillis: Long,
    val evidence: AirPlayDisconnectEvidence,
    val hotspotMode: WirelessHotspotMode,
    val factoryHotspot: Boolean,
    val hotspot: WirelessHotspotSnapshot?,
    val handoffRequested: Boolean,
    val tunnelReady: Boolean,
    val handoffComplete: Boolean,
    val bluetoothBootstrapOpen: Boolean,
)

/** Correlates peer-initiated screen teardown and the first terminal signal for an active session. */
internal class WirelessDisconnectTracker {
    private class Active(val session: AirPlaySession, val generation: Int) {
        val id = UUID.randomUUID().toString()
        val startedAt = SystemClock.elapsedRealtime()
        var peerTeardown = false
        var terminalRecorded = false
    }

    private var active: Active? = null

    @Synchronized
    fun activate(generation: Int, session: AirPlaySession) {
        if (active?.session !== session || active?.generation != generation) active = Active(session, generation)
    }

    @Synchronized
    fun observe(
        generation: Int,
        session: AirPlaySession?,
        evidence: AirPlayDisconnectEvidence,
        capture: (String, Long) -> WirelessDisconnectObservation,
    ): WirelessDisconnectObservation? {
        val current = active ?: return null
        if (current.generation != generation || (session != null && current.session !== session) ||
            current.terminalRecorded) return null
        when (evidence.reason) {
            AirPlayDisconnectEvidence.Reason.PEER_TEARDOWN -> current.peerTeardown = true
            AirPlayDisconnectEvidence.Reason.UNSPECIFIED_CLOSE -> {
                current.terminalRecorded = true
                if (current.peerTeardown) return null
            }
            else -> current.terminalRecorded = true
        }
        return capture(current.id, current.startedAt)
    }

    @Synchronized
    fun end(generation: Int, session: AirPlaySession) {
        if (active?.generation == generation && active?.session === session) active = null
    }

    @Synchronized
    fun clear() {
        active = null
    }
}
