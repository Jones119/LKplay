package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import dalvik.system.DexClassLoader

/**
 * Proves the reflection recipe for the head unit's media centre before anything depends on it.
 *
 * `VendorMediaCenterProbe` established that the *internal* action reaches
 * `IMediaCenterInternalApiSvc` (playback control only). Registering CarPlay as a music source — the
 * part that would make the car show its metadata and route the wheel's media keys to us — needs the
 * *public* `IMediaCenterSvc` API instead, whose interface classes live inside
 * `/system/app/XSFMediaCenter.apk` rather than on our classpath. EasyPlay loads exactly that dex
 * (`LegacyEcarxMediaCenter` via a `LegacyEcarxSdkConnection`, docs/_digest/37).
 *
 * This step binds the public action, loads the APK's dex and resolves the AIDL stub, logging each
 * outcome. It registers nothing, claims no source and changes no behaviour, so the next on-car run
 * says whether the recipe works before the state machine is written against it.
 */
internal object VendorMediaCenterConnection {
    private const val TAG = "DiPlay-MediaCenter"
    private const val APK = "/system/app/XSFMediaCenter.apk"
    private const val SERVICE_PACKAGE = "ecarx.xsf.mediacenter"
    private const val PUBLIC_ACTION = "ecarx.xsf.MEDIA_CENTER_SERVICE"
    private const val SERVICE_STUB = "ecarx.xsf.mediacenter.IMediaCenterSvc\$Stub"
    private const val CLIENT_STUB = "ecarx.xsf.mediacenter.IMusicClient\$Stub"

    fun probe(context: Context) {
        val app = context.applicationContext
        val loader = runCatching {
            DexClassLoader(APK, app.getDir("mediacenter-dex", Context.MODE_PRIVATE).absolutePath, null, javaClass.classLoader)
        }.getOrElse {
            Log.w(TAG, "media centre dex loader failed for $APK", it)
            return
        }
        val serviceStub = runCatching { loader.loadClass(SERVICE_STUB) }.getOrNull()
        val clientStub = runCatching { loader.loadClass(CLIENT_STUB) }.getOrNull()
        Log.i(
            TAG,
            "media centre dex loaded serviceStub=${serviceStub != null} clientStub=${clientStub != null} apk=$APK",
        )
        if (serviceStub == null) return

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val descriptor = runCatching { service?.interfaceDescriptor }.getOrNull()
                val iface = runCatching {
                    serviceStub.getMethod("asInterface", IBinder::class.java).invoke(null, service)
                }.getOrNull()
                Log.i(
                    TAG,
                    "public service connected component=${name?.flattenToShortString()} " +
                        "descriptor=$descriptor interface=${iface?.javaClass?.name}",
                )
                if (iface != null) {
                    val methods = runCatching { iface.javaClass.methods.map { it.name }.distinct().sorted() }
                        .getOrDefault(emptyList())
                    Log.i(TAG, "public interface methods=$methods")
                }
                runCatching { app.unbindService(this) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.i(TAG, "public service disconnected component=${name?.flattenToShortString()}")
            }
        }
        val bound = runCatching {
            app.bindService(Intent(PUBLIC_ACTION).setPackage(SERVICE_PACKAGE), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        Log.i(TAG, "public service bind action=$PUBLIC_ACTION bound=$bound")
        if (!bound) runCatching { app.unbindService(connection) }
    }
}
