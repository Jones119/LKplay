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
    private var lastFlush = 0L
    private var truncated = false
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

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
            lastFlush = 0
            DiagnosticLogPolicy.header(
                startedAtMillis = startedAt,
                appVersion = appVersion(context),
                sdkInt = Build.VERSION.SDK_INT,
                release = Build.VERSION.RELEASE ?: "?",
                model = Build.MODEL ?: "?",
                product = Build.PRODUCT ?: "?",
                sessionLabel = sessionLabel,
            ).forEach { writeLocked(it) }
            writeLocked("logFile=${target.absolutePath}")
            flushLocked()
            installCrashHandler()
            Log.i(TAG, "diagnostic log started: ${target.absolutePath}")
            return target
        }
    }

    fun line(message: String) {
        synchronized(lock) {
            if (writer == null) return
            val stamp = timestamp.format(Date())
            writeLocked("$stamp I $message")
            maybeFlushLocked()
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

    private fun maybeFlushLocked() {
        val now = System.currentTimeMillis()
        if (now - lastFlush >= FLUSH_INTERVAL_MILLIS) flushLocked()
    }

    private fun flushLocked() {
        lastFlush = System.currentTimeMillis()
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
