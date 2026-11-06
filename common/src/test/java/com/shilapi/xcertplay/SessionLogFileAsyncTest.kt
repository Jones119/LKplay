package com.shilapi.xcertplay

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session log is queued and written by one background thread (upstream `e0ccc4de`, AUD-10).
 * These assert the two contracts callers depend on: [SessionLogFile.appendNow] is immediate, and
 * [SessionLogFile.close] flushes what was queued before the activity ends.
 */
class SessionLogFileAsyncTest {
    @Test fun appendNowWritesBeforeReturning() {
        val folder = Files.createTempDirectory("diplay-log-now").toFile()
        try {
            SessionLogFile(folder.resolve("diplay.log")).use { log ->
                log.reset("started")
                log.appendNow("immediate line")
                assertTrue(read(log).contains("immediate line"))
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun closeFlushesQueuedLines() {
        val folder = Files.createTempDirectory("diplay-log-close").toFile()
        try {
            val log = SessionLogFile(folder.resolve("diplay.log"))
            log.reset("started")
            log.append("queued before close")
            log.close()
            assertTrue(folder.resolve("diplay.log").readText().contains("queued before close"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun queuedAndImmediateLinesKeepTheirOrder() {
        val folder = Files.createTempDirectory("diplay-log-order").toFile()
        try {
            SessionLogFile(folder.resolve("diplay.log")).use { log ->
                log.reset("started")
                log.appendNow("first")
                log.append("second")
                assertTrue(log.awaitIdle(2_000))
                val lines = read(log).lines().filter { it.endsWith("first") || it.endsWith("second") }
                assertEquals(listOf("first", "second"), lines.map { it.substringAfterLast("  ") })
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun neverExportedLinesAreRefusedBeforeTheyAreStored() {
        val folder = Files.createTempDirectory("diplay-log-reject").toFile()
        try {
            SessionLogFile(folder.resolve("diplay.log")).use { log ->
                log.reset("started")
                log.append("TRACE IAP2 tx secret")
                log.append("payload=private")
                log.append("kept")
                assertTrue(log.awaitIdle(2_000))
                val text = read(log)
                assertFalse(text.contains("TRACE"))
                assertFalse(text.contains("private"))
                assertTrue(text.contains("kept"))
            }
        } finally { folder.deleteRecursively() }
    }

    private fun read(log: SessionLogFile): String {
        log.awaitIdle(2_000)
        return log.file.readText()
    }
}
