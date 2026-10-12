package com.shilapi.xcertplay.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The decisions behind the on-car log file (docs/_digest/59).
 *
 * These matter because the file is the only evidence a session leaves when USB debugging is off: the name
 * has to sort chronologically so rotation drops the right one, a line must not be able to bury the sequence
 * around it, and the header has to say which build and which transport a later reader is looking at.
 */
class DiagnosticLogPolicyTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun theFileNameSortsChronologically() {
        // 2026-10-13 21:45:30 UTC
        val name = DiagnosticLogPolicy.fileName(1_791_927_930_000L, utc)
        assertEquals("diplay-20261013-214530.log", name)
        val earlier = DiagnosticLogPolicy.fileName(1_791_927_930_000L - 86_400_000L, utc)
        assertTrue("$earlier must sort before $name", earlier < name)
    }

    @Test
    fun rotationKeepsTheNewestSessionsAndIgnoresForeignFiles() {
        val existing = listOf(
            "diplay-20261010-100000.log",
            "diplay-20261011-100000.log",
            "diplay-20261012-100000.log",
            "diplay-20261013-100000.log",
            "diplay-20261014-100000.log",
            "notes.txt",
            "diplay-notes.log.bak",
        )
        val stale = DiagnosticLogPolicy.filesToDelete(existing)
        assertEquals(
            listOf("diplay-20261010-100000.log", "diplay-20261011-100000.log"),
            stale,
        )
        // The two newest sessions and every foreign file stay.
        assertFalse(stale.contains("diplay-20261014-100000.log"))
        assertFalse(stale.contains("notes.txt"))
    }

    @Test
    fun aLineIsFlattenedAndCapped() {
        assertEquals("one two", DiagnosticLogPolicy.sanitize("one\ntwo"))
        assertEquals("a b", DiagnosticLogPolicy.sanitize("  a\r\nb  "))
        val long = "x".repeat(DiagnosticLogPolicy.MAX_LINE_CHARS + 500)
        val capped = DiagnosticLogPolicy.sanitize(long)
        assertTrue(capped.startsWith("x".repeat(100)))
        assertTrue(capped.length < long.length)
        assertTrue("must say it truncated: ${capped.takeLast(30)}", capped.contains("truncated 500"))
    }

    @Test
    fun theHeaderIdentifiesTheBuildTheRomAndTheSession() {
        val header = DiagnosticLogPolicy.header(
            startedAtMillis = 1_791_927_930_000L,
            appVersion = "1.2.3(45) com.shihab.diplay.hudtest",
            sdkInt = 19,
            release = "4.4.3",
            model = "CS11",
            product = "cs11_19a_h",
            sessionLabel = "transport=WIRED",
        ).joinToString("\n")
        assertTrue(header.contains("appVersion=1.2.3(45)"))
        assertTrue(header.contains("sdk19"))
        assertTrue(header.contains("product=cs11_19a_h"))
        assertTrue(header.contains("session=transport=WIRED"))
    }

    @Test
    fun theCapsAreWhatTheWriterAssumes() {
        assertEquals(1_000_000, DiagnosticLogPolicy.MAX_FILE_BYTES)
        assertEquals(4, DiagnosticLogPolicy.KEEP_FILES)
        assertTrue(DiagnosticLogPolicy.MAX_LINE_CHARS in 1_000..10_000)
    }
}
