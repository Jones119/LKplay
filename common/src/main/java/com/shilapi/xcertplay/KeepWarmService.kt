package com.shilapi.xcertplay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Keeps this process resident between connections.
 *
 * A cold `CarPlayHostActivity` start costs about two seconds on the CS11 before the controller is even
 * created - process start, dex loading and the first layout - and the owner noticed the difference
 * immediately: a second launch in the same process is much faster than the first. With no resident
 * component the process dies after every connection, so every plug-in paid that cost again.
 *
 * This service does nothing but exist, which is what keeps the process alive; the framework then reuses the
 * loaded classes and the warmed JIT for the next activity. It is started from [BootReceiver] and stays put
 * across task changes.
 *
 * On API 19 a started service needs no foreground notification to stay alive. If the platform reports that
 * it has no foreground service type to give, `startForeground` is skipped and the service simply runs as a
 * background started service.
 */
class KeepWarmService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "keep-warm service started; process stays resident for the next connection")
        return START_STICKY
    }

    companion object {
        private const val TAG = "xcertplay-warm"

        /** Starts the service; safe to call repeatedly. */
        fun start(context: Context) {
            runCatching { context.startService(Intent(context, KeepWarmService::class.java)) }
                .onFailure { Log.w(TAG, "keep-warm service could not start", it) }
        }
    }
}
