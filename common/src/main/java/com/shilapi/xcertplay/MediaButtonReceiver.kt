package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * CS11 / Android 4.4: the car system delivers the steering wheel's media keys as
 * android.intent.action.MEDIA_BUTTON broadcasts (Android 5+ routes them through MediaSession
 * instead). The receiver is named to AudioManager while CarPlay owns the media audio — the same
 * route HYLink's SwcMediaButtonReceiver uses on this platform (docs/_digest/08).
 */
class MediaButtonReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent ?: return
        CarPlayMediaKeys.onLegacyMediaKeyEvent(event)
    }
}
