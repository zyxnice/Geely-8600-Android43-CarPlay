package com.shilapi.xcertplay.airplay

/** Fixed, manually triggered URL probes. Nothing here proves iOS accepted or executed navigation. */
object GeelyPhoneNavigationProbe {
    const val MAX_QUEUE_AGE_NS = 2_000_000_000L

    /** Coordinates are supplied by the parked tester, never inferred or filled with a sample POI. */
    fun command(kind: String, latitude: Double? = null, longitude: Double? = null,
                coordinateSystem: String? = null): Map<String, Any?> {
        val url = when (kind) {
            "control" -> "maps:"
            "amap-map" -> "iosamap://rootmap?sourceApplication=DiPlay8600"
            "amap-navi" -> {
                require(latitude != null && latitude.isFinite() && latitude in -90.0..90.0)
                require(longitude != null && longitude.isFinite() && longitude in -180.0..180.0)
                val dev = when (coordinateSystem) {
                    "gcj02" -> 0
                    "wgs84" -> 1
                    else -> throw IllegalArgumentException("Explicit coordinate system required")
                }
                "iosamap://navi?sourceApplication=DiPlay8600&lat=$latitude&lon=$longitude&dev=$dev&style=0"
            }
            else -> throw IllegalArgumentException("Unsupported phone navigation probe")
        }
        return linkedMapOf("type" to "requestUI", "params" to linkedMapOf("url" to url))
    }

    fun maySend(queuedAtNs: Long, nowNs: Long, sameSession: Boolean): Boolean =
        sameSession && nowNs - queuedAtNs in 0..MAX_QUEUE_AGE_NS
}
