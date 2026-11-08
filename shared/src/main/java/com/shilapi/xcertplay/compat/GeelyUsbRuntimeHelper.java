package com.shilapi.xcertplay.compat;

import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;

/** One-shot OEM child for fixed preparation/boot-role recovery; no caller-supplied shell or paths. */
public final class GeelyUsbRuntimeHelper implements GeelyUsbRuntimePolicy.Port {
    static final String ENTRY_POINT = "com.shilapi.xcertplay.compat.GeelyUsbRuntimeHelper";
    private static final String OTG = "/sys/class/otg_peripheral/";
    private final long deadline;
    private final File lease;
    private final String nonce;
    private boolean rollingBack;

    private GeelyUsbRuntimeHelper(long deadline, File lease, String nonce) {
        this.deadline = deadline; this.lease = lease; this.nonce = nonce;
    }

    public static void main(String[] arguments) {
        Thread watchdog = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(8000); } catch (InterruptedException ignored) { }
                Runtime.getRuntime().halt(2);
            }
        }, "geely-runtime-deadline");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            if (arguments.length != 4 || !arguments[0].matches("[0-9]{1,16}")
                    || !arguments[2].matches("[a-f0-9]{32}")
                    || !validOperation(arguments[3]))
                throw new IOException("runtime_arguments");
            File lease = new File(arguments[1]);
            if (!lease.getCanonicalFile().equals(lease) || !lease.getName().equals("runtime-" + arguments[2] + ".lease")
                    || !lease.getParent().equals("/data/data/com.shihab.diplay.repair1.geely8600/files/geely-usb"))
                throw new IOException("runtime_lease_path");
            long deadline = Long.parseLong(arguments[0]);
            if (deadline <= SystemClock.elapsedRealtime() || deadline - SystemClock.elapsedRealtime() > 6000)
                throw new IOException("runtime_expired");
            if (Process.myUid() != 0 || !GeelyUsbRuntimePolicy.matches(Build.VERSION.SDK_INT, Build.BOARD, Build.DISPLAY, true))
                throw new IOException("runtime_platform_guard");
            GeelyUsbRuntimeHelper port = new GeelyUsbRuntimeHelper(deadline, lease, arguments[2]);
            if ("recover-boot-role".equals(arguments[3])) {
                GeelyUsbRuntimePolicy.State initial = port.read();
                android.util.Log.i("GeelyUsbRuntime", "Boot recovery start role=" + initial.role
                        + " config=" + initial.config + " applied=" + initial.appliedConfig
                        + " charging=" + initial.charging + " power=" + initial.power
                        + " fault=" + initial.fault + " device=" + initial.bootRecoveryDevice);
            }
            String result;
            if ("recover-boot-role".equals(arguments[3])) result = GeelyUsbRuntimePolicy.recoverBootRole(port);
            else result = GeelyUsbRuntimePolicy.prepare(port);
            System.out.println("GEELY_RUNTIME_OK:" + result);
            android.util.Log.i("GeelyUsbRuntime", "Runtime operation=" + arguments[3] + " result=" + result);
        } catch (Exception failure) {
            android.util.Log.w("GeelyUsbRuntime", "Runtime operation failed", failure);
            String reason = failure.getMessage();
            System.out.println("GEELY_RUNTIME_FAILED:" + (reason != null && reason.matches("[a-z_]{1,64}") ? reason : "runtime_failure"));
        }
        System.exit(0);
    }

    static boolean validOperation(String operation) {
        return "prepare".equals(operation) || "recover-boot-role".equals(operation);
    }

    private void checkDeadline() throws IOException {
        verifyLease(lease, nonce, SystemClock.elapsedRealtime(), deadline, rollingBack);
    }

    static void verifyLease(File lease, String nonce, long now, long deadline, boolean rollingBack) throws IOException {
        if (now >= deadline + (rollingBack ? 1500 : 0)) throw new IOException("runtime_expired");
        if (!rollingBack && (!lease.isFile() || !nonce.equals(text(lease.getPath())))) throw new IOException("runtime_cancelled");
    }

    @Override public void beginRollback() { rollingBack = true; }

    @Override public void awaitPowerOff() throws Exception {
        // Let the OTG supply settle before restoring it; a second charging leg can keep the phone powered.
        checkDeadline();
        Thread.sleep(500);
        checkDeadline();
    }

    @Override public void awaitRoleObservation() throws Exception {
        checkDeadline();
        Thread.sleep(250);
        checkDeadline();
    }

    @Override public void awaitRoleSettled() throws Exception {
        long until = Math.min(deadline - 2000, SystemClock.elapsedRealtime() + 1500);
        do {
            checkDeadline();
            String role = text("/proc/driver/usbroleswitch/0/mode");
            if ("0".equals(role) || "1".equals(role)) return;
            if (!"-1".equals(role)) throw new IOException("unexpected_usb_role");
            Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < until);
        throw new IOException("boot_role_still_busy");
    }

    @Override public void awaitRestored(GeelyUsbRuntimePolicy.State original) throws Exception {
        do {
            checkDeadline();
            if (original.role.equals(text("/proc/driver/usbroleswitch/0/mode"))
                    && original.config.equals(property("sys.usb.state"))) return;
            Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline + 1500);
        throw new IOException("runtime_restore_timeout");
    }

    @Override public GeelyUsbRuntimePolicy.State read() throws Exception {
        checkDeadline();
        String role = text("/proc/driver/usbroleswitch/0/mode");
        return new GeelyUsbRuntimePolicy.State(property("sys.usb.config"), property("sys.usb.state"),
                role, text(OTG + "charging_mode"),
                text(OTG + "power"), text(OTG + "pin_fault/status"), activeApple(), busOccupied(),
                "-1".equals(role) ? bootRecoveryDevice() : null);
    }

    @Override public boolean readPowered() throws Exception {
        checkDeadline();
        // USB descriptor nodes are expected to disappear during a power cycle. They must
        // not participate in judging whether supply was safely removed or restored.
        return GeelyUsbRuntimePolicy.isPowered(text(OTG + "power"));
    }

    @Override public void configuration(String value) throws Exception {
        checkDeadline();
        if (!GeelyUsbRuntimePolicy.HOST_CONFIG.equals(value) && !GeelyUsbRuntimePolicy.ADB_CONFIG.equals(value))
            throw new IOException("runtime_config_value");
        Class<?> properties = Class.forName("android.os.SystemProperties");
        properties.getDeclaredMethod("set", String.class, String.class).invoke(null, "sys.usb.config", value);
        android.util.Log.i("GeelyUsbRuntime", "Runtime configuration requested config=" + value
                + " rollback=" + rollingBack);
    }

    @Override public void power(String value) throws Exception {
        if (!"on".equals(value) && !"off".equals(value)) throw new IOException("runtime_power_value");
        write(OTG + "power", value);
    }

    @Override public void charging(String value) throws Exception {
        if (!"sdp".equals(value) && !"cdp".equals(value) && !"dcp".equals(value)) throw new IOException("runtime_charge_value");
        // H53 UsbProtectProvider transaction 8 writes exactly this node (1 -> cdp, 2 -> sdp).
        // Avoid a nested synchronous Binder call back into the launching OEM daemon.
        write(OTG + "charging_mode", value);
    }

    @Override public void awaitHost() throws Exception {
        long until = Math.min(deadline - 1500, SystemClock.elapsedRealtime() + 2000);
        do {
            if ("0".equals(text("/proc/driver/usbroleswitch/0/mode"))
                    && GeelyUsbRuntimePolicy.HOST_CONFIG.equals(property("sys.usb.state"))) return;
            Thread.sleep(50);
        } while (SystemClock.elapsedRealtime() < until);
        throw new IOException("host_transition_timeout");
    }

    static String property(String key) throws Exception {
        Method method = Class.forName("android.os.SystemProperties").getDeclaredMethod("get", String.class);
        return (String) method.invoke(null, key);
    }

    static String text(String path) throws IOException {
        FileInputStream input = new FileInputStream(path);
        try {
            byte[] bytes = new byte[128];
            int count = input.read(bytes);
            if (count <= 0 || count == bytes.length) throw new IOException("runtime_state_unreadable");
            return new String(bytes, 0, count, "US-ASCII").trim().toLowerCase(java.util.Locale.US);
        } finally { input.close(); }
    }

    private void write(String path, String value) throws Exception {
        checkDeadline();
        // Never energize a port with a current fault, including between two preparation steps.
        if ("on".equals(value) && !GeelyUsbRuntimePolicy.isHealthy(text(OTG + "pin_fault/status"))) throw new IOException("otg_power_fault");
        FileOutputStream output = new FileOutputStream(path);
        try { output.write(value.getBytes("US-ASCII")); output.flush(); }
        finally { output.close(); }
        android.util.Log.i("GeelyUsbRuntime", "Runtime write node=" + path + " value=" + value + " rollback=" + rollingBack);
    }

    private static boolean activeApple() throws IOException {
        File[] paths = new File("/sys/bus/usb/devices").listFiles();
        if (paths == null) throw new IOException("runtime_usb_listing");
        for (File path : paths) {
            if (!GeelyUsbIdentity.isValidSysfsName(path.getName()) || !path.getName().startsWith("1-")) continue;
            if ("05ac".equals(text(path + "/idVendor")) && "6".equals(text(path + "/bConfigurationValue"))) return true;
        }
        return false;
    }

    private static boolean busOccupied() throws IOException {
        File[] paths = new File("/sys/bus/usb/devices").listFiles();
        if (paths == null) throw new IOException("runtime_usb_listing");
        for (File path : paths) {
            // Bus 1 is the H53 OTG port. Other buses include the vehicle's built-in devices.
            // Include interface/incomplete names too: uncertainty must prevent a power cycle.
            if (GeelyUsbRuntimePolicy.occupiesOtgBus(path.getName())) return true;
        }
        return false;
    }

    private static String bootRecoveryDevice() throws IOException {
        return bootRecoveryDevice(new File("/sys/bus/usb/devices"));
    }

    static String bootRecoveryDevice(File devices) throws IOException {
        File[] paths = devices.listFiles();
        if (paths == null) throw new IOException("runtime_usb_listing");
        boolean found = false;
        for (File path : paths) {
            String name = path.getName();
            if (!GeelyUsbRuntimePolicy.occupiesOtgBus(name)) continue;
            if ("1-1".equals(name)) found = true;
            else if (!bootPhoneInterface(name)) return null;
        }
        if (!found) return null;
        File phone = new File(devices, "1-1");
        if (!"05ac".equals(text(phone + "/idVendor")) || !"12a8".equals(text(phone + "/idProduct"))
                || !"2".equals(text(phone + "/bConfigurationValue"))) return null;
        String number = text(phone + "/devnum");
        if (!number.matches("[1-9][0-9]{0,2}")) return null;
        return "1-1:" + number;
    }

    static boolean bootPhoneInterface(String name) {
        return name.matches("1-1:[0-9]+\\.[0-9]+");
    }
}
