package com.shilapi.xcertplay.hud

import java.nio.charset.Charset

/**
 * Candidate for the 8600 AutoNaviReceiver ICON vocabulary, not BYD's NEW_ICON. This value type
 * never sends data; a separate, manually enabled trial owner controls experimental dispatch.
 */
internal data class GeelyNavigationFrame(
    val icon: Int,
    val roundaboutExit: Int,
    val distanceMeters: Int,
    /** Non-null next/current road; the OEM converts it to GB2312. */
    val road: String,
    /** Unknown/out-of-range optional values stay absent, never become zero. */
    val remainingMeters: Int?,
    val remainingSeconds: Int?,
    /** Original distance update time; clock ticks and route keepalives must not refresh it. */
    val distanceUpdatedNanos: Long? = null,
) {
    /** Guidance fields only; lifecycle and Android Intent dispatch belong to the output owner. */
    fun candidateFields(): Map<String, Any> = linkedMapOf<String, Any>(
        "KEY_TYPE" to 10001,
        "ICON" to icon,
        "ROUNG_ABOUT_NUM" to roundaboutExit, // OEM spelling is part of the contract.
        "SEG_REMAIN_DIS" to distanceMeters,
        "NEXT_ROAD_NAME" to road,
    ).apply {
        remainingMeters?.let { put("ROUTE_REMAIN_DIS", it) }
        remainingSeconds?.let { put("ROUTE_REMAIN_TIME", it) }
    }

    companion object {
        /** Proposed mapping; unknown types/traffic sides or incomplete distances are not publishable. */
        fun from(maneuver: Iap2RouteManeuver): GeelyNavigationFrame? {
            if (!maneuver.distanceKnown || !maneuver.drivingSideKnown || maneuver.distanceMeters < 0 ||
                maneuver.drivingSide !in 0..1) return null
            if (!Charset.forName("GB2312").newEncoder().canEncode(maneuver.road)) return null
            val leftHand = maneuver.drivingSide == 1
            val type = maneuver.type
            val exit = if (type in 28..46) type - 27 else 0
            val icon = when {
                exit > 0 -> if (leftHand) 17 else 11
                else -> when (type) {
                    1, 20 -> 2
                    2, 21 -> 3
                    13, 22, 49, 52 -> 4
                    14, 23, 50, 53 -> 5
                    47 -> 6
                    48 -> 7
                    4, 18, 19, 26 -> if (leftHand) 19 else 8
                    3, 5, 8, 9, 11, 51 -> 9
                    6 -> if (leftHand) 17 else 11
                    7 -> if (leftHand) 18 else 12
                    10, 12, 24, 25, 27 -> 15
                    else -> return null
                }
            }
            return GeelyNavigationFrame(icon, exit, maneuver.distanceMeters, maneuver.road,
                maneuver.remainingMeters?.asOemInt(), maneuver.remainingSeconds?.asOemInt(),
                maneuver.distanceUpdatedNanos)
        }

        private fun Long.asOemInt(): Int? = if (this in 0..Int.MAX_VALUE.toLong()) toInt() else null
    }
}
