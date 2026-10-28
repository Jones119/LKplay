package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.glance.CallBanner
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/**
 * The answer / hang-up banner the head unit showed for a call with a CarPlay box, drawn by this app.
 *
 * With a box the banner came from the head unit's own hands-free link: HFP carried the call, so the
 * native phone UI drew it wherever the driver was (2026-10-28). This project releases HFP on purpose so
 * that calls stay inside CarPlay (docs/_digest/73-75), which is why that banner never appears any more.
 * Releasing HFP was measured to be the only way to keep CarPlay's telephony audio, so the banner is
 * redrawn here instead -- above whatever screen the driver is on, with its two keys relayed to the same
 * CarPlay telephony HID the cluster card's keys use.
 *
 * API 19 has no `TYPE_APPLICATION_OVERLAY`: a window that draws over other applications is
 * [WindowManager.LayoutParams.TYPE_PHONE], and `SYSTEM_ALERT_WINDOW` is granted at install time on this
 * Android version (the per-app switch arrived in 6.0), so no permission prompt is involved.
 *
 * The banner is a window, not a view in [CarPlayHostActivity]: the point is that it also shows on the
 * launcher and on the task page, where this app is not visible.
 */
internal class CallBannerOverlay(
    private val context: Context,
    /** Where the "was the window accepted" line goes: the session's own log, readable without adb. */
    private val report: (String) -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var root: LinearLayout? = null
    private var titleView: TextView? = null
    private var subtitleView: TextView? = null
    private var answerView: TextView? = null
    private var endView: TextView? = null
    private var hideTask: Runnable? = null

    /** What is on screen, so an unchanged banner costs nothing. */
    private var shown: CallBanner? = null

    /** True once the window manager accepted the banner; logged, because a silent refusal is invisible. */
    private var attached = false

    /**
     * Set when the owning activity is going away for good.
     *
     * A listener callback can still be in flight when that happens, and re-adding a window from a dead
     * activity would leak it on screen for the rest of the drive.
     */
    private var released = false

    /**
     * Shows [banner], updates it, or removes the window when it is null.
     *
     * Must be called on the main thread: it adds and removes windows.
     */
    fun update(banner: CallBanner?, onAnswer: () -> Unit, onEnd: () -> Unit) {
        if (released) return
        if (banner == null) {
            dismiss()
            return
        }
        if (banner == shown && root != null) return
        shown = banner
        val view = root ?: build(onAnswer, onEnd).also { root = it }
        titleView?.text = banner.title
        subtitleView?.text = context.getString(
            if (banner.ringing) R.string.call_banner_incoming else R.string.call_banner_active,
        )
        // Nothing to answer without a ringing phase: an inferred call is already established.
        answerView?.visibility = if (banner.ringing && !banner.inferred) View.VISIBLE else View.GONE
        endView?.text = context.getString(R.string.call_banner_end)
        if (!attached) attach(view)
        scheduleRingTimeout(banner)
        Log.i(TAG, "call banner shown title=${banner.title} ringing=${banner.ringing} inferred=${banner.inferred}")
    }

    /** The owning activity is gone: the window must never come back, even from a stale callback. */
    fun release() {
        released = true
        dismiss()
    }

    /** Removes the window; safe to call when nothing is shown. */
    fun dismiss() {
        hideTask?.let { handler.removeCallbacks(it) }
        hideTask = null
        if (shown == null && root == null) return
        shown = null
        val view = root ?: return
        root = null
        titleView = null
        subtitleView = null
        answerView = null
        endView = null
        if (attached) {
            runCatching { windowManager?.removeView(view) }
                .onFailure { Log.w(TAG, "call banner could not be removed", it) }
            attached = false
        }
        Log.i(TAG, "call banner hidden")
    }

    /**
     * A ringing banner does not outlive the ring.
     *
     * The iPhone reports the end of a call, so this is only the guard for the case where it stops
     * reporting mid-ring (the failure mode this whole feature exists for): a banner with an answer key
     * that nobody can answer must not sit on the screen for the rest of the drive.
     */
    private fun scheduleRingTimeout(banner: CallBanner) {
        hideTask?.let { handler.removeCallbacks(it) }
        hideTask = null
        if (!banner.ringing) return
        val task = Runnable {
            if (shown?.ringing == true) {
                Log.i(TAG, "call banner timed out while ringing")
                dismiss()
            }
        }
        hideTask = task
        handler.postDelayed(task, RING_TIMEOUT_MILLIS)
    }

    private fun attach(view: LinearLayout) {
        val manager = windowManager ?: return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // API 19: the draw-over-applications window type. TYPE_APPLICATION_OVERLAY is API 26.
            WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(28)
        }
        val added = runCatching { manager.addView(view, params) }.onFailure {
            Log.w(TAG, "call banner window refused", it)
            runCatching { report("Call: banner refused by the window manager ${it.javaClass.simpleName}") }
        }.isSuccess
        attached = added
        runCatching { report("Call: banner window shown=$added") }
    }

    private fun build(onAnswer: () -> Unit, onEnd: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(14), dp(12))
            minimumWidth = dp(420)
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(PANEL_COLOR)
            }
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitleView = TextView(context).apply {
            setTextColor(SECONDARY_COLOR)
            textSize = 14f
            maxLines = 1
        }
        texts.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        texts.addView(subtitleView, LinearLayout.LayoutParams(-1, -2))
        row.addView(texts)

        answerView = bannerButton(R.string.call_banner_answer, ANSWER_COLOR) {
            Log.i(TAG, "call banner answer")
            onAnswer()
        }
        endView = bannerButton(R.string.call_banner_end, END_COLOR) {
            Log.i(TAG, "call banner end")
            onEnd()
        }
        row.addView(answerView, buttonParams())
        row.addView(endView, buttonParams())
        return row
    }

    private fun bannerButton(label: Int, color: Int, action: () -> Unit): TextView = TextView(context).apply {
        text = context.getString(label)
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        setPadding(dp(22), dp(9), dp(22), dp(9))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(color)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun buttonParams() = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "DiPlay-Call"

        /** Long enough for any real ring (WeChat can ring for a minute), short enough that a lost "call ended" cannot sit there. */
        const val RING_TIMEOUT_MILLIS = 90_000L

        val PANEL_COLOR = 0xF21C1C1C.toInt()
        val SECONDARY_COLOR = 0xFFB4B4B4.toInt()
        val ANSWER_COLOR = 0xFF2E7D32.toInt()
        val END_COLOR = 0xFFB3261E.toInt()
    }
}
