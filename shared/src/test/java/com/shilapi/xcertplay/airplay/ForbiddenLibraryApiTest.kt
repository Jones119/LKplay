package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CS11: the guard for the audit's key blind spot — **lint does not report `java.*` library APIs.**
 *
 * All three reports of the API-19 audit (`_artifacts/api19-lint-audit/lint-{shared,common,mobile}.xml`)
 * contain zero `java.*` findings even though `Math.floorDiv(long, long)` (API 24, Java 8) was called
 * from `NtpClock.kt`. With core library desugaring enabled, lint only checks the `android.*` platform
 * APIs; the `java.*` surface is assumed to be handled by desugaring, which is only true for the subset
 * `desugar_jdk_libs` lists. `java.lang.Math` is not in that list, so the call site resolved to a Dalvik
 * `NoSuchMethodError` on 4.4 — an `Error`, so `catch (Exception)` could not contain it.
 *
 * This test therefore scans the **module sources** (not the tests: they never run on the car) for
 * library APIs that are neither platform APIs nor desugar-covered, and fails loudly listing every
 * offender. When one is needed on purpose, either add it to [DESUGARED] with evidence, or keep the use
 * inside a version-isolated holder (`AudioApiBridge`, `MediaSessionBridge`, `Api21Configuration`,
 * `Api21CodecList`, `Api33Locale`) — do not widen the scan.
 *
 * The scan is intentionally textual: it is cheap, needs no extra toolchain, and runs with every
 * unit-test invocation. It cannot see a call made through reflection, which in this repository is only
 * used for platform hidden APIs and is guarded separately (`_digest/08 §4.5`).
 */
class ForbiddenLibraryApiTest {
    private data class Forbidden(val label: String, val pattern: Regex, val why: String)

