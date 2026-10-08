package com.shilapi.xcertplay

import android.content.Context
import android.util.Log

/**
 * Reports what the head unit's DIM (instrument cluster) navigation API actually offers.
 *
 * EasyPlay pushes CarPlay's turn-by-turn guidance into the cluster through the vendor DIM service
 * (`LegacyLynkNavigationBridge`, docs/_digest/37) and this unit really runs
 * `com.ecarx.dim_interaction/.server.DiServer`, with `DimInteraction`/`INaviInteraction` present in
 * `/system/framework/ecarx.adapterapi.jar` (the same jar we already reflect for the Bluetooth
 * profiles). Implementing the bridge blind is not worth it, so this probe only resolves the entry
 * point and logs the method names it finds; the next on-car run then says which call to make.
 *
 * Log-only: it never registers with the cluster and never changes guidance behaviour.
 */
internal object VendorDimProbe {
    private const val TAG = "DiPlay-Dim"
    private const val DIM_INTERACTION = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction"

    fun probe(context: Context) {
        val type = runCatching { Class.forName(DIM_INTERACTION) }.getOrNull()
        if (type == null) {
            Log.i(TAG, "dim api class not on the classpath ($DIM_INTERACTION)")
            return
        }
        Log.i(TAG, "dim api methods=${type.methods.map { it.name }.distinct().sorted().take(24)}")
        val instance = runCatching {
            type.getMethod("create", Context::class.java).invoke(null, context)
        }.getOrNull()
        Log.i(TAG, "dim instance via create(Context)=${instance != null}")
        val owner = instance ?: return
        val navi = listOf("getNaviInteraction", "getNavigationInteraction", "getINaviInteraction")
            .firstNotNullOfOrNull { name ->
                runCatching { owner.javaClass.getMethod(name).invoke(owner) }.getOrNull()
            }
        if (navi == null) {
            Log.i(TAG, "navi interaction unavailable on ${owner.javaClass.name}")
            return
        }
        val methods = runCatching { navi.javaClass.methods.map { it.name }.distinct().sorted() }
            .getOrDefault(emptyList())
        Log.i(TAG, "navi interaction=${navi.javaClass.name} methods=$methods")
    }
}
