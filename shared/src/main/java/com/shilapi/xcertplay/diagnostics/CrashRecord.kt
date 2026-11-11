package com.shilapi.xcertplay.diagnostics

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 上一次未捕获崩溃的一行摘要，供“保存诊断报告”读取（doc 127 `CrashRecord`）。
 *
 * 会话日志写在 `/sdcard/DiPlay/logs`，那里可能不可写、也可能在报告里读不到；这里始终写应用私有
 * 目录，崩溃后下一次启动一定能拿到。只保留最近一次（覆盖写），且只记线程、异常与前 24 行栈。
 */
object CrashRecord {
    private const val FILE_NAME = "crash-last.txt"
    private const val MAX_STACK_LINES = 24
    private const val MAX_CHARS = 4_000

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Runs inside the uncaught handler: must not allocate much and must never throw. */
    fun record(directory: File, threadName: String, error: Throwable) {
        runCatching {
            val text = buildString {
                append("time=").append(stamp.format(Date()))
                append(" thread=").append(threadName).append('\n')
                append(error.javaClass.name).append(": ").append(error.message ?: "").append('\n')
                error.stackTrace.take(MAX_STACK_LINES).forEach {
                    append("  at ").append(it.toString()).append('\n')
                }
            }.take(MAX_CHARS)
            File(directory, FILE_NAME).writeText(text, Charsets.UTF_8)
        }
    }

    /** The recorded crash, or null when this install has not crashed yet. */
    fun read(directory: File): String? = runCatching {
        val file = File(directory, FILE_NAME)
        if (!file.isFile) return@runCatching null
        file.readText(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}
