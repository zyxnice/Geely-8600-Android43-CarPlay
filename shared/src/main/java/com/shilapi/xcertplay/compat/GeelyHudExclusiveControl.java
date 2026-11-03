package com.shilapi.xcertplay.compat;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import com.shilapi.xcertplay.hud.GeelyHudTrialSettings;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Process-wide HUD owner. Only navigation ticks renew the independent root child's short lease. */
public final class GeelyHudExclusiveControl {
    public enum Status { OFF, WAITING, STARTING, ISOLATED, ABSENT, DISABLED, RESTORING, RESTORED, FAILED }
    private static final String TAG = "GeelyHudExclusive";
    private static final String PREFS = "geely_hud_exclusive";
    private static Session active;
    private static Session retiring;
    private static volatile Status status = Status.WAITING;
    private static boolean recovering;
    private static Thread launchThread;
    private static long retiredAt;
    private static Context manualRecovery;
    private static Object withdrawnOwner;

    private GeelyHudExclusiveControl() { }

    public static boolean enabled(Context context) {
        return GeelyHudTrialSettings.INSTANCE.available(context)
                && context.getSharedPreferences(PREFS, 0).getBoolean("enabled", true);
    }

    public static synchronized void setEnabled(Context context, boolean enabled) {
        if (!GeelyHudTrialSettings.INSTANCE.available(context)) return;
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", enabled).apply();
        if (!enabled) {
            // Turning off isolation must not leave an active CarPlay writer competing with AMap.
            GeelyHudTrialSettings.INSTANCE.setEnabled(context, false);
            requestRecoveryAfterClear(context);
        } else status = Status.WAITING;
    }

    /** Manual recovery stops HUD output as well, so the next navigation tick cannot re-acquire. */
    public static synchronized void restoreNow(Context context) {
        if (!GeelyHudTrialSettings.INSTANCE.available(context)) return;
        GeelyHudTrialSettings.INSTANCE.setEnabled(context, false);
        requestRecoveryAfterClear(context);
    }

