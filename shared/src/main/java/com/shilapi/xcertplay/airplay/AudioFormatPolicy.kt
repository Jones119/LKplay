package com.shilapi.xcertplay.airplay

import android.util.Log

/** Which wire format the media (music) audio may use. */
enum class AudioFormatOverride {
    /** Advertise everything, as shipped: iOS picks per link type. */
    AUTO,

    /** Music may only be AAC, so a wired session carries ~250 kbps instead of ~1.5 Mbps. */
    NO_PCM,

    /** Music may only be PCM, so a wireless session carries the wired load. */
    NO_AAC,
}

/**
 * The link type decides the media audio wire format on its own, and the two are not equivalent.
 *
 * Measured on the car (2026-10-10, docs/_digest/51): a **wired** session runs type 100 **LPCM** - 134.9
 * packets/s, 1565 kbps, 1449 B each - while a **wireless** session runs type 102 **AAC-LC** - 47.8
 * packets/s, 252 kbps, 658 B each, on the same video path (TCP 110) in both. So the wired link carries
 * 2.8x the audio packets and 6x the audio bitrate through the same queue as the video bursts, which is
 * why the wired session stutters where the wireless one does not.
 *
 * This knob swaps the two so one ignition window can prove or reject that attribution:
 *
 * ```
 * adb shell setprop debug.diplay.audio_formats no-pcm   # music must be AAC (test it wired)
 * adb shell setprop debug.diplay.audio_formats no-aac   # music must be PCM (test it wireless)
 * adb shell setprop debug.diplay.audio_formats ''       # back to shipped behaviour
 * ```
 *
 * Only the media entries move: microphone input formats and the telephony/speech-recognition entries
 * are untouched, because removing formats there made iOS build no audio stream at all (docs 27/39).
 *
 * **Measured outcome (2026-10-10, wired session, `/info` served with the override): `NO_PCM` made iOS
 * build no audio stream whatsoever** - it answered `/info` (10 audioFormats advertised, including
 * 102/media and 103/media AAC-LC) and then sent a SETUP for type 110 only, with no audio SETUP at all.
 * So on a USB/wired session iOS insists on PCM in the type-100 media entry and will not fall back to
 * AAC, which means the wired link's ~1.5 Mbps / 135 pps audio load is **not negotiable from this side**.
 * `NO_PCM` stays as the recorded negative result: do not retry it without new evidence.
 */
object AudioFormatPolicy {
    private const val TAG = "xcertplay-usb"
    private const val PROPERTY = "debug.diplay.audio_formats"

    /** The pure part: one property value to one policy. JVM-testable without a device. */
    fun parse(raw: String?): AudioFormatOverride = when (raw?.trim()?.lowercase()) {
        "no-pcm", "no_pcm", "aac", "aac-only" -> AudioFormatOverride.NO_PCM
        "no-aac", "no_aac", "pcm", "pcm-only" -> AudioFormatOverride.NO_AAC
        else -> AudioFormatOverride.AUTO
    }

    /** The policy for the next `/info`, read once per session setup. */
    fun current(): AudioFormatOverride = parse(systemProperty()).also { mode ->
        if (mode != AudioFormatOverride.AUTO) {
            Log.i(TAG, "audio format override by $PROPERTY: $mode")
        }
    }

    /** `debug.diplay.audio_formats`; the ROM has no public API for it on API 19. */
    private fun systemProperty(): String? = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        get.invoke(null, PROPERTY) as? String
    }.getOrNull()
}
