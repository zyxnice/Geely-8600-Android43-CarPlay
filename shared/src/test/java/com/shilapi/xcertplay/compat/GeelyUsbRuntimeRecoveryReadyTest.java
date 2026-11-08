package com.shilapi.xcertplay.compat;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSystemClock;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28,
        instrumentedPackages = "com.shilapi.xcertplay.compat",
        shadows = {GeelyUsbRuntimeRecoveryReadyTest.EnabledRecovery.class,
                GeelyUsbRuntimeRecoveryReadyTest.ReadyOtg.class,
                GeelyUsbRuntimeRecoveryReadyTest.NoHelperLaunch.class})
public class GeelyUsbRuntimeRecoveryReadyTest {
    @Test public void readyHostCdpDoesNotLaunchHelperAfterRepeatedEmptyDiscoveryWaits() throws Exception {
        GeelyUsbRuntimeRecovery recovery = new GeelyUsbRuntimeRecovery(RuntimeEnvironment.getApplication());
        // Repeated initial-discovery polls must stay read-only beyond the former 2s reset timer
        // and the 5s repair cooldown, even without an enumerated phone.
        for (int poll = 0; poll < 4; poll++) {
            FutureTask<Boolean> preparation = new FutureTask<>(recovery::prepare);
            Thread worker = new Thread(preparation, "usb-ready-regression");
            worker.start();
            assertTrue(preparation.get(2, TimeUnit.SECONDS));
            assertEquals("ready", recovery.summary());
            ShadowSystemClock.advanceBy(Duration.ofSeconds(6));
        }
    }

    /** The exact H53/metadata gate is tested separately; exercise the enabled runtime path. */
    @Implements(value = GeelyUsbRuntimeRecovery.class, isInAndroidSdk = false)
    public static class EnabledRecovery {
        @Implementation protected static boolean isEnabled(Context context) { return true; }
    }

    @Implements(value = GeelyUsbRuntimeHelper.class, isInAndroidSdk = false)
    public static class ReadyOtg {
        @Implementation protected static String text(String path) throws IOException {
            if ("/proc/driver/usbroleswitch/0/mode".equals(path)) return "0";
            if ("/sys/class/otg_peripheral/charging_mode".equals(path)) return "cdp";
            if ("/sys/class/otg_peripheral/power".equals(path)) return "normal, power on";
            if ("/sys/class/otg_peripheral/pin_fault/status".equals(path)) return "working fine.";
            throw new IOException("unexpected_read:" + path);
        }
    }

    @Implements(value = GeelyUsbFdBroker.class, isInAndroidSdk = false)
    public static class NoHelperLaunch {
        @Implementation protected File prepareHelper() {
            throw new AssertionError("Ready Host/CDP must not prepare a privileged helper");
        }
    }
}