    private companion object {
        /** Java 8/9 library APIs that desugaring does **not** rewrite; these break the car. */
        val FORBIDDEN = listOf(
            Forbidden(
                "Math.floorDiv",
                Regex("""\bMath\s*\.\s*floorDiv\s*\("""),
                "API 24; java.lang.Math is not in desugar_jdk_libs, so 4.4 throws NoSuchMethodError",
            ),
            Forbidden(
                "Math.floorMod",
                Regex("""\bMath\s*\.\s*floorMod\s*\("""),
                "API 24, not desugared; use floorModLong in NtpClock.kt",
            ),
            Forbidden(
                "Objects.requireNonNullElse",
                Regex("""\bObjects\s*\.\s*requireNonNullElse(Get)?\s*\("""),
                "API 30, not desugared",
            ),
            Forbidden(
                "String.join",
                Regex("""\bString\s*\.\s*join\s*\("""),
                "API 26, not desugared on this toolchain; Kotlin joinToString works on 4.4",
            ),
            Forbidden(
                "Map.of / Map.ofEntries / Map.entry",
                Regex("""\bMap\s*\.\s*(of|ofEntries|entry|copyOf)\s*\("""),
                "Java 9 collection factory; not in the desugar list",
            ),
            Forbidden(
                "List.of / List.copyOf",
                Regex("""\bList\s*\.\s*(of|copyOf)\s*\("""),
                "Java 9/10 collection factory; not in the desugar list",
            ),
            Forbidden(
                "Set.of / Set.copyOf",
                Regex("""\bSet\s*\.\s*(of|copyOf)\s*\("""),
                "Java 9/10 collection factory; not in the desugar list",
            ),
            Forbidden(
                "java.nio.file",
                Regex("""\bjava\s*\.\s*nio\s*\.\s*file\b"""),
                "needs desugar_jdk_libs_nio, which this repository does not depend on",
            ),
            Forbidden(
                "CompletableFuture",
                Regex("""(\bjava\s*\.\s*util\s*\.\s*concurrent\s*\.\s*)?\bCompletableFuture\b"""),
                "API 24, not desugared; CarPlayVideo.kt switched to a CountDownLatch for this reason",
            ),
            Forbidden(
                "java.util.Optional",
                Regex("""(\bjava\s*\.\s*util\s*\.\s*)?\bOptional\b"""),
                "not usable on the car's runtime; desugaring coverage alone is not enough here",
            ),
        )

        /**
         * Desugar-covered, so production use is deliberate and fine. Kept explicit so the next reader
         * can tell "covered on purpose" from "the scan never looked there"; the second test below
         * fails if one of them silently disappears from the sources.
         */
        val DESUGARED = mapOf(
            "java.util.Base64" to Regex("""\bjava\s*\.\s*util\s*\.\s*Base64\b"""),
            "java.time" to Regex("""\bjava\s*\.\s*time\s*\.\s*[A-Z]\w*"""),
        )

        /**
         * Uses that are allowed only because they sit in a version-isolated holder. Recorded here so a
         * reader looking for "who may use a Java 8+ API" finds one list.
         */
        val VERSION_ISOLATED = listOf(
            "AudioApiBridge.kt" to "AudioAttributes/AudioTrack.Builder/AudioRecord.Builder, 8 call sites guarded",
            "MediaSessionBridge.kt" to "MediaSession/MediaMetadata/PlaybackState/AudioFocusRequest",
            "Api21Configuration.kt" to "UsbConfiguration and its members",
            "Api21CodecList" to "MediaCodecList(REGULAR_CODECS).codecInfos",
            "Api33Locale.kt" to "android.app.LocaleManager",
        )

        /**
         * Module source roots, relative to the repository root.
         */
        val MODULES = listOf("shared", "common", "mobile")

        /**
         * **Second blind spot, found while fixing F3.** A newer-platform class named only as a
         * `Foo::class.java` class literal and handed to a helper never goes through lint's NewApi
         * check, so it is invisible in the reports: the audit's `lint-common.xml` has **zero** findings
         * for `AppLocale.kt` even though it passed `LocaleManager::class.java` (API 33) to
         * `Cs11Compat.systemService` from a method that runs on every Activity creation. A direct
         * `locale.script` call in that same file *was* reported (`Locale#getScript`, API 21) — only the
         * laundered class literal stayed hidden.
         *
         * The rule is therefore structural: the class literal may appear in exactly one
         * version-isolated holder file and nowhere else.
         */
        val LAUNDERED_CLASS_LITERALS = listOf(
            Triple(
                "android.app.LocaleManager",
                Regex("""\bLocaleManager\s*::\s*class\b"""),
                "common/src/main/java/com/shilapi/xcertplay/Api33Locale.kt",
            ),
        )
    }