    private static void requestRecoveryAfterClear(Context context) {
        // The navigation worker clears its HUD output before release. If it dies, lease expiry
        // still restores AMap; a UI action must not restore first and then clear AMap's new route.
        manualRecovery = context.getApplicationContext();
        if (active == null) {
            recover(manualRecovery);
            manualRecovery = null;
            return;
        }
        final Session requested = active;
        final Context app = manualRecovery;
        Thread fallback = new Thread(new Runnable() {
            @Override public void run() {
                // Normal ticks clear within 100 ms. Bound waiting if the decoder worker is stuck.
                try { Thread.sleep(2000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                synchronized (GeelyHudExclusiveControl.class) {
                    if (active != requested) return;
                    // Revoke late dispatch before releasing the lease. A Binder call already sent
                    // cannot be recalled, but future old-worker clear/guidance is now forbidden.
                    withdrawnOwner = requested.owner;
                    cancelActive();
                    manualRecovery = null;
                    recover(app);
                }
            }
        }, "geely-hud-manual-recovery");
        fallback.setDaemon(true);
        fallback.start();
    }

    public static synchronized boolean allowsDispatch(Object owner) { return owner != withdrawnOwner; }

    public static synchronized Status status(Context context) {
        if (active != null) refresh(active);
        else refreshRetiring();
        if (!enabled(context) && active == null && retiring == null && !recovering && status != Status.FAILED)
            return Status.OFF;
        return status;
    }

    /** Nonblocking, called only for a valid route and the current HUD output owner. */
    public static synchronized boolean ready(Context context, Object owner) {
        if (owner == withdrawnOwner) return false;
        if (!enabled(context)) return true;
        if (recovering) return false;
        refreshRetiring();
        if (retiring != null) return false;
        try {
            if (active == null) {
                active = new Session(context, owner);
                status = Status.STARTING;
                renew(active);
                launch(context, "watch", active);
            }
            if (active.owner != owner || active.failed) return false;
            renew(active);
            refresh(active);
            return status == Status.ISOLATED || status == Status.ABSENT || status == Status.DISABLED;
        } catch (Exception failure) {
            if (active != null) { active.failed = true; revoke(active); }
            status = Status.FAILED;
            Log.e(TAG, "HUD isolation unavailable; output withheld", failure);
            return false;
        }
    }

    /** Called after HUD clear/teardown. A stale controller cannot cancel a newer owner's lease. */
    public static synchronized void release(Object owner) {
        if (active == null || active.owner != owner) return;
        cancelActive();
        if (manualRecovery != null) {
            recover(manualRecovery);
            manualRecovery = null;
        }
    }

    private static void cancelActive() {
        if (active == null) return;
        revoke(active);
        retiring = active;
        retiredAt = SystemClock.elapsedRealtime();
        active = null;
        status = Status.RESTORING;
    }

    private static void refreshRetiring() {
        if (retiring == null) return;
        String response = readStatus(retiring);
        if ("restored".equals(response) || "preserved".equals(response) || "idle".equals(response)) {
            retiring = null;
            status = Status.RESTORED;
        } else if ("failed".equals(response) || SystemClock.elapsedRealtime() - retiredAt > 18000) {
            // Never trust a missing/old success acknowledgement to resume another writer.
            retiring = null;
            status = Status.FAILED;
        }
    }

    /** A boot IntentService keeps the app process available while the bounded acknowledgement is read. */
    public static void recoverAndWait(Context context) {
        recover(context);
        long until = SystemClock.elapsedRealtime() + 19000;
        while (SystemClock.elapsedRealtime() < until) {
            synchronized (GeelyHudExclusiveControl.class) { if (!recovering) return; }
            try { Thread.sleep(200); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** Boot/startup recovery is independent of the UI auto-start preference; no journal means no mutation. */
    public static synchronized void recover(final Context context) {
        if (!GeelyHudTrialSettings.INSTANCE.available(context) || active != null || recovering) return;
        final Context app = context.getApplicationContext();
        final File journal = new File(app.getFilesDir(), "geely-usb/hud-exclusive.journal");
        if (!journal.exists()) {
            if (status == Status.RESTORING) status = Status.RESTORED;
            return;
        }
        recovering = true;
        status = Status.RESTORING;
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Session recovery = new Session(app, null);
                    synchronized (GeelyHudExclusiveControl.class) { launch(app, "recover", recovery); }
                    long until = SystemClock.elapsedRealtime() + 18000;
                    while (SystemClock.elapsedRealtime() < until) {
                        String response = readStatus(recovery);
                        if ("restored".equals(response) || "preserved".equals(response) || "idle".equals(response)) {
                            status = Status.RESTORED;
                            synchronized (GeelyHudExclusiveControl.class) { retiring = null; }
                            Log.i(TAG, "HUD recovery verified result=" + response);
                            return;
                        }
                        if ("failed".equals(response) || "busy".equals(response)) throw new IOException("hud_recovery_failed");
                        Thread.sleep(200);
                    }
                    throw new IOException("hud_recovery_timeout");
                } catch (Exception failure) {
                    status = Status.FAILED;
                    Log.e(TAG, "HUD recovery pending; durable record retained", failure);
                } finally {
                    synchronized (GeelyHudExclusiveControl.class) { recovering = false; }
                }
            }
        }, "geely-hud-recovery");
        worker.setDaemon(true);
        worker.start();
    }

    private static void launch(final Context context, final String operation, final Session session) throws IOException {
        if (launchThread != null && launchThread.isAlive()) throw new IOException("hud_bridge_busy");
        launchThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File dex = new GeelyUsbFdBroker(context.getApplicationContext()).prepareHelper();
                    new GeelyUsbOemBridge().startHudExclusive(dex, operation, session.nonce, session.boot);
                } catch (Exception failure) {
                    synchronized (GeelyHudExclusiveControl.class) {
                        session.failed = true;
                        revoke(session);
                        if (active == session || recovering) status = Status.FAILED;
                    }
                    Log.e(TAG, "HUD helper launch failed operation=" + operation, failure);
                }
            }
        }, "geely-hud-launch");
        launchThread.setDaemon(true);
        launchThread.start();
    }

    private static void renew(Session session) throws IOException {
        long now = SystemClock.elapsedRealtime();
        if (session.lastRenewed >= 0 && now - session.lastRenewed < 1000) return;
        File temporary = new File(session.lease.getPath() + ".tmp");
        FileOutputStream output = new FileOutputStream(temporary);
        try {
            output.write((session.nonce + "|" + session.boot + "|"
                    + (now + GeelyHudExclusivePolicy.LEASE_MS)).getBytes("US-ASCII"));
        } finally { output.close(); }
        if (!temporary.renameTo(session.lease)) throw new IOException("hud_lease_write");
        session.lastRenewed = now;
    }

    private static void revoke(Session session) {
        if (session.lease.exists() && !session.lease.delete()) Log.e(TAG, "HUD lease revoke failed; waiting for expiry");
    }

    private static void refresh(Session session) {
        if (session.failed) { status = Status.FAILED; return; }
        String response = readStatus(session);
        Status next = Status.STARTING;
        if ("isolated".equals(response)) next = Status.ISOLATED;
        else if ("absent".equals(response)) next = Status.ABSENT;
        else if ("disabled".equals(response)) next = Status.DISABLED;
        boolean terminal = response != null && next == Status.STARTING;
        if (terminal || next == Status.STARTING && SystemClock.elapsedRealtime() - session.started > 10000) {
            session.failed = true;
            revoke(session);
            next = Status.FAILED;
        }
        if (status != next) Log.i(TAG, "HUD exclusive state=" + next);
        status = next;
    }

    private static String readStatus(Session session) {
        try {
            return GeelyHudExclusivePolicy.response(GeelyUsbRuntimeHelper.text(session.response.getPath()),
                    session.nonce, SystemClock.elapsedRealtime());
        } catch (Exception unavailable) { return null; }
    }

    private static final class Session {
        final Object owner;
        final String nonce = UUID.randomUUID().toString().replace("-", "");
        final String boot;
        final File lease;
        final File response;
        final long started = SystemClock.elapsedRealtime();
        long lastRenewed = -1;
        boolean failed;
        Session(Context context, Object owner) throws IOException {
            this.owner = owner;
            boot = GeelyUsbRuntimeHelper.text("/proc/sys/kernel/random/boot_id");
            if (!GeelyVpnConsentPolicy.validBootId(boot)) throw new IOException("hud_boot_unreadable");
            File directory = new File(context.getFilesDir(), "geely-usb");
            if (!directory.isDirectory() && !directory.mkdir()) throw new IOException("hud_directory");
            lease = new File(directory, "hud-" + nonce + ".lease");
            response = new File(directory, "hud-" + nonce + ".status");
        }
    }
}
