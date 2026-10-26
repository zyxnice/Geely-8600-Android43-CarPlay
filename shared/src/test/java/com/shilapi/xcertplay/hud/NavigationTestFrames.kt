package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.ByteArrayOutputStream

internal object NavigationTestFrames {
    fun tlv(id: Int, vararg value: Int): ByteArray = ByteArrayOutputStream().apply {
        val length = value.size + 4
        write(length ushr 8); write(length); write(id ushr 8); write(id)
        value.forEach(::write)
    }.toByteArray()

    fun fields(vararg values: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        values.forEach(::write)
    }.toByteArray()

    fun maneuver(index: Int = 1, type: Int = 2): Iap2Frame = Iap2Frame(
        Iap2RouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
        fields(tlv(1, index ushr 8, index), tlv(3, type), tlv(8, 0)),
    )

    fun route(index: Int = 1, distance: Int = 250): Iap2Frame = Iap2Frame(
        Iap2RouteState.ROUTE_GUIDANCE_UPDATE,
        fields(tlv(1, 1), tlv(10, distance ushr 24, distance ushr 16, distance ushr 8, distance),
            tlv(13, index ushr 8, index)),
    )
}
