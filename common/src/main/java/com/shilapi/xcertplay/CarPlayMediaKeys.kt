package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.graphics.drawable.toBitmap
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import com.shilapi.xcertplay.compat.systemService
import com.shilapi.xcertplay.compat.getDrawableCompat

/**
 * Steering-wheel and other hardware media buttons for CarPlay.
 *
 * Android delivers media keys to a media session; the head unit picks the session of the audio-focus
 * owner. Once CarPlay plays music, DiPlay holds audio focus and an active session until the
 * CarPlay session ends, so play also works after a pause. Keys go to the iPhone as CarPlay media
 * HID presses ([CarPlayMediaButton]).
 */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"
    private const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS

    private val mainHandler = Handler(Looper.getMainLooper())
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "audio focus change=$change")
        // Only a permanent loss moves the car's media keys elsewhere; transient losses come back.
        if (change == AudioManager.AUDIOFOCUS_LOSS) synchronized(this) { focusHeld = false }
    }
    private val artworkQueue = NowPlayingArtworkQueue(
        worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-now-playing-artwork").apply { isDaemon = true }
        },
        main = Executor { mainHandler.post(it) },
        decode = ::decodeArtwork,
        publish = ::onArtworkDecoded,
        discard = Bitmap::recycle,
    )
    private var artworkOwner: Any? = null
    private var controller: CarPlayController? = null
    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    private var appContext: Context? = null
    private var mediaAudioActive = false
    private var nowPlaying = CarPlayNowPlaying()
    private var elapsedUpdatedAt = 0L
    private var artwork: Bitmap? = null
    private val artworkCache = LinkedHashMap<Int, Bitmap?>()
    private var placeholder: Bitmap? = null

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        if (controller !== next) {
            releaseLocked()
            artworkOwner = artworkQueue.newSession()
        }
        appContext = context.applicationContext
        controller = next
        next.playbackListener = { playing -> onIphonePlaying(next, playing) }
        next.nowPlayingListener = { update -> onNowPlayingChanged(next, update) }
        next.artworkListener = { id, bytes -> onArtworkChanged(next, id, bytes) }
    }

    /** Ends key handling for [expected]; a newer controller's state is left alone. */
    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        expected.playbackListener = null
        expected.nowPlayingListener = null
        expected.artworkListener = null
        controller = null
        releaseLocked()
    }

    /** Called when CarPlay music starts or stops; may run on any thread. */
    fun onMediaAudioChanged(active: Boolean) {
        mainHandler.post { synchronized(this) { updateLocked(active) } }
    }

    /** The iPhone started or stopped playing; may run on any thread. */
    private fun onIphonePlaying(expected: CarPlayController, playing: Boolean) {
        if (playing) mainHandler.post {
            synchronized(this) {
                if (controller === expected) regainFocusLocked()
            }
        }
    }

    /** Publishes the iPhone's retained metadata through Android's system media session. */
    private fun onNowPlayingChanged(expected: CarPlayController, update: CarPlayNowPlaying) {
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected) return@synchronized
                val previousArtwork = artwork
                if (nowPlaying.artworkTransferId != update.artworkTransferId) {
                    artwork = nextArtwork(update.artworkTransferId, artworkCache, artwork)
                }
                if (nowPlaying.elapsedMillis != update.elapsedMillis) elapsedUpdatedAt = SystemClock.elapsedRealtime()
                val metadataChanged = metadataChanged(nowPlaying, update) || artwork !== previousArtwork
                nowPlaying = update
                // The iPhone repeats NowPlayingUpdate about twice a second for the position alone.
                // Republishing the metadata each time sent a copy of the artwork through system_server
                // to every media listener, and on one head unit that exhausted memory within
                // minutes. The position goes in the playback state.
                if (metadataChanged) session?.setMetadata(androidMetadata(update, shownArtworkLocked()))
                publishPlaybackStateLocked()
            }
        }
    }

    @Synchronized
    private fun onArtworkChanged(expected: CarPlayController, id: Int, bytes: ByteArray) {
        if (controller !== expected) return
        artworkOwner?.let { artworkQueue.submit(it, id, bytes) }
    }

    @Synchronized
    private fun onArtworkDecoded(expected: Any, id: Int, decoded: Bitmap?) {
        if (artworkOwner !== expected) {
            decoded?.recycle()
            return
        }
        // The cluster only ever showed the first track's cover, so record which transfer each decode
        // belongs to and whether it is the track's current one.
        Log.i(
            TAG,
            "artwork decoded id=$id bytes=${decoded?.byteCount} current=${nowPlaying.artworkTransferId}",
        )
        artworkCache.remove(id)
        artworkCache[id] = decoded
        while (artworkCache.size > MAX_CACHED_ARTWORK) artworkCache.remove(artworkCache.keys.first())
        if (nowPlaying.artworkTransferId == id) {
            artwork = decoded
            session?.setMetadata(androidMetadata(nowPlaying, shownArtworkLocked()))
            // The instrument cluster wants the same cover, handed over as a Uri it can read.
            runCatching { com.shilapi.xcertplay.media.VendorClusterBridge.setArtwork(shownArtworkLocked()) }
        }
    }

    // Another car app (its own Spotify, the radio) took audio focus and with it the steering-wheel
    // keys. When CarPlay starts playing again it becomes the car's media source again, as any player
    // would; only the start counts, so a car source picked while the iPhone plays on is not undone.
    private fun regainFocusLocked() {
        if (Build.VERSION.SDK_INT < 21) return
        if (focusHeld) return
        val audio = appContext?.systemService(AudioManager::class.java) ?: return
        // CS11: 焦点请求对象式 API 是 26+；21–25 走遗留重载，<21 直接不启用
        focusHeld = if (Build.VERSION.SDK_INT >= 26) {
            val request = focusRequest ?: return
            audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            audio.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        Log.i(TAG, "audio focus regained=$focusHeld")
    }

    private fun updateLocked(active: Boolean) {
        val context = appContext ?: return
        if (controller == null) return
        mediaAudioActive = active
        if (active && session == null) start(context) else if (active) regainFocusLocked()
        publishPlaybackStateLocked()
    }

    private fun start(context: Context) {
        // CS11: MediaSession 是 API21+；4.4（CS11）改走遗留 MEDIA_BUTTON 广播路径——
        // 车机以 ACTION_MEDIA_BUTTON 广播方向盘媒体键，报名的接收器收到（与 HYLink 同法，见 _digest/08）
        if (Build.VERSION.SDK_INT < 21) {
            startLegacyKeys(context)
            return
        }
        val audio = context.systemService(AudioManager::class.java)
        val granted: Boolean
        if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
                .build()
            granted = audio?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            focusRequest = request
        } else {
            granted = audio?.requestAudioFocus(
                focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        focusHeld = granted
        val cb = callback ?: CarPlayMediaCallback(
            send = ::send,
        ).also { callback = it }
        session = MediaSession(context, "DiPlay CarPlay").apply {
            setCallback(cb, mainHandler)
            setMetadata(androidMetadata(nowPlaying, shownArtworkLocked()))
            isActive = true
        }
        Log.i(TAG, "media keys active focusGranted=$granted")
    }

    /** 4.4: the wheel's media keys arrive as android.intent.action.MEDIA_BUTTON broadcasts (the same
     *  route HYLink's SwcMediaButtonReceiver uses on this platform). The last AudioManager
     *  registration wins, so hold it while CarPlay owns the media audio. */
    private var legacyKeysRegistered = false

    @Suppress("DEPRECATION")
    private fun startLegacyKeys(context: Context) {
        val audio = context.systemService(AudioManager::class.java) ?: return
        focusHeld = audio.requestAudioFocus(
            focusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN,
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        audio.registerMediaButtonEventReceiver(ComponentName(context, MediaButtonReceiver::class.java))
        legacyKeysRegistered = true
        Log.i(TAG, "media keys active (legacy MEDIA_BUTTON) focusGranted=$focusHeld")
    }

    @Suppress("DEPRECATION")
    private fun stopLegacyKeys(context: Context) {
        if (!legacyKeysRegistered) return
        legacyKeysRegistered = false
        context.systemService(AudioManager::class.java)?.unregisterMediaButtonEventReceiver(
            ComponentName(context, MediaButtonReceiver::class.java),
        )
    }

    /** A legacy MEDIA_BUTTON KeyEvent from [MediaButtonReceiver]; same mapping as the media session. */
    fun onLegacyMediaKeyEvent(event: KeyEvent): Boolean {
        // CS11 / 4.4: with the car's own voice assistant switched off, the steering wheel's voice key
        // reaches us as a MEDIA_BUTTON broadcast. Open Siri with it instead of dropping it as an
        // unmapped key, matching what the host activity does when the key arrives there instead.
        if (CarPlayMediaButton.opensSiri(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_UP) {
                val sent = controller?.requestSiri() == true
                Log.i(TAG, "legacy voice key opens Siri sent=$sent")
            }
            return true
        }
        val index = CarPlayMediaButton.forKeyCode(event.keyCode) ?: run {
            Log.i(TAG, "legacy media key unmapped code=${event.keyCode}")
            return false
        }
        // The same press can also arrive through the host activity or the wheel-key service; only the
        // first delivery of that exact press acts (docs/_digest/37, EasyPlay's LegacyMediaKeyGate).
        if (!MediaKeyEchoGate.accept(event.keyCode, event.downTime, event.eventTime)) {
            Log.i(TAG, "legacy media key repeat ignored code=${event.keyCode}")
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            send(index, KeyEvent.keyCodeToString(event.keyCode))
        }
        return true
    }

    private fun releaseLocked() {
        artworkOwner = null
        artworkQueue.clear()
        MediaKeyEchoGate.reset()
        runCatching { com.shilapi.xcertplay.media.VendorClusterBridge.setArtwork(null) }
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        mediaAudioActive = false
        nowPlaying = CarPlayNowPlaying()
        artwork = null
        artworkCache.clear()
        // CS11: abandonAudioFocusRequest 是 26+；21 以下走遗留 abandon；4.4 同时撤下遗留按键注册
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { request -> appContext?.systemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
        } else {
            appContext?.systemService(AudioManager::class.java)?.abandonAudioFocus(focusChangeListener)
        }
        appContext?.let(::stopLegacyKeys)
        focusRequest = null
        focusHeld = false
    }

    private fun publishPlaybackStateLocked() {
        val playing = if (nowPlaying.elapsedMillis != null || nowPlaying.title != null) {
            nowPlaying.playing
        } else {
            mediaAudioActive
        }
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    nowPlaying.elapsedMillis ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f,
                    // The iPhone sends elapsed time only on play, pause or seek, so Android must
                    // extrapolate from when it arrived, not from this republish.
                    elapsedUpdatedAt,
                )
                .build(),
        )
    }

    private fun send(index: Int, source: String) {
        // While the car's video player is on screen the wheel drives it: a CarPlay play/pause would
        // make the iPhone end the video session.
        if (CarPlayVideo.onMediaKey(index)) {
            Log.i(TAG, "media key $source -> car video player $index")
            return
        }
        val sent = synchronized(this) { controller }?.sendMediaButton(index) ?: false
        Log.i(TAG, "media key $source -> CarPlay $index sent=$sent")
    }

    // CS11: MediaSession.Callback 是 API21+；回调改为在 start() 里懒创建（<21 永不触碰该类）
    private var callback: CarPlayMediaCallback? = null

    /** Whether [next] changes what the media session's metadata shows; position and play state do not. */
    internal fun metadataChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        previous.copy(elapsedMillis = null, playing = false) != next.copy(elapsedMillis = null, playing = false)

    internal fun androidMetadata(info: CarPlayNowPlaying, artwork: Bitmap? = null): MediaMetadata =
        MediaMetadata.Builder().apply {
            info.title?.let {
                putString(MediaMetadata.METADATA_KEY_TITLE, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it)
            }
            info.artist?.let {
                putString(MediaMetadata.METADATA_KEY_ARTIST, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
            }
            info.album?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
            info.durationMillis?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
            info.sourceApp?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, it) }
            artwork?.let {
                putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
            }
        }.build()

    // Without art the car draws DiPlay's bright launcher icon instead.
    private fun shownArtworkLocked(): Bitmap? =
        artwork ?: placeholder ?: appContext?.let(::placeholderArt)?.also { placeholder = it }

    internal fun placeholderArt(context: Context): Bitmap? = context
        .getDrawableCompat(R.drawable.art_now_playing_placeholder)
        ?.toBitmap(MAX_ARTWORK_DIMENSION, MAX_ARTWORK_DIMENSION)

    /**
     * The art to show once the iPhone names transfer [id]. A pending transfer keeps [current], so the
     * placeholder does not flash between tracks.
     */
    internal fun nextArtwork(id: Int?, cache: Map<Int, Bitmap?>, current: Bitmap?): Bitmap? = when {
        id == null -> null
        cache.containsKey(id) -> cache[id]
        else -> current
    }

    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_ARTWORK_SOURCE_DIMENSION ||
            bounds.outHeight !in 1..MAX_ARTWORK_SOURCE_DIMENSION
        ) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ARTWORK_DIMENSION * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val largest = maxOf(decoded.width, decoded.height)
        if (largest <= MAX_ARTWORK_DIMENSION) return decoded
        val scale = MAX_ARTWORK_DIMENSION.toFloat() / largest
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { scaled -> if (scaled !== decoded) decoded.recycle() }
    }

    private const val MAX_ARTWORK_DIMENSION = 384
    private const val MAX_ARTWORK_SOURCE_DIMENSION = 8_192
    private const val MAX_CACHED_ARTWORK = 4
}

/**
 * Media-session input → CarPlay presses. Hardware keys arrive as button events and keep the toggle;
 * media controllers (not hardware keys) call [onPlay] and [onPause] with an explicit intent.
 */
internal class CarPlayMediaCallback(
    private val send: (index: Int, source: String) -> Unit,
) : MediaSession.Callback() {
    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        val index = CarPlayMediaButton.forKeyCode(event.keyCode)
            ?: return super.onMediaButtonEvent(mediaButtonIntent)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            send(index, KeyEvent.keyCodeToString(event.keyCode))
        }
        return true
    }

    override fun onPlay() = send(CarPlayMediaButton.PLAY, "play")
    override fun onPause() = send(CarPlayMediaButton.PAUSE, "pause")
    override fun onSkipToNext() = send(CarPlayMediaButton.NEXT, "next")
    override fun onSkipToPrevious() = send(CarPlayMediaButton.PREVIOUS, "previous")
}
