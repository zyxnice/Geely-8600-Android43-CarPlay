package com.shilapi.xcertplay.hud

internal data class BydHudGuidance(
    val distanceMeters: Int,
    /** Native HUD arrow (field 28). */
    val maneuver: Int,
    /** Gaode maneuver code: selects the HUD icon (field 8). */
    val gaode: Int = 0,
    val road: String = "",
    val arrivalEpochSeconds: Long? = null,
)

internal data class BydAppleManeuver(
    val distanceMeters: Int,
    val type: Int,
    val drivingSide: Int,
    val road: String = "",
    val remainingSeconds: Long? = null,
    val remainingMeters: Long? = null,
    val arrivalEpochSeconds: Long? = null,
)

internal enum class BydHudRouteChange {
    NONE,
    GUIDANCE,
    CLEAR,
}

/** Retains the existing BYD API and icon semantics over the shared iAP2 decoder. */
internal class BydHudRouteState(private val nanoTime: () -> Long = System::nanoTime) {
    private val route = Iap2RouteState { nanoTime() }

    fun accept(messageId: Int, payload: ByteArray): BydHudRouteChange = when (route.accept(messageId, payload)) {
        Iap2RouteChange.NONE -> BydHudRouteChange.NONE
        Iap2RouteChange.GUIDANCE -> BydHudRouteChange.GUIDANCE
        Iap2RouteChange.CLEAR -> BydHudRouteChange.CLEAR
    }

    fun current(): BydHudGuidance? {
        val maneuver = route.current() ?: return null
        val gaode = BydManeuverCodes.gaode(maneuver.type, maneuver.drivingSide)
        return BydHudGuidance(maneuver.distanceMeters, BydManeuverCodes.hudArrow(gaode),
            gaode, maneuver.road, maneuver.arrivalEpochSeconds)
    }

    fun currentApple(): BydAppleManeuver? = route.current()?.let {
        BydAppleManeuver(it.distanceMeters, it.type, it.drivingSide, it.road,
            it.remainingSeconds, it.remainingMeters, it.arrivalEpochSeconds)
    }

    fun clear(): Boolean = route.clear()

    companion object {
        const val ROUTE_GUIDANCE_UPDATE = Iap2RouteState.ROUTE_GUIDANCE_UPDATE
        const val ROUTE_GUIDANCE_MANEUVER_UPDATE = Iap2RouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE
    }
}
