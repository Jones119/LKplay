package com.shilapi.xcertplay

import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarHotspotSetupTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("diplay_car_hotspot", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun freshInstallAndExistingHotspotConfigurationRemainOptedOut() {
        assertFalse(CarHotspotSettings.enabled(context))
        configureHotspot()
        assertFalse(eligible())
    }

    @Test fun adbAccessDecidesWhetherTheSettingIsVisible() {
        for (access in LocalAdb.Access.entries) {
            assertEquals(access == LocalAdb.Access.READY || access == LocalAdb.Access.NOT_APPROVED,
                CarHotspotSettings.visible(access))
        }
        assertTrue(CarHotspotSettings.visible(LocalAdb.Access.NOT_APPROVED))
        assertTrue(CarHotspotSettings.visible(LocalAdb.Access.READY))
        assertFalse(CarHotspotSettings.visible(LocalAdb.Access.UNREACHABLE))
        assertFalse(CarHotspotSettings.visible(LocalAdb.Access.UNSUPPORTED))
    }

    @Test fun losingAdbHidesTheSettingWithoutClearingOrDisablingTheSavedChoice() {
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        assertFalse(CarHotspotSettings.visible(LocalAdb.Access.UNREACHABLE))
        assertTrue(CarHotspotSettings.enabled(context))
        assertTrue(eligible())
    }

    @Test fun startupNeedsValidCredentialsAndNoExistingSession() {
        CarHotspotSettings.setEnabled(context, true)
        assertFalse(eligible())
        configureHotspot()
        assertTrue(eligible())
        assertFalse(CarHotspotSetup.shouldStartOnLaunch(context, hasSession = true))
        AirPlayPersistence.saveManualHotspotPassphrase(context, "short")
        assertFalse(eligible())
    }

    @Test fun wiredModeNeverPreparesAHotspotEvenWhenTheChoiceIsSaved() {
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        AirPlayPersistence.saveWirelessEnabled(context, false)
        assertFalse(eligible())
        assertFalse(CarHotspotSettings.shouldEnable(context, false, WirelessHotspotMode.MANUAL))
        assertTrue(CarHotspotSettings.enabled(context))
    }

    @Test fun wifiDirectIsNotAutomaticallyManaged() {
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        assertFalse(eligible())
    }

    @Test fun disablingTheChoiceCancelsFutureStartupWithoutChangingConnectionSettings() {
        configureHotspot()
        CarHotspotSettings.setEnabled(context, true)
        assertTrue(eligible())
        CarHotspotSettings.setEnabled(context, false)
        assertFalse(eligible())
        assertEquals("Car hotspot", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("12345678", AirPlayPersistence.loadManualHotspotPassphrase(context))
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(context))
    }

    // CS11/4.4 exposes the legacy soft-AP API, which needs no ADB, so the setting is reachable there.
    @Test fun legacySoftApFirmwareSeesTheSettingWithoutAdb() {
        assertTrue(CarHotspotSettings.visible(access = LocalAdb.Access.UNREACHABLE, legacySupported = true))
        for (access in LocalAdb.Access.entries) {
            assertTrue(CarHotspotSettings.visible(access, legacySupported = true))
        }
        assertTrue(CarHotspotSettings.visible(LocalAdb.Access.READY, legacySupported = false))
        assertFalse(CarHotspotSettings.visible(LocalAdb.Access.UNREACHABLE, legacySupported = false))
    }

    // The legacy firmware stores the hotspot credentials, so the launch path may start it without them.
    @Test fun credentiallessLegacyStartupStillNeedsTheOptInAndWirelessMode() {
        CarHotspotSettings.setEnabled(context, true)
        configureHotspotWithoutCredentials()
        assertFalse(eligible())
        assertTrue(CarHotspotSetup.shouldStartOnLaunch(context, hasSession = false, allowCredentiallessStart = true))
        assertFalse(CarHotspotSetup.shouldStartOnLaunch(context, hasSession = true, allowCredentiallessStart = true))
        AirPlayPersistence.saveWirelessEnabled(context, false)
        assertFalse(CarHotspotSetup.shouldStartOnLaunch(context, hasSession = false, allowCredentiallessStart = true))
        AirPlayPersistence.saveWirelessEnabled(context, true)
        CarHotspotSettings.setEnabled(context, false)
        assertFalse(CarHotspotSetup.shouldStartOnLaunch(context, hasSession = false, allowCredentiallessStart = true))
    }

    private fun eligible() = CarHotspotSetup.shouldStartOnLaunch(context, hasSession = false)

    private fun configureHotspot() {
        AirPlayPersistence.saveWirelessEnabled(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(context, "Car hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "12345678")
    }

    private fun configureHotspotWithoutCredentials() {
        AirPlayPersistence.saveWirelessEnabled(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
    }
}
