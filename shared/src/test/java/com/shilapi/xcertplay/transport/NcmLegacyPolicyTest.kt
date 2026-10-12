package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `debug.diplay.ncm_legacy`: which wired data path may be used while the userspace NCM bridge is
 * being ported (docs/_digest/58 §3).
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
    fun theKernelSpellingsAreAccepted() {
        for (value in listOf("kernel", "off", "root", "netdev")) {
            assertEquals(NcmLegacyMode.KERNEL, NcmLegacyPolicy.parse(value))
        }
    }

    @Test
    fun onlyKernelForbidsTheUserspaceBridge() {
        assertTrue(NcmLegacyPolicy.allowsUserspace(NcmLegacyMode.AUTO))
        assertTrue(NcmLegacyPolicy.allowsUserspace(NcmLegacyMode.FORCE))
        assertFalse(NcmLegacyPolicy.allowsUserspace(NcmLegacyMode.KERNEL))
    }

    @Test
    fun onlyForceForbidsTheKernelFallback() {
        assertTrue(NcmLegacyPolicy.allowsKernelFallback(NcmLegacyMode.AUTO))
        assertFalse(NcmLegacyPolicy.allowsKernelFallback(NcmLegacyMode.FORCE))
        assertTrue(NcmLegacyPolicy.allowsKernelFallback(NcmLegacyMode.KERNEL))
    }
}
