package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * The rules for handing a cover to the head unit (docs/_digest/38 §3 and 45 G5/§2.4).
 *
 * The encoding rules (JPEG, longest edge 256 px, encoded under 1 MiB), the three-tier precedence the
 * car forced on us (the loopback HTTP server when it bound, a readable external `file://` otherwise,
 * the `content://` provider only when there is no file), the provider's exact Uri shape, the mapping it
 * parses back and the loopback URL's shape are all decided here, on the JVM. What needs the car --
 * whether the cluster really paints a cover, and whether the vendor's process opens a `file://` Uri,
 * fetches the loopback URL or does neither -- is listed in the digest rather than asserted here,
 * because a JVM test cannot speak for another process. The server's own wire behaviour is asserted
 * against a real socket in `ClusterArtworkHttpServerTest`, and the tier a store really ends in in
 * `ClusterArtworkStoreHttpTierTest`.
 *
 * A temporary directory stands in for `cacheDir` and for the external files directory, so both the
 * provider mapping and the cleanup sweeps are checked against real files instead of a mocked
 * filesystem.
 */
class ClusterArtworkTransportTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val packageName = "com.shihab.diplay.hudtest"

    @Test
    fun theScaleCapsTheLongestEdgeAt256PixelsAndNeverEnlarges() {
        assertEquals(0.25f, clusterArtworkScale(1024, 512), 0.0001f)
        assertEquals(256f / 300f, clusterArtworkScale(120, 300), 0.0001f)
        // Smaller than the cap: handed over as it is, not blown up to the cap.
        assertEquals(1f, clusterArtworkScale(100, 50), 0.0001f)
        assertEquals(1f, clusterArtworkScale(256, 256), 0.0001f)
        // A degenerate source is refused rather than divided by zero.
        assertEquals(0f, clusterArtworkScale(0, 10), 0.0001f)
    }

    @Test
    fun theScaledEdgesAreRoundedDownButNeverBelowOnePixel() {
        assertEquals(256, clusterArtworkScaledEdge(1024, clusterArtworkScale(1024, 512)))
        assertEquals(128, clusterArtworkScaledEdge(512, clusterArtworkScale(1024, 512)))
        assertEquals(1, clusterArtworkScaledEdge(3, 0.1f))
        assertEquals(0, clusterArtworkScaledEdge(300, 0f))
    }

    @Test
    fun theByteBudgetIsTheReferencesInclusiveOneMebibyte() {
        assertTrue(clusterArtworkWithinBudget(1L))
        assertTrue(clusterArtworkWithinBudget(CLUSTER_ARTWORK_MAX_ENCODED_BYTES))
        assertFalse(clusterArtworkWithinBudget(0L))
        assertFalse(clusterArtworkWithinBudget(CLUSTER_ARTWORK_MAX_ENCODED_BYTES + 1))
    }

    @Test
    fun aSourceImageOutsideTheReferenceLimitsIsRefusedBeforeItIsEncoded() {
        assertTrue(clusterArtworkSourceAccepted(384, 384))
        assertTrue(clusterArtworkSourceAccepted(4096, 4096))
        assertFalse("an edge above the reference's 8192 px limit", clusterArtworkSourceAccepted(8193, 10))
        assertFalse(clusterArtworkSourceAccepted(10, 8193))
        assertFalse(clusterArtworkSourceAccepted(0, 10))
        assertFalse("more than 16 M pixels", clusterArtworkSourceAccepted(3000, 6000))
        // The compressed-size half of the rule, which the decode path upstream already applies.
        assertTrue(clusterArtworkSourceAccepted(384, 384, byteSize = 1024))
        assertFalse(clusterArtworkSourceAccepted(384, 384, byteSize = 0))
        assertFalse(clusterArtworkSourceAccepted(384, 384, byteSize = 1_048_577))
    }

    @Test
    fun theFirstQualityThatFitsTheBudgetIsTheOneUsedAndTheOthersAreNeverTried() {
        val tried = mutableListOf<Int>()
        val discarded = mutableListOf<String>()

        val result = encodeClusterArtworkWithinBudget(
            encode = { quality -> tried += quality; "encoded-$quality" },
            sizeOf = { 512_000L },
            discard = { discarded += it },
        )

        assertEquals("encoded-88", result)
        assertEquals(listOf(88), tried)
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun aNoisyCoverIsRetriedAtLowerQualityUntilItFits() {
        val tried = mutableListOf<Int>()
        val discarded = mutableListOf<String>()
        val sizes = mapOf(88 to 2_000_000L, 62 to CLUSTER_ARTWORK_MAX_ENCODED_BYTES + 1, 45 to 700_000L)

        val result = encodeClusterArtworkWithinBudget(
            encode = { quality -> tried += quality; "encoded-$quality" },
            sizeOf = { sizes.getValue(it.substringAfterLast('-').toInt()) },
            discard = { discarded += it },
        )

        assertEquals("encoded-45", result)
        assertEquals(listOf(88, 62, 45), tried)
        // A cover that is too large must not be left behind in the cache directory.
        assertEquals(listOf("encoded-88", "encoded-62"), discarded)
    }

    @Test
    fun aCoverThatFitsNoQualityIsRefusedAndNothingIsLeftBehind() {
        val discarded = mutableListOf<String>()

        val result = encodeClusterArtworkWithinBudget(
            encode = { "encoded-$it" },
            sizeOf = { CLUSTER_ARTWORK_MAX_ENCODED_BYTES + 1 },
            discard = { discarded += it },
        )

        assertNull(result)
        assertEquals(CLUSTER_ARTWORK_QUALITIES.size, discarded.size)
    }

    @Test
    fun anEncodingThatProducedNothingIsSkippedRatherThanTreatedAsACover() {
        val tried = mutableListOf<Int>()

        val result = encodeClusterArtworkWithinBudget<String>(
            encode = { quality -> tried += quality; if (quality == 88) null else "encoded-$quality" },
            sizeOf = { 1L },
            discard = { },
        )

        assertEquals("encoded-62", result)
        assertEquals(listOf(88, 62), tried)
    }

    @Test
    fun theHandoverUriHasTheShapeTheProviderParsesBack() {
        val session = "0f0e0d0c-0b0a-0908-0706-050403020100"
        val fileName = "11111111-2222-3333-4444-555555555555.jpg"

        assertEquals(
            "content://com.shihab.diplay.hudtest.artwork/covers/$session/$fileName",
            clusterArtworkUriText(packageName, session, fileName),
        )
        assertEquals("com.shihab.diplay.hudtest.artwork", clusterArtworkAuthority(packageName))
    }

    @Test
    fun theTwoVendorReadersAreTheOnlyGrantTargets() {
        assertEquals(listOf("com.ecarx.dim_interaction", "ecarx.xsf.mediacenter"), CLUSTER_ARTWORK_READERS)
        // FLAG_GRANT_READ_URI_PERMISSION; the reference passes the literal 1.
        assertEquals(1, CLUSTER_ARTWORK_GRANT_FLAG)
    }

    @Test
    fun theReadableExternalFileWinsOverTheProviderAndTheProviderOnlyCoversItsFailure() {
        // The tier the car demonstrably displays, even when the provider tier is fully ready. This is
        // the rule *without* the HTTP tier, i.e. the handover a car that cannot bind a loopback port
        // gets; the server's own precedence is in `ClusterArtworkTierTest`.
        assertEquals(
            ClusterArtworkTier.FILE,
            clusterArtworkTier(fileUsable = true, providerRegistered = true, granted = 2),
        )
        // No external tree (or a copy that did not read back): the provider carries the cover.
        assertEquals(
            ClusterArtworkTier.CONTENT,
            clusterArtworkTier(fileUsable = false, providerRegistered = true, granted = 1),
        )
        // Either half of the provider tier missing and no file: nothing may be handed over, because a
        // Uri the reader can only fail on is worse than no cover.
        assertNull(clusterArtworkTier(fileUsable = false, providerRegistered = false, granted = 2))
        assertNull(clusterArtworkTier(fileUsable = false, providerRegistered = true, granted = 0))
    }

    @Test
    fun onlyACopyThatReadsBackIsHandedOverAsFile() {
        val readable = File(folder.newFolder("external"), "cover.jpg")
        readable.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01))
        assertTrue(clusterArtworkFileReadable(readable))

        // A write that reported success but left nothing (or nothing readable) is not a cover: this is
        // the check that sends the handover to the provider instead of announcing a dead `file://`.
        val empty = File(readable.parentFile, "empty.jpg")
        empty.createNewFile()
        assertFalse("an empty file", clusterArtworkFileReadable(empty))
        assertFalse("a file that is not there", clusterArtworkFileReadable(File(readable.parentFile, "missing.jpg")))
        assertFalse("a directory", clusterArtworkFileReadable(readable.parentFile))
    }

    @Test
    fun theFlatCoversOfTheOldFallbackAreDeletedAndNothingElseIsTouched() {
        val externalRoot = folder.newFolder("external-files")
        val legacy = File(externalRoot, "${CLUSTER_ARTWORK_LEGACY_FILE_PREFIX}1696800000000.jpg")
        legacy.writeBytes(byteArrayOf(1, 2, 3))
        // The current scheme is a directory, and an unrelated file is not ours to remove.
        val current = File(File(externalRoot, CLUSTER_ARTWORK_DIRECTORY), UUID.randomUUID().toString())
        assertTrue(current.mkdirs())
        val other = File(externalRoot, "unrelated.txt")
        other.writeBytes(byteArrayOf(4))

        cleanLegacyClusterArtworkFiles(externalRoot)

        assertFalse("the pre-2026-10-09 leftovers are gone", legacy.exists())
        assertTrue("the current session directory stays", current.isDirectory)
        assertTrue("only our own names are swept", other.exists())
        // A car with no external files directory is not an error.
        cleanLegacyClusterArtworkFiles(null)
    }

    @Test
    fun theProviderMapsItsOwnUrisBackToTheFileInTheCacheAndRefusesEverythingElse() {
        val cacheDir = folder.newFolder("cache")
        val session = UUID.randomUUID().toString()
        val fileName = newClusterArtworkFileName()
        val directory = File(File(cacheDir, CLUSTER_ARTWORK_DIRECTORY), session)
        assertTrue(directory.mkdirs())
        val cover = File(directory, fileName)
        cover.writeBytes(byteArrayOf(1, 2, 3))

        val mapped = clusterArtworkCoverFile(
            cacheDir = cacheDir,
            packageName = packageName,
            scheme = "content",
            authority = clusterArtworkAuthority(packageName),
            pathSegments = listOf(CLUSTER_ARTWORK_PATH, session, fileName),
            query = null,
            fragment = null,
        )
        assertEquals(cover.canonicalFile, mapped?.canonicalFile)

        // Everything the reference's provider also refuses. A provider that guessed would hand out
        // files from outside the cover directory.
        val cases = mapOf(
            "another scheme" to listOf("file", clusterArtworkAuthority(packageName), listOf(CLUSTER_ARTWORK_PATH, session, fileName), null, null),
            "another authority" to listOf("content", "com.example.artwork", listOf(CLUSTER_ARTWORK_PATH, session, fileName), null, null),
            "a query" to listOf("content", clusterArtworkAuthority(packageName), listOf(CLUSTER_ARTWORK_PATH, session, fileName), "x=1", null),
            "a fragment" to listOf("content", clusterArtworkAuthority(packageName), listOf(CLUSTER_ARTWORK_PATH, session, fileName), null, "frag"),
            "only two segments" to listOf("content", clusterArtworkAuthority(packageName), listOf(session, fileName), null, null),
            "not under covers" to listOf("content", clusterArtworkAuthority(packageName), listOf("other", session, fileName), null, null),
            "a session that is not a uuid" to listOf("content", clusterArtworkAuthority(packageName), listOf(CLUSTER_ARTWORK_PATH, "session", fileName), null, null),
            "a name that is not a uuid" to listOf("content", clusterArtworkAuthority(packageName), listOf(CLUSTER_ARTWORK_PATH, session, "cover.jpg"), null, null),
            "a name that is not a jpeg" to listOf("content", clusterArtworkAuthority(packageName), listOf(CLUSTER_ARTWORK_PATH, session, "$session.png"), null, null),
        )
        for ((label, arguments) in cases) {
            assertNull(
                label,
                clusterArtworkCoverFile(
                    cacheDir = cacheDir,
                    packageName = packageName,
                    scheme = arguments[0] as String?,
                    authority = arguments[1] as String?,
                    pathSegments = arguments[2] as List<String>,
                    query = arguments[3] as String?,
                    fragment = arguments[4] as String?,
                ),
            )
        }
        // A structurally valid Uri for a file that was already evicted is not readable either.
        assertNull(
            "a cover that is no longer there",
            clusterArtworkCoverFile(
                cacheDir = cacheDir,
                packageName = packageName,
                scheme = "content",
                authority = clusterArtworkAuthority(packageName),
                pathSegments = listOf(CLUSTER_ARTWORK_PATH, session, navUuidFileName()),
                query = null,
                fragment = null,
            ),
        )
    }

    @Test
    fun aFreshCoverNameIsAUuidJpeg() {
        val name = newClusterArtworkFileName()
        assertTrue(name, name.endsWith(".jpg"))
        assertEquals(36, name.removeSuffix(".jpg").length)
        // The provider accepts exactly what the store writes.
        assertEquals(UUID.fromString(name.removeSuffix(".jpg")).toString(), name.removeSuffix(".jpg"))
    }

    @Test
    fun onlyTheLastFourCoversStayReadableAndTheEvictedOnesAreHandedBack() {
        val retention = ClusterArtworkRetention(limit = 2)
        assertTrue(retention.add(entry("a")).isEmpty())
        assertTrue(retention.add(entry("b")).isEmpty())
        // The caller revokes and deletes exactly these.
        assertEquals(listOf("a"), retention.add(entry("c")).map { it.file.name })
        assertEquals(listOf("b", "c"), retention.clear().map { it.file.name })
        assertTrue("clearing twice must not hand the same file out again", retention.clear().isEmpty())
    }

    @Test
    fun staleSessionDirectoriesAreDeletedAndTheLiveOneIsSpared() {
        val root = folder.newFolder("cluster-artwork")
        val live = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        val stale = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        File(stale, "cover.jpg").writeBytes(byteArrayOf(1))
        val notASession = File(root, "not-a-session").apply { mkdirs() }

        cleanStaleClusterArtworkSessions(root, live.name)

        assertTrue("the live session keeps its covers", live.isDirectory)
        assertFalse("a dead process' covers are removed", stale.exists())
        assertTrue("only session directories are touched", notASession.isDirectory)
    }

    private fun entry(name: String): ClusterArtworkRetention.Entry =
        ClusterArtworkRetention.Entry(File(folder.root, name), null)

    private fun navUuidFileName(): String = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.jpg"
}
