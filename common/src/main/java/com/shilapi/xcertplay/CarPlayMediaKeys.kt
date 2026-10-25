package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.core.graphics.drawable.toBitmap
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import com.shilapi.xcertplay.compat.systemServiceOrNull
import com.shilapi.xcertplay.compat.getDrawableCompat
import com.shilapi.xcertplay.compat.WorkerThreads

/**
 * Steering-wheel and other hardware media buttons for CarPlay.
 *
 * CS11 上方向盘媒体键走 `MEDIA_BUTTON` 广播的遗留路径（[startLegacyKeys]，与 HYLink 的
 * `SwcMediaButtonReceiver` 同法）：CarPlay 播放时 DiPlay 持有 audio focus，车机把按键投递到本应用。
 * Keys go to the iPhone as CarPlay media HID presses ([CarPlayMediaButton]).
 */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 遗留焦点监听；车机以音频焦点决定方向盘按键的归属。 */
    private val legacyFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "audio focus change=$change")
        // Only a permanent loss moves the car's media keys elsewhere; transient losses come back.
        if (change == AudioManager.AUDIOFOCUS_LOSS) synchronized(this) { focusHeld = false }
    }
    private val artworkQueue = NowPlayingArtworkQueue(
        worker = Executors.newSingleThreadExecutor { task ->
            WorkerThreads.create(task, "diplay-now-playing-artwork").apply { isDaemon = true }
        },
        main = Executor { mainHandler.post(it) },
        decode = ::decodeArtwork,
        publish = ::onArtworkDecoded,
        discard = Bitmap::recycle,
    )
    private var artworkOwner: Any? = null
    private var controller: CarPlayController? = null

    private var focusHeld = false
    private var appContext: Context? = null
    private var mediaAudioActive = false
    private var nowPlaying = CarPlayNowPlaying()
    private var artwork: Bitmap? = null
    private val artworkCache = LinkedHashMap<Int, Bitmap?>()
    private var placeholder: Bitmap? = null

    /**
     * Which cover the *instrument cluster* shows (G3), kept apart from [artwork].
     *
     * [artwork] feeds Android's media session, where the car draws our placeholder when the iPhone has
     * sent none. The cluster must never get that placeholder -- it has its own default image, and
     * submitting ours as if it were the album's art is exactly the "wrong cover" the driver saw
     * (docs/_digest/45 G6) -- so the cluster gets the state machine's answer instead, and the
     * placeholder is handed to the state machine precisely so it can refuse it.
     */
    private var clusterArtwork: ClusterArtworkAssociation<Bitmap>? = null

    /** What was last handed to the cluster, so a cover that did not change is not re-encoded. */
    private var clusterCover: Bitmap? = null

    /**
     * The newest decoded cover whose transfer id was not the current one when it finished.
     *
     * The iPhone may send the bytes before the metadata that names their transfer, and on this car it
     * evidently also sends ids the metadata never repeats (docs/_digest/45 G3). Holding the decode as
     * a candidate and offering it to the next metadata frame keeps both cases working, while an
     * already-decoded cover for the transfer the metadata *does* name always wins.
     */
    private var pendingCover: Bitmap? = null
    /** Transfer id [pendingCover] belongs to; a cover must never be published for another id. */
    private var pendingCoverId: Int? = null

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        if (controller !== next) {
            releaseLocked()
            artworkOwner = artworkQueue.newSession()
        }
        appContext = context.applicationContext
        controller = next
        // The placeholder is materialised here so the cluster state machine can be told what not to
        // publish; it is the same instance the media session falls back to.
        if (placeholder == null) placeholder = placeholderArt(context)
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
                if (previousArtwork != null && artwork == null) {
                    Log.i(TAG, "artwork cleared for transfer=${update.artworkTransferId} title=${update.title} " +
                    "playing=${update.playing}")
                }
                // QQ Music streams lyric lines through the title field and the iPhone corrects a title just after
                // the cover arrived. Neither is a new track: artist, album, duration and the artwork transfer
                // all stay, so the cover must not be re-associated (it flashes and disappears otherwise).
                val titleOnlyChurn = clusterTitleOnlyChurn(nowPlaying, update)
                nowPlaying = update
                // The cluster's cover follows the *identity*, not the transfer id. The picture offered
                // is the one decoded for the announced transfer, else the candidate held from a decode
                // that arrived early, else the one already loaded -- the reference's `song.artwork`,
                // which is what lets a track whose artist/album arrive in a later frame keep its cover
                // instead of being mistaken for a new track (G3).
                val announced = update.artworkTransferId?.let { artworkCache[it] }
                // Never hand the retained previous picture over as the new track cover: doing that
                // wrote track A bitmap under track B name, and the cluster showed a one-track lag
                // (A to A, B to A, D showed C; car 2026-10-23). Only the bitmap the phone paired with
                // this transfer id may be published; until it arrives the cluster keeps the old one.
                if (update.artworkTransferId != nowPlaying.artworkTransferId) {
                    pendingCover = null
                    pendingCoverId = null
                }
                val offered = announced ?: pendingCover?.takeIf { pendingCoverId == update.artworkTransferId }
                pendingCover = null
                pendingCoverId = null
                if (titleOnlyChurn) {
                    Log.i(TAG, "cluster cover held across title churn title=${update.title}")
                } else {
                    publishClusterCoverLocked(offered)
                }
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
            pendingCover = null
            pendingCoverId = null
            // The cluster's cover follows the track's identity (G3).
            publishClusterCoverLocked(decoded)
        } else {
            // Bytes for a transfer the metadata has not named -- yet, or ever. Drop them and the
            // cluster would show the first track's cover for the rest of the session, which is the
            // reported fault; hold them as the next metadata frame's candidate instead.
            pendingCover = decoded
            pendingCoverId = id
            Log.i(TAG, "artwork parked id=$id current=${nowPlaying.artworkTransferId}")
        }
    }

    /**
     * Hands the cluster the cover that [ClusterArtworkAssociation] associates with the current track.
     *
     * [offered] is a picture to associate -- a freshly decoded one, or the one cached for the transfer
     * the latest metadata named -- and null when this update brought none, in which case the retained
     * cover comes back and the cluster keeps showing it. The result is what leaves this object; a null
     * result clears the slot on purpose, so the head unit draws its own default (G6).
     *
     * A cover that did not change is not written again: the association returns the *same* image
     * instance for a retained cover, and the metadata stream repeats about twice a second.
     */
    private fun publishClusterCoverLocked(offered: Bitmap?) {
        // The iPhone pairs artwork with the track through artworkTransferId; trust that instead of the
        // identity machine, which kept dropping covers whose metadata lagged the decode (car
        // 2026-10-23: `artwork decoded id=132` followed by `artwork=null` for the rest of the track).
        if (offered == null) {
            if (clusterCover != null && nowPlaying.playing) {
                Log.i(TAG, "cluster cover held through track change title=${nowPlaying.title}")
                return
            }
            clusterCover = null
            runCatching { com.shilapi.xcertplay.media.VendorClusterBridge.setArtwork(null) }
            return
        }
        val repeated = offered === clusterCover
        if (repeated && com.shilapi.xcertplay.media.VendorClusterBridge.artworkMatches(nowPlaying)) return
        clusterCover = offered
        Log.i(
            TAG,
            "cluster cover id=${nowPlaying.artworkTransferId} title=${nowPlaying.title} " +
                "rehand=${repeated && !com.shilapi.xcertplay.media.VendorClusterBridge.artworkMatches(nowPlaying)}",
        )
        runCatching { com.shilapi.xcertplay.media.VendorClusterBridge.setArtwork(offered) }
    }

    // Another car app (its own Spotify, the radio) took audio focus and with it the steering-wheel
    // keys. When CarPlay starts playing again it becomes the car's media source again, as any player
    // would; only the start counts, so a car source picked while the iPhone plays on is not undone.
    private fun regainFocusLocked() {
        val context = appContext ?: return
        val audio = context.systemServiceOrNull(AudioManager::class.java) ?: return
        focusHeld = audio.requestAudioFocus(
            legacyFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN,
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun updateLocked(active: Boolean) {
        val context = appContext ?: return
        if (controller == null) return
        mediaAudioActive = active
        if (active && !legacyKeysRegistered) start(context) else if (active) regainFocusLocked()
    }

    private fun start(context: Context) {
        // CS11: 4.4 走遗留 MEDIA_BUTTON 广播路径——车机以 ACTION_MEDIA_BUTTON 广播方向盘媒体键，
        // 报名的接收器收到（与 HYLink 同法，见 _digest/08）
        startLegacyKeys(context)
    }

    /** 4.4: the wheel's media keys arrive as android.intent.action.MEDIA_BUTTON broadcasts (the same
     *  route HYLink's SwcMediaButtonReceiver uses on this platform). The last AudioManager
     *  registration wins, so hold it while CarPlay owns the media audio. */
    private var legacyKeysRegistered = false

    @Suppress("DEPRECATION")
    private fun startLegacyKeys(context: Context) {
        val audio = context.systemServiceOrNull(AudioManager::class.java) ?: return
        focusHeld = audio.requestAudioFocus(
            legacyFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN,
        ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        audio.registerMediaButtonEventReceiver(ComponentName(context, MediaButtonReceiver::class.java))
        legacyKeysRegistered = true
        Log.i(TAG, "media keys active (legacy MEDIA_BUTTON) focusGranted=$focusHeld")
    }

    /**
     * Re-claims the 4.4 MEDIA_BUTTON route. The media centre enables its own receiver when we claim
     * the source; on 4.4 the last registered receiver wins, and re-registering an already registered
     * component does not reorder it, so drop ours first.
     */
    @Synchronized

    @Suppress("DEPRECATION")
    private fun stopLegacyKeys(context: Context) {
        if (!legacyKeysRegistered) return
        legacyKeysRegistered = false
        context.systemServiceOrNull(AudioManager::class.java)?.unregisterMediaButtonEventReceiver(
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
        // The same press can also arrive through the host activity's dispatchKeyEvent; only the first
        // delivery of that exact press acts (docs/_digest/37, EasyPlay's LegacyMediaKeyGate).
        if (!MediaKeyEchoGate.accept(event.keyCode, event.downTime, event.eventTime)) {
            Log.i(TAG, "$source media key repeat ignored code=${event.keyCode}")
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            send(index, KeyEvent.keyCodeToString(event.keyCode))
        }
        return true
    }

    private fun releaseLocked() {
        Log.i(TAG, "media session released title=${nowPlaying.title} playing=${nowPlaying.playing}")
        artworkOwner = null
        artworkQueue.clear()
        MediaKeyEchoGate.reset()
        runCatching { com.shilapi.xcertplay.media.VendorClusterBridge.setArtwork(null) }
        mediaAudioActive = false
        nowPlaying = CarPlayNowPlaying()
        artwork = null
        artworkCache.clear()
        // The next session starts with no cover of its own; the cluster clears with it.
        clusterArtwork?.reset()
        clusterCover = null
        pendingCover = null
        pendingCoverId = null
        appContext?.let(::stopLegacyKeys)
        focusHeld = false
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

    /**
     * A title that moved while artist, album, duration and the artwork transfer all stayed put.
     *
     * QQ Music streams lyric lines through the NowPlayingUpdate title field, and the iPhone sometimes corrects
     * a title right after the cover arrives. Neither is a track change, so the cluster cover must stay
     * (measured on the car 2026-10-23: the cover was published and cleared again within half a second on every
     * lyric line).
     */
    internal fun clusterTitleOnlyChurn(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        previous.title != null && next.title != null &&
            previous.title != next.title &&
            previous.artist == next.artist &&
            previous.album == next.album &&
            previous.durationMillis == next.durationMillis &&
            previous.artworkTransferId == next.artworkTransferId

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
