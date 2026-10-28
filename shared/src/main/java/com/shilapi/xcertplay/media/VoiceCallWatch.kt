package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioStreamId

/**
 * Spots a voice call that the iPhone never announces to the accessory.
 *
 * The cluster's phone card is driven by iAP2 `CallStateUpdate` (0x4155), which the iPhone sends for its
 * own telephone app (docs/_digest/86). A third-party VOIP call -- WeChat's voice call is the one that
 * matters on this car -- is not a telephone call, so nothing in that frame family ever describes it and
 * the card stays empty while the driver is on a call. What the iPhone *does* tell us is where the audio
 * went: it opens a CarPlay audio stream with a microphone and plays the other party on the same stream.
 *
 * That "same stream, both directions" shape is the whole idea:
 *
 *  - A voice **note** (holding the button in WeChat) opens the microphone and plays nothing back, so
 *    there is no downlink on that stream and no card. This is also why [downlinkStarted] exists at all
 *    instead of confirming on the microphone alone: on this car the voice-note path was measured as
 *    `SETUP ... audioType=speechRecognition input=true` with no downlink (docs/_digest/58 §10).
 *  - A **call** is full duplex from the first second: the microphone is open and the other party's
 *    audio arrives on the same stream.
 *  - Music and guidance prompts never ask for a microphone at all.
 *
 * Only `telephony`, `default` and `compatibility` qualify. `speechrecognition` is deliberately out even
 * though Siri and voice input use it: Siri answers back on its own stream, so "microphone plus downlink"
 * would describe every Siri turn as a call.
 *
 * **Which type a VOIP call actually uses is not known yet.** The iPhone picks the CarPlay audio type from
 * the app's audio session, and a call-mode session (`playAndRecord` / `voiceChat`) may well arrive as
 * `telephony` -- the same type a cellular call uses -- or as `default`. Both are accepted here; the
 * diagnostic line names the one that showed up, so the first on-car test settles it. The iAP2 call state
 * wins over this inference whenever both exist (see `CarPlayGlance`), which is what keeps a real
 * telephone call from being relabelled the moment its audio stream lingers for a few hundred milliseconds
 * after the iPhone reports the call over.
 *
 * Threading: the microphone and downlink events arrive on the session's audio threads and the stop events
 * may arrive from the teardown thread, so every method is serialised on this object. [publish] and
 * [report] are called under that lock; both are short (an enqueue and a log line) and neither calls back
 * into this class.
 */
internal class VoiceCallWatch(
    /** Where the milestones go: the session's own log file, so a call can be read back without adb. */
    private val report: (String) -> Unit = {},
    /** `(active, confirmedAtMillis)`; only ever called on a transition. */
    private val publish: (Boolean, Long) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Active(val id: AudioStreamId, val since: Long)

    /** Streams whose microphone is really recording (the uplink was started, not just requested). */
    private val microphones = HashSet<AudioStreamId>()

    /** Streams that have delivered downlink audio, i.e. the iPhone is playing on them. */
    private val downlinks = HashSet<AudioStreamId>()

    private var active: Active? = null

    /** The microphone of [id] is open; keyed per stream because a session can carry several. */
    @Synchronized
    fun microphoneStarted(id: AudioStreamId) {
        if (!microphones.add(id)) return
        if (!isCallType(id.audioType)) {
            // The line that settles the one assumption this class makes. A VOIP call arriving as
            // `speechrecognition` -- the type Siri and voice notes use on this car -- would be ignored by
            // policy, and this is the trace that says so instead of leaving the card's absence unexplained.
            report("Call: microphone opened on type=${id.audioType} stream=${id.type}; not a call by policy")
            return
        }
        confirm(id)
    }

    @Synchronized
    fun microphoneStopped(id: AudioStreamId) {
        if (!microphones.remove(id)) return
        end(id)
    }

    /** [id] delivered its first audio; for a call this is the other party becoming audible. */
    @Synchronized
    fun downlinkStarted(id: AudioStreamId) {
        if (!isCallType(id.audioType)) return
        if (!downlinks.add(id)) return
        confirm(id)
    }
    @Synchronized
    fun downlinkStopped(id: AudioStreamId) {
        if (!downlinks.remove(id)) return
        end(id)
    }

    /**
     * The session ended. Nothing that was on the cluster can be acted on any more, so the card is
     * withdrawn if it was up; a no-op otherwise.
     */
    @Synchronized
    fun reset() {
        microphones.clear()
        downlinks.clear()
        val call = active ?: return
        active = null
        report("Call: voice stream type=${call.id.audioType} ended with the session")
        publish(false, clock())
    }

    /** The audio type as the iPhone named it, normalised: the SETUP plist is not case-stable. */
    private fun confirm(id: AudioStreamId) {
        if (active != null) return
        if (!isCallType(id.audioType)) return
        if (id !in microphones || id !in downlinks) return
        val now = clock()
        active = Active(id, now)
        // The line an on-car readback greps to learn which audio type a VOIP call uses, and that the
        // inference fired at all rather than the iPhone staying silent about the call.
        report("Call: voice call spotted type=${id.audioType} stream=${id.type} bothWays=true")
        publish(true, now)
    }

    private fun end(id: AudioStreamId) {
        val call = active ?: return
        if (call.id != id) return
        active = null
        val now = clock()
        report("Call: voice call ended type=${id.audioType} durationMs=${now - call.since}")
        publish(false, now)
    }

    private companion object {
        /** CarPlay audio types that can carry a two-way call; see the class comment for the by-type rule. */
        fun isCallType(audioType: String): Boolean = when (audioType.lowercase()) {
            "telephony", "default", "compatibility" -> true
            else -> false
        }
    }
}
