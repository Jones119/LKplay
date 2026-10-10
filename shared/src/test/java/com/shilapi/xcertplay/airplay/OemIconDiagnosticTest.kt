package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one-line OEM-icon summary the /info path logs (the sidebar "car" icon that never appeared on
 * the CS11, even after deleting and redoing the pairing).
 *
 * The line exists to answer, from the car's own log, whether the fields were in the dictionary at all
 * and what they contained, so the format is the contract: the visible flag, the label, and per icon
 * its pixel size and byte count. An empty list additionally names the reason, because that is the
 * only variant whose cause is on our side rather than iOS's.
 */
class OemIconDiagnosticTest {
    @Test
    fun aDeclaredIconIsReportedWithItsPixelSizeAndByteCount() {
        val line = oemIconDiagnostic(
            visible = true,
            label = "DiPlay",
            icons = listOf(AirPlayIcon(192, 192, ByteArray(4821))),
        )

        assertEquals("airplay oemIcon visible=true label=\"DiPlay\" icons=1 icon[0]=192x192 bytes=4821", line)
    }

    @Test
    fun everyIconIsReportedWhenMoreThanOneIsDeclared() {
        val line = oemIconDiagnostic(
            visible = true,
            label = "DiPlay",
            icons = listOf(AirPlayIcon(192, 192, ByteArray(10)), AirPlayIcon(64, 64, ByteArray(3))),
        )

        assertEquals(
            "airplay oemIcon visible=true label=\"DiPlay\" icons=2 icon[0]=192x192 bytes=10 icon[1]=64x64 bytes=3",
            line,
        )
    }

    @Test
    fun anEmptyIconListNamesWhyNothingWasDeclared() {
        // A custom icon that is not square, and a packaged default that does not decode either.
        assertEquals(
            "airplay oemIcon visible=false label=\"DiPlay\" icons=0 reason=custom icon is not a square decodable image",
            oemIconDiagnostic(
                visible = false,
                label = "DiPlay",
                icons = emptyList(),
                absence = OemIconAbsence.CUSTOM_ICON_REJECTED_AS_NOT_SQUARE,
            ),
        )
        assertEquals(
            "airplay oemIcon visible=false label=\"DiPlay\" icons=0 " +
                "reason=no custom icon file and the packaged default did not decode",
            oemIconDiagnostic(
                visible = false,
                label = "DiPlay",
                icons = emptyList(),
                absence = OemIconAbsence.NO_CUSTOM_FILE_AND_PACKAGED_DEFAULT_UNDECODABLE,
            ),
        )
    }

    @Test
    fun anEmptyIconListWithoutAKnownReasonSaysSoInsteadOfGuessing() {
        assertEquals(
            "airplay oemIcon visible=false label=\"DiPlay\" icons=0 reason=not reported",
            oemIconDiagnostic(visible = false, label = "DiPlay", icons = emptyList()),
        )
    }

    @Test
    fun theConfigOverloadReportsExactlyWhatTheVisibleFlagOfTheInfoDictionaryUses() {
        val base = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "950.7.1",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            oemLabel = "DiPlay",
        )

        assertEquals(
            "airplay oemIcon visible=false label=\"DiPlay\" icons=0 reason=not reported",
            oemIconDiagnostic(base),
        )
        assertEquals(
            "airplay oemIcon visible=true label=\"DiPlay\" icons=1 icon[0]=192x192 bytes=8",
            oemIconDiagnostic(base.copy(icons = listOf(AirPlayIcon(192, 192, ByteArray(8))))),
        )
        assertEquals(
            "airplay oemIcon visible=false label=\"DiPlay\" icons=0 " +
                "reason=no custom icon file and the packaged default did not decode",
            oemIconDiagnostic(
                base.copy(oemIconAbsence = OemIconAbsence.NO_CUSTOM_FILE_AND_PACKAGED_DEFAULT_UNDECODABLE),
            ),
        )
    }

    @Test
    fun theInfoDictionaryOmitsTheOemKeysExactlyWhenTheLineSaysItDoes() {
        // The two sides of one decision: an icon means the keys are there, an empty list means they
        // are absent (AirPlayInfoPlist only adds `oemIconVisible` when it has icons).
        val withIcon = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "950.7.1",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            icons = listOf(AirPlayIcon(192, 192, ByteArray(8))),
        )

        assertEquals(true, AirPlayInfoPlist.build(withIcon)["oemIconVisible"])
        assertEquals(false, AirPlayInfoPlist.build(withIcon.copy(icons = emptyList())).containsKey("oemIconVisible"))
        assertEquals("airplay oemIcon visible=false", oemIconDiagnostic(withIcon.copy(icons = emptyList())).substringBefore(" label="))
    }
}
