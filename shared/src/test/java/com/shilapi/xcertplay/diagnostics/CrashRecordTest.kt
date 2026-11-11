package com.shilapi.xcertplay.diagnostics

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The private-directory copy of the last uncaught crash, which the diagnostic report reads
 * (`doc 127` CrashRecord). It has to work when `/sdcard` is not writable, so it is plain file I/O.
 */
class CrashRecordTest {
    private fun tempDir(): File = Files.createTempDirectory("diplay-crash").toFile()

    @Test
    fun readReturnsNullBeforeAnyCrash() {
        val dir = tempDir()
        try {
            assertNull(CrashRecord.read(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun recordKeepsTheThreadAndTheTopOfTheStack() {
        val dir = tempDir()
        try {
            CrashRecord.record(dir, "carplay-audio", IllegalStateException("boom"))
            val text = CrashRecord.read(dir)
            assertNotNull(text)
            assertTrue(text!!.contains("thread=carplay-audio"))
            assertTrue(text.contains("IllegalStateException: boom"))
            assertTrue(text.contains("CrashRecordTest"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun onlyTheLatestCrashIsKept() {
        val dir = tempDir()
        try {
            CrashRecord.record(dir, "first", IllegalStateException("one"))
            CrashRecord.record(dir, "second", IllegalStateException("two"))
            val text = CrashRecord.read(dir)!!
            assertTrue(text.contains("thread=second"))
            assertFalse(text.contains("thread=first"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun anEnormousMessageCannotGrowTheFileWithoutBound() {
        val dir = tempDir()
        try {
            CrashRecord.record(dir, "t", IllegalStateException("x".repeat(10_000)))
            assertTrue(CrashRecord.read(dir)!!.length <= 4_000)
        } finally {
            dir.deleteRecursively()
        }
    }
}
