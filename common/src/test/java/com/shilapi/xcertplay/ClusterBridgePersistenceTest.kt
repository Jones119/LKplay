package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The persisted cluster-push setting (docs/_digest/38).
 *
 * The push used to be gated on `debug.diplay.cluster` alone, which no ignition survives. The setting
 * replaces it, so its default and its independence from the cluster-map experiment both matter: the
 * map stream is a different feature on the same head unit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterBridgePersistenceTest {
    @Test
    fun thePushIsOnByDefaultSoAFreshInstallFillsTheClusterWithoutAdb() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(AirPlayPersistence.loadClusterBridgeEnabled(context))
    }

    @Test
    fun theOwnersChoiceIsWhatASubsequentLookupSees() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveClusterBridgeEnabled(context, false)
        // The second read stands in for the next process after a reboot: only SharedPreferences can
        // bring the value back, and that is the whole point of the setting.
        assertFalse(AirPlayPersistence.loadClusterBridgeEnabled(context))
        assertFalse(AirPlayPersistence.loadClusterBridgeEnabled(RuntimeEnvironment.getApplication()))
        AirPlayPersistence.saveClusterBridgeEnabled(context, true)
        assertTrue(AirPlayPersistence.loadClusterBridgeEnabled(RuntimeEnvironment.getApplication()))
    }

    @Test
    fun thePushAndTheClusterMapStreamAreSeparateSettings() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveClusterMapEnabled(context, true)
        AirPlayPersistence.saveClusterBridgeEnabled(context, false)
        assertTrue("the map stream is its own experiment", AirPlayPersistence.loadClusterMapEnabled(context))
        assertFalse(
            "switching the push off must not disturb the map stream",
            AirPlayPersistence.loadClusterBridgeEnabled(context),
        )
    }
}
