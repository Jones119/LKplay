package com.shilapi.xcertplay.compat

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * CS11 (Android 4.4 / API 19) 兼容层。
 *
 * `Context.getSystemService(Class)` 是 API 23 起才有的重载：在 4.4 上直接调用会抛
 * NoSuchMethodError。低于 API 23 时改走 [ContextCompat.getSystemService]（内部把类映射为
 * 服务名字符串，覆盖系统内置服务全表：audio/usb/wifi/wifip2p/notification/connectivity/
 * location/display/power/input_method 等），因此本扩展对上述服务在 4.4 上等价可用。
 *
 * 用法：把 `ctx.getSystemService(Foo::class.java)` 替换为 `ctx.systemService(Foo::class.java)`。
 */
fun <T : Any> Context.systemService(serviceClass: Class<T>): T =
    if (Build.VERSION.SDK_INT >= 23) {
        getSystemService(serviceClass)
    } else {
        ContextCompat.getSystemService(this, serviceClass)
            ?: throw IllegalStateException("No system service for ${serviceClass.name}")
    }
