package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log

/**
 * Probes the head unit's own media centre, the way EasyPlay registers itself as a music source
 * (`LegacyEcarxMediaCenter`, docs/_digest/37).
 *
 * Why it matters: the car's media centre is what arbitrates media keys and the "current source", so a
 * CarPlay app that never registers with it only has the deprecated Android 4.4 MEDIA_BUTTON broadcast
 * to rely on — the reason a steering-wheel key can look swallowed. Acting on it needs the AIDL
 * interfaces out of `/system/app/XSFMediaCenter.apk`, which we have not pulled yet, so this probe
 * only binds, reports the descriptor it actually reaches and lets go. It never claims a source and
 * never changes what CarPlay does.
 *
 * On this car the service is `ecarx.xsf.mediacenter/.MediaCenterService` and the action it is
 * advertised with is `ecarx.xsf.MEDIA_CENTER_INTERNAL_API_SERVICE` — note that EasyPlay's source
 * looks for `ecarx.xsf.MEDIA_CENTER_SERVICE`, which does not match what dumpsys reports here.
 */
internal object VendorMediaCenterProbe {
    private const val TAG = "DiPlay-MediaCenter"
    private const val SERVICE_PACKAGE = "ecarx.xsf.mediacenter"
    private const val SERVICE_ACTION = "ecarx.xsf.MEDIA_CENTER_INTERNAL_API_SERVICE"

    /** Binds once, logs what answered, and unbinds. Safe to call from any thread. */
    fun probe(context: Context) {
        val intent = Intent(SERVICE_ACTION).setPackage(SERVICE_PACKAGE)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val descriptor = runCatching { service?.interfaceDescriptor }.getOrNull()
                Log.i(
                    TAG,
                    "media centre reachable component=${name?.flattenToShortString()} " +
                        "binder=${service?.javaClass?.name} descriptor=$descriptor",
                )
                runCatching { context.applicationContext.unbindService(this) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.i(TAG, "media centre disconnected component=${name?.flattenToShortString()}")
            }
        }
        val bound = runCatching {
            context.applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        Log.i(TAG, "media centre bind action=$SERVICE_ACTION package=$SERVICE_PACKAGE bound=$bound")
        if (!bound) runCatching { context.applicationContext.unbindService(connection) }
    }
}
