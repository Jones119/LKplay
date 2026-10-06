package com.android.vpndialogs;

import android.app.Activity;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * CS11 VPN consent stub.
 *
 * The CS11 ROM ships without the AOSP VpnDialogs app, so VpnService.prepare() builds an
 * explicit intent to com.android.vpndialogs/.ConfirmDialog that nothing can handle.
 * Installing THIS package under that name makes Android's Vpn.enforceControlPermission()
 * accept its calls (the check compares the caller uid with the uid that the name
 * "com.android.vpndialogs" resolves to), which is what lets the DiPlay app be granted
 * VPN ownership without root.
 *
 * The activity auto-approves ONLY the DiPlay package; anything else is cancelled.
 */
public class ConfirmDialog extends Activity {
    private static final String TAG = "VpnDialogsStub";
    private static final String ALLOWED_CALLER_PREFIX = "com.shihab.diplay";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String calling = getCallingPackage();
        Log.i(TAG, "VPN consent requested by " + calling);
        boolean recorded = false;
        if (calling != null && calling.startsWith(ALLOWED_CALLER_PREFIX)) {
            recorded = recordConsent(calling);
        } else {
            Log.w(TAG, "consent refused for unexpected caller: " + calling);
        }
        Log.i(TAG, "consent recorded=" + recorded + " caller=" + calling);
        setResult(recorded ? RESULT_OK : RESULT_CANCELED);
        finish();
    }

    private boolean recordConsent(String pkg) {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, "connectivity");
            Class<?> stub = Class.forName("android.net.IConnectivityManager$Stub");
            Method asInterface = stub.getMethod("asInterface", IBinder.class);
            Object service = asInterface.invoke(null, binder);
            Class<?> iface = Class.forName("android.net.IConnectivityManager");
            Method prepareVpn = iface.getMethod("prepareVpn", String.class, String.class);
            Object result = prepareVpn.invoke(service, null, pkg);
            Log.i(TAG, "prepareVpn(null, " + pkg + ") -> " + result);
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            Log.e(TAG, "recordConsent failed", t);
            return false;
        }
    }
}
