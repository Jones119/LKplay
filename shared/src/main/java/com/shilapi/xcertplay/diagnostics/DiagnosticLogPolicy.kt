package com.shilapi.xcertplay.diagnostics

import java.util.TimeZone

/**
 * The pure decisions behind the on-car diagnostic log: what the files are called, which ones get deleted,
 * what a line may look like and what a session header says.
 *
 * Split out from [DiagnosticLog] because this is the part worth testing: the log itself has to work on a
 * car where USB debugging is off and `adb logcat` is therefore unavailable, so it is the only evidence a
 * failed session leaves behind (docs/_digest/59).
 */
internal object DiagnosticLogPolicy {
    /** One file stops growing here; a long session then says so instead of filling the card. */
    const val MAX_FILE_BYTES = 1_000_000

    /** How many sessions are kept: newest first, older ones are deleted on start. */
    const val KEEP_FILES = 4

    /** A single line is capped so one dumped payload cannot bury the sequence around it. */
    const val MAX_LINE_CHARS = 4_000

    private const val PREFIX = "diplay-"

    /** `diplay-20261013-214530.log`: fixed width, so sorting the names sorts them by time. */
    fun fileName(startedAtMillis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val format = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        format.timeZone = zone
        return "$PREFIX${format.format(java.util.Date(startedAtMillis))}.log"
    }

    /**
     * The files to delete so that this session plus [keep] - 1 older ones survive, oldest first: a Windows
     * line ending collapses to one space, not two.
     */
    fun filesToDelete(existing: List<String>, keep: Int = KEEP_FILES): List<String> =
        existing.filter { it.startsWith(PREFIX) && it.endsWith(".log") }
            .sorted()
            .dropLast((keep - 1).coerceAtLeast(1))

    /** One line, without newlines, capped at [MAX_LINE_CHARS]. */
    fun sanitize(line: String): String {
        val flattened = line.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim()
        return if (flattened.length <= MAX_LINE_CHARS) {
            flattened
        } else {
            flattened.take(MAX_LINE_CHARS) + "…(truncated ${flattened.length - MAX_LINE_CHARS})"
        }
    }

    /**
     * The header every session starts with. It carries the identity a later reader needs to tell whether a
     * run was the normal-use one: build, ROM, and the transport that was chosen.
     */
    fun header(
        startedAtMillis: Long,
        appVersion: String,
        sdkInt: Int,
        release: String,
        model: String,
        product: String,
        sessionLabel: String,
    ): List<String> = listOf(
        "=== DiPlay diagnostic log ===",
        "started=${java.util.Date(startedAtMillis)}",
        "session=$sessionLabel",
        "appVersion=$appVersion",
        "android=sdk$sdkInt release=$release model=$model product=$product",
    )
}
