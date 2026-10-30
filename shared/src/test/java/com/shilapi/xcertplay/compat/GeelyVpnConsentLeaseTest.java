package com.shilapi.xcertplay.compat;

import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class GeelyVpnConsentLeaseTest {
    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void validLeaseCanBeRecheckedThroughTheSameOpenDescriptor() throws Exception {
        File lease = lease(NONCE);
        try (FileInputStream input = new FileInputStream(lease)) {
            GeelyVpnConsentHelper.checkLease(input, NONCE, SystemClock.elapsedRealtime() + 4000);
            GeelyVpnConsentHelper.checkLease(input, NONCE, SystemClock.elapsedRealtime() + 4000);
        }
    }

    @Test public void cancellationTruncationIsVisibleThroughAnAlreadyOpenDescriptor() throws Exception {
        File lease = lease(NONCE);
        try (FileInputStream input = new FileInputStream(lease)) {
            GeelyVpnConsentHelper.checkLease(input, NONCE, SystemClock.elapsedRealtime() + 4000);
            try (FileOutputStream output = new FileOutputStream(lease)) { output.flush(); }
            expectFailure(input, NONCE, SystemClock.elapsedRealtime() + 4000, "vpn_cancelled");
        }
    }

    @Test public void expiredAndWrongNonceLeasesCannotReachTheNextBinderCall() throws Exception {
        File lease = lease(NONCE);
        try (FileInputStream input = new FileInputStream(lease)) {
            expectFailure(input, NONCE, SystemClock.elapsedRealtime(), "vpn_expired");
            expectFailure(input, "ffffffffffffffffffffffffffffffff", SystemClock.elapsedRealtime() + 4000, "vpn_cancelled");
        }
    }

    private File lease(String nonce) throws Exception {
        File lease = files.newFile();
        try (FileOutputStream output = new FileOutputStream(lease)) { output.write(nonce.getBytes("US-ASCII")); }
        return lease;
    }

    private static void expectFailure(FileInputStream input, String nonce, long deadline, String reason) throws Exception {
        try { GeelyVpnConsentHelper.checkLease(input, nonce, deadline); fail("invalid lease accepted"); }
        catch (IOException expected) { assertEquals(reason, expected.getMessage()); }
    }
}
