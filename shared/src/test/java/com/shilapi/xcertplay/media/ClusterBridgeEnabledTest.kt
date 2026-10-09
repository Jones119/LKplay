package com.shilapi.xcertplay.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The precedence between the persisted cluster-push setting and its adb override (docs/_digest/38).
 *
 * The property used to be the only switch, so the cluster came back empty after every ignition until
 * someone ran `setprop` again. The persisted setting is now the default and the property survives as a
 * bench override, which has to be able to force the push off as well as on: a comparison run cannot
 * tell "this is what the cluster does" from "this is what adb left switched on" otherwise.
 */
class ClusterBridgeEnabledTest {
    @Test
    fun theSettingDecidesWhileThePropertyIsUnset() {
        // The state after a reboot: setprop does not persist, so nothing is set and the setting rules.
        assertTrue(resolveClusterBridgeEnabled(settingEnabled = true, propertyValue = null))
        assertFalse(resolveClusterBridgeEnabled(settingEnabled = false, propertyValue = null))
    }

    @Test
    fun thePropertyOverridesTheSettingInBothDirections() {
        assertTrue(resolveClusterBridgeEnabled(settingEnabled = false, propertyValue = "1"))
        assertFalse(resolveClusterBridgeEnabled(settingEnabled = true, propertyValue = "0"))
    }

    @Test
    fun anUnrecognisedPropertyValueLeavesTheSettingInCharge() {
        for (value in listOf("", "00", "2", "on", "true", "yes")) {
            assertTrue(value, resolveClusterBridgeEnabled(settingEnabled = true, propertyValue = value))
            assertFalse(value, resolveClusterBridgeEnabled(settingEnabled = false, propertyValue = value))
        }
    }

    @Test
    fun nothingRunsBeforeTheAppHasHandedTheBridgeAContext() {
        // The other half of "disabled means nothing runs": a process that never attached cannot push,
        // whatever the setting says.
        assertFalse(VendorClusterBridge.enabled)
    }
}
