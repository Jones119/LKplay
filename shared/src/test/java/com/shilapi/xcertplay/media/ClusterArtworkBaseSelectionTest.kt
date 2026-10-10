package com.shilapi.xcertplay.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Which external base a `file://` cover is delivered on, and how a base is accepted or refused.
 *
 * This is the decision that the 2026-10-10 on-car `adb` run falsified: the store wrote its cover into
 * the app's own `Android/data/<pkg>/files` tree and handed over a path string that resolved nowhere on
 * that ROM, while the bytes were verifiably on disk -- a self-check that reused the writer's own `File`
 * could not see the difference. The rule that replaces it is a decision, not an I/O detail, so it is
 * exercised here on the JVM with every filesystem answer injected: existence, the write, the read-back
 * through the delivered path and the delete.
 *
 * What no JVM test can decide is what a *different process* does with the delivered string. Whether the
 * DIM service's reader can open `/sdcard/DiPlay/cluster-artwork/…` on the CS11 is an on-car fact (the
 * shared directory exists because the car's own media application keeps its covers in one), and the
 * `logcat` lines named in [ClusterArtworkRejection] are what the car has to answer.
 */
class ClusterArtworkBaseSelectionTest {
    @get:Rule
    val folder = TemporaryFolder()

    private companion object {
        /** The fixed name a base probe writes and then removes, spelled as the store's own constant is. */
        const val PROBE_NAME = "base-probe.jpg"
    }

    private val session = "0f0e0d0c-0b0a-0908-0706-050403020100"
    private val shared = "/sdcard/$CLUSTER_ARTWORK_SHARED_ROOT"

