package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.compat.systemService
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/**
 * CS11: 版本隔离持有者——`android.media.session.MediaSession` / `MediaMetadata` / `PlaybackState`
 * 与 `AudioFocusRequest` 分别是 API 21 / 26 才有的类型。按本项目约定（`docs/_digest/08 §4.3`、
 * `docs/_digest/17 §2` 第 3 条），这些类型的引用必须只出现在"只在够高版本上才被加载的类"里：
 * Dalvik 会惰性但急切地校验**被执行的方法体**（`new-instance` 的目标类型也算），方法体里出现 4.4
 * 上不存在的类，首次执行即 `NoClassDefFoundError`。
 *
 * 因此 [Api21MediaSession] 只由 [MediaSessionBridge.of] 在 API 21+ 上加载，[Api26AudioFocus] 只在
 * 26+ 上被它触碰；而 [MediaSessionBridge]、[MediaSessionController]、[MediaSessionBridgeFactory]
 * 与 [MediaKeysPlatform] 自身**不含**任何 ≥21 类型的字段或方法体，可以安全地在 4.4 上被校验。
 * 4.4 的媒体键路径仍是 `MEDIA_BUTTON` 广播（见 [CarPlayMediaKeys.startLegacyKeys]），与本文件无关。
 */
internal interface MediaSessionBridge {
    /** Whether the car currently routes its media keys to this session. */
    val isActive: Boolean

    /** Re-requests audio focus when the iPhone starts playing again. Returns whether focus was granted. */
    fun activate(): Boolean

    fun setMetadata(metadata: Any)

    fun setPlaybackState(state: Any)

    fun release()

    companion object {
        /**
         * Null below API 21: the caller then keeps the legacy `MEDIA_BUTTON` path and never touches a
         * media-session type. The API-level decision lives in [MediaKeysPlatform.ownsMediaSession] so
         * it can be asserted on the JVM without loading [Api21MediaSession].
         */
        fun of(context: Context, controller: MediaSessionController): MediaSessionBridge? =
            if (MediaKeysPlatform.ownsMediaSession(Build.VERSION.SDK_INT)) {
                Api21MediaSession(context, controller)
            } else {
                null
            }
    }
}

/**
 * CS11: what [Api21MediaSession] needs from its owner, expressed without any media-session type in a
 * signature, so the owner (`CarPlayMediaKeys`) never resolves one. The `Any` metadata/state payloads
 * are the objects built by [MediaSessionBridgeFactory].
 */
internal interface MediaSessionController {
    /** Focus-request state changes the owner keeps for the legacy path; not called on 4.4. */
    fun audioFocusHeld(held: Boolean)

    /** "play"/"pause"/"next"/"previous" from a media controller rather than a hardware key. */
    fun mediaKey(index: Int, source: String)

    /** A media button event this app does not claim; false hands it back to the platform. */
    fun unclaimedMediaKey(event: KeyEvent): Boolean

    fun audioFocusChanged(change: Int)
}

/** CS11: the API-level decisions behind the guards, split out so they are unit-testable on the JVM. */
internal object MediaKeysPlatform {
    const val MEDIA_SESSION_API = 21
    const val AUDIO_FOCUS_REQUEST_API = 26

    /** API 21 introduces `MediaSession`, `MediaMetadata` and `PlaybackState`. */
    fun ownsMediaSession(level: Int): Boolean = level >= MEDIA_SESSION_API

    /** API 26 introduces `AudioFocusRequest`; 21–25 keep the legacy `requestAudioFocus` overload. */
    fun ownsAudioFocusRequest(level: Int): Boolean = level >= AUDIO_FOCUS_REQUEST_API
}

/**
 * The `android.media.*` objects that only the API 21+ holder may build. Split out from
 * [Api21MediaSession] so the decisions they turn into objects stay testable on the JVM: the actual
 * "is it playing / where are we" choice is made in [CarPlayMediaKeys.playbackStateRequest], and this
 * object only formats it.
 */
internal object MediaSessionBridgeFactory {
    /**
     * The action flags the car's media controller uses. `PlaybackState.ACTION_*` are API 21 constants
     * (inlined into 4.4 bytecode, so harmless there, but they belong to the holder all the same).
     */
    fun actions(): Long = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS

    /** `MediaMetadata` is API 21; only reachable through the bridge's `setMetadata`. */
    fun metadataOf(info: CarPlayNowPlaying, artwork: Bitmap?): Any {
        val metadata = MediaMetadata.Builder()
        info.title?.let {
            metadata.putString(MediaMetadata.METADATA_KEY_TITLE, it)
            metadata.putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it)
        }
        info.artist?.let {
            metadata.putString(MediaMetadata.METADATA_KEY_ARTIST, it)
            metadata.putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
        }
        info.album?.let { metadata.putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
        info.durationMillis?.let { metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
        info.sourceApp?.let { metadata.putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, it) }
        artwork?.let {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
            metadata.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
        }
        return metadata.build()
    }

