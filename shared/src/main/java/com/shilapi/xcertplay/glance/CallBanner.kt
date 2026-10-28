package com.shilapi.xcertplay.glance

import com.shilapi.xcertplay.hud.ClusterCall

/**
 * What the head unit's own call banner should show for the call the cluster card is showing.
 *
 * Why this exists at all: with a CarPlay box the driver got an answer/hang-up banner on the centre
 * screen, because the head unit's own hands-free link (HFP) carried the call and its native phone UI
 * drew it (2026-10-28, from the driver's description of the box). This project deliberately releases
 * HFP so that calls stay inside CarPlay (docs/_digest/73-75), so that native banner never appears any
 * more -- not for telephone calls and not for WeChat's voice call. This banner is our replacement: drawn
 * by the app, above whatever is on screen, with the same two keys routed to CarPlay's telephony HID.
 *
 * [inferred] marks a call the iPhone never announced, known only from a two-way audio stream
 * ([com.shilapi.xcertplay.media.VoiceCallWatch]): there is no ringing phase for it, so it can only be
 * shown as an established call.
 */
data class CallBanner(
    /** Who is calling: the iPhone's display name, else its number, else a generic voice-call label. */
    val title: String,
    /** True while the call is still ringing, i.e. there is something to answer. */
    val ringing: Boolean,
    /** True when the call is only known from its audio, so nothing about it can be answered. */
    val inferred: Boolean,
)

/**
 * The banner for [call], or null when the centre screen should show nothing.
 *
 * Only calls the iPhone cannot put a number on get a banner (plus the inferred ones, which have no
 * number by construction). A telephone call already has CarPlay's own call screen on the head unit and
 * its cluster card, and a second banner over the top of that would be noise -- whereas a WeChat call has
 * no number at all and nothing else draws it (docs/_digest/121).
 */
internal fun callBannerFor(call: ClusterCall?, inferred: Boolean, fallbackTitle: String): CallBanner? {
    if (call == null) return null
    if (!inferred && call.number.isNotEmpty()) return null
    val title = when {
        call.name.isNotEmpty() -> call.name
        call.number.isNotEmpty() -> call.number
        else -> fallbackTitle
    }
    return CallBanner(
        title = title,
        ringing = call.phase == ClusterCall.Phase.RINGING,
        inferred = inferred,
    )
}
