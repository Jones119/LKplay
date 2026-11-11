package com.shilapi.xcertplay.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A diagnostic log that survives a session in which `adb` was never available.
 *
 * Without USB debugging there is no `adb logcat` on this car, so a wired session that fails in normal use
 * (phone on the car's own USB port, debugging off) used to leave nothing to look at (docs/_digest/59). This
 * writes the same curated lines the controller already produces to a file **outside** the app sandbox, in
 * the shared tree the cluster-artwork work proved another process can read on this ROM: `/sdcard/DiPlay/`.
 *
 * Deliberately cheap and harmless: one buffered writer, flushed at most once a second (and on errors and
 * crashes), capped per file, four sessions kept, and every failure swallowed - a head unit must never fail
 * to display CarPlay because a log line could not be written.
 */
internal object DiagnosticLog {
    private const val TAG = "xcertplay-log"

    /** The literal `/sdcard` alias, not `Environment.getExternalStorageDirectory()`: docs/_digest/48. */
    private const val SHARED_ROOT = "/sdcard/DiPlay"

    private const val DIRECTORY = "logs"
    private const val FLUSH_INTERVAL_MILLIS = 1_000L

    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var bytesWritten = 0L
    private var truncated = false
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var flusher: java.util.Timer? = null
    /** Private directory that survives a read-only /sdcard; see [CrashRecord]. */
    private var crashDirectory: File? = null

    private val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** True once a file is open, i.e. the log can be retrieved from the car after the fact. */
    val isActive: Boolean get() = synchronized(lock) { writer != null }

    /** The file being written, for a UI that wants to show the path. */
    fun currentFile(): File? = synchronized(lock) { file }

    /**
     * Opens this session's file and installs the crash reporter. Returns the file, or null when no location
     * was writable - in which case the app carries on with logcat only.
     */
    fun start(context: Context, sessionLabel: String): File? {
        synchronized(lock) {
            if (writer != null) return file
            val directory = writableDirectory(context) ?: run {
                Log.w(TAG, "no writable directory for the diagnostic log; logcat only")
                return null
            }
            val startedAt = System.currentTimeMillis()
            rotate(directory)
            val target = File(directory, DiagnosticLogPolicy.fileName(startedAt))
            val stream = runCatching { FileOutputStream(target, true) }.getOrNull() ?: run {
                Log.w(TAG, "could not open $target; logcat only")
                return null
            }
            writer = BufferedWriter(OutputStreamWriter(stream, "UTF-8"))
            file = target
            bytesWritten = 0
            truncated = false
            DiagnosticLogPolicy.header(
                startedAtMillis = startedAt,
                appVersion = appVersion(context),
                // CS11 only: the runtime API level is always 19.
                sdkInt = 19,
                release = Build.VERSION.RELEASE ?: "?",
                model = Build.MODEL ?: "?",
                product = Build.PRODUCT ?: "?",
                sessionLabel = sessionLabel,
            ).forEach { writeLocked(it) }
            writeLocked("logFile=${target.absolutePath}")
            crashDirectory = runCatching { context.filesDir }.getOrNull()
            flushLocked()
            startFlusher()
            installCrashHandler()
            Log.i(TAG, "diagnostic log started: ${target.absolutePath}")
            return target
        }
    }

    /**
     * 把周期 flush 交给一条专用线程。
     *
     * `/sdcard` 在 CS11 上很慢，一次 flush 可能十几毫秒；以前这个代价落在调用线程上，而调用方
     * 包括音频与 RTSP 线程，正好是连接/播放最敏感的窗口。写入顺序仍在调用线程内完成，只有
     * 刷盘搬走了。`error()`、`stop()`、`start()` 仍同步 flush —— 崩溃前必须落盘。
     */
    private fun startFlusher() {
        if (flusher != null) return
        val timer = java.util.Timer("diplay-diag-flush", true)
        timer.schedule(object : java.util.TimerTask() {
            override fun run() {
                synchronized(lock) { flushLocked() }
            }
        }, FLUSH_INTERVAL_MILLIS, FLUSH_INTERVAL_MILLIS)
        flusher = timer
    }

    fun line(message: String) {
        synchronized(lock) {
            if (writer == null) return
            val stamp = timestamp.format(Date())
            writeLocked("$stamp I $message")
            // No flush here: the dedicated flusher owns that (startFlusher).
        }
    }

    fun error(message: String, error: Throwable) {
        synchronized(lock) {
            if (writer == null) return
            val stamp = timestamp.format(Date())
            writeLocked("$stamp E $message")
            writeLocked("$stamp E   ${error.javaClass.name}: ${error.message}")
            error.stackTrace.take(24).forEach { writeLocked("$stamp E     at $it") }
            // An error is exactly when the file has to be on disk: the process may not survive it.
            flushLocked()
        }
    }

    /** Copies the newest sessions into this session's file, for a report the user can read on the car. */
    fun stop() {
        synchronized(lock) {
            flusher?.cancel()
            flusher = null
            writeLocked("${timestamp.format(Date())} I log stopped")
            flushLocked()
            runCatching { writer?.close() }
            writer = null
        }
    }

    private fun writeLocked(line: String) {
        val target = writer ?: return
        if (truncated) return
        val clean = DiagnosticLogPolicy.sanitize(line)
        if (bytesWritten >= DiagnosticLogPolicy.MAX_FILE_BYTES) {
            truncated = true
            runCatching {
                target.write("${timestamp.format(Date())} W file reached " +
                    "${DiagnosticLogPolicy.MAX_FILE_BYTES} bytes; further lines are not written")
                target.flush()
            }
            return
        }
        bytesWritten += clean.length + 1
        runCatching { target.write(clean); target.newLine() }
    }

    private fun flushLocked() {
        runCatching { writer?.flush() }
    }

    /**
     * Reports an uncaught exception into the file before the process dies. The previous handler is chained,
     * so the platform still does whatever it would have done.
     */
    private fun installCrashHandler() {
        if (previousHandler != null) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        previousHandler = previous
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            error("uncaught on ${thread.name}", error)
            // 私有目录那一份与日志文件相互独立：日志可能在 /sdcard 上写不了，报告也未必读得到。
            crashDirectory?.let { CrashRecord.record(it, thread.name, error) }
            stop()
            previous?.uncaughtException(thread, error)
        }
    }

    private fun rotate(directory: File) {
        val existing = directory.list()?.toList() ?: return
        DiagnosticLogPolicy.filesToDelete(existing).forEach { stale ->
            runCatching { File(directory, stale).delete() }
        }
    }

    private fun writableDirectory(context: Context): File? {
        val candidates = ArrayList<File>(3)
        candidates.add(File(SHARED_ROOT, DIRECTORY))
        context.getExternalFilesDir(null)?.let { candidates.add(File(it, DIRECTORY)) }
        candidates.add(File(context.filesDir, DIRECTORY))
        for (candidate in candidates) {
            val ok = runCatching {
                if (!candidate.isDirectory) candidate.mkdirs() else true
            }.getOrDefault(false)
            if (ok && candidate.isDirectory && candidate.canWrite()) return candidate
        }
        return null
    }

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName}(${info.versionCode}) ${context.packageName}"
    }.getOrDefault("unknown")
}
