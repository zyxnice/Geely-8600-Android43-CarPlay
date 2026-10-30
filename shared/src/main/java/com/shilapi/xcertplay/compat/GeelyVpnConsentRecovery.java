package com.shilapi.xcertplay.compat;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Restores previously confirmed VPN trust once per new H53 kernel boot, off the UI thread. */
public final class GeelyVpnConsentRecovery {
    private static final String TAG = "GeelyVpnConsent";
    private static final String OPT_IN = "com.shilapi.xcertplay.GEELY_VPN_RESTORE";
    private static final String PREFS = "geely_vpn_consent";
    private static final String TRUSTED = "system_confirmed";
    private static final String GRANTED_BOOT = "granted_boot_id";
    private static final String ATTEMPT_BOOT = "restore_attempt_boot_id";
    private static FutureTask<String> inFlight;
    private final Context context;
    private boolean stopped;
    private File activeLease;

    public GeelyVpnConsentRecovery(Context context) { this.context = context.getApplicationContext(); }

    /** Records only an actual current system grant, including one obtained through its dialog. */
    public static void recordGranted(Context context) {
        if (!isEnabled(context) || VpnService.prepare(context) != null) return;
        String boot = bootId();
        if (!GeelyVpnConsentPolicy.validBootId(boot)) return;
        preferences(context).edit().putBoolean(TRUSTED, true).putString(GRANTED_BOOT, boot).apply();
    }

    /** A rejected system dialog removes permission to restore trust at subsequent boots. */
    public static void recordDenied(Context context) {
        if (isEnabled(context)) preferences(context).edit().clear().apply();
    }

    public static boolean shouldRestore(Context context) {
        String boot = bootId();
        SharedPreferences saved = preferences(context);
        return GeelyVpnConsentPolicy.needsRestore(isEnabled(context), saved.getBoolean(TRUSTED, false),
                saved.getString(GRANTED_BOOT, null), boot)
                && !boot.equals(saved.getString(ATTEMPT_BOOT, null));
    }

    /** Bounded caller wait. A hung OEM Binder remains single-flight rather than spawning retries. */
    public boolean restore() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("vpn_restore_on_ui_thread");
        if (!shouldRestore(context)) return false;
        try {
            final File dex = new GeelyUsbFdBroker(context).prepareHelper();
            final String boot = bootId();
            final String nonce = UUID.randomUUID().toString().replace("-", "");
            final File lease = new File(dex.getParentFile(), "vpn-" + nonce + ".lease");
            final long deadline = SystemClock.elapsedRealtime() + 4000;
            FutureTask<String> task;
            synchronized (GeelyVpnConsentRecovery.class) {
                if (inFlight != null && !inFlight.isDone()) return false;
                synchronized (this) {
                    if (stopped || !shouldRestore(context)) return false;
                    // Consume this boot's attempt before privilege work. Failed/cancelled attempts
                    // must go back to the system dialog, not silently retry later in the same boot.
                    if (!preferences(context).edit().putString(ATTEMPT_BOOT, boot).commit()) return false;
                    FileOutputStream output = new FileOutputStream(lease);
                    try { output.write(nonce.getBytes("US-ASCII")); } finally { output.close(); }
                    activeLease = lease;
                }
                task = new FutureTask<String>(new Callable<String>() {
                    @Override public String call() throws Exception {
                        synchronized (GeelyVpnConsentRecovery.this) {
                            if (stopped) throw new IOException("vpn_cancelled");
                        }
                        return new GeelyUsbOemBridge().restoreVpn(dex, new File(dex.getParentFile(), "runtime"),
                                deadline, lease, nonce, boot);
                    }
                });
                inFlight = task;
                Thread worker = new Thread(task, "geely-vpn-oem");
                worker.setDaemon(true);
                worker.start();
            }
            String result = task.get(5, TimeUnit.SECONDS);
            boolean granted = VpnService.prepare(context) == null;
            Log.i(TAG, "VPN restore result=" + result + " systemGranted=" + granted);
            if (granted) recordGranted(context);
            return granted;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            Log.w(TAG, "VPN restore unavailable; requesting system confirmation", failure);
            return false;
        } finally {
            revokeLease();
        }
    }

    /** Stops future helper calls; a Binder request already dispatched cannot be cancelled on H53. */
    public synchronized void stop() {
        stopped = true;
        revokeLease();
    }

    private synchronized void revokeLease() {
        File lease = activeLease;
        activeLease = null;
        if (lease == null) return;
        // The UID1000 child holds a read descriptor and cannot reopen the app-private path.
        // Truncate first so cancellation remains visible even after the pathname is removed.
        try {
            FileOutputStream output = new FileOutputStream(lease);
            output.close();
        } catch (IOException failure) { Log.w(TAG, "Could not invalidate VPN recovery lease", failure); }
        if (lease.exists() && !lease.delete()) Log.w(TAG, "Could not remove VPN recovery lease");
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String bootId() {
        try { return GeelyUsbRuntimeHelper.text("/proc/sys/kernel/random/boot_id"); }
        catch (IOException unavailable) { return ""; }
    }

    static boolean isEnabled(Context context) {
        if (!GeelyVpnConsentPolicy.PACKAGE.equals(context.getPackageName())) return false;
        try {
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            return GeelyUsbRuntimePolicy.matches(Build.VERSION.SDK_INT, Build.BOARD, Build.DISPLAY,
                    app.metaData != null && app.metaData.getBoolean(OPT_IN, false));
        } catch (PackageManager.NameNotFoundException absent) { return false; }
    }
}
