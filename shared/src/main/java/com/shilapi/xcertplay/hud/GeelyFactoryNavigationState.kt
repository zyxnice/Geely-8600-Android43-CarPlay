package com.shilapi.xcertplay.hud

/**
 * Navigation evidence from the stock AutoNavi protocol, independent of background processes.
 * UNKNOWN means no lifecycle has been observed; it is not proof that the factory is idle.
 * Only the explicitly consented, manually exclusive trial may proceed in that state.
 */
internal class GeelyFactoryNavigationState {
    enum class Activity { UNKNOWN, INACTIVE, ACTIVE }

    @Volatile var activity = Activity.UNKNOWN
        private set

    /**
     * H53 lifecycle 8/9 is real navigation start/end; 10/12 is simulation start/end.
     * Guidance evidence can reveal a route whose start preceded our registration. This conservative
     * trial predicate is not the full AMap protocol: foreign icons need not be locally encodable,
     * roads may be empty, and zero metres/seconds remain valid navigation values.
     * Empty/background updates do not start a route or erase an already known active route.
     * No timer expires ACTIVE: only a protocol end can release a known factory route.
     */
    @Synchronized fun observe(
        keyType: Int,
        state: Int?,
        icon: Int?,
        distanceMeters: Int?,
        nextRoad: String?,
        remainingMeters: Int? = null,
        remainingSeconds: Int? = null,
    ): Activity {
        if (keyType == 10019) {
            when (state) {
                8, 10 -> activity = Activity.ACTIVE
                9, 12 -> activity = Activity.INACTIVE
            }
        } else if (keyType == 10001) {
            val distances = listOfNotNull(distanceMeters, remainingMeters, remainingSeconds)
            val hasDistanceOrTime = distances.any { it >= 0 }
            val hasRouteEvidence = (icon != null && icon > 0) || !nextRoad.isNullOrBlank() ||
                distances.any { it > 0 }
            if (hasDistanceOrTime && hasRouteEvidence) activity = Activity.ACTIVE
        }
        return activity
    }
}
