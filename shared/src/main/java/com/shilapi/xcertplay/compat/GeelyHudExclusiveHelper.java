package com.shilapi.xcertplay.compat;

import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.channels.FileLock;

/** H53 root child: one package, one durable journal, cross-process lock and expiring navigation lease. */
public final class GeelyHudExclusiveHelper {
    static final String ENTRY_POINT = "com.shilapi.xcertplay.compat.GeelyHudExclusiveHelper";
    static final String DIRECTORY = "/data/data/com.shihab.diplay.repair1.geely8600/files/geely-usb";

    public static void main(String[] args) {
        try {
            if (Process.myUid() != 0 || !GeelyUsbRuntimePolicy.matches(Build.VERSION.SDK_INT, Build.BOARD, Build.DISPLAY, true))
                throw new IOException("hud_platform_guard");
            if (args.length != 3 || !("watch".equals(args[0]) || "recover".equals(args[0]))
                    || !args[1].matches("[a-f0-9]{32}") || !GeelyVpnConsentPolicy.validBootId(args[2]))
                throw new IOException("hud_arguments");
            if (!args[2].equals(GeelyUsbRuntimeHelper.text("/proc/sys/kernel/random/boot_id")))
                throw new IOException("hud_boot_changed");
            run(args[0], args[1], args[2]);
        } catch (Exception failure) {
            Log.e("GeelyHudExclusiveHelper", "HUD exclusive helper failed; recovery record retained if needed", failure);
            System.err.println("GEELY_HUD_FAILED:" + failure.getClass().getSimpleName() + ":" + failure.getMessage());
        }
        System.exit(0);
    }

    private static void run(String operation, String nonce, String boot) throws Exception {
        final File directory = new File(DIRECTORY);
        if (!directory.getCanonicalPath().equals(DIRECTORY)) throw new IOException("hud_private_path");
        File status = child("hud-" + nonce + ".status");
        RandomAccessFile lockFile = new RandomAccessFile(child("hud-exclusive.lock"), "rw");
        try {
            FileLock lock = lockFile.getChannel().tryLock();
            long waitUntil = SystemClock.elapsedRealtime() + 15000;
            while (lock == null && "recover".equals(operation) && SystemClock.elapsedRealtime() < waitUntil) {
                Thread.sleep(200);
                lock = lockFile.getChannel().tryLock();
            }
            if (lock == null) { report(status, nonce, "busy"); return; }
            try {
                final PackagePort port = new PackagePort(nonce, boot);
                String recovered = GeelyHudExclusivePolicy.restore(port);
                Log.i("GeelyHudExclusiveHelper", "HUD recovery result=" + recovered + " operation=" + operation);
                if ("recover".equals(operation)) { report(status, nonce, recovered); return; }
                try {
                    String state = GeelyHudExclusivePolicy.acquire(port);
                    Log.i("GeelyHudExclusiveHelper", "HUD acquisition result=" + state);
                    while (port.leased()) {
                        GeelyHudExclusivePolicy.Snapshot current = port.snapshot();
                        // A replacement/re-enabled package ends this session instead of silently re-disabling it.
                        if ("isolated".equals(state) && (current == null || current.enabled != 3
                                || !port.readJournal().substring(2).equals(current.identity)))
                            throw new IOException("hud_package_changed");
                        if (("absent".equals(state) && current != null)
                                || ("disabled".equals(state) && current != null && current.runnable))
                            throw new IOException("hud_package_changed");
                        report(status, nonce, state);
                        Thread.sleep(1000);
                    }
                } finally {
                    // A failed restore leaves the durable journal for boot/startup/manual retry.
                    String result = GeelyHudExclusivePolicy.restore(port);
                    Log.i("GeelyHudExclusiveHelper", "HUD lease ended recovery=" + result);
                    report(status, nonce, result);
                }
            } catch (Exception failure) {
                report(status, nonce, "failed");
                throw failure;
            } finally { lock.release(); }
        } finally { lockFile.close(); }
    }

    static File child(String name) throws IOException {
        File file = new File(DIRECTORY, name);
        if (!file.getCanonicalFile().equals(file)) throw new IOException("hud_private_path");
        return file;
    }

    private static void report(File file, String nonce, String state) throws IOException {
        File temporary = child(file.getName() + ".tmp");
        write(temporary, nonce + "|" + state + "|" + SystemClock.elapsedRealtime(), false);
        if (!temporary.renameTo(file)) throw new IOException("hud_status_write");
    }

    /** File fsync makes recovery intent durable before the PackageManager mutation. */
    static void write(File file, String value, boolean durable) throws IOException {
        FileOutputStream stream = new FileOutputStream(file);
        try {
            stream.write(value.getBytes("US-ASCII"));
            stream.flush();
            if (durable) stream.getFD().sync();
        } finally { stream.close(); }
        if (!file.setReadable(true, false)) throw new IOException("hud_status_permissions");
    }

    private static final class PackagePort implements GeelyHudExclusivePolicy.Port {
        final Object manager;
        final Method info;
        final Method read;
        final Method write;
        final String nonce;
        final String boot;
        final File journal;

        PackagePort(String nonce, String boot) throws Exception {
            this.nonce = nonce;
            this.boot = boot;
            journal = child("hud-exclusive.journal");
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "package");
            manager = Class.forName("android.content.pm.IPackageManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            Class<?> contract = Class.forName("android.content.pm.IPackageManager");
            info = contract.getMethod("getPackageInfo", String.class, int.class, int.class);
            read = contract.getMethod("getApplicationEnabledSetting", String.class, int.class);
            write = contract.getMethod("setApplicationEnabledSetting", String.class, int.class, int.class, int.class, String.class);
        }

        public GeelyHudExclusivePolicy.Snapshot snapshot() throws Exception {
            PackageInfo pkg = (PackageInfo) info.invoke(manager, GeelyHudExclusivePolicy.PACKAGE, 0, 0);
            if (pkg == null || pkg.applicationInfo == null) return null;
            int enabled = ((Integer) read.invoke(manager, GeelyHudExclusivePolicy.PACKAGE, 0)).intValue();
            return new GeelyHudExclusivePolicy.Snapshot(pkg.applicationInfo.uid + ":" + pkg.firstInstallTime + ":" + pkg.lastUpdateTime,
                    enabled, pkg.applicationInfo.enabled);
        }
        public String readJournal() throws IOException { return journal.exists() ? GeelyUsbRuntimeHelper.text(journal.getPath()) : null; }
        public void saveJournal(String value) throws IOException { GeelyHudExclusiveHelper.write(journal, value, true); }
        public void clearJournal() throws IOException {
            if (journal.exists() && !journal.delete()) throw new IOException("hud_journal_delete");
        }
        public void setEnabled(int value) throws Exception { write.invoke(manager, GeelyHudExclusivePolicy.PACKAGE, value, 0, 0, null); }
        public boolean leased() throws IOException {
            File lease = child("hud-" + nonce + ".lease");
            return lease.isFile() && GeelyHudExclusivePolicy.validLease(GeelyUsbRuntimeHelper.text(lease.getPath()),
                    nonce, boot, SystemClock.elapsedRealtime());
        }
    }
}
