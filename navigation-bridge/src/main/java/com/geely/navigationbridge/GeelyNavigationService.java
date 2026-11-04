package com.geely.navigationbridge;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * H53 speech-client compatibility endpoint while factory navigation is removed.
 * Binding and listener registration succeed; navigation operations explicitly fail until an
 * iPhone execution channel is validated. This service never reports a queued request as navigation.
 * Runs in its own small APK, and never polls or starts a DiPlay/CarPlay session.
 */
public final class GeelyNavigationService extends Service {
    static final String ACTION = "com.iflytek.navigationservice.NavigationService";
    static final String DESCRIPTOR = "com.iflytek.navi.aidl.NaviAidlService";
    static final String SPEECH_PACKAGE = "com.iflytek.cutefly.speechclient";
    private static final String TAG = "GeelyNaviBridge";
    private final NavigationBinder endpoint = new NavigationBinder();

    /** One-shot activation clears a newly installed package's stopped state, without a worker. */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "One-shot activation; waiting for speech-client binding");
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction()) || !supportedPlatform()) {
            Log.w(TAG, "Binding refused: action or platform mismatch");
            return null;
        }
        try {
            getPackageManager().getApplicationInfo("com.autonavi.amapauto", 0);
            Log.w(TAG, "Binding refused: factory navigation is installed");
            return null;
        } catch (PackageManager.NameNotFoundException absent) {
            Log.i(TAG, "Bound compatibility endpoint; phone navigation backend unavailable");
            return endpoint;
        }
    }

    private boolean supportedPlatform() {
        return Build.VERSION.SDK_INT == 18 && "C3ALFUS".equals(Build.BOARD)
                && "06.03.08600.H53.00060".equals(Build.DISPLAY);
    }

    /** Check each transaction: a bound client could otherwise forward this Binder to another UID. */
    private void enforceSpeechCaller() {
        String[] packages = getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages != null) {
            for (String name : packages) {
                if (SPEECH_PACKAGE.equals(name)) return;
            }
        }
        throw new SecurityException("Only the installed H53 speech client may use navigation IPC");
    }

    final class NavigationBinder extends Binder {
        NavigationBinder() {
            attachInterface(null, DESCRIPTOR);
        }

        /**
         * Wire ABI is the installed speech client's rp/rq, not the newer Amap stub.
         * In particular transaction 14 returns location text; Amap's newer version uses 16.
         * Location is empty when unavailable, so the caller can use its existing platform fallback.
         */
        @Override
        protected boolean onTransact(int code, Parcel input, Parcel reply, int flags)
                throws RemoteException {
            enforceSpeechCaller();
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            if (code < 1 || code > 14) return super.onTransact(code, input, reply, flags);
            input.enforceInterface(DESCRIPTOR);
            if (code == 1) {
                IBinder callback = input.readStrongBinder();
                reply.writeNoException();
                // No asynchronous operations exist yet, so retain no callback or background worker.
                reply.writeInt(callback != null ? 1 : 0);
                Log.i(TAG, "Listener registration; accepted=" + (callback != null));
                return true;
            }
            if (code == 14) {
                reply.writeNoException();
                reply.writeString("");
                return true;
            }
            consumeRequest(code, input);
            reply.writeNoException();
            reply.writeInt(0);
            Log.i(TAG, "Navigation operation unavailable; transaction=" + code);
            return true;
        }

        private void consumeRequest(int code, Parcel input) {
            switch (code) {
                case 2: // Search POI: JSON, request category.
                case 3: // Search nearby.
                case 4: // Search along route.
                    input.readString();
                    input.readInt();
                    break;
                case 5: // Search favorites: category, request category.
                    input.readInt();
                    input.readInt();
                    break;
                case 6: // General navigation: JSON, route mode, request category.
                    input.readString();
                    input.readInt();
                    input.readInt();
                    break;
                case 8: // Navigate: destination JSON, route mode, waypoint JSON.
                    input.readString();
                    input.readInt();
                    input.readString();
                    break;
                case 9: // Show current location: no arguments.
                    break;
                default: // 7 map, 10 traffic, 11 settings, 12 avoid, 13 waypoint.
                    input.readString();
                    break;
            }
        }
    }
}
