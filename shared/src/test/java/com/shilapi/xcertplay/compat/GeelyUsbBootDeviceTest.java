package com.shilapi.xcertplay.compat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class GeelyUsbBootDeviceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void onlyDirectAppleConfigurationTwoHasRecoveryIdentity() throws Exception {
        File devices = temporary.newFolder();
        File phone = phone(devices);
        assertTrue(new File(devices, "2-1").mkdir());
        assertEquals("1-1:2", GeelyUsbRuntimeHelper.bootRecoveryDevice(devices));
        write(phone, "devnum", "3");
        assertEquals("1-1:3", GeelyUsbRuntimeHelper.bootRecoveryDevice(devices));
        write(phone, "bConfigurationValue", "6");
        assertNull(GeelyUsbRuntimeHelper.bootRecoveryDevice(devices));
    }

    @Test public void otherDownstreamOrIncompleteNodesRejectRecovery() throws Exception {
        // Windows cannot create Linux interface names containing ':'. Their name rules
        // are covered separately without pretending an unsuccessful mkdir created a node.
        for (String other : new String[] {"1-2", "1-1.2", "1-unknown"}) {
            File devices = temporary.newFolder(); phone(devices);
            assertTrue(new File(devices, other).mkdir());
            assertNull(other, GeelyUsbRuntimeHelper.bootRecoveryDevice(devices));
        }
    }

    @Test public void linuxInterfaceNamesOnlyBelongToTheDirectPhone() {
        assertTrue(GeelyUsbRuntimeHelper.bootPhoneInterface("1-1:2.0"));
        assertFalse(GeelyUsbRuntimeHelper.bootPhoneInterface("1-2:1.0"));
        assertFalse(GeelyUsbRuntimeHelper.bootPhoneInterface("1-1.2:1.0"));
        assertFalse(GeelyUsbRuntimeHelper.bootPhoneInterface("1-1:unknown"));
    }

    @Test public void differentVendorProductOrUnknownEnumerationNumberRejectsRecovery() throws Exception {
        for (String field : new String[] {"idVendor", "idProduct", "devnum"}) {
            File devices = temporary.newFolder(); File phone = phone(devices);
            write(phone, field, "unknown");
            assertNull(field, GeelyUsbRuntimeHelper.bootRecoveryDevice(devices));
        }
    }

    private File phone(File devices) throws Exception {
        File phone = new File(devices, "1-1"); assertTrue(phone.mkdir());
        write(phone, "idVendor", "05ac"); write(phone, "idProduct", "12a8");
        write(phone, "bConfigurationValue", "2"); write(phone, "devnum", "2");
        return phone;
    }

    private void write(File directory, String name, String value) throws Exception {
        Files.write(new File(directory, name).toPath(), value.getBytes(StandardCharsets.US_ASCII));
    }
}
