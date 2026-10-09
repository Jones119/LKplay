package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the dropped primitive setters (docs/_digest/38).
 *
 * `int.class.isAssignableFrom(Integer.class)` is false, so the old "first assignable candidate" rule
 * matched `updateNextGuidancePointName(String)` and nothing else: the cluster showed a road name with
 * no arrow, distance or ETA, and the skipped call was traced as `-> null`, exactly like a successful
 * `void` call. These tests drive the matcher the bridge uses and then invoke the method it chose, so
 * they fail the moment the ranking stops reaching a primitive parameter.
 */
class VendorMethodMatcherTest {
    /** One setter per type the vendor's DIM surface uses, each recording what it was handed. */
    private class PrimitiveSetters {
        // Named "recorded*" so the generated property accessors cannot clash with the "set*" methods.
        var recordedInt = Int.MIN_VALUE
        var recordedLong = Long.MIN_VALUE
        var recordedText: String? = null
        var recordedFlag = false
        var recordedAny: Any? = null

        fun setInt(value: Int) {
            recordedInt = value
        }

        fun setLong(value: Long) {
            recordedLong = value
        }

        fun setText(value: String) {
            recordedText = value
        }

        fun setFlag(value: Boolean) {
            recordedFlag = value
        }

        fun setObject(value: Any?) {
            recordedAny = value
        }
    }

    /**
     * The vendor's setter shapes, so one test mirrors the calls the bridge actually makes. The arrow
     * takes either the int fallback or an image; the image stands in for the vendor's `Bitmap`
     * overload here to keep this JVM test off the Android graphics classes.
     */
    private class DimLikeTarget {
        val calls = mutableListOf<String>()

        fun updateCurrentSourceType(type: Int) {
            calls += "source=$type"
        }

        fun updateCurrentProgress(position: Long) {
            calls += "progress=$position"
        }

        fun updateNextGuidancePointName(name: String) {
            calls += "road=$name"
        }

        fun updateDistanceToNextGuidancePoint(meters: Int) {
            calls += "distance=$meters"
        }

        fun updateDistanceToDestination(meters: Int) {
            calls += "remaining=$meters"
        }

        fun updateETA(seconds: Int) {
            calls += "eta=$seconds"
        }

        fun updateTurnByTurnArrow(resource: Int) {
            calls += "arrow=$resource"
        }

        fun updateTurnByTurnArrow(image: Any?) {
            calls += "arrow-image"
        }
    }

    /** Overloads of one name, where a wrong pick is visible instead of merely reaching a target. */
    private class Overloaded {
        var picked: String? = null

        fun put(value: Int) {
            picked = "int:$value"
        }

        fun put(value: Long) {
            picked = "long:$value"
        }

        fun put(value: String) {
            picked = "string:$value"
        }

        fun put(value: Any?) {
            picked = "object:$value"
        }
    }

    /** A setter that only accepts a reference type, used to pin down how null still matches. */
    private class NullSink {
        var invoked = false

        fun accept(value: String?) {
            invoked = true
        }
    }

    /** Runs the bridge's own selection and then the invocation it would perform. */
    private fun invoke(target: Any, name: String, vararg args: Any?): VendorMethodMatcher.Match? {
        val overloads = target.javaClass.methods.filter { it.name == name }
        val match = VendorMethodMatcher.select(overloads, args.map { it?.javaClass }) ?: return null
        match.method.invoke(target, *args)
        return match
    }

    @Test
    fun everyPrimitiveTypedSetterIsInvokedWithItsValue() {
        val setters = PrimitiveSetters()
        invoke(setters, "setInt", 6)
        invoke(setters, "setLong", 1_785_000_000L)
        invoke(setters, "setFlag", true)
        invoke(setters, "setText", "建国路")

        assertEquals(6, setters.recordedInt)
        assertEquals(1_785_000_000L, setters.recordedLong)
        assertTrue(setters.recordedFlag)
        assertEquals("建国路", setters.recordedText)
        // The catch-all stays untouched: it is a worse fit than every exact parameter above.
        assertNull(setters.recordedAny)
    }

    @Test
    fun theVendorSetterShapesAllReachTheirTargets() {
        val target = DimLikeTarget()
        invoke(target, "updateCurrentSourceType", 6)
        invoke(target, "updateCurrentProgress", 42_000L)
        invoke(target, "updateNextGuidancePointName", "建国路")
        invoke(target, "updateDistanceToNextGuidancePoint", 320)
        invoke(target, "updateDistanceToDestination", 12_400)
        invoke(target, "updateETA", 1_785_000_000)
        invoke(target, "updateTurnByTurnArrow", 17)

        // Before the ranking change only the road name arrived.
        assertEquals(
            listOf(
                "source=6",
                "progress=42000",
                "road=建国路",
                "distance=320",
                "remaining=12400",
                "eta=1785000000",
                "arrow=17",
            ),
            target.calls,
        )
    }

