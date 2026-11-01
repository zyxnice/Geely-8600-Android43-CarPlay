package com.shilapi.xcertplay.compat;

import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;

/** One-shot OEM child restoring only DiPlay's VPN ownership on the verified H53 firmware. */
public final class GeelyVpnConsentHelper {
    static final String ENTRY_POINT = "com.shilapi.xcertplay.compat.GeelyVpnConsentHelper";
    private static final String PRIVATE_DIRECTORY = "/data/data/" + GeelyVpnConsentPolicy.PACKAGE + "/files/geely-usb";

    private GeelyVpnConsentHelper() { }

    /** Fixed protocol: elapsed-realtime deadline, private lease path, nonce, current kernel boot ID. */
    public static void main(String[] arguments) {
        Thread watchdog = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(6000); } catch (InterruptedException ignored) { }
                Runtime.getRuntime().halt(2);
            }
        }, "geely-vpn-expiry");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            if (arguments.length != 4 || !arguments[0].matches("[0-9]{1,16}")
                    || !arguments[2].matches("[a-f0-9]{32}")
                    || !GeelyVpnConsentPolicy.validBootId(arguments[3])) throw new IOException("vpn_arguments");
            final long deadline = Long.parseLong(arguments[0]);
            final File lease = new File(arguments[1]);
            final String nonce = arguments[2];
            if (!lease.getCanonicalFile().equals(lease) || !PRIVATE_DIRECTORY.equals(lease.getParent())
                    || !lease.getName().equals("vpn-" + nonce + ".lease")) throw new IOException("vpn_lease_path");
            if (Process.myUid() != 0 || !GeelyUsbRuntimePolicy.matches(Build.VERSION.SDK_INT, Build.BOARD, Build.DISPLAY, true))
                throw new IOException("vpn_platform_guard");
            verifyLease(lease, nonce, deadline);
            if (!arguments[3].equals(GeelyUsbRuntimeHelper.text("/proc/sys/kernel/random/boot_id")))
                throw new IOException("vpn_boot_changed");

            FileInputStream leaseInput = new FileInputStream(lease);
            try {
                System.out.println("GEELY_VPN_OK:" + restoreAsSystem(leaseInput, nonce, deadline));
            } finally { leaseInput.close(); }
        } catch (Exception failure) {
            String reason = failure.getMessage();
            System.out.println("GEELY_VPN_FAILED:"
                    + (reason != null && reason.matches("[a-z_]{1,64}") ? reason : "vpn_helper_failure"));
        }
        System.exit(0);
    }

    /** Package-private so an independently built API18 probe can exercise the real Binder seam. */
    static String restoreAsSystem(final FileInputStream leaseInput, final String nonce, final long deadline) throws Exception {
        // H53 Vpn.enforceControlPermission accepts UID 1000 or vpndialogs, but not root.
        // Both native methods and prepareVpn's two-string signature were checked in its ODEX.
        dropToSystemUid();
        Class<?> manager = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) manager.getDeclaredMethod("getService", String.class).invoke(null, "connectivity");
        if (binder == null) throw new IOException("vpn_service_missing");
        Class<?> stub = Class.forName("android.net.IConnectivityManager$Stub");
        final Object service = stub.getDeclaredMethod("asInterface", IBinder.class).invoke(null, binder);
        final Method prepare = Class.forName("android.net.IConnectivityManager")
                .getMethod("prepareVpn", String.class, String.class);
        final Method legacyInfo = Class.forName("android.net.IConnectivityManager").getMethod("getLegacyVpnInfo");
        // Keep the already-open private lease readable after dropping UID. Cancellation
        // truncates the lease before unlinking it, so this descriptor also observes it.
        return GeelyVpnConsentPolicy.restore(new GeelyVpnConsentPolicy.Port() {
            @Override public boolean prepare(String oldPackage, String newPackage) throws Exception {
                checkLease(leaseInput, nonce, deadline);
                return (Boolean) prepare.invoke(service, oldPackage, newPackage);
            }
            @Override public boolean hasLegacyVpn() throws Exception {
                checkLease(leaseInput, nonce, deadline);
                return legacyInfo.invoke(service) != null;
            }
        });
    }

    static void checkLease(FileInputStream leaseInput, String nonce, long deadline) throws IOException {
        if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("vpn_expired");
        leaseInput.getChannel().position(0);
        byte[] bytes = new byte[33];
        int count = leaseInput.read(bytes);
        if (count != 32 || !nonce.equals(new String(bytes, 0, count, "US-ASCII"))) throw new IOException("vpn_cancelled");
    }

    private static void verifyLease(File lease, String nonce, long deadline) throws IOException {
        long now = SystemClock.elapsedRealtime();
        if (deadline <= now || deadline - now > 4000) throw new IOException("vpn_expired");
        if (!lease.isFile() || !nonce.equals(GeelyUsbRuntimeHelper.text(lease.getPath()))) throw new IOException("vpn_cancelled");
    }

    private static void dropToSystemUid() throws Exception {
        int gidResult = (Integer) Process.class.getDeclaredMethod("setGid", Integer.TYPE).invoke(null, 1000);
        int uidResult = (Integer) Process.class.getDeclaredMethod("setUid", Integer.TYPE).invoke(null, 1000);
        if (gidResult != 0 || uidResult != 0 || Process.myUid() != 1000) throw new IOException("vpn_system_uid");
    }
}
