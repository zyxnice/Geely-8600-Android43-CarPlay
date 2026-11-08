package com.shilapi.xcertplay.compat;

import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** API18 fixed OEM child: only ifconfig UP for a revalidated Apple cdc_ncm interface. */
public final class GeelyKernelNcmUpHelper implements GeelyKernelNcmUpPolicy.Port {
    static final String ENTRY_POINT = "com.shilapi.xcertplay.compat.GeelyKernelNcmUpHelper";
    private static final String PRIVATE_DIRECTORY = "/data/data/com.shihab.diplay.repair1.geely8600/files/geely-usb";
    private static final String HOST_PREFIX = "/sys/devices/platform/fsl-ehci.0/";
    private final long deadline;
    private final File lease;
    private final String nonce;
    private final String boot;
    private final GeelyKernelNcmUpPolicy.Request expected;
    private volatile java.lang.Process child;

    private GeelyKernelNcmUpHelper(long deadline, File lease, String nonce, String boot,
            GeelyKernelNcmUpPolicy.Request expected) {
        this.deadline = deadline;
        this.lease = lease;
        this.nonce = nonce;
        this.boot = boot;
        this.expected = expected;
    }

    public static void main(String[] args) {
        GeelyKernelNcmUpHelper helper = null;
        try {
            if (args.length != 16 || !args[0].matches("[0-9]{1,16}") || !args[2].matches("[a-f0-9]{32}")
                    || !GeelyVpnConsentPolicy.validBootId(args[3])) throw new IOException("ncm_arguments");
            File lease = new File(args[1]);
            if (!lease.getCanonicalFile().equals(lease) || !PRIVATE_DIRECTORY.equals(lease.getParent())
                    || !lease.getName().equals("ncm-" + args[2] + ".lease")) throw new IOException("ncm_lease_path");
            long deadline = Long.parseLong(args[0]);
            long now = SystemClock.elapsedRealtime();
            if (deadline <= now || deadline - now > 6000) throw new IOException("ncm_expired");
            helper = new GeelyKernelNcmUpHelper(deadline, lease, args[2], args[3], GeelyKernelNcmUpPolicy.Request.parse(args, 4));
            final GeelyKernelNcmUpHelper running = helper;
            Thread watchdog = new Thread(new Runnable() {
                @Override public void run() {
                    try { Thread.sleep(8000); } catch (InterruptedException ignored) { }
                    java.lang.Process process = running.child;
                    if (process != null) process.destroy();
                    Runtime.getRuntime().halt(2);
                }
            }, "geely-ncm-deadline");
            watchdog.setDaemon(true);
            watchdog.start();
            String result = GeelyKernelNcmUpPolicy.enable(helper.expected, helper);
            Log.i("GeelyKernelNcm", "Interface preparation result=" + result + " iface=" + helper.expected.name
                    + " ifindex=" + helper.expected.ifindex);
            System.out.println("GEELY_NCM_OK:" + result);
        } catch (Exception failure) {
            Log.w("GeelyKernelNcm", "Interface preparation failed", failure);
            String reason = failure.getMessage();
            System.out.println("GEELY_NCM_FAILED:"
                    + (reason != null && reason.matches("[a-z_]{1,64}") ? reason : "ncm_helper_failure"));
        } finally {
            if (helper != null && helper.child != null) helper.child.destroy();
        }
        System.exit(0);
    }

    private void guard() throws Exception {
        GeelyUsbRuntimeHelper.verifyLease(lease, nonce, SystemClock.elapsedRealtime(), deadline, false);
        if (Process.myUid() != 0 || !GeelyUsbRuntimePolicy.matches(Build.VERSION.SDK_INT, Build.BOARD, Build.DISPLAY, true))
            throw new IOException("ncm_platform_guard");
        if (!boot.equals(text("/proc/sys/kernel/random/boot_id"))) throw new IOException("ncm_boot_changed");
        if (!GeelyKernelNcmUpPolicy.usbReady(text("/proc/driver/usbroleswitch/0/mode"),
                text("/sys/class/otg_peripheral/charging_mode"), text("/sys/class/otg_peripheral/power"),
                text("/sys/class/otg_peripheral/pin_fault/status")))
            throw new IOException("ncm_usb_not_ready");
    }

    @Override public GeelyKernelNcmUpPolicy.State read() throws Exception {
        guard();
        File net = new File("/sys/class/net", expected.name);
        File control = new File(net, "device").getCanonicalFile();
        File usb = control.getParentFile();
        if (!control.getPath().startsWith(HOST_PREFIX) || usb == null
                || !new File(control, "driver").getCanonicalFile().getName().equals("cdc_ncm")
                || !"6".equals(text(new File(usb, "bConfigurationValue").getPath()))
                || !usb.getName().equals(expected.usb.sysfsName)) throw new IOException("ncm_owner_changed");
        byte[] descriptors;
        FileInputStream input = new FileInputStream(new File(usb, "descriptors"));
        try { descriptors = GeelyUsbFdHelper.readLimited(input, GeelyUsbDescriptorParser.MAX_DESCRIPTOR_BYTES); }
        finally { input.close(); }
        GeelyUsbIdentity identity = new GeelyUsbIdentity(usb.getName(), decimal(usb, "busnum"), decimal(usb, "devnum"),
                hex(usb, "idVendor"), hex(usb, "idProduct"), GeelyUsbIdentity.sha256(descriptors));
        GeelyUsbFdHelper.verifyDescriptors(identity, descriptors);
        int controlId = hex(control, "bInterfaceNumber");
        File data = new File(usb, usb.getName() + ":6." + expected.dataInterface);
        GeelyKernelNcmUpPolicy.Request current = new GeelyKernelNcmUpPolicy.Request(identity, expected.name,
                decimal(net, "ifindex"), text(new File(net, "address").getPath()).replace(":", ""),
                controlId, expected.dataInterface, hex(data, "bAlternateSetting"));
        String flags = text(new File(net, "flags").getPath());
        return new GeelyKernelNcmUpPolicy.State(current, Integer.parseInt(flags.startsWith("0x") ? flags.substring(2) : flags, 16));
    }

    @Override public void enable(GeelyKernelNcmUpPolicy.Request request) throws Exception {
        GeelyKernelNcmUpPolicy.State current = read();
        GeelyKernelNcmUpPolicy.requireIdentity(request, current);
        if (current.isUp()) return;
        guard();
        Log.i("GeelyKernelNcm", "Enable interface iface=" + request.name + " ifindex=" + request.ifindex);
        child = new ProcessBuilder("/system/bin/busybox", "ifconfig", request.name, "up").redirectErrorStream(true).start();
        int status = child.waitFor();
        child.getInputStream().close();
        if (status != 0) throw new IOException("ncm_ifconfig_failed");
    }

    private static String text(String path) throws IOException { return GeelyUsbRuntimeHelper.text(path); }
    private static int decimal(File directory, String name) throws IOException {
        return Integer.parseInt(text(new File(directory, name).getPath()));
    }
    private static int hex(File directory, String name) throws IOException {
        return Integer.parseInt(text(new File(directory, name).getPath()), 16);
    }
}