    @Test
    fun moduleSourcesDoNotUseLibraryApisThatBreakAndroid4() {
        val root = repositoryRoot()
        val offenders = scanProductionSources(root) { text, path ->
            FORBIDDEN.flatMap { rule ->
                rule.pattern.findAll(text).map { match ->
                    "${rule.label} at $path:${lineOf(text, match.range.first)} — ${rule.why}"
                }.toList()
            }
        }

        assertTrue(
            "Library APIs that core library desugaring does not cover were found in the module " +
                "sources. They fail on Android 4.4 with NoSuchMethodError/NoClassDefFoundError, and " +
                "lint will NOT report them (see this class's KDoc). Replace them, or move the use " +
                "behind a version-isolated holder:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * Guards the guard: the desugar-covered entries are in real use today, so if the scanner stops
     * finding them the scanner (not the code) has broken and the first test would silently pass.
     */
    @Test
    fun theScannerStillFindsInUseDesugarCoveredApis() {
        val root = repositoryRoot()
        val seen = mutableSetOf<String>()
        for (module in MODULES) {
            val sources = File(root, "$module/src/main/java")
            if (!sources.isDirectory) continue
            sources.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { file ->
                val text = stripCommentsAndStrings(file.readText())
                for ((label, pattern) in DESUGARED) if (pattern.containsMatchIn(text)) seen += label
            }
        }
        assertTrue("the source scan found no java.util.Base64 at all; the scanner is broken", "java.util.Base64" in seen)
        assertTrue("the source scan found no java.time at all; the scanner is broken", "java.time" in seen)
    }

    /**
     * Newer-platform types that a helper launders past lint's NewApi check may only be named as a class
     * literal inside their version-isolated holder. See [LAUNDERED_CLASS_LITERALS] for why.
     */
    @Test
    fun newerPlatformClassLiteralsStayInsideTheirVersionIsolatedHolder() {
        val root = repositoryRoot()
        val offenders = mutableListOf<String>()
        for ((label, pattern, holder) in LAUNDERED_CLASS_LITERALS) {
            for (module in MODULES) {
                val sources = File(root, "$module/src/main/java")
                if (!sources.isDirectory) continue
                sources.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { file ->
                    val relative = file.relativeTo(root).path.replace('\\', '/')
                    if (relative == holder) return@forEach
                    val text = stripCommentsAndStrings(file.readText())
                    pattern.findAll(text).forEach { match ->
                        offenders += "$label at $relative:${lineOf(text, match.range.first)} — only $holder may name it"
                    }
                }
            }
        }
        assertTrue(
            "A type that lint cannot see (a `Foo::class.java` literal passed to a helper rather than a " +
                "direct call) escaped its version-isolated holder. Keep it inside the holder so Dalvik " +
                "never has to resolve it on 4.4:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun everyForbiddenRuleMatchesItsOwnExample() {
        val examples = mapOf(
            "Math.floorDiv" to "Math.floorDiv(a, b)",
            "Math.floorMod" to "Math.floorMod(a, b)",
            "Objects.requireNonNullElse" to "Objects.requireNonNullElse(a, b)",
            "String.join" to "String.join(\",\", list)",
            "Map.of / Map.ofEntries / Map.entry" to "Map.of(1, 2)",
            "List.of / List.copyOf" to "List.of(1)",
            "Set.of / Set.copyOf" to "Set.of(1)",
            "java.nio.file" to "import java.nio.file.Files",
            "CompletableFuture" to "java.util.concurrent.CompletableFuture",
            "java.util.Optional" to "Optional.of(1)",
        )
        for (rule in FORBIDDEN) {
            val example = examples.getValue(rule.label)
            assertTrue("${rule.label} does not match its example '$example'", rule.pattern.containsMatchIn(example))
        }
    }

    private fun scanProductionSources(root: File, collect: (String, String) -> List<String>): List<String> {
        val offenders = mutableListOf<String>()
        for (module in MODULES) {
            val sources = File(root, "$module/src/main/java")
            if (!sources.isDirectory) continue
            sources.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                .forEach { file ->
                    val text = stripCommentsAndStrings(file.readText())
                    offenders += collect(text, file.relativeTo(root).path)
                }
        }
        return offenders
    }

    /**
     * Removes `//` and block comments and string literals, so a rule cannot fire on documentation or
     * on a log message that merely names the API (this repository's KDoc quotes `Math.floorDiv`).
     */
    private fun stripCommentsAndStrings(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        while (index < source.length) {
            val c = source[index]
            when {
                c == '/' && index + 1 < source.length && source[index + 1] == '/' -> {
                    while (index < source.length && source[index] != '\n') index++
                }
                c == '/' && index + 1 < source.length && source[index + 1] == '*' -> {
                    index += 2
                    while (index + 1 < source.length && !(source[index] == '*' && source[index + 1] == '/')) index++
                    index = minOf(index + 2, source.length)
                }
                c == '"' -> {
                    index++
                    while (index < source.length && source[index] != '"') {
                        if (source[index] == '\\') index++
                        index++
                    }
                    index++
                }
                else -> {
                    out.append(c)
                    index++
                }
            }
        }
        return out.toString()
    }

    private fun lineOf(text: String, offset: Int): Int = text.take(offset).count { it == '\n' } + 1

    private fun repositoryRoot(): File {
        // The Gradle test JVM starts in the module directory (`shared`), so walk up to the repo root.
        val moduleDir = File(System.getProperty("user.dir")).absoluteFile
        return sequenceOf(moduleDir, moduleDir.parentFile).filterNotNull()
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("could not locate the repository root from ${moduleDir.absolutePath}")
    }
}
