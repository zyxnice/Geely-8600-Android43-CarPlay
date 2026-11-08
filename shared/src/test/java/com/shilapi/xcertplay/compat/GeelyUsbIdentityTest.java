package com.shilapi.xcertplay.compat;

import org.junit.Test;
import static org.junit.Assert.*;

public final class GeelyUsbIdentityTest {
    private static final String SHA = GeelyUsbIdentity.sha256(new byte[]{1, 2, 3});
    @Test public void descriptorHashesPreserveAllBytesAndLowercasePadding() throws Exception {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                GeelyUsbIdentity.sha256(new byte[0]));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                GeelyUsbIdentity.sha256("abc".getBytes("US-ASCII")));
        byte[] allBytes = new byte[256];
        for (int i = 0; i < allBytes.length; ++i) allBytes[i] = (byte) i;
        assertEquals("40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880",
                GeelyUsbIdentity.sha256(allBytes));
        try { GeelyUsbIdentity.sha256(null); fail("Null descriptors must remain rejected"); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void canonicalPathAndCompleteEnumerationEquality() {
        GeelyUsbIdentity identity = identity("1-2.3", 1, 7, SHA);
        assertEquals("/dev/bus/usb/001/007", identity.devicePath());
        assertEquals(identity, identity("1-2.3", 1, 7, SHA));
        assertEquals(identity.hashCode(), identity("1-2.3", 1, 7, SHA).hashCode());
        assertNotEquals(identity, identity("1-2.3", 1, 8, SHA));
        assertNotEquals(identity, identity("1-2.4", 1, 7, SHA));
        assertNotEquals(identity, identity("1-2.3", 1, 7, GeelyUsbIdentity.sha256(new byte[]{3, 2, 1})));
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", SHA);
    }
    @Test public void rejectsPathInjectionInvalidUsbNumbersAndHashes() {
        for (String name : new String[]{"../1-2", "1-2/../../../dev", "1-2:1.0", "usb1", "0-1", "1-0", "01-2", "1-2;id", "1-2\n", "1-2..3"})
            invalid(name, 1, 7, SHA);
        invalid("2-1", 1, 7, SHA);
        invalid("1-2", 0, 7, SHA);
        invalid("1-2", 1, 0, SHA);
        invalid("1-2", 1, 128, SHA);
        invalid("1-2", 1, 7, "");
        invalid("1-2", 1, 7, SHA.toUpperCase(java.util.Locale.US));
        invalid("1-2", 1, 7, null);
    }
    private static GeelyUsbIdentity identity(String name, int bus, int dev, String hash) {
        return new GeelyUsbIdentity(name, bus, dev, 0x05ac, 0x1234, hash);
    }
    private static void invalid(String name, int bus, int dev, String hash) {
        try { identity(name, bus, dev, hash); fail("Accepted invalid USB identity"); }
        catch (IllegalArgumentException expected) { }
    }
}
