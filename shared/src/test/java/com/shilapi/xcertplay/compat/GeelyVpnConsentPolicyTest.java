package com.shilapi.xcertplay.compat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeelyVpnConsentPolicyTest {
    private static final String BEFORE = "11111111-2222-3333-4444-555555555555";
    private static final String NOW = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @Test public void firstUseAndDeniedTrustNeverRestore() {
        assertFalse(GeelyVpnConsentPolicy.needsRestore(true, false, BEFORE, NOW));
        assertFalse(GeelyVpnConsentPolicy.needsRestore(true, true, null, NOW));
    }

    @Test public void onlyAConfirmedPreviousBootCanRestore() {
        assertTrue(GeelyVpnConsentPolicy.needsRestore(true, true, BEFORE, NOW));
        assertFalse(GeelyVpnConsentPolicy.needsRestore(false, true, BEFORE, NOW));
    }

    @Test public void sameBootRevocationAndUnreadableBootIdsRequireTheDialog() {
        assertFalse(GeelyVpnConsentPolicy.needsRestore(true, true, NOW, NOW));
        assertFalse(GeelyVpnConsentPolicy.needsRestore(true, true, BEFORE, ""));
        assertFalse(GeelyVpnConsentPolicy.needsRestore(true, true, "unknown", NOW));
        assertFalse(GeelyVpnConsentPolicy.validBootId(NOW + "\n"));
    }

    @Test public void alreadyPreparedDoesNotChangeTheOwner() throws Exception {
        Fake port = new Fake(GeelyVpnConsentPolicy.PACKAGE);
        assertEquals("already_prepared", GeelyVpnConsentPolicy.restore(port));
        assertEquals(1, port.calls.size());
    }

    @Test public void freshBootRestoresOnlyTheFixedPackageAndRechecks() throws Exception {
        Fake port = new Fake(GeelyVpnConsentPolicy.LEGACY);
        assertEquals("restored", GeelyVpnConsentPolicy.restore(port));
        assertEquals(GeelyVpnConsentPolicy.PACKAGE, port.owner);
        assertEquals(3, port.calls.size());
        assertEquals(GeelyVpnConsentPolicy.LEGACY + ":" + GeelyVpnConsentPolicy.PACKAGE, port.calls.get(1));
    }

    @Test public void anotherVpnIsNeverRevoked() throws Exception {
        Fake port = new Fake("com.example.other.vpn");
        try { GeelyVpnConsentPolicy.restore(port); fail("another VPN was accepted"); }
        catch (IOException expected) { assertEquals("vpn_owned_elsewhere", expected.getMessage()); }
        assertEquals("com.example.other.vpn", port.owner);
    }

    @Test public void activeBuiltInVpnIsNotTheIdleBootDefault() throws Exception {
        Fake port = new Fake(GeelyVpnConsentPolicy.LEGACY);
        port.legacyActive = true;
        try { GeelyVpnConsentPolicy.restore(port); fail("active legacy VPN accepted"); }
        catch (IOException expected) { assertEquals("vpn_legacy_active", expected.getMessage()); }
        assertEquals(1, port.calls.size());
        assertEquals(GeelyVpnConsentPolicy.LEGACY, port.owner);
    }

    @Test public void competingOwnerChangeBetweenProbeAndRestoreIsPreserved() throws Exception {
        Fake port = new Fake(GeelyVpnConsentPolicy.LEGACY);
        port.race = true;
        try { GeelyVpnConsentPolicy.restore(port); fail("race was accepted"); }
        catch (IOException expected) { assertEquals("vpn_owned_elsewhere", expected.getMessage()); }
        assertEquals("com.example.other.vpn", port.owner);
    }

    @Test public void successfulMutationStillRequiresSystemReadback() throws Exception {
        Fake port = new Fake(GeelyVpnConsentPolicy.LEGACY);
        port.ignoreMutation = true;
        try { GeelyVpnConsentPolicy.restore(port); fail("unconfirmed grant accepted"); }
        catch (IOException expected) { assertEquals("vpn_not_confirmed", expected.getMessage()); }
    }

    @Test public void permissionFailurePropagatesWithoutAnUnconditionalRetry() throws Exception {
        final List<String> calls = new ArrayList<>();
        try {
            GeelyVpnConsentPolicy.restore(new GeelyVpnConsentPolicy.Port() {
                @Override public boolean hasLegacyVpn() { return false; }
                @Override public boolean prepare(String oldPackage, String newPackage) {
                    calls.add(oldPackage);
                    throw new SecurityException("Unauthorized Caller");
                }
            });
            fail("permission failure accepted");
        } catch (SecurityException expected) { assertEquals(1, calls.size()); }
    }

    private static final class Fake implements GeelyVpnConsentPolicy.Port {
        String owner;
        boolean race;
        boolean ignoreMutation;
        boolean legacyActive;
        final List<String> calls = new ArrayList<>();
        Fake(String owner) { this.owner = owner; }
        @Override public boolean hasLegacyVpn() { return legacyActive; }
        @Override public boolean prepare(String oldPackage, String newPackage) {
            calls.add(oldPackage + ":" + newPackage);
            if (race && newPackage != null) owner = "com.example.other.vpn";
            if (!owner.equals(oldPackage)) return false;
            if (newPackage != null && !ignoreMutation) owner = newPackage;
            return true;
        }
    }
}
