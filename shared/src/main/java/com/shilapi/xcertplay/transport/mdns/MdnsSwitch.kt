package com.shilapi.xcertplay.transport.mdns

/**
 * `debug.diplay.mdns=off` switches the announcement off, the way every other hot switch in this app works
 * (ADR-8). The property is read through the platform class because this API-19 ROM exposes no public API
 * for it; anything other than `off` leaves it on, so a missing property keeps the fixed behaviour and the
 * car can A/B the announcement without a rebuild.
 */
internal object MdnsSwitch {
    private const val PROPERTY = "debug.diplay.mdns"

    fun enabled(): Boolean {
        val raw = runCatching {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val get = systemProperties.getMethod("get", String::class.java, String::class.java)
            get.invoke(null, PROPERTY, "") as? String
        }.getOrNull()
        return raw?.trim()?.lowercase() != "off"
    }
}