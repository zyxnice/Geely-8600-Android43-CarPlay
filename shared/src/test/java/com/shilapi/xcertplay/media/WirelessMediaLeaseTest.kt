package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class WirelessMediaLeaseTest {
    private class Link(var address: String? = PHONE) : WirelessMediaLease.Link {
        val calls = mutableListOf<String>()
        var acceptDisconnect = true
        var acceptConnect = true
        var failRead = false
        override fun connectedAddress(): String? {
            check(!failRead) { "Binder unavailable" }
            return address
        }
        override fun disconnect(address: String): Boolean {
            calls += "disconnect:$address"
            if (acceptDisconnect) this.address = null
            return acceptDisconnect
        }
        override fun connect(address: String): Boolean {
            calls += "connect:$address"
            if (acceptConnect) this.address = address
            return acceptConnect
        }
    }

    @Test fun selectedPhoneReleasedWhileActiveAndRestoredOnEnd() {
        val link = Link()
        val lease = WirelessMediaLease(PHONE, link) {}
        val session = Any()
        lease.activate(session)
        repeat(5) { lease.refresh() }
        assertEquals(listOf("disconnect:$PHONE"), link.calls)
        lease.end(session)
        assertEquals(listOf("disconnect:$PHONE", "connect:$PHONE"), link.calls)
        lease.close()
        assertEquals(2, link.calls.size)
    }

    @Test fun inactiveLeaseDoesNotTouchBluetooth() {
        val link = Link()
        val lease = WirelessMediaLease(PHONE, link) {}
        lease.refresh()
        lease.close()
        lease.activate(Any())
        assertTrue(link.calls.isEmpty())
    }

    @Test fun anotherPhoneIsNeverDisconnected() {
        val link = Link(OTHER)
        val lease = WirelessMediaLease(PHONE, link) {}
        lease.activate(Any())
        lease.close()
        assertTrue(link.calls.isEmpty())
    }

    @Test fun alreadyDisconnectedPhoneIsNotConnectedOnExit() {
        val link = Link(null)
        val lease = WirelessMediaLease(PHONE, link) {}
        lease.activate(Any())
        lease.close()
        assertTrue(link.calls.isEmpty())
    }

    @Test fun lateOldSessionEndCannotRestoreUnderNewSession() {
        val link = Link()
        val lease = WirelessMediaLease(PHONE, link) {}
        val old = Any()
        val next = Any()
        lease.activate(old)
        lease.activate(next)
        lease.end(old)
        assertNull(link.address)
        lease.end(next)
        assertEquals(PHONE, link.address)
    }

    @Test fun factoryReconnectIsReleasedAgainDuringSession() {
        val link = Link()
        val lease = WirelessMediaLease(PHONE, link) {}
        lease.activate(Any())
        link.address = PHONE
        lease.refresh()
        assertEquals(listOf("disconnect:$PHONE", "disconnect:$PHONE"), link.calls)
        lease.close()
        assertEquals("connect:$PHONE", link.calls.last())
    }

    @Test fun userSwitchToAnotherPhoneIsPreservedOnExit() {
        val link = Link()
        val lease = WirelessMediaLease(PHONE, link) {}
        lease.activate(Any())
        link.address = OTHER
        lease.close()
        assertEquals(listOf("disconnect:$PHONE"), link.calls)
        assertEquals(OTHER, link.address)
    }

    @Test fun rejectedDisconnectDoesNotAcquireRestoreOwnership() {
        val link = Link().apply { acceptDisconnect = false }
        val lease = WirelessMediaLease(PHONE, link) {}
        lease.activate(Any())
        link.address = null
        lease.close()
        assertEquals(listOf("disconnect:$PHONE"), link.calls)
    }

    @Test fun readFailureDoesNotBecomeReconnectRequest() {
        val link = Link()
        val lease = WirelessMediaLease(PHONE, link) {}
        val session = Any()
        lease.activate(session)
        link.failRead = true
        assertThrows(IllegalStateException::class.java) { lease.end(session) }
        assertEquals(listOf("disconnect:$PHONE"), link.calls)
        link.failRead = false
        lease.close()
        assertEquals(PHONE, link.address)
    }

    @Test fun failedRestoreRetriesAtFinalClose() {
        val link = Link()
        val lease = WirelessMediaLease(PHONE, link) {}
        val session = Any()
        lease.activate(session)
        link.acceptConnect = false
        lease.end(session)
        link.acceptConnect = true
        lease.close()
        assertEquals(listOf("disconnect:$PHONE", "connect:$PHONE", "connect:$PHONE"), link.calls)
    }

    companion object {
        private const val PHONE = "00:11:22:33:44:55"
        private const val OTHER = "66:77:88:99:AA:BB"
    }
}