    /**
     * A candidate as the store builds it: a root, then the `cluster-artwork` directory.
     *
     * Spelled like the car's own framework answer (`/storage/emulated/0/…`), because the store appends
     * `cluster-artwork` itself and a root that already ended in that name would be a path no device
     * produces.
     */
    private val framework = "/storage/emulated/0/Android/data/com.shihab.diplay.hudtest/files"
    private val frameworkBase = "$framework/$CLUSTER_ARTWORK_DIRECTORY"
    private val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0x33)

    /**
     * A path with `/` as its only separator, which is what the store's candidate list holds.
     *
     * The candidates are the car's own Unix paths, and the JVM running these tests builds `File` paths
     * with the host separator, so every string the fake keys on has to be folded the same way the store
     * folds its candidates.
     */
    private fun normalised(path: String): String = path.replace('\\', '/').trimEnd('/')

    private val source: File by lazy {
        File(folder.newFolder("cache-session"), newClusterArtworkFileName()).apply { writeBytes(payload) }
    }

    @Test
    fun theSharedDirectoryIsProbedFirstAndItsPathIsTheOneReturned() {
        val raw = FakeRaw(exists = mutableSetOf(shared))

        val chosen = selector(listOf(shared, frameworkBase), raw).select(source)

        assertEquals(
            "the car's own media application hands the cluster a /sdcard path; that form goes first",
            shared,
            chosen,
        )
        // The probe target has to be the delivered shape -- the session directory the cover lands in --
        // because a base that is a directory while its session directory is not proves nothing.
        assertEquals(
            listOf("$shared/$CLUSTER_ARTWORK_DIRECTORY/$session"),
            raw.mkdirsCalls,
        )
        assertEquals(listOf(shared), raw.probeWrites.map { it.first })
        // The probe file is cleaned up again: a driver's next run must not find it.
        assertTrue(raw.deleted.any { it.endsWith("/$PROBE_NAME") })
    }

    @Test
    fun aRomWhereTheSharedDirectoryValidatesNothingFallsToTheFrameworkPath() {
        // The 2026-10-10 shape: both directories are there, the bytes go into the shared one, and the
        // same path reads nothing back -- so the framework path carries the cover instead.
        val raw = FakeRaw(exists = mutableSetOf(shared, frameworkBase), silentBases = mutableSetOf(shared))

        val chosen = selector(listOf(shared, frameworkBase), raw).select(source)

        assertEquals("the second candidate carries the cover", frameworkBase, chosen)
        assertEquals(listOf(shared to ClusterArtworkRejection.READ_FAILED), raw.recorder.rejections)
    }

    @Test
    fun aCandidateThatWritesButCannotBeReadBackIsRejected() {
        val raw = FakeRaw(
            exists = mutableSetOf(shared, frameworkBase),
            silentBases = mutableSetOf(shared),
        )

        val chosen = selector(listOf(shared, frameworkBase), raw).select(source)

        assertEquals("a base nobody reads back is not a delivery", frameworkBase, chosen)
        assertEquals(
            listOf(shared to ClusterArtworkRejection.READ_FAILED),
            raw.recorder.rejections,
        )
    }

    @Test
    fun aCandidateThatReadsBackDifferentBytesIsRejected() {
        // The right byte count, the wrong bytes: a stale or half-written file at the delivered path.
        val raw = FakeRaw(
            exists = mutableSetOf(shared, frameworkBase),
            wrongBytes = mutableMapOf(shared to byteArrayOf(1, 2, 3, 4, 5)),
        )

        val chosen = selector(listOf(shared, frameworkBase), raw).select(source)

        assertEquals(frameworkBase, chosen)
        assertEquals(
            listOf(shared to ClusterArtworkRejection.BYTES_MISMATCH),
            raw.recorder.rejections,
        )
    }

    @Test
    fun aCandidateWhoseWriteThrowsIsRejectedAndTheNextOneTried() {
        val raw = FakeRaw(exists = mutableSetOf(shared, frameworkBase), throwOnWrite = mutableSetOf(shared))

        val chosen = selector(listOf(shared, frameworkBase), raw).select(source)

        assertEquals(frameworkBase, chosen)
        assertEquals(listOf(shared to ClusterArtworkRejection.WRITE_FAILED), raw.recorder.rejections)
    }

    @Test
    fun noCandidateThatValidatesMeansNoBaseAtAll() {
        // A car whose shared volume cannot be written and whose framework directory does not exist --
        // exactly the shape of the 2026-10-10 delivery, minus the wrong self-check.
        val raw = FakeRaw(exists = mutableSetOf(), throwOnWrite = mutableSetOf(shared))

        assertNull(selector(listOf(shared, frameworkBase), raw).select(source))
        assertEquals(
            listOf(shared to ClusterArtworkRejection.WRITE_FAILED, frameworkBase to ClusterArtworkRejection.WRITE_FAILED),
            raw.recorder.rejections,
        )
    }

    @Test
    fun theDecisionIsCachedSoALaterTrackDoesNotProbeAgain() {
        val raw = FakeRaw(exists = mutableSetOf(shared))
        val selector = selector(listOf(shared, frameworkBase), raw)

        assertEquals(shared, selector.select(source))
        raw.probeWrites.clear()
        raw.mkdirsCalls.clear()

        assertEquals(shared, selector.select(source))
        assertTrue("a probe per track would cost a write and a read on every album change", raw.probeWrites.isEmpty())
        assertEquals(shared, selector.currentBase)
    }

    @Test
    fun aBaseThatFailsAfterItWasChosenIsReProbedAndTheNextOneTakesOver() {
        // The shared base works, then the volume goes away: its writes throw from the second handover.
        val failing = mutableSetOf<String>()
        val raw = FakeRaw(exists = mutableSetOf(shared, frameworkBase), throwOnWrite = failing)
        val selector = selector(listOf(shared, frameworkBase), raw)
        assertEquals(shared, selector.select(source))

        failing += shared
        selector.invalidate(shared)

        assertEquals(frameworkBase, selector.select(source))
        assertEquals("the chosen base is the one that validated last", frameworkBase, selector.currentBase)
        // One probe from the retry, one from the walk that follows it, and then the next candidate:
        // the failed base is not given a third attempt inside the same handover.
        assertEquals(
            "the base that failed was probed again before the framework path was tried",
            listOf(shared, shared, frameworkBase),
            raw.probeWrites.map { it.first },
        )
        assertEquals(
            "a base that fails again is reported once, not twice",
            listOf(shared to ClusterArtworkRejection.WRITE_FAILED),
            raw.recorder.rejections,
        )
    }

    @Test
    fun aVolumeThatComesBackAfterAFailureIsUsedAgainInsteadOfStayingDead() {
        // The other half of re-probing: a base that failed once is not dead for the process' life, so a
        // mount that flaps costs one probe and not the rest of the drive's covers.
        val failing = mutableSetOf(shared)
        val raw = FakeRaw(exists = mutableSetOf(shared, frameworkBase), throwOnWrite = failing)
        val selector = selector(listOf(shared, frameworkBase), raw)
        val whileBroken = selector.select(source)

        // The volume is back, and the store reports the base whose delivery failed. That report is what
        // gives the base its one retry, so this drives `invalidate` for the base that actually failed
        // rather than for the one that stood in for it.
        failing.clear()
        selector.invalidate(shared)

        assertEquals("the framework directory stood in while the shared tree was broken", frameworkBase, whileBroken)
        assertEquals(
            "the shared tree is the car's proven form, so it takes the cover back once it is writable",
            shared,
            selector.select(source),
        )
        assertEquals(shared, selector.currentBase)
        assertEquals(
            "the shared tree was probed for the first handover, the framework path for the second, and " +
                "the recovered shared tree first for the third",
            listOf(shared, frameworkBase, shared),
            raw.probeWrites.map { it.first },
        )
    }

    @Test
    fun aRejectionIsReportedOncePerBaseNotOncePerTrack() {
        // The shared base takes the bytes and reads nothing back; the framework directory cannot even
        // be created; neither is reported more than once, however many tracks are published.
        val raw = FakeRaw(
            exists = mutableSetOf(shared, frameworkBase),
            silentBases = mutableSetOf(shared),
            throwOnWrite = mutableSetOf(frameworkBase),
        )
        val selector = selector(listOf(shared, frameworkBase), raw)

        assertNull(selector.select(source))
        assertNull(selector.select(source))
        assertNull(selector.select(source))

        assertEquals(
            "the same two lines for every track would bury the reason in a long drive's logcat",
            listOf(shared to ClusterArtworkRejection.READ_FAILED, frameworkBase to ClusterArtworkRejection.WRITE_FAILED),
            raw.recorder.rejections,
        )
    }

    @Test
    fun theBaseLogTagNamesTheBaseWithoutCarryingItsSlashes() {
        // The scalar an on-car grep uses to tell which spelling was refused.
        assertEquals("sdcard-DiPlay", clusterArtworkBaseLogTag("/sdcard/DiPlay"))
        assertEquals("storage-emulated-legacy-DiPlay", clusterArtworkBaseLogTag("/storage/emulated/legacy/DiPlay"))
        assertEquals("mnt-shell-emulated-0-DiPlay", clusterArtworkBaseLogTag("/mnt/shell/emulated/0/DiPlay"))
        assertEquals("root", clusterArtworkBaseLogTag("/"))
    }

    @Test
    fun theDeliveredUriIsBuiltFromTheVeryStringThatWasValidated() {
        val path = "$shared/$CLUSTER_ARTWORK_DIRECTORY/$session/11111111-2222-3333-4444-555555555555.jpg"

        assertEquals("file://$path", clusterArtworkFileUriText(path))
    }

    @Test
    fun theSharedBasesAreDeduplicatedAndAllCarryOurOwnDirectory() {
        // Whatever the ROM's spellings are, every shared base is `<root>/DiPlay` and no root is tried
        // twice, so a volume reachable by two names costs one probe rather than two. The roots are
        // injected, because on a JVM the framework's own answer is the host's temporary directory
        // rather than one of the car's mount points.
        val bases = clusterArtworkSharedBasesFrom(listOf("/sdcard", "/sdcard/", "/storage/emulated/legacy"))

        assertEquals(
            listOf("/sdcard/$CLUSTER_ARTWORK_SHARED_ROOT", "/storage/emulated/legacy/$CLUSTER_ARTWORK_SHARED_ROOT"),
            bases,
        )
        // A car that exposes no external storage at all contributes no shared candidate.
        assertTrue(clusterArtworkSharedBasesFrom(listOf(null)).isEmpty())
    }

    @Test
    fun theFileSelfCheckComparesTheWholeEncodingRatherThanItsLength() {
        val target = File(folder.newFolder("delivery"), "cover.jpg")
        target.writeBytes(payload)
        assertTrue(clusterArtworkFileMatchesBytes(target, payload))

        // A cover of the right length but the wrong bytes is what a stale or half-written file looks
        // like, and the reader would paint it.
        val wrong = payload.copyOf().also { it[1] = 0x00 }
        assertTrue(clusterArtworkWithinBudget(payload.size.toLong()))
        assertFalse(clusterArtworkFileMatchesBytes(target, wrong))
        assertFalse("a longer file is not our cover", clusterArtworkFileMatchesBytes(target, payload + byteArrayOf(1)))
        assertFalse(clusterArtworkFileMatchesBytes(File(target.parentFile, "missing.jpg"), payload))
        // An encoding outside the accepted range is refused before any file is consulted.
        assertFalse(clusterArtworkFileMatchesBytes(target, ByteArray(0)))
    }

    @Test
    fun theFrameworkAliasesAreBuiltFromItsAndroidTailOnly() {
        val aliases = listOf(File("/sdcard"), File("/storage/emulated/legacy"))

        assertEquals(
            listOf(
                File("/sdcard/Android/data/com.shihab.diplay.hudtest/files"),
                File("/storage/emulated/legacy/Android/data/com.shihab.diplay.hudtest/files"),
                File("/storage/emulated/0/Android/data/com.shihab.diplay.hudtest/files"),
            ),
            frameworkAliasCandidates(
                File("/storage/emulated/0/Android/data/com.shihab.diplay.hudtest/files"),
                aliases,
            ),
        )
        // A framework answer of an unexpected shape produces no candidate rather than a guessed path,
        // and a car without the directory at all produces none either.
        assertTrue(frameworkAliasCandidates(File("/data/data/com.shihab.diplay.hudtest/files"), aliases).isEmpty())
        assertTrue(frameworkAliasCandidates(null, aliases).isEmpty())
    }

    private fun selector(candidates: List<String>, raw: FakeRaw): ClusterArtworkBaseSelector =
        ClusterArtworkBaseSelector(
            candidates = candidates,
            session = session,
            raw = raw.raw,
            onRejected = raw.recorder,
        )

    /** Every filesystem answer the selector can ask for, with the failure modes the car measured. */
    private inner class FakeRaw(
        /** Candidate bases, named the way the store names them: `/`-separated. */
        exists: MutableSet<String>,
        /** Bases whose write succeeds and whose read-back then returns nothing. */
        private val silentBases: MutableSet<String> = mutableSetOf(),
        /** Bases whose read-back returns exactly these bytes instead of the cover's. */
        private val wrongBytes: MutableMap<String, ByteArray> = mutableMapOf(),
        /** Bases whose write throws, as a read-only or permission-denied mount does. */
        private val throwOnWrite: MutableSet<String> = mutableSetOf(),
    ) {
        private val exists: MutableSet<String> = exists.mapTo(mutableSetOf()) { normalised(it) }
        val recorder = RejectionRecorder()
        val mkdirsCalls = mutableListOf<String>()
        val probeWrites = mutableListOf<Pair<String, String>>()
        val deleted = mutableListOf<String>()
        private val probeFiles = mutableListOf<String>()

        val raw = RawFileSystem(
            isDirectory = { file -> normalised(file) in exists },
            mkdirs = { directory -> mkdirsCalls += normalised(directory); mkdirsSucceeds(directory) },
            // The probe file is only "there" while a previous probe's copy is still on the fake disk.
            isFile = { file -> normalised(file) in probeFiles },
            writeCopyAndReadBack = { _, directory ->
                val base = baseOf(directory)
                // Recorded before the refusal, so the trace of a probe is the trace of the attempt.
                probeWrites += base to normalised(File(directory, PROBE_NAME))
                if (base in throwOnWrite) error("read-only file system")
                probeFiles += normalised(File(directory, PROBE_NAME))
                when {
                    base in silentBases -> null
                    base in wrongBytes -> wrongBytes[base]
                    else -> source.readBytes()
                }
            },
            delete = { file ->
                deleted += normalised(file)
                probeFiles -= normalised(file)
                true
            },
        )

        /** One level of {@code mkdirs}: the session directory exists once its base is in [exists]. */
        private fun mkdirsSucceeds(directory: File): Boolean {
            val base = baseOf(directory)
            if (base !in exists) return false
            exists += normalised(directory)
            return true
        }

        /**
         * `<base>/cluster-artwork/<session>` -> `<base>`, by string rather than by [File.parentFile].
         *
         * Two reasons. The candidate bases are the car's own Unix paths, and `File.parentFile` answers
         * null for a Unix-absolute path on the JVM running these tests; and the base is found from the
         * `cluster-artwork` marker so that a candidate which *is* a `cluster-artwork` directory keeps
         * that name in its own path.
         */
        private fun baseOf(directory: File): String {
            val marker = "/$CLUSTER_ARTWORK_DIRECTORY"
            val path = normalised(directory)
            val index = path.lastIndexOf(marker)
            return if (index > 0) path.substring(0, index) else path
        }

        private fun normalised(file: File): String = normalised(file.path)
    }

    private inner class RejectionRecorder : ClusterArtworkRejectionLog {
        val rejections = mutableListOf<Pair<String, ClusterArtworkRejection>>()

        override fun rejected(base: String, reason: ClusterArtworkRejection) {
            rejections += base to reason
        }
    }
}














