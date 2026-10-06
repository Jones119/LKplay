package com.shilapi.xcertplay.compat

import android.content.Context
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
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

// ---------- 媒体路径（API19）----------

/** `MediaCodec.getInputBuffer` 需要 API 21；低版本走已废弃的 `getInputBuffers()` 数组。 */
@Suppress("DEPRECATION")
fun MediaCodec.inputBufferCompat(index: Int): java.nio.ByteBuffer? =
    if (Build.VERSION.SDK_INT >= 21) getInputBuffer(index) else getInputBuffers().getOrNull(index)

/** `MediaCodec.getOutputBuffer` 需要 API 21；低版本走已废弃的 `getOutputBuffers()` 数组。 */
@Suppress("DEPRECATION")
fun MediaCodec.outputBufferCompat(index: Int): java.nio.ByteBuffer? =
    if (Build.VERSION.SDK_INT >= 21) getOutputBuffer(index) else getOutputBuffers().getOrNull(index)

/** `AudioTrack.getUnderrunCount` 需要 API 24；低版本返回 0（仅统计用途）。 */
fun trackUnderrunsCompat(track: AudioTrack): Int =
    if (Build.VERSION.SDK_INT >= 24) track.underrunCount else 0

/** `AudioTrack.getRoutedDevice`（AudioDeviceInfo）需要 API 23。 */
fun trackRouteTypeCompat(track: AudioTrack): Int =
    if (Build.VERSION.SDK_INT >= 23) track.routedDevice?.type ?: -1 else -1

/** `AudioTrack.getBufferSizeInFrames` 需要 API 23。 */
fun trackBufferSizeFramesCompat(track: AudioTrack): Int =
    if (Build.VERSION.SDK_INT >= 23) track.bufferSizeInFrames else -1

/** `AudioRecord.getRoutedDevice`（AudioDeviceInfo）需要 API 23。 */
fun recorderRouteTypeCompat(recorder: AudioRecord): Int? =
    if (Build.VERSION.SDK_INT >= 23) recorder.routedDevice?.type else null

/** `Context.getDrawable` 需要 API 21；低版本走 resources.getDrawable。 */
@Suppress("DEPRECATION")
fun Context.getDrawableCompat(id: Int): android.graphics.drawable.Drawable? =
    if (Build.VERSION.SDK_INT >= 21) getDrawable(id) else resources.getDrawable(id)
