package com.shilapi.xcertplay.hud

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
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return false
        val status = runCatching { body.optionalU8(STATUS) }.getOrNull() ?: return false
        val remote = runCatching { body.optionalString(REMOTE_ID) }.getOrNull()?.trim().orEmpty()
        val display = runCatching { body.optionalString(DISPLAY_NAME) }.getOrNull()?.trim().orEmpty()
        val id = runCatching { body.optionalString(CALL_UUID) }.getOrNull()?.takeIf { it.isNotBlank() } ?: remote
        // A frame that names no call at all cannot be applied to one. The reference implementation keys
        // it under the empty string, which lets an unattributable "ringing" take the card over -- and a
        // card is exactly what this class exists to hand to the dashboard. Ignoring it is the safe half
        // of that behaviour, and it costs nothing: several calls can be live at once, so the frames that
        // matter have to name their call anyway.
        if (id.isEmpty()) return false
        if (status == DISCONNECTED || status == DISCONNECTING) {
            calls.remove(id)
        } else {
            val previous = calls[id]
            val connected = status == ACTIVE || status == HELD
            calls[id] = Call(
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
