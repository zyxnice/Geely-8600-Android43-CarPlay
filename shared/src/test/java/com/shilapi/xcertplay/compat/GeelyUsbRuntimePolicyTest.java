package com.shilapi.xcertplay.compat;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public final class GeelyUsbRuntimePolicyTest {
    @Test public void rootHubInterfaceDoesNotBlockEmptyOtgRecovery() {
        assertFalse(GeelyUsbRuntimePolicy.occupiesOtgBus("1-0:1.0"));
        assertFalse(GeelyUsbRuntimePolicy.occupiesOtgBus("usb1"));
        assertFalse(GeelyUsbRuntimePolicy.occupiesOtgBus("2-1"));
        assertTrue(GeelyUsbRuntimePolicy.occupiesOtgBus("1-1"));
        assertTrue(GeelyUsbRuntimePolicy.occupiesOtgBus("1-1:1.0"));
        assertTrue(GeelyUsbRuntimePolicy.occupiesOtgBus("1-1.2"));
    }
    @Test public void emptyDiscoveryWaitIsBoundedAndConsumedOnce() {
        GeelyUsbRuntimePolicy.EnumerationWait wait = new GeelyUsbRuntimePolicy.EnumerationWait();
        assertFalse(wait.claimRecovery(9000));
        wait.observed(false, 10000);
        wait.observed(false, 11000);
        assertFalse(wait.claimRecovery(11999));
        assertTrue(wait.claimRecovery(12000));
        assertFalse(wait.claimRecovery(99000));
        wait.observed(true, 100000);
        wait.observed(false, 101000);
        assertFalse(wait.claimRecovery(110000));
    }

    @Test public void phoneArrivalAndRuntimeReversionRestartTheEmptyWait() {
        GeelyUsbRuntimePolicy.EnumerationWait wait = new GeelyUsbRuntimePolicy.EnumerationWait();
        wait.observed(false, 0);
        wait.observed(true, 1000);
        assertFalse(wait.claimRecovery(2000));
        wait.observed(false, 9000);
        wait.resetWaiting();
        assertFalse(wait.claimRecovery(20000));
        wait.observed(false, 21000);
        assertFalse(wait.claimRecovery(22999));
        assertTrue(wait.claimRecovery(23000));
    }

    @Test public void emptyHealthyHostRestartsOnlyPower() throws Exception {
        Fake port = readyPort();
        assertEquals("enumeration-restarted", GeelyUsbRuntimePolicy.recoverEnumeration(port));
        assertEquals("[power:off, discharge, power:on]", port.calls.toString());
        assertTrue(port.read().ready());
    }

    @Test public void anyEnumeratedDevicePreventsPowerCycle() throws Exception {
        for (boolean apple : new boolean[] {false, true}) {
            Fake port = readyPort(); port.occupied = true; port.active = apple;
            assertEquals("device-preserved", GeelyUsbRuntimePolicy.recoverEnumeration(port));
            assertTrue(port.calls.isEmpty());
        }
    }

    @Test public void deviceAppearingDuringHelperLaunchIsPreserved() throws Exception {
        Fake port = readyPort(); port.attachOnSecondRead = true;
        assertEquals("device-preserved", GeelyUsbRuntimePolicy.recoverEnumeration(port));
        assertTrue(port.calls.isEmpty());
    }

    @Test public void unhealthyOrUnreadyPortIsNotPowerCycled() throws Exception {
        Fake unready = new Fake();
        assertEquals("not-ready", GeelyUsbRuntimePolicy.recoverEnumeration(unready));
        assertTrue(unready.calls.isEmpty());
        Fake fault = readyPort(); fault.fault = "overcurrent";
        try { GeelyUsbRuntimePolicy.recoverEnumeration(fault); fail(); }
        catch (IOException expected) { assertEquals("otg_power_fault", expected.getMessage()); }
        assertTrue(fault.calls.isEmpty());
    }

    @Test public void requestedOrAppliedAdbConfigurationPreventsPowerCycle() throws Exception {
        for (boolean applied : new boolean[] {false, true}) {
            Fake port = readyPort();
            if (applied) port.pendingConfig = true;
            else port.config = "accessory,ncm,adb";
            assertEquals("not-ready", GeelyUsbRuntimePolicy.recoverEnumeration(port));
            assertTrue(port.calls.isEmpty());
        }
    }

    @Test public void cancelledPowerCycleRestoresPowerBeforeLeaving() throws Exception {
        Fake port = readyPort(); port.cancelDuringDischarge = true;
        try { GeelyUsbRuntimePolicy.recoverEnumeration(port); fail(); }
        catch (IOException expected) { assertEquals("runtime_cancelled", expected.getMessage()); }
        assertTrue(port.read().powered());
        assertEquals("[power:off, discharge, power:on]", port.calls.toString());
    }

    @Test public void ignoredPowerOffFailsWithoutPretendingToRestartEnumeration() throws Exception {
        Fake port = readyPort(); port.ignorePowerOff = true;
        try { GeelyUsbRuntimePolicy.recoverEnumeration(port); fail(); }
        catch (IOException expected) { assertEquals("runtime_power_off_readback", expected.getMessage()); }
        assertEquals("[power:off, power:on]", port.calls.toString());
    }

    @Test public void powerRestoreFailureIsExplicit() throws Exception {
        Fake port = readyPort(); port.failPowerOn = true;
        try { GeelyUsbRuntimePolicy.recoverEnumeration(port); fail(); }
        catch (IOException expected) { assertEquals("runtime_rollback_failed", expected.getMessage()); }
    }

    private static Fake readyPort() {
        Fake port = new Fake();
        port.config = "accessory,ncm"; port.role = "0"; port.charge = "sdp"; port.power = "on";
        return port;
    }

    @Test public void actualH53SysfsTextPreparesAndThenStaysReady() throws Exception {
        Fake port = new Fake();
        port.power = "normal, power on"; port.fault = "working fine."; port.charge = "cdp";
        assertEquals("prepared", GeelyUsbRuntimePolicy.prepare(port));
        assertEquals("[config:accessory,ncm, host, charge:sdp]", port.calls.toString());
        port.calls.clear();
        assertEquals("ready", GeelyUsbRuntimePolicy.prepare(port));
        assertTrue(port.calls.isEmpty());
    }

    @Test public void descriptiveFaultsAreNotAcceptedBySubstring() throws Exception {
        assertFalse(GeelyUsbRuntimePolicy.isPowered("abnormal, power on"));
        assertFalse(GeelyUsbRuntimePolicy.isHealthy("not working fine."));
        Fake port = new Fake(); port.power = "normal, power on"; port.fault = "overcurrent";
        try { GeelyUsbRuntimePolicy.prepare(port); fail("fault accepted"); }
        catch (IOException expected) { assertEquals("otg_power_fault", expected.getMessage()); }
        assertTrue(port.calls.isEmpty());
    }

    @Test public void coldBootRestoresHostPowerAndSdpWithoutPersistence() throws Exception {
        Fake port = new Fake();
        assertEquals("prepared", GeelyUsbRuntimePolicy.prepare(port));
        assertTrue(port.read().ready());
        assertEquals("config:accessory,ncm,host,power:on,charge:sdp", String.join(",", port.calls));
    }
    @Test public void stableWaitingDoesNotRewriteNodes() throws Exception {
        Fake port = new Fake(); GeelyUsbRuntimePolicy.prepare(port); port.calls.clear();
        assertEquals("ready", GeelyUsbRuntimePolicy.prepare(port));
        assertTrue(port.calls.isEmpty());
    }
    @Test public void oemChargingReversionOnlyRepairsCharging() throws Exception {
        Fake port = new Fake(); GeelyUsbRuntimePolicy.prepare(port); port.calls.clear(); port.charge = "dcp";
        GeelyUsbRuntimePolicy.prepare(port);
        assertEquals("[charge:sdp]", port.calls.toString());
    }
    @Test public void selectedCarPlayDeviceIsNeverReset() throws Exception {
        Fake port = new Fake(); port.role = "0"; port.active = true;
        assertEquals("active-preserved", GeelyUsbRuntimePolicy.prepare(port));
        assertTrue(port.calls.isEmpty());
    }
    @Test public void overcurrentAndUnknownStateAreRejectedBeforeWrites() throws Exception {
        for (int scenario = 0; scenario < 4; scenario++) {
            Fake port = new Fake();
            if (scenario == 0) port.fault = "overcurrent";
            if (scenario == 1) port.config = "mtp,adb";
            if (scenario == 2) port.role = "unknown";
            if (scenario == 3) port.power = "fault/off";
            try { GeelyUsbRuntimePolicy.prepare(port); fail("unsafe state accepted"); } catch (IOException expected) { }
            assertTrue(port.calls.isEmpty());
        }
    }
    @Test public void failedHostTransitionRestoresOnlyConfiguration() throws Exception {
        Fake port = new Fake(); port.hostFails = true;
        try { GeelyUsbRuntimePolicy.prepare(port); fail(); } catch (IOException expected) { }
        assertEquals("accessory,ncm,adb", port.config);
        assertEquals("[config:accessory,ncm, host, config:accessory,ncm,adb]", port.calls.toString());
    }
    @Test public void failedReadbackRestoresAllOwnedWrites() throws Exception {
        Fake port = new Fake(); port.ignoreSdp = true;
        try { GeelyUsbRuntimePolicy.prepare(port); fail(); } catch (IOException expected) { }
        assertEquals("accessory,ncm,adb", port.config); assertEquals("dcp", port.charge); assertEquals("off", port.power);
        assertEquals("1", port.role);
    }
    @Test public void platformGuardIsExactAndOptIn() {
        assertTrue(GeelyUsbRuntimePolicy.matches(18, "C3ALFUS", "06.03.08600.H53.00060", true));
        assertFalse(GeelyUsbRuntimePolicy.matches(19, "C3ALFUS", "06.03.08600.H53.00060", true));
        assertFalse(GeelyUsbRuntimePolicy.matches(18, "C3ALFUS", "06.03.08600.H53.00061", true));
        assertFalse(GeelyUsbRuntimePolicy.matches(18, "C3ALFUS", "06.03.08600.H53.00060", false));
        assertFalse(GeelyUsbRuntimePolicy.matches(18, "other", "06.03.08600.H53.00060", true));
    }

    @Test public void cancellationDuringHostWaitPreventsForwardPowerAndChargeWrites() throws Exception {
        Fake port = new Fake(); port.cancelDuringWait = true;
        try { GeelyUsbRuntimePolicy.prepare(port); fail(); } catch (IOException expected) { }
        assertEquals("[config:accessory,ncm, host, config:accessory,ncm,adb]", port.calls.toString());
        assertEquals("accessory,ncm,adb", port.config);
    }
    @Test public void rollbackContinuesAfterItsFirstWriteFails() throws Exception {
        Fake port = new Fake(); port.ignoreSdp = true; port.failChargeRollback = true;
        try { GeelyUsbRuntimePolicy.prepare(port); fail(); }
        catch (IOException expected) { assertEquals("runtime_rollback_failed", expected.getMessage()); }
        assertEquals("off", port.power); assertEquals("accessory,ncm,adb", port.config);
    }
    @Test public void expiredOrRevokedLeaseAllowsOnlyBoundedRollback() throws Exception {
        java.io.File lease = java.io.File.createTempFile("geely-runtime-", ".lease");
        String nonce = "0123456789abcdef0123456789abcdef";
        try {
            java.io.FileOutputStream output = new java.io.FileOutputStream(lease);
            output.write(nonce.getBytes("US-ASCII")); output.close();
            GeelyUsbRuntimeHelper.verifyLease(lease, nonce, 100, 200, false);
            try { GeelyUsbRuntimeHelper.verifyLease(lease, nonce, 201, 200, false); fail(); }
            catch (IOException expected) { assertEquals("runtime_expired", expected.getMessage()); }
            assertTrue(lease.delete());
            try { GeelyUsbRuntimeHelper.verifyLease(lease, nonce, 101, 200, false); fail(); }
            catch (IOException expected) { assertEquals("runtime_cancelled", expected.getMessage()); }
            GeelyUsbRuntimeHelper.verifyLease(lease, nonce, 201, 200, true);
            try { GeelyUsbRuntimeHelper.verifyLease(lease, nonce, 1700, 200, true); fail(); }
            catch (IOException expected) { assertEquals("runtime_expired", expected.getMessage()); }
        } finally { lease.delete(); }
    }
    @Test public void restoredPropertyWithWrongRoleIsNotSuccessfulRollback() throws Exception {
        Fake port = new Fake(); port.ignoreSdp = true; port.ignoreRoleRestore = true;
        try { GeelyUsbRuntimePolicy.prepare(port); fail(); }
        catch (IOException expected) { assertEquals("runtime_rollback_failed", expected.getMessage()); }
        assertEquals("accessory,ncm,adb", port.config); assertEquals("0", port.role);
    }
    @Test public void cancelledPreparationRemainsPendingUntilRollbackCompletes() throws Exception {
        java.lang.reflect.Field pending = GeelyUsbRuntimeRecovery.class.getDeclaredField("inFlight");
        pending.setAccessible(true);
        Object previous = pending.get(null);
        java.util.concurrent.FutureTask<String> rollback = new java.util.concurrent.FutureTask<>(() -> "rolled-back");
        try {
            pending.set(null, rollback);
            assertTrue(GeelyUsbRuntimeRecovery.operationPending());
            rollback.run();
            assertFalse(GeelyUsbRuntimeRecovery.operationPending());
        } finally { pending.set(null, previous); }
    }

    private static final class Fake implements GeelyUsbRuntimePolicy.Port {
        String config = "accessory,ncm,adb", role = "1", charge = "dcp", power = "off", fault = "working fine";
        boolean active, hostFails, ignoreSdp, cancelDuringWait, cancelled, rollback, failChargeRollback, ignoreRoleRestore;
        boolean occupied, attachOnSecondRead, cancelDuringDischarge, ignorePowerOff, failPowerOn, pendingConfig;
        int reads;
        List<String> calls = new ArrayList<>();
        public GeelyUsbRuntimePolicy.State read() throws IOException {
            if (cancelled && !rollback) throw new IOException("runtime_cancelled");
            if (++reads == 2 && attachOnSecondRead) occupied = true;
            return new GeelyUsbRuntimePolicy.State(config, pendingConfig ? "accessory,ncm,adb" : config,
                    role, charge, power, fault, active, occupied || active);
        }
        public void configuration(String value) { calls.add("config:" + value); config = value; }
        public void power(String value) throws IOException {
            calls.add("power:" + value);
            if (failPowerOn && "on".equals(value)) throw new IOException("power_on_failed");
            if (!ignorePowerOff || !"off".equals(value)) power = value;
        }
        public void awaitPowerOff() throws IOException {
            calls.add("discharge");
            if (cancelDuringDischarge) { cancelled = true; throw new IOException("runtime_cancelled"); }
        }
        public void charging(String value) throws IOException {
            calls.add("charge:" + value);
            if (rollback && failChargeRollback) throw new IOException("restore_charge_failed");
            if (!ignoreSdp || !value.equals("sdp")) charge = value;
        }
        public void awaitHost() throws IOException {
            calls.add("host"); if (hostFails) throw new IOException("host_timeout"); role = "0";
            if (cancelDuringWait) cancelled = true;
        }
        public void beginRollback() { rollback = true; }
        public void awaitRestored(GeelyUsbRuntimePolicy.State original) {
            if (!ignoreRoleRestore) role = original.role;
        }
    }
}
