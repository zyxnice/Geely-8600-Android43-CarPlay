package com.shilapi.xcertplay.compat;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
import java.util.concurrent.TimeoutException;

/** Bounded, cancellable interface-UP request for the opt-in H53 kernel NCM connection path. */
public final class GeelyKernelNcmPreparation {
    /** Cancellation of this connection generation, including its link being closed. */
    public interface Cancellation { boolean isCancelled(); }

    private static final String TAG = "GeelyKernelNcm";
    private static FutureTask<String> inFlight;
    private final Context context;

    public GeelyKernelNcmPreparation(Context context) { this.context = context.getApplicationContext(); }

    /**
     * Enable only the supplied current iPhone NIC, then verify its identity and IFF_UP in the helper.
     * Never writes IP, routes, DNS, USB roles, persistent properties or interface DOWN on failure.
     * Must run off-main; cancellation revokes the private lease but cannot undo an already issued ioctl.
     */
    public String enable(final GeelyKernelNcmUpPolicy.Request request, final Cancellation cancellation) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("ncm_on_ui_thread");
        if (!enabled()) throw new IOException("ncm_preparation_disabled");
        if (request == null || cancellation == null || cancellation.isCancelled()) throw new IOException("ncm_cancelled");
        File lease = null;
        try {
            final File dex = new GeelyUsbFdBroker(context).prepareHelper();
            final String boot = GeelyUsbRuntimeHelper.text("/proc/sys/kernel/random/boot_id");
            final String nonce = UUID.randomUUID().toString().replace("-", "");
            final File activeLease = new File(dex.getParentFile(), "ncm-" + nonce + ".lease");
            final long deadline = SystemClock.elapsedRealtime() + 6000;
            FutureTask<String> task;
            synchronized (GeelyKernelNcmPreparation.class) {
                if (inFlight != null && !inFlight.isDone()) throw new IOException("ncm_previous_operation_pending");
                if (cancellation.isCancelled()) throw new IOException("ncm_cancelled");
                lease = activeLease;
                FileOutputStream output = new FileOutputStream(lease);
                try { output.write(nonce.getBytes("US-ASCII")); } finally { output.close(); }
                task = new FutureTask<String>(new Callable<String>() {
                    @Override public String call() throws Exception {
                        if (cancellation.isCancelled()) throw new IOException("ncm_cancelled");
                        return new GeelyUsbOemBridge().enableKernelNcm(dex, new File(dex.getParentFile(), "runtime"),
                                deadline, activeLease, nonce, boot, request);
                    }
                });
                inFlight = task;
                Log.i(TAG, "Interface preparation requested iface=" + request.name + " ifindex=" + request.ifindex
                        + " usb=" + request.usb.devicePath());
                Thread worker = new Thread(task, "geely-ncm-up");
                worker.setDaemon(true);
                worker.start();
            }
            while (true) {
                if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) throw new IOException("ncm_cancelled");
                long remaining = deadline + 1000 - SystemClock.elapsedRealtime();
                if (remaining <= 0) throw new IOException("ncm_preparation_timeout");
                try {
                    String result = task.get(Math.min(remaining, 50), TimeUnit.MILLISECONDS);
                    Log.i(TAG, "Interface preparation result=" + result + " iface=" + request.name + " ifindex=" + request.ifindex);
                    return result;
                } catch (TimeoutException pending) { /* Keep observing cancellation without blocking the caller for 7s. */ }
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            Log.w(TAG, "Interface preparation failed", cause);
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("ncm_preparation_failed", cause);
        } finally {
            // Keep a hung Binder single-flight; lease revocation fences further helper writes.
            if (lease != null && lease.exists() && !lease.delete()) Log.e(TAG, "Could not revoke NCM preparation lease");
        }
    }

    private boolean enabled() {
        try {
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            return GeelyUsbRuntimePolicy.matches(Build.VERSION.SDK_INT, Build.BOARD, Build.DISPLAY,
                    app.metaData != null && app.metaData.getBoolean("com.shilapi.xcertplay.GEELY_KERNEL_NCM", false));
        } catch (PackageManager.NameNotFoundException absent) { return false; }
    }
}