    @Test
    fun anExactTypedOverloadWinsOverTheObjectOverload() {
        val target = Overloaded()
        invoke(target, "put", 7)
        assertEquals("int:7", target.picked)
        invoke(target, "put", 8L)
        assertEquals("long:8", target.picked)
        invoke(target, "put", "建国路")
        assertEquals("string:建国路", target.picked)
        // Only the Object parameter accepts a type the target does not know, so that path still works.
        val list = ArrayList<String>()
        invoke(target, "put", list)
        assertEquals("object:$list", target.picked)
    }

    @Test
    fun theChosenSignatureIsReportedSoATraceCannotLookLikeAVoidCall() {
        val setters = PrimitiveSetters()
        assertEquals("setInt(int)", invoke(setters, "setInt", 6)?.signature)
        assertEquals("setLong(long)", invoke(setters, "setLong", 6L)?.signature)
        assertEquals("updateTurnByTurnArrow(int)", invoke(DimLikeTarget(), "updateTurnByTurnArrow", 17)?.signature)
    }

    @Test
    fun aPrimitiveParameterOutranksTheObjectCatchAll() {
        // The arrow's int fallback and its image overload are both candidates for an Integer; the
        // primitive pair has to win, or the cluster would be handed the integer as an "image".
        val target = DimLikeTarget()
        invoke(target, "updateTurnByTurnArrow", 17)
        assertEquals(listOf("arrow=17"), target.calls)
    }

    @Test
    fun aNullArgumentStillFitsTheReferenceTypedSetter() {
        val target = NullSink()
        invoke(target, "accept", null)
        assertTrue("null stayed compatible before the ranking change and must stay compatible", target.invoked)
    }

    @Test
    fun aLongArgumentDoesNotMatchAnIntParameter() {
        val setters = PrimitiveSetters()
        val overloads = setters.javaClass.methods.filter { it.name == "setInt" }
        assertNull(
            "a Long is not assignable to int, and reflection would silently narrow it",
            VendorMethodMatcher.select(overloads, listOf<Class<*>?>(java.lang.Long::class.java)),
        )
        // The wrapper's own class does fit, which is the pair the vendor's primitive setters need.
        assertNotNull(
            VendorMethodMatcher.select(overloads, listOf<Class<*>?>(java.lang.Integer::class.java)),
        )
        // And no overload accepts a wide argument either.
        assertNull(
            VendorMethodMatcher.select(overloads, listOf<Class<*>?>(java.lang.Double::class.java)),
        )
    }

    @Test
    fun anOverloadWithTheWrongParameterCountIsNotACandidate() {
        val setters = PrimitiveSetters()
        val overloads = setters.javaClass.methods.filter { it.name == "setInt" }
        assertNull(
            VendorMethodMatcher.select(
                overloads,
                listOf<Class<*>?>(java.lang.Integer::class.java, java.lang.Integer::class.java),
            ),
        )
    }

    @Test
    fun aCallWhoseArgumentsFitNoOverloadIsReportedWithItsCandidates() {
        val setters = PrimitiveSetters()
        val overloads = setters.javaClass.methods.filter { it.name == "setInt" }
        val argumentTypes = listOf<Class<*>?>(java.lang.String::class.java)

        assertNull("no overload takes a String", VendorMethodMatcher.select(overloads, argumentTypes))
        val report = VendorMethodMatcher.missReport("setInt", argumentTypes, overloads)
        assertTrue(report, report.contains("setInt"))
        assertTrue(report, report.contains("java.lang.String"))
        assertTrue(report, report.contains("setInt(int)"))
        // A name the target does not declare at all is reported the same way, with no candidates.
        val missing = setters.javaClass.methods.filter { it.name == "setMissing" }
        assertTrue(missing.isEmpty())
        assertTrue(VendorMethodMatcher.missReport("setMissing", argumentTypes, missing).contains("none"))
    }

    @Test
    fun aMissingOverloadIsReportedOnlyOncePerMethodName() {
        val report = MissingOverloadReport()
        assertTrue(report.shouldReport("updateETA"))
        assertFalse("a per-guidance-frame call must not flood logcat", report.shouldReport("updateETA"))
        assertFalse(report.shouldReport("updateETA"))
        assertTrue("another setter still gets its own line", report.shouldReport("updateDistanceToDestination"))
    }
}
