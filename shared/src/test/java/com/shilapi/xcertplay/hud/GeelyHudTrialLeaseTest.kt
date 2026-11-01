package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class GeelyHudTrialLeaseTest {
    @Test fun `default and new process require explicit consent`() {
        val first = GeelyHudTrialLease()
        assertFalse(first.acquire(Any()))
        first.request(true)
        assertTrue(first.enabled())
        assertFalse(GeelyHudTrialLease().enabled())
    }

    @Test fun `revoked owner retains exclusion until its asynchronous clear finishes`() {
        val lease = GeelyHudTrialLease()
        val old = Any()
        val next = Any()
        lease.request(true)
        assertTrue(lease.acquire(old))
        lease.request(false)
        assertFalse(lease.enabled())
        assertFalse(lease.acquire(old))
        assertTrue(lease.owns(old))
        assertFalse(lease.acquire(next))
        lease.release(old)
        assertFalse(lease.acquire(next))
    }

    @Test fun `late old cleanup cannot revoke or release a new explicit owner`() {
        val lease = GeelyHudTrialLease()
        val old = Any()
        val next = Any()
        lease.request(true)
        assertTrue(lease.acquire(old))
        lease.revoke(old)
        lease.release(old)
        lease.request(true)
        assertTrue(lease.acquire(next))
        lease.revoke(old)
        lease.release(old)
        assertTrue(lease.acquire(next))
        assertTrue(lease.owns(next))
        assertFalse(lease.acquire(old))
    }
}
