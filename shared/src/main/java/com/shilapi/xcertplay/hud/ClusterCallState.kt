package com.shilapi.xcertplay.hud

import android.util.Log
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** What the instrument cluster's phone card should show: the phase, and who is calling. */
internal data class ClusterCall(
    val phase: Phase,
    /**
     * The iPhone's display name for the caller, trimmed; empty when it sent none. It is passed through
     * as sent rather than substituted here, because the vendor service applies its own fallback chain
     * (name, then number, then its own "unknown number" string) when it builds the card
     * (docs/_digest/86 §2.1).
     */
    val name: String,
    /** The remote id from the iPhone, i.e. the number as the phone knows it; trimmed, may be empty. */
    val number: String,
    /**
     * When the call became connected, for the card's own call timer; null while it is not connected
     * (ringing, dialling). The vendor ticks that timer itself every 600 ms once it gets a duration
     * (docs/_digest/86 §2.4 S3), so this only has to be right at the moment the card is submitted.
     */
    val activeSinceMillis: Long? = null,
) {
    enum class Phase { RINGING, DIALING, ACTIVE }
}

/**
 * The iPhone's calls, from iAP2 CallStateUpdate (0x4155): remote id (0), display name (1), status (2)
 * and call UUID (4). Status: 0 disconnected, 1 sending, 2 ringing, 3 connecting, 4 active, 5 held,
 * 6 disconnecting.
 *
 * Several calls can be live at once -- call waiting, or a second call taken while the first is held --
 * so each is tracked under its own call UUID and the card follows the one that matters: a ringing call
 * first, then a connected one (active, else held), then an outgoing one. A call whose UUID is missing
 * falls back to its remote id as the key.
 *
 * **These frames were already arriving.** The subscription has been in
 * [com.shilapi.xcertplay.iap2.message.Iap2ControlMessages.subscriptions] all along (0x4154, sent during
 * both the wired and the wireless bring-up); nothing consumed 0x4155 until now, which is why the
 * cluster's phone card stayed empty even though the DIM service has been ready to draw one
 * (docs/_digest/86).
 *
 * The parse is a port of the reference implementation's own call state (`DiPlay`, `hud/BydCarPlayCall.kt`
 * `CarPlayCallState`, which is covered by tests there); only the BYD-specific display half was dropped.
 * The card's own layout, its buttons and their labels belong to the vendor service and are not ours to
 * choose (docs/_digest/86 §2).
 *
 * **A third-party call is not absent from these frames.** On 2026-10-28 the driver reported that a WeChat
 * voice call *does* reach the cluster sometimes -- and that the same car showed it with a CarPlay box
 * before -- so the iPhone does send 0x4155 for it, at least sometimes, and a missing card is our fault
 * rather than the phone's. The one rule that could drop such a call wholesale was the identity rule: a
 * VOIP call has no telephone number, so its frames can carry only a display name and a status. They are
 * accepted now (see [ANONYMOUS]), and every frame leaves one trace line so the next car run can say
 * whether the phone stayed silent or the frame arrived and was applied.
 */
