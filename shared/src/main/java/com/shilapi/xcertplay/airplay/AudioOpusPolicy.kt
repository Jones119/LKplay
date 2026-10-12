package com.shilapi.xcertplay.airplay

import android.util.Log

/** How much Opus this accessory advertises, for matching another receiver's `/info` on the car. */
enum class AudioOpusMode {
    /** Shipped behaviour: Opus stays in every output entry; the microphone offers it when encodable. */
    AUTO,

    /** PCM-only microphone formats; outputs keep Opus. */
    INPUT_OFF,

    /** No Opus anywhere, matching a receiver whose probe finds no encoder at all. */
    OFF,
}

/**
 * `debug.diplay.opus` - the CarPlay `/info` advertisement decides which recording route iOS picks,
 * and the reference "legacy" EasyPlay build installed on this car advertises **no Opus at all**
 * (`Audio capabilities enabled=true formats=9 rate=48000 opus=false microphone=true`, easyplay v74)
 * while this fork advertises it, because the bundled software encoder makes
 * [com.shilapi.xcertplay.media.OpusEncoderSupport] succeed. Under that build a WeChat voice note
 * records sound over wired CarPlay; under this one it is silent. This knob exists to test whether
 * the Opus advertisement is what separates them (docs/_digest/28, 2026-10-12).
 *
 * **Measured outcome (2026-10-12, CS11, wireless): do not use [AudioOpusMode.OFF] as a
 * configuration.** With `off`, iOS requested **no audio stream at all** on the wireless session -
 * video (`type=110`) and the iAP2 channel (`type=130`) only - and the phone played music through its
 * own speaker. Clearing the property made iOS set up `type=102`/AAC-LC immediately and audio worked
 * again (`underruns=0`, `skippedMs=0`). That is the failure the shipped code already documents at
 * `AirPlayInfoPlist.audioFormats` (docs 27/39). [AudioOpusMode.OFF] stays as the switch that
 * reproduces and falsifies that claim, not as something a head unit should run with.
 *
 * The mode also failed to explain the one thing it was built for: a WeChat voice note stayed silent
 * over wired CarPlay with `off` set, exactly as with the shipped formats (docs/_digest/28).
 *
 * ```
 * adb shell setprop debug.diplay.opus input-off   # PCM-only microphone
 * adb shell setprop debug.diplay.opus off         # no Opus anywhere; breaks wireless audio
 * adb shell setprop debug.diplay.opus ''          # back to shipped behaviour
 * ```
 *
 * Read once per session, next to [AudioFormatPolicy.current].
 */
object AudioOpusPolicy {
    private const val TAG = "xcertplay-usb"
    private const val PROPERTY = "debug.diplay.opus"

    /** The pure part: one property value to one mode. JVM-testable without a device. */
    fun parse(raw: String?): AudioOpusMode = when (raw?.trim()?.lowercase()) {
        "input-off", "input_off", "no-input", "pcm-input" -> AudioOpusMode.INPUT_OFF
        "off", "no-opus", "pcm", "pcm-only" -> AudioOpusMode.OFF
        else -> AudioOpusMode.AUTO
    }

    fun current(): AudioOpusMode = parse(systemProperty()).also { mode ->
        if (mode != AudioOpusMode.AUTO) {
            Log.i(TAG, "audio opus override by $PROPERTY: $mode")
        }
    }

    /**
     * Output entries keep Opus in every mode but [AudioOpusMode.OFF]: iOS built no audio stream at
     * all when a head unit without an encoder also advertised none (docs 27/39), so clearing it is
     * the experiment, not a capability report.
     */
    fun opusOutput(mode: AudioOpusMode): Boolean = mode != AudioOpusMode.OFF

    /** The microphone offers Opus only when an encoder exists and the mode still allows it. */
    fun microphoneOpus(mode: AudioOpusMode, encoderAvailable: Boolean): Boolean =
        encoderAvailable && mode == AudioOpusMode.AUTO

    /** `debug.diplay.opus`; the ROM has no public API for it on API 19. */
    private fun systemProperty(): String? = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        get.invoke(null, PROPERTY) as? String
    }.getOrNull()
}
