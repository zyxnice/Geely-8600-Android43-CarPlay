package com.shilapi.xcertplay.compat;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeelyKernelNcmUpPolicyTest {
    private static GeelyKernelNcmUpPolicy.Request request(String name, int index, int device) {
        return new GeelyKernelNcmUpPolicy.Request(new GeelyUsbIdentity("1-1", 1, device, 0x05ac, 0x12a8,
                new String(new char[64]).replace('\0', 'a')), name, index, "262aea373335", 3, 4, 1);
    }

    @Test public void alreadyUpNeverInvokesEnable() throws Exception {
        Fake port = new Fake();
        port.flags = 0x1003;
        assertEquals("already-up", GeelyKernelNcmUpPolicy.enable(port.identity, port));
        assertEquals(0, port.writes);
    }

    @Test public void preparedCdpAndPreservedActiveSdpCanBothEnableTheirPinnedNic() throws Exception {
        for (String charging : new String[] {"cdp", "sdp"}) {
            assertTrue(GeelyKernelNcmUpPolicy.usbReady("0", charging, "normal, power on", "working fine."));
            Fake port = new Fake();
            assertEquals("enabled", GeelyKernelNcmUpPolicy.enable(port.identity, port));
            assertEquals(1, port.writes);
        }
        assertFalse(GeelyKernelNcmUpPolicy.usbReady("0", "dcp", "on", "working fine."));
        assertFalse(GeelyKernelNcmUpPolicy.usbReady("1", "cdp", "on", "working fine."));
        assertFalse(GeelyKernelNcmUpPolicy.usbReady("-1", "cdp", "on", "working fine."));
        assertFalse(GeelyKernelNcmUpPolicy.usbReady("0", "cdp", "off", "working fine."));
        assertFalse(GeelyKernelNcmUpPolicy.usbReady("0", "cdp", "on", "fault"));
    }

    @Test public void downInterfaceIsEnabledOnceAndVerified() throws Exception {
        Fake port = new Fake();
        assertEquals("enabled", GeelyKernelNcmUpPolicy.enable(port.identity, port));
        assertEquals(1, port.writes);
        assertEquals(0x1003, port.flags);
        assertEquals("already-up", GeelyKernelNcmUpPolicy.enable(port.identity, port));
        assertEquals(1, port.writes);
    }

    @Test public void anotherActorBringingItUpBeforeWriteIsPreserved() throws Exception {
        Fake port = new Fake();
        port.upOnSecondRead = true;
        assertEquals("already-up", GeelyKernelNcmUpPolicy.enable(port.identity, port));
        assertEquals(0, port.writes);
    }

    @Test public void nameIfindexOrEnumerationReplacementNeverInheritsTheRequest() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            Fake port = new Fake();
            GeelyKernelNcmUpPolicy.Request expected = port.identity;
            if (scenario == 0) port.identity = request("usb8", 10, 6);
            if (scenario == 1) port.identity = request("usb1", 11, 6);
            if (scenario == 2) port.identity = request("usb1", 10, 7);
            try { GeelyKernelNcmUpPolicy.enable(expected, port); fail(); }
            catch (IOException error) { assertEquals("ncm_identity_changed", error.getMessage()); }
            assertEquals(0, port.writes);
        }
    }

    @Test public void replacementDuringPreparationStopsBeforeTheWrite() throws Exception {
        Fake port = new Fake();
        port.replaceOnSecondRead = true;
        try { GeelyKernelNcmUpPolicy.enable(port.identity, port); fail(); }
        catch (IOException error) { assertEquals("ncm_identity_changed", error.getMessage()); }
        assertEquals(0, port.writes);
    }

    @Test public void descriptorMacAndNcmInterfaceChangesRejectTheWrite() throws Exception {
        for (int scenario = 0; scenario < 5; scenario++) {
            Fake port = new Fake();
            GeelyKernelNcmUpPolicy.Request expected = port.identity;
            GeelyUsbIdentity usb = expected.usb;
            if (scenario == 0) usb = new GeelyUsbIdentity("1-1", 1, 6, 0x05ac, 0x12a8,
                    new String(new char[64]).replace('\0', 'b'));
            port.identity = new GeelyKernelNcmUpPolicy.Request(usb, expected.name, expected.ifindex,
                    scenario == 1 ? "262aea373336" : expected.mac,
                    scenario == 2 ? 5 : expected.controlInterface,
                    scenario == 3 ? 7 : expected.dataInterface,
                    scenario == 4 ? 2 : expected.dataAlternate);
            try { GeelyKernelNcmUpPolicy.enable(expected, port); fail(); }
            catch (IOException error) { assertEquals("ncm_identity_changed", error.getMessage()); }
            assertEquals(0, port.writes);
        }
    }

    @Test public void ignoredUpAndReplacementAfterWriteAreExplicitFailuresWithoutDown() throws Exception {
        for (boolean replace : new boolean[] {false, true}) {
            Fake port = new Fake();
            port.ignoreUp = !replace;
            port.replaceAfterWrite = replace;
            try { GeelyKernelNcmUpPolicy.enable(port.identity, port); fail(); }
            catch (IOException error) { assertEquals(replace ? "ncm_identity_changed" : "ncm_up_unverified", error.getMessage()); }
            assertEquals(1, port.writes);
            assertEquals(replace ? 0x1003 : 0x1002, port.flags);
        }
    }

    @Test public void cancelledOrFailedWriteDoesNotProduceSuccess() throws Exception {
        Fake port = new Fake();
        port.failEnable = true;
        try { GeelyKernelNcmUpPolicy.enable(port.identity, port); fail(); }
        catch (IOException error) { assertEquals("cancelled", error.getMessage()); }
        assertEquals(0, port.writes);
    }

    @Test public void argumentsRoundTripAndRejectCommandOrPathFragments() {
        GeelyKernelNcmUpPolicy.Request expected = request("usb9", 157, 47);
        assertTrue(expected.matches(GeelyKernelNcmUpPolicy.Request.parse(expected.arguments(), 0)));
        for (String bad : new String[] {"-a", "usb1;reboot", "../usb1", "usb1:0", "usb1 up"}) {
            try { request(bad, 157, 47); fail(bad); } catch (IllegalArgumentException expectedFailure) { }
        }
    }

    private static final class Fake implements GeelyKernelNcmUpPolicy.Port {
        GeelyKernelNcmUpPolicy.Request identity = request("usb1", 10, 6);
        int flags = 0x1002, reads, writes;
        boolean upOnSecondRead, replaceOnSecondRead, replaceAfterWrite, ignoreUp, failEnable;
        public GeelyKernelNcmUpPolicy.State read() {
            if (++reads == 2) {
                if (upOnSecondRead) flags |= 1;
                if (replaceOnSecondRead) identity = request("usb1", 11, 6);
            }
            return new GeelyKernelNcmUpPolicy.State(identity, flags);
        }
        public void enable(GeelyKernelNcmUpPolicy.Request expected) throws IOException {
            if (failEnable) throw new IOException("cancelled");
            writes++;
            if (!ignoreUp) flags |= 1;
            if (replaceAfterWrite) identity = request("usb1", 11, 6);
        }
    }
}
