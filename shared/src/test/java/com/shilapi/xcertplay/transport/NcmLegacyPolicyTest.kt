package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `debug.diplay.ncm_legacy`: the one wired data path this build has, and the diagnostic mode that reports
 * what it would be missing (docs/_digest/58 §3, docs/_digest/63).
 */
class NcmLegacyPolicyTest {
    @Test
    fun autoIsTheDefault() {
        for (value in listOf(null, "", "  ", "auto", "true")) {
            assertEquals(NcmLegacyMode.AUTO, NcmLegacyPolicy.parse(value))
        }
    }

    @Test
    fun theForceSpellingsAreAccepted() {
        for (value in listOf("force", "FORCE", " userspace ", "legacy")) {
            assertEquals(NcmLegacyMode.FORCE, NcmLegacyPolicy.parse(value))
        }
    }

    @Test
    fun theRetiredKernelSpellingsParseToAuto() {
        // The kernel netdev is deleted (docs/_digest/63), so its spellings must not select anything: an old
        // script or a property left over from a previous build keeps working, it just means "userspace".
        for (value in listOf("kernel", "off", "root", "netdev")) {
            assertEquals(NcmLegacyMode.AUTO, NcmLegacyPolicy.parse(value))
        }
    }

    @Test
    fun noModeNamesAKernelTransport() {
        // A regression that reintroduces the kernel path has to add the mode back deliberately; until then no
        // value may name it, and nothing may claim to allow it (docs/_digest/63 §5).
        assertTrue(
            "modes left: ${NcmLegacyMode.values().joinToString { it.name }}",
            NcmLegacyMode.values().none { it.name.contains("KERNEL") },
        )
    }

    @Test
    fun anUnsetOrBlankPropertyMeansUserspacePreferred() {
        // There is no user setting to consult: the product has one transport, and the property is a
        // debug override only (docs/_digest/63 §1).
        for (value in listOf(null, "", "   ", "auto", "true")) {
            assertEquals(NcmLegacyMode.AUTO, NcmLegacyPolicy.parse(value))
        }
    }
}