internal class ClusterCallState(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Call(val status: Int, val name: String, val number: String, val activeSince: Long?)

    private val calls = LinkedHashMap<String, Call>()
    private var last: ClusterCall? = null

    /**
     * Feeds one frame in. Returns true when the card changed -- including the change to "no call" --
     * so a caller can forward just the transitions; false for an unrelated frame and for a frame that
     * moved a call the card is not showing (a held call's timer, say).
     */
    fun accept(frame: Iap2Frame): Boolean {
        if (frame.messageId != CALL_STATE_UPDATE) return false
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull()
        if (body == null) {
            // Deliberately without the payload: `Iap2Frame.payload` hands out a copy, and a trace line is
            // not worth copying a frame for.
            Log.i(TAG, "call state frame unreadable")
            return false
        }
        val status = runCatching { body.optionalU8(STATUS) }.getOrNull()
        if (status == null) {
            Log.i(TAG, "call state frame without a status")
            return false
        }
        val remote = runCatching { body.optionalString(REMOTE_ID) }.getOrNull()?.trim().orEmpty()
        val display = runCatching { body.optionalString(DISPLAY_NAME) }.getOrNull()?.trim().orEmpty()
        val uuid = runCatching { body.optionalString(CALL_UUID) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val id = uuid ?: remote
        // One line per frame, always on. Frames are rare (a handful per call) and this is the only place
        // that can answer why a call that the car does show sometimes never reached the cluster: what the
        // iPhone actually put in the frame, and whether it was applied (docs/_digest/121).
        Log.i(
            TAG,
            "call state status=$status nameChars=${display.length} remoteChars=${remote.length}" +
                " uuid=${uuid != null} key=${if (id.isEmpty()) "anonymous" else "named"}" +
                " calls=${calls.size}",
        )
        if (id.isNotEmpty()) {
            // A call the iPhone can name takes the card from an unattributable one: the anonymous frame
            // was either that same call before it was identified, or noise.
            if (calls.remove(ANONYMOUS) != null) Log.i(TAG, "anonymous call dropped: a named call is in play")
        } else if (calls.keys.any { it.isNotEmpty() }) {
            Log.i(TAG, "anonymous call state ignored: a named call owns the card")
            return false
        }
        val key = id.ifEmpty { ANONYMOUS }
        if (status == DISCONNECTED || status == DISCONNECTING) {
            calls.remove(key)
        } else {
            val previous = calls[key]
            val connected = status == ACTIVE || status == HELD
            calls[key] = Call(
                status = status,
                // A later frame may carry only the status (the iPhone sends what changed), so the
                // caller's identity is carried over rather than replaced by an empty string.
                name = display.ifEmpty { previous?.name.orEmpty() },
                number = remote.ifEmpty { previous?.number.orEmpty() },
                activeSince = previous?.activeSince ?: if (connected) clock() else null,
            )
        }
        val next = card()
        if (next == last) return false
        last = next
        return true
    }

    /** The card for the calls known so far, or null when there is nothing to show. */
    fun current(): ClusterCall? = last

    /** The session ended: forget every call, so the next one starts from a clean card. */
    fun clear() {
        calls.clear()
        last = null
    }

    private fun card(): ClusterCall? {
        calls.values.firstOrNull { it.status == RINGING }?.let {
            return ClusterCall(ClusterCall.Phase.RINGING, it.name, it.number)
        }
        (calls.values.firstOrNull { it.status == ACTIVE } ?: calls.values.firstOrNull { it.status == HELD })?.let {
            return ClusterCall(ClusterCall.Phase.ACTIVE, it.name, it.number, it.activeSince)
        }
        calls.values.firstOrNull { it.status == SENDING || it.status == CONNECTING }?.let {
            return ClusterCall(ClusterCall.Phase.DIALING, it.name, it.number)
        }
        return null
    }

    companion object {
        /** iAP2 CallStateUpdate, the frame this class exists for. */
        const val CALL_STATE_UPDATE = 0x4155

        private const val TAG = "DiPlay-Call"

        /**
         * The key a call is tracked under when the iPhone names neither a number nor a call UUID.
         *
         * A VOIP call has no telephone number, so a frame carrying only the caller's display name and a
         * status is exactly what one looks like; dropping those frames is why the cluster could stay empty
         * through a WeChat call while the same car showed an ordinary telephone call. The reference
         * implementation keys them under the empty string, which is what this restores -- bounded by the
         * rule in [accept]: an anonymous frame is applied only while no named call holds the card, and any
         * named frame evicts it, so an unattributable "ringing" can never shadow a real call.
         */
        private const val ANONYMOUS = ""

        private const val REMOTE_ID = 0
        private const val DISPLAY_NAME = 1
        private const val STATUS = 2
        private const val CALL_UUID = 4

        private const val DISCONNECTED = 0
        private const val SENDING = 1
        private const val RINGING = 2
        private const val CONNECTING = 3
        private const val ACTIVE = 4
        private const val HELD = 5
        private const val DISCONNECTING = 6
    }
}
