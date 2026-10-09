package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
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
 *
 * CS11 约定（`docs/_digest/08 §4.3`、`docs/_digest/17 §2` 第 3 条）：本 object 的方法体在 4.4 上会被
 * Dalvik 校验，所以字段与**所有**方法体里都不能出现 API 21+ 的类型——`MediaSession`/`MediaMetadata`/
 * `PlaybackState`/`AudioFocusRequest` 全部搬进 [Api21MediaSession]（由 [MediaSessionBridge.of] 按版本
 * 加载），本文件只通过 [MediaSessionBridge] 接口与它交互。4.4 仍走 `MEDIA_BUTTON` 广播的遗留路径
 * （[startLegacyKeys]，与 HYLink 的 `SwcMediaButtonReceiver` 同法）。
 */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"
    private const val UNKNOWN_POSITION = -1L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 4.4/21–25 的遗留焦点监听；26+ 的请求对象式 API 由 [Api21MediaSession] 自己持有。 */
    private val legacyFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
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

    /**
     * CS11: declared as the interface so this object never references a media-session class; stays
     * null below API 21, where the legacy `MEDIA_BUTTON` registration is used instead.
     */
    private var session: MediaSessionBridge? = null
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
                if (metadataChanged) session?.setMetadata(
                    MediaSessionBridgeFactory.metadataOf(update, shownArtworkLocked()),
                )
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
            session?.setMetadata(MediaSessionBridgeFactory.metadataOf(nowPlaying, shownArtworkLocked()))
            // The instrument cluster wants the same cover, handed over as a Uri it can read.
            runCatching { com.shilapi.xcertplay.media.VendorClusterBridge.setArtwork(shownArtworkLocked()) }
        }
    }

    // Another car app (its own Spotify, the radio) took audio focus and with it the steering-wheel
    // keys. When CarPlay starts playing again it becomes the car's media source again, as any player
    // would; only the start counts, so a car source picked while the iPhone plays on is not undone.
    private fun regainFocusLocked() {
        val active = session ?: return
        if (!active.isActive) return
        active.activate()
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
        if (Build.VERSION.SDK_INT < MediaKeysPlatform.MEDIA_SESSION_API) {
            startLegacyKeys(context)
            return
        }
        session = MediaSessionBridge.of(context, sessionController)
        session?.setMetadata(MediaSessionBridgeFactory.metadataOf(nowPlaying, shownArtworkLocked()))
        Log.i(TAG, "media keys active focusGranted=$focusHeld")
    }

    /** 4.4: the wheel's media keys arrive as android.intent.action.MEDIA_BUTTON broadcasts (the same
     *  route HYLink's SwcMediaButtonReceiver uses on this platform). The last AudioManager
     *  registration wins, so hold it while CarPlay owns the media audio. */
    private var legacyKeysRegistered = false

    @Suppress("DEPRECATION")
    private fun startLegacyKeys(context: Context) {
        val audio = context.systemService(AudioManager::class.java) ?: return
        focusHeld = audio.requestAudioFocus(
            legacyFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN,
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
    fun onLegacyMediaKeyEvent(event: KeyEvent): Boolean = dispatchMediaKey(event, "legacy")

    /**
     * The single key mapping behind both routes: the 4.4 `MEDIA_BUTTON` broadcast and the 21+ media
     * session callback. Free of media-session types, so the JVM unit tests can cover it without a
     * device; [controller] lookups are simply null in those tests.
     */
    internal fun dispatchMediaKey(event: KeyEvent, source: String): Boolean {
        // CS11 / 4.4: with the car's own voice assistant switched off, the steering wheel's voice key
        // reaches us as a MEDIA_BUTTON broadcast. Open Siri with it instead of dropping it as an
        // unmapped key, matching what the host activity does when the key arrives there instead.
        if (CarPlayMediaButton.opensSiri(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_UP) {
                val sent = controller?.requestSiri() == true
                Log.i(TAG, "$source voice key opens Siri sent=$sent")
            }
            return true
        }
        val index = CarPlayMediaButton.forKeyCode(event.keyCode) ?: run {
            Log.i(TAG, "$source media key unmapped code=${event.keyCode}")
            return false
        }
        // The same press can also arrive through the host activity or the wheel-key service; only the
        // first delivery of that exact press acts (docs/_digest/37, EasyPlay's LegacyMediaKeyGate).
        if (!MediaKeyEchoGate.accept(event.keyCode, event.downTime, event.eventTime)) {
            Log.i(TAG, "$source media key repeat ignored code=${event.keyCode}")
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            send(index, KeyEvent.keyCodeToString(event.keyCode))
        }
        return true
    }

    /**
     * What [Api21MediaSession] calls back into. Every member is API-19-safe by construction, which is
     * what keeps the bridge from having to know anything about this object's internals.
     */
    private val sessionController = object : MediaSessionController {
        override fun mediaKey(index: Int, source: String) = send(index, source)

        override fun unclaimedMediaKey(event: KeyEvent): Boolean = dispatchMediaKey(event, "session")

        override fun audioFocusHeld(held: Boolean) {
            focusHeld = held
        }

        override fun audioFocusChanged(change: Int) {
            // Only a permanent loss moves the car's media keys elsewhere; transient losses come back.
            if (change == AudioManager.AUDIOFOCUS_LOSS) synchronized(this@CarPlayMediaKeys) { focusHeld = false }
        }
    }

    private fun releaseLocked() {
        artworkOwner = null
        artworkQueue.clear()
        MediaKeyEchoGate.reset()
        runCatching { com.shilapi.xcertplay.media.VendorClusterBridge.setArtwork(null) }
        // CS11: MediaSession.release() 与 abandonAudioFocusRequest 都是 21+/26+，且在桥内一并处理；
        // 4.4 上 session 恒为 null，只走下面的遗留按键注销。21–25 由桥发遗留 abandon。
        session?.release()
        session = null
        mediaAudioActive = false
        nowPlaying = CarPlayNowPlaying()
        artwork = null
        artworkCache.clear()
        appContext?.let(::stopLegacyKeys)
        focusHeld = false
    }

    private fun publishPlaybackStateLocked() {
        val request = playbackStateRequest(nowPlaying, mediaAudioActive, elapsedUpdatedAt)
        session?.setPlaybackState(
            MediaSessionBridgeFactory.playbackStateOf(request.playing, request.position, request.updatedAt),
        )
    }

    /** The play state, position and update time the bridge turns into a `PlaybackState`. */
    internal data class PlaybackStateRequest(val playing: Boolean, val position: Long, val updatedAt: Long)

    /**
     * The two independent "is it playing" signals: explicit iPhone now-playing metadata, else the
     * audio-on-the-wire flag. Testable without an Android device, which is the only part of the
     * playback-state publish that a JVM test can still reach after the bridge split.
     */
    internal fun playbackStateRequest(
        info: CarPlayNowPlaying,
        mediaAudioActive: Boolean,
        updatedAt: Long,
    ): PlaybackStateRequest {
        val hasMetadata = info.elapsedMillis != null || info.title != null
        return PlaybackStateRequest(
            playing = if (hasMetadata) info.playing else mediaAudioActive,
            position = info.elapsedMillis ?: UNKNOWN_POSITION,
            updatedAt = updatedAt,
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

    /** Whether [next] changes what the media session's metadata shows; position and play state do not. */
    internal fun metadataChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        previous.copy(elapsedMillis = null, playing = false) != next.copy(elapsedMillis = null, playing = false)

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
