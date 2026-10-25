package com.shilapi.xcertplay.compat

import android.content.Context
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import androidx.core.content.ContextCompat

/**
 * CS11 (Android 4.4 / API 19) 兼容层。
 *
 * 4.4 没有 `Context.getSystemService(Class)`（API 23）、`MediaCodec.getInputBuffer`（API 21）等
 * 新重载，因此统一在这里改用 4.4 就有的旧 API。调用方只看到与业务无关的函数名，不直接出现
 * 版本判断。
 */
fun <T : Any> Context.systemService(serviceClass: Class<T>): T =
    ContextCompat.getSystemService(this, serviceClass)
        ?: throw IllegalStateException("No system service for ${serviceClass.name}")

/**
 * 与 [systemService] 相同，但把"本机没有这个服务"如实返回为 `null`。
 *
 * 凡是写成 `?: 兜底` / `?.` 的调用点都必须改用本函数——否则那些守卫是**死代码**：本机服务缺失
 * 时不是优雅跳过，而是直接崩（CS11 上 `UsbManager` 缺失会让 CarPlayController 的构造器失败，
 * 而不是给出"USB service is unavailable on this device"这种可重试的失败态）。
 */
fun <T : Any> Context.systemServiceOrNull(serviceClass: Class<T>): T? =
    ContextCompat.getSystemService(this, serviceClass)

// ---------- 媒体路径（API19）----------

/** 4.4 没有 `getInputBuffer`，使用已废弃的 `getInputBuffers()` 数组。 */
@Suppress("DEPRECATION")
fun MediaCodec.inputBufferCompat(index: Int): java.nio.ByteBuffer? = getInputBuffers().getOrNull(index)

/** 4.4 没有 `getOutputBuffer`，使用已废弃的 `getOutputBuffers()` 数组。 */
@Suppress("DEPRECATION")
fun MediaCodec.outputBufferCompat(index: Int): java.nio.ByteBuffer? = getOutputBuffers().getOrNull(index)

/** 4.4 没有 underrun 计数，返回 0（仅统计用途）。 */
@Suppress("UNUSED_PARAMETER")
fun trackUnderrunsCompat(track: AudioTrack): Int = 0

/** 4.4 没有 routed-device 查询，返回 -1。 */
@Suppress("UNUSED_PARAMETER")
fun trackRouteTypeCompat(track: AudioTrack): Int = -1

/** 4.4 没有 bufferSizeInFrames，返回 -1。 */
@Suppress("UNUSED_PARAMETER")
fun trackBufferSizeFramesCompat(track: AudioTrack): Int = -1

/** 4.4 没有 routed-device 查询，返回 null。 */
@Suppress("UNUSED_PARAMETER")
fun recorderRouteTypeCompat(recorder: AudioRecord): Int? = null

/** 4.4 用 `resources.getDrawable`。 */
@Suppress("DEPRECATION")
fun Context.getDrawableCompat(id: Int): android.graphics.drawable.Drawable? = resources.getDrawable(id)

/** 4.4 安装即授予权限，用 PackageManager 静态判定。 */
fun Context.hasPermissionCompat(permission: String): Boolean =
    packageManager.checkPermission(permission, packageName) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

/** 4.4 没有 `noBackupFilesDir`，退到 filesDir/no_backup。 */
fun Context.noBackupFilesDirCompat(): java.io.File =
    java.io.File(filesDir, "no_backup").apply { mkdirs() }