    /** `PlaybackState` is API 21; [CarPlayMediaKeys.playbackStateRequest] already made the decisions. */
    fun playbackStateOf(playing: Boolean, position: Long, updatedAt: Long): Any =
        PlaybackState.Builder()
            .setActions(actions())
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                position,
                if (playing) 1f else 0f,
                // The iPhone sends elapsed time only on play, pause or seek, so Android must
                // extrapolate from when it arrived, not from this republish.
                updatedAt,
            )
            .build()
}

/**
 * CS11: `AudioFocusRequest` is API 26. Hidden in its own object because a method body containing the
 * builder type would fail verification on 21–25 even when the call is guarded by `SDK_INT`.
 */
private object Api26AudioFocus {
    /** The caller stores the returned request for the matching `abandonAudioFocusRequest`. */
    fun build(manager: AudioManager, onLoss: AudioManager.OnAudioFocusChangeListener, main: Handler): AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(onLoss, main)
            .build()
}

/**
 * The real media session. Only [MediaSessionBridge.of] loads this class, and only on API 21+.
 */
private class Api21MediaSession(
    context: Context,
    private val controller: MediaSessionController,
) : MediaSessionBridge {
    private val mainHandler = Handler(Looper.getMainLooper())
    // CS11: Context#getSystemService(Class) is API 23, so 21–22 must go through Cs11Compat like the
    // rest of the repository; a direct call would silently yield no AudioManager there.
    private val audioManager = context.systemService(AudioManager::class.java)
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "audio focus change=$change")
        controller.audioFocusChanged(change)
    }
    private val session = MediaSession(context, "DiPlay CarPlay")
    private var focusRequest: AudioFocusRequest? = null

    // Declared before `init` so the initializer can install it.
    private val callback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: android.content.Intent): Boolean {
            @Suppress("DEPRECATION")
            val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(android.content.Intent.EXTRA_KEY_EVENT)
                ?: return false
            return controller.unclaimedMediaKey(event)
        }

        override fun onPlay() = controller.mediaKey(CarPlayMediaButton.PLAY, "play")
        override fun onPause() = controller.mediaKey(CarPlayMediaButton.PAUSE, "pause")
        override fun onSkipToNext() = controller.mediaKey(CarPlayMediaButton.NEXT, "next")
        override fun onSkipToPrevious() = controller.mediaKey(CarPlayMediaButton.PREVIOUS, "previous")
    }

    init {
        session.setCallback(callback, mainHandler)
        session.isActive = true
        controller.audioFocusHeld(gainFocus())
    }

    override val isActive: Boolean get() = session.isActive

    @Suppress("DEPRECATION")
    override fun activate(): Boolean {
        val granted = if (MediaKeysPlatform.ownsAudioFocusRequest(Build.VERSION.SDK_INT)) {
            requestFocusObject()
        } else {
            // 21–25: the legacy listener overload. The compat helper throws rather than returning
            // null when the service is missing, as Cs11Compat.systemService documents.
            audioManager.requestAudioFocus(
                focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        controller.audioFocusHeld(granted)
        Log.i(TAG, "audio focus regained=$granted")
        return granted
    }

    override fun setMetadata(metadata: Any) {
        session.setMetadata(metadata as MediaMetadata)
    }

    override fun setPlaybackState(state: Any) {
        session.setPlaybackState(state as PlaybackState)
    }

    @Suppress("DEPRECATION")
    override fun release() {
        session.isActive = false
        session.release()
        // 26+: the request object releases its own hold. 21–25: requestAudioFocus(listener, …) is
        // abandoned through the legacy listener overload. 4.4 never gets here (session is null).
        if (MediaKeysPlatform.ownsAudioFocusRequest(Build.VERSION.SDK_INT)) {
            focusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            audioManager.abandonAudioFocus(focusListener)
        }
        focusRequest = null
        controller.audioFocusHeld(false)
    }

    /** 26+ path; separated from [activate] so the 26-only builder never enters an executed body. */
    private fun requestFocusObject(): Boolean {
        val request = Api26AudioFocus.build(audioManager, focusListener, mainHandler)
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /**
     * Same as [activate] but without the log line, for the one-off hand-off in [init]. Kept separate
     * so `init` stays free of the API 26 builder type on 21–25.
     */
    @Suppress("DEPRECATION")
    private fun gainFocus(): Boolean {
        if (MediaKeysPlatform.ownsAudioFocusRequest(Build.VERSION.SDK_INT)) return requestFocusObject()
        return audioManager.requestAudioFocus(
            focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN,
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private companion object {
        const val TAG = "DiPlay-MediaKeys"
    }
}
