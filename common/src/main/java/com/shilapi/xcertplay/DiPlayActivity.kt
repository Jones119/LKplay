// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.view.Window
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.network.CarHotspotTethering
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import com.shilapi.xcertplay.compat.systemService
import com.shilapi.xcertplay.compat.systemServiceOrNull
import com.shilapi.xcertplay.compat.hasPermissionCompat

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"
    private var clusterContentRequestVersion = 0L
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var usbPermissionOperation: UsbPermissionSetup.Operation? = null
    private var usbPermissionDialog: AlertDialog? = null
    internal var usbPermissionOperationFactory: (Context) -> UsbPermissionSetup.Operation = {
        UsbPermissionSetup.Operation(it.applicationContext)
    }
    private var navigationStreamType = 14
    private var testToneTrack: AudioTrack? = null
    private var toneStop: Runnable? = null
    private var exportButton: Button? = null
    private var rootScroll: ScrollView? = null
    private var renderedPage: String? = null
    private var pendingScrollY: Int? = null
    private var carButtonCard: LinearLayout? = null
    private var hotspotControls: LinearLayout? = null
    private var adbSwitchChangePending = false
    private var pausedForAdbSwitchChange = false
    private var updatingAdbSwitches = false
    private var adbStatus: TextView? = null
    private val adbSwitches = mutableMapOf<Int, Pair<Switch, () -> Boolean>>()
    private var hotspotStartupResult: CarHotspotTethering.Result? = null
    @Volatile private var startupHotspotCancelled = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothAutoConnectPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        DiPlayPreferences.saveConnectOnPhoneBluetooth(this, granted)
        render()
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation()) {
            applyLocationReporting(true)
        } else {
            render()
            permissionHelp(getString(R.string.location), getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p))
        }
    }
    private val iconPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) iconCrop.launch(Intent(this, ImageCropActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    private val iconCrop = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        refreshCarButton()
        carButtonSaved()
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        languagePreferenceAtCreate = AppLocale.preference(this)
        WheelKeyService.restoreIfNeeded(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        if (Build.VERSION.SDK_INT >= 21) { window.statusBarColor = BG; window.navigationBarColor = BG } // CS11: API21+；4.4 由主题值兜底
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setupError = runCatching { DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }.exceptionOrNull()?.let {
            android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
            getString(R.string.setup_error_auth)
        }
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        // Back on the home page finishes this activity while the session runs on, so the launcher icon lands
        // here with nothing under it. Going straight to CarPlay from onCreate - and finishing - means the
        // connect screen never flashes on the way. Ported from upstream 10f19a6b; the switch is honoured
        // because `openCarPlayScreenIfConnected` is what decides.
        if (savedInstanceState == null && isLauncherIntent(intent) &&
            CarPlayBackgroundSession.hasSession() && openCarPlayScreenIfConnected()
        ) {
            finish()
            return
        }
        // Capture the ThreadGroup while the ROM still reports one; see WorkerThreads.
        com.shilapi.xcertplay.compat.WorkerThreads.capture()
        // Also start it here, not only from the boot receiver: the owner should not have to reboot the head
        // unit for the process to become resident. Every later plug-in is then a warm start.
        KeepWarmService.start(this)
        warmUpPreferences()
        render()
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") { page = "home"; render() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        page = intent.getStringExtra("page") ?: "home"; render()
        handleWirelessRecovery()
        if (consumeBluetoothAutoConnectIntent(intent)) {
            if (initialLaunch) { initialLaunch = false; startCarHotspotOnLaunch() }
            handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
        }
        openCarPlayScreenIfConnected()
    }

    /**
     * A bare tap on the launcher icon.
     *
     * The launcher sends `ACTION_MAIN` + `CATEGORY_LAUNCHER` and nothing else; every internal entry point
     * (notification, Bluetooth auto-connect, the recovery links) carries extras instead — in particular a
     * `page` — so they must not be treated as "the driver wants CarPlay back". Ported from upstream 10f19a6b,
     * which also notes that the user-leave hint cannot tell these cases apart.
     */
    internal fun isLauncherIntent(intent: Intent?): Boolean =
        intent != null && intent.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_LAUNCHER) && intent.getStringExtra("page") == null

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    private fun openOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        if (runCatching { startActivity(intent) }.isFailure) {
            android.widget.Toast.makeText(this, R.string.center_map_no_permission_screen, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        CenterMapOverlay.onDiPlayScreenShown()
        carPlayScreenBroughtForward = false
        syncNightModeWithScreen()
    }

    override fun onStop() {
        if (ambientListening) {
            ambientLight.stop()
            ambientListening = false
        }
        cancelUsbPermissionSetup()
        startupHotspotCancelled = true
        super.onStop()
        // Bringing a running CarPlay screen forward stops this activity too, and scheduling the
        // centre-screen card there would drop it on top of CarPlay — which looks exactly like a black
        // screen while frames keep flowing (docs/_digest/36). The host activity's own visibility is
        // the reliable answer; CarPlayBackgroundSession.hasSession() stays false here.
        if (!isFinishing && !isChangingConfigurations &&
            !carPlayScreenBroughtForward && !DiPlayScreenState.carPlayHostVisible
        ) {
            CenterMapOverlay.scheduleShow()
        }
    }

    /** Set while this activity hands the foreground to the running CarPlay screen. */
    private var carPlayScreenBroughtForward = false

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); handler.post(tick)
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && !adbSwitchChangePending && !pausedForAdbSwitchChange &&
            (page == "home" || page == "settings" || page == "connection")) render()
        pausedForAdbSwitchChange = false
        if (initialLaunch) {
            initialLaunch = false
            startCarHotspotOnLaunch()
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
            }
        }
        if (consumeBluetoothAutoConnectIntent(intent)) {
            if (initialLaunch) { initialLaunch = false; startCarHotspotOnLaunch() }
            handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
        }
        openCarPlayScreenIfConnected()
    }

    /**
     * True when [PhoneBluetoothReceiver] opened us for the selected iPhone. The receiver's extra is
     * consumed here, and the request only counts when nothing is connected yet.
     */
    /**
     * A running CarPlay session already owns the video surface, so tapping DiPlay goes straight back
     * to the CarPlay screen instead of the wired/wireless page. That is what the driver expects, and
     * it keeps the host activity's surface from being torn down and rebuilt for nothing. The
     * connection page stays underneath, so Back still reaches it.
     *
     * The owner can turn this off ([DiPlayPreferences.resumeCarPlayOnOpen]), in which case the home page
     * shows as it did before the behaviour was added. That only covers the *already connected* case: with no
     * session there is nothing to return to, and dialling out stays with the auto-connect switches.
     */
    private fun openCarPlayScreenIfConnected(): Boolean {
        val explicitPage = intent.getStringExtra("page")
        val hasSession = CarPlayBackgroundSession.hasSession()
        val owns = CarPlayBackgroundSession.isOwner(this)
        val resume = DiPlayPreferences.resumeCarPlayOnOpen(this)
        if (explicitPage != null || !hasSession || !resume) {
            android.util.Log.i(
                "DiPlay-Open",
                "staying on the connection page page=$explicitPage hasSession=$hasSession owner=$owns " +
                    "resumeCarPlay=$resume",
            )
            return false
        }
        return runCatching {
            startActivity(
                Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            carPlayScreenBroughtForward = true
            true
        }.onFailure {
            android.util.Log.w("DiPlay-Open", "bringing the CarPlay screen forward failed", it)
        }.getOrDefault(false)
    }

    private fun consumeBluetoothAutoConnectIntent(intent: Intent): Boolean {
        val requested = intent.getBooleanExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, false)
        intent.removeExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT)
        return requested && DiPlayPreferences.connectOnPhoneBluetooth(this) &&
            DiPlayPreferences.phoneAddress(this) != null && setupError == null &&
            !CarPlayBackgroundSession.hasSession()
    }
    override fun onPause() {
        WheelKeyService.cancelLearning()
        pausedForAdbSwitchChange = adbSwitchChangePending
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        cancelUsbPermissionSetup()
        WheelKeyService.cancelLearning()
        super.onDestroy()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        render()
    }

    private val isCompactLayout: Boolean
        // Window size only: a multi-window task can fill the whole screen, and in multi-window the
        // configuration already reports the window's own size.
        get() = resources.configuration.screenWidthDp < 550 ||
            resources.configuration.screenHeightDp < 450

    private fun render() {
        // A pending assignment belongs to the widgets being replaced, never to another page.
        WheelKeyService.cancelLearning()
        // A restore still waiting for layout keeps its target: the old page was never laid out.
        val previousScrollY = (pendingScrollY ?: rootScroll?.scrollY)?.takeIf { renderedPage == page }
        status = null; connectButton = null; disconnectButton = null; lastRunning = null; carButtonCard = null
        adbSwitches.clear()
        adbStatus = null
        val compact = isCompactLayout
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; clipToPadding = false }
        rootScroll = scroll
        val content = column().apply {
            if (compact) setPadding(dp(12), dp(10), dp(12), dp(12))
            else setPadding(dp(32), dp(24), dp(32), dp(32))
        }
        scroll.addView(content)
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        // The brand lockup the way the brand itself signs things: a block, a widely letterspaced wordmark,
        // and the product as a micro-label. The launcher icon's green is gone with it (docs/_digest/60).
        header.addView(
            View(this).apply { setBackgroundColor(TEXT) },
            LinearLayout.LayoutParams(if (compact) dp(8) else dp(10), if (compact) dp(8) else dp(10)),
        )
        header.addView(
            label(getString(R.string.diplay), if (compact) 18 else 24, TEXT, true).apply {
                if (Build.VERSION.SDK_INT >= 21) letterSpacing = .34f
                setPadding(if (compact) dp(10) else dp(14), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, if (compact) dp(36) else dp(56), 1f),
        )
        if (!compact) {
            header.addView(
                label("CARPLAY · CS11", 13, MUTED).apply {
                    if (Build.VERSION.SDK_INT >= 21) letterSpacing = .16f
                },
            )
        }
        if (page != "home" || !compact) {
            header.addView(
                button(if (page == "home") getString(R.string.car_home) else getString(R.string.back), false) {
                    if (page == "home") startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                    else { page = "home"; render() }
                },
                LinearLayout.LayoutParams(if (compact) dp(80) else dp(130), if (compact) dp(36) else dp(56)),
            )
        }
        content.addView(header)
        // The rule under the header, which is what divides a page in this system instead of a shadow.
        content.addView(space(if (compact) 10 else 18))
        content.addView(
            View(this).apply { setBackgroundColor(BORDER) },
            LinearLayout.LayoutParams(-1, dp(1)),
        )
        content.addView(space(if (compact) 8 else 24))
        when (page) {
            "connection" -> connectionSetup(content)
            "settings" -> settingsPage(content)
            "about" -> about(content)
            else -> home(content)
        }
        setContentView(scroll)
        renderedPage = page
        // Remembered so `syncNightModeWithScreen` can tell whether the resolved mode moved while this page
        // was not visible, and re-render instead of leaving a stale palette on screen.
        lastRenderedNight = resolvedNight
        refreshStatus()
        pendingScrollY = previousScrollY
        // A stopped window still dispatches pre-draw but skips layout, so wait for a real layout;
        // the listener stays on this view and goes away with it.
        previousScrollY?.let { y ->
            scroll.doOnLayout {
                if (rootScroll === scroll) {
                    scroll.scrollTo(0, y)
                    pendingScrollY = null
                }
            }
        }
    }

    private fun home(content: LinearLayout) {
        val compact = isCompactLayout
        if (compact) {
            val card = card().apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
            status = label(getString(R.string.ready_when_you_are), 16, TEXT, true).apply {
                setPadding(0, 0, 0, dp(8))
            }
            card.addView(status)
            connectButton = button(getString(R.string.connect_phone), true) {
                if (CarPlayBackgroundSession.hasSession()) openProjection()
                else connect(true)
            }
            card.addView(connectButton, matchButton(0, 44))

            val buttonRow = row().apply {
                setPadding(0, dp(8), 0, 0)
                gravity = Gravity.CENTER_VERTICAL
            }
            val usbBtn = button(getString(R.string.connect_with_usb), false) { connect(false) }
            val settingsBtn = button(getString(R.string.settings), false) { page = "settings"; render() }
            buttonRow.addView(usbBtn, LinearLayout.LayoutParams(0, dp(38), 1f))
            buttonRow.addView(space(8), LinearLayout.LayoutParams(dp(8), 1))
            buttonRow.addView(settingsBtn, LinearLayout.LayoutParams(0, dp(38), 1f))
            card.addView(buttonRow)

            disconnectButton = button(getString(R.string.disconnect), false) {
                disconnectButton?.isEnabled = false
                CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
            }.apply { visibility = View.GONE }
            card.addView(disconnectButton, matchButton(8, 38))

            content.addView(card)
            setupError?.let { content.addView(label(it, 13, WARNING).apply { setPadding(0, dp(6), 0, 0) }) }
            return
        }

        val wide = resources.configuration.screenWidthDp >= 850
        val body = column()
        val left = column()
        left.addView(label(getString(R.string.your_phone_your_drive), 12, MUTED, true).apply { if (Build.VERSION.SDK_INT >= 21) letterSpacing = .18f })
        // The state line is the page's headline now rather than a card title: it is what the driver reads
        // first, and it is the sentence the whole screen is about.
        status = label(getString(R.string.ready_when_you_are), if (wide) 40 else 34, TEXT, true).apply { setPadding(0, dp(14), 0, dp(12)) }
        left.addView(status)
        left.addView(label(getString(R.string.plug_your_iphone_into_a_usb_data_port_allow_carplay_when_y), 18, MUTED))
        connectButton = button(getString(R.string.connect_phone), true) {
            if (CarPlayBackgroundSession.hasSession()) openProjection()
            else connect(true)
        }
        left.addView(connectButton, matchButton(26, 56))
        disconnectButton = button(getString(R.string.disconnect), false) {
            disconnectButton?.isEnabled = false
            CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
        }.apply { visibility = View.GONE }
        left.addView(disconnectButton, matchButton(12, 56))
        val card = card()
        card.addView(label(getString(R.string.wireless_carplay), 12, MUTED, true).apply { if (Build.VERSION.SDK_INT >= 21) letterSpacing = .12f })
        val connectionHint = when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
            WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.existing_wifi_hint)
            WirelessHotspotMode.MANUAL -> getString(R.string.hotspot_hint_manual)
            else -> getString(R.string.hotspot_hint_p2p)
        }
        card.addView(label(connectionHint, 15, MUTED).apply { setPadding(0, dp(14), 0, 0) })
        val startupProblem = hotspotStartupResult?.takeIf {
            it != CarHotspotTethering.Result.READY && it != CarHotspotTethering.Result.CANCELLED &&
                CarHotspotSettings.shouldEnable(this, true, AirPlayPersistence.loadWirelessHotspotMode(this)) &&
                com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) != true
        }
        if (startupProblem != null) {
            card.addView(label(hotspotResultText(startupProblem), 15, WARNING))
            card.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(10, 56))
        } else if (carHotspotOff()) {
            card.addView(label(getString(R.string.msg_car_hotspot_off, AirPlayPersistence.loadManualHotspotSsid(this)), 15, WARNING).apply { setPadding(0, dp(14), 0, 0) })
            card.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(10, 56))
        }
        card.addView(button(getString(R.string.choose_iphone), false) { choosePhone() }, matchButton(16, 56))
        val right = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        // The artwork panel: its own near-black gradient, the drawn composition, and a hairline caption row
        // carrying the address the phone is told. Drawn in code rather than shipped as a photograph, so it
        // stays in the same monochrome register as the tokens (docs/_digest/60).
        val branding = column().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(0x0E, 0x0D, 0x0C), Color.rgb(0x08, 0x08, 0x07)),
            ).apply { cornerRadius = dp(4).toFloat(); setStroke(dp(1), BORDER) }
            addView(
                DiPlayConnectVisual(this@DiPlayActivity).apply { contentDescription = getString(R.string.carplay_icon) },
                LinearLayout.LayoutParams(-1, -2),
            )
            addView(
                View(this@DiPlayActivity).apply { setBackgroundColor(BORDER) },
                LinearLayout.LayoutParams(-1, dp(1)),
            )
            val caption = row().apply { setPadding(dp(18), dp(14), dp(18), dp(14)) }
            caption.addView(
                label("CONNECT TO CARPLAY", 12, MUTED).apply { if (Build.VERSION.SDK_INT >= 21) letterSpacing = .16f },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            caption.addView(label("fe80::2", 12, MUTED))
            addView(caption)
        }
        right.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton())
        right.addView(label(getString(R.string.plug_your_iphone_into_a_usb_data_port_allow_carplay_when_y), 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(24)) })
        right.addView(button(getString(R.string.settings), false) { page = "settings"; render() }, matchButton())
        right.addView(label(getString(R.string.make_diplay_feel_right_for_your_car), 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(24)) })
        right.addView(label("${getString(R.string.home_public_preview)}${version()}", 12, MUTED).apply { if (Build.VERSION.SDK_INT >= 21) letterSpacing = .08f })
        if (wide) {
            // Both rows share column widths. The USB button starts at the wireless
            // card's top edge, independently of hero wrapping or font scaling.
            fun columns(first: View, second: View, stretchSecond: Boolean = false) = row().apply {
                gravity = Gravity.TOP
                addView(first, LinearLayout.LayoutParams(0, -2, 1.6f))
                addView(space(40), LinearLayout.LayoutParams(dp(40), 1))
                addView(second, LinearLayout.LayoutParams(0, if (stretchSecond) -1 else -2, 1f))
            }
            body.addView(columns(left, branding, true))
            body.addView(space(26))
            body.addView(columns(card, right))
        } else {
            body.addView(left)
            body.addView(space(26))
            body.addView(card)
            body.addView(space(26))
            body.addView(branding)
            body.addView(space(24))
            body.addView(right)
        }
        setupError?.let { body.addView(label(it, 16, WARNING).apply { setPadding(0, dp(16), 0, 0) }) }
        content.addView(body)
    }

    /**
     * Which settings category the left index has selected, and the section indices each category shows.
     *
     * The indices are the order `settings()` adds its sections in. Filtering by index - rather than gating
     * the calls - cannot silently drop a section: every section is still built, and the filter only decides
     * what is visible. Reordering the sections in `settings()` means updating these sets (docs/_digest/60).
     */
    private var settingsCategoryIndex = 0

    /**
     * An open detail inside the settings page, e.g. the connection setup. It renders into the content column
     * next to the category index instead of replacing the whole screen, so the index stays usable.
     */
    private var settingsDetail: String? = null

    private fun settingsCategoryLabels(): List<String> = listOf(
        getString(R.string.connection),
        getString(R.string.display_and_performance),
        getString(R.string.audio_routing),
        getString(R.string.carplay_controls),
        getString(R.string.diagnostics),
        getString(R.string.settings_category_other),
        getString(R.string.about),
    )

    private fun settingsCategorySections(): List<Set<Int>> = listOf(
        // connection: connection setup, automatic connection.
        setOf(1, 3),
        // display: display and performance, and the map on the instrument cluster.
        setOf(4, 8),
        // audio: audio routing.
        setOf(6),
        // controls: CarPlay controls (the gesture), and the car button inside CarPlay.
        setOf(0, 5),
        // diagnostics: diagnostics, permissions and connection help.
        setOf(2, 9),
        // other: everything the categories above do not name, e.g. location.
        setOf(7),
        // about: about, the public preview note, and the open-source credits.
        setOf(10, 11, 12),
    )

    private fun applySettingsCategoryFilter(sections: LinearLayout) {
        val categories = settingsCategorySections()
        val visible = categories.getOrNull(settingsCategoryIndex) ?: return
        // A section no category names stays visible everywhere: a new section can never be lost by forgetting
        // to list it here, which is the failure mode an index-based filter would otherwise have.
        val named = categories.flatten().toSet()
        // Only the section cards are counted. The page's own title and subtitle are children of the same
        // column, and counting them shifted every category by two: the first screenshot of this screen showed
        // the title hidden and the connection category holding the language section. section() tags its card
        // with this literal, deliberately not a new companion-object constant - adding one to this class by
        // script broke the build once already.
        val cards = (0 until sections.childCount).filter {
            sections.getChildAt(it).tag == "diplay-settings-section"
        }
        cards.forEachIndexed { index, childIndex ->
            val shown = index in visible || index !in named
            sections.getChildAt(childIndex).visibility = if (shown) View.VISIBLE else View.GONE
        }
    }

    /**
     * The settings page as the brand lays one out: the categories as an index on the left with a 3 dp bar on
     * the selected entry, and the sections in one column on the right.
     *
     * The sections themselves are built by `settings()`, which is handed the right column as its container -
     * so no call site inside it had to change, and the filter runs after it returns.
     */
    private fun settingsPage(content: LinearLayout) {
        val sections = column()
        // A category's detail is built into the same content column rather than taking over the screen: the
        // index stays visible, which is the point of a two-column settings page (docs/_digest/60).
        val detail = settingsDetail
        val fillSections = {
            if (detail == null) {
                settings(sections)
            } else {
                sections.addView(
                    button(getString(R.string.back), false) { settingsDetail = null; render() },
                    LinearLayout.LayoutParams(dp(160), dp(44)).apply { bottomMargin = dp(18) },
                )
                if (detail == "connection") connectionSetup(sections)
            }
        }
        if (isCompactLayout) {
            // A narrow screen has no room for an index beside the content, so the sections run full width.
            content.addView(sections)
            fillSections()
            if (detail == null) content.post { applySettingsCategoryFilter(sections) }
            return
        }
        val nav = column().apply { setPadding(0, 0, dp(34), 0) }
        settingsCategoryLabels().forEachIndexed { index, title ->
            val selected = index == settingsCategoryIndex
            val item = row().apply { gravity = Gravity.CENTER_VERTICAL }
            item.addView(
                View(this).apply { setBackgroundColor(if (selected) TEXT else Color.TRANSPARENT) },
                LinearLayout.LayoutParams(dp(3), dp(44)),
            )
            item.addView(
                label(title, 16, if (selected) TEXT else MUTED, selected).apply { setPadding(dp(14), 0, 0, 0) },
                LinearLayout.LayoutParams(0, dp(44), 1f),
            )
            item.setOnClickListener {
                if (settingsCategoryIndex != index || settingsDetail != null) {
                    settingsCategoryIndex = index
                    // Picking a category closes any open detail, otherwise the click looks like it did nothing.
                    settingsDetail = null
                    render()
                }
            }
            nav.addView(item, LinearLayout.LayoutParams(-1, -2))
        }
        // The right column scrolls on its own, so the index stays where it is while the sections move: a page
        // scroll would carry the index away with the content, which defeats a two-column settings page. The
        // height is fixed because a child of the outer ScrollView cannot be told to match the viewport.
        val sectionScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(sections, LinearLayout.LayoutParams(-1, -2))
        }
        val page = row().apply { gravity = Gravity.TOP }
        // 220dp, not 300dp: at 300 the index took a quarter of the 1280dp-wide screen and pushed the
        // cards' right edge under the car's own status strip (owner report, 2026-10-18).
        page.addView(nav, LinearLayout.LayoutParams(dp(220), -2))
        page.addView(
            sectionScroll,
            LinearLayout.LayoutParams(0, (resources.displayMetrics.heightPixels - dp(240)).coerceAtLeast(dp(360)), 1f),
        )
        content.addView(page)
        fillSections()
        // After the sections are attached, so the filter sees every child whatever the category order is.
        if (detail == null) sectionScroll.post { applySettingsCategoryFilter(sections) }
    }

    private fun settings(content: LinearLayout) {
        content.addView(label(getString(R.string.your_drive_your_way), 34, TEXT, true))
        content.addView(label(getString(R.string.apply_reconnects_carplay_for_size_resolution_music_buffer), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, getString(R.string.carplay_controls), R.drawable.ic_dp_controls) { card ->
            val gestureFingers = listOf(2, 3, 4)
            choice(card, getString(R.string.settings_gesture_fingers_label),
                gestureFingers.map { getString(R.string.settings_gesture_fingers_option, it) },
                gestureFingers.indexOf(AirPlayPersistence.loadSettingsGestureFingers(this)).coerceAtLeast(0),
                reconnects = false) {
                AirPlayPersistence.saveSettingsGestureFingers(this, gestureFingers[it])
            }
            card.addView(label(getString(R.string.settings_gesture_fingers_hint), 14, MUTED).apply {
                setPadding(0, dp(10), 0, 0)
            })
        }
        section(content, getString(R.string.connection_setup), R.drawable.ic_dp_connection) { card ->
            card.addView(label(getString(R.string.choose_how_to_connect_follow_the_setup_steps_and_save_your), 16, MUTED))
            card.addView(button(getString(R.string.open_connection_setup), false) { settingsDetail = "connection"; render() }, matchButton(12, 60))
        }
        section(content, getString(R.string.diagnostics), R.drawable.ic_dp_diagnostics) { card ->
            exportButton = button(if (exportInProgress) getString(R.string.saving_report) else getString(R.string.save_diagnostic_report), false) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportDiagnostics()
                else chooseReportDestination()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(10, 60))
            card.addView(button(getString(R.string.choose_save_location), false) { chooseReportDestination() }, matchButton(10, 60))
            val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getString(R.string.reports_save_to_downloads_diplay) else getString(R.string.choose_where_to_save_your_report)
            card.addView(label(destination + getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        }
        section(content, getString(R.string.automatic_connection), R.drawable.ic_dp_automation) { card ->
            toggle(card, getString(R.string.connect_when_diplay_opens), getString(R.string.use_your_last_connection_type_and_selected_iphone), DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) }
            // Distinct from the switch above: that one decides whether to dial out, this one decides where
            // the app lands when a session is *already* running. Defaults on, which is the behaviour the
            // driver asked for (docs/_digest/84).
            toggle(
                card,
                getString(R.string.resume_carplay_on_open),
                getString(R.string.resume_carplay_on_open_description),
                DiPlayPreferences.resumeCarPlayOnOpen(this),
            ) { DiPlayPreferences.saveResumeCarPlayOnOpen(this, it) }
            toggle(card, getString(R.string.connect_when_iphone_bluetooth_connects),
                getString(R.string.connect_when_iphone_bluetooth_connects_description),
                DiPlayPreferences.connectOnPhoneBluetooth(this)) { enabled ->
                if (!enabled) {
                    DiPlayPreferences.saveConnectOnPhoneBluetooth(this, false)
                } else if (Build.VERSION.SDK_INT >= 31 &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                ) {
                    bluetoothAutoConnectPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    // CS11 (API 19) has no runtime Bluetooth permission, so enabling takes effect here.
                    DiPlayPreferences.saveConnectOnPhoneBluetooth(this, true)
                    render()
                }
            }
            adbToggle(card, R.string.open_after_the_car_starts,
                R.string.availability_depends_on_your_head_unit_s_startup_settings,
                read = { AirPlayPersistence.loadAutoStartOnBoot(this) },
                needsAdb = { CarHotspotSettings.enabled(this) &&
                    AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL },
                permissions = { listOf(CarHotspotSetup.Permission.BOOT_LAUNCH) }) {
                AirPlayPersistence.saveAutoStartOnBoot(this, it)
            }
            val autoConfirmActive = UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)
            toggle(
                card,
                getString(R.string.usb_auto_confirm_title),
                getString(R.string.usb_auto_confirm_subtitle),
                autoConfirmActive,
            ) { enabled ->
                if (enabled) promptEnableUsbAutoConfirm()
                else {
                    if (!UsbAutoConfirmService.openSettings(this)) toast(getString(R.string.wheel_keys_no_settings))
                    render()
                }
            }
            if (!autoConfirmActive) {
                card.addView(
                    // Secondary, not primary: this is one permission step inside a card, not the page's main
                    // action. It used to pass `true` here and `false` at the identical button in the
                    // dashboard section, so the same action was a white block on one screen and a dark one on
                    // the other (docs/_digest/84).
                    button(
                        getString(R.string.btn_auto_apply_permissions),
                        false,
                    ) {
                        autoApplyPermissions()
                    },
                    matchButton(8, 54),
                )
            } else {
                card.addView(
                    label(getString(R.string.usb_auto_confirm_active_hint), 14, SUCCESS).apply {
                        setPadding(0, dp(4), 0, dp(8))
                    },
                )
            }
            card.addView(button("${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
        }
        hotspotSettingsControls(content)
        section(content, getString(R.string.display_and_performance), R.drawable.ic_dp_display) { card ->
            // 跟随光感 is offered only where the hardware exists.
            //
            // The CS11 has no ambient light sensor at all: `SensorManager` lists accelerometer, gyroscope,
            // rotation vector and the AOSP composites but no `TYPE_LIGHT`, and the sysfs path named by
            // `ro.hardware.lightsensor` (/sys/class/i2c-dev/i2c-1/device/1-0044/) does not exist on this
            // firmware — it is a leftover from a reference design. Offering the option there would be a
            // control that can never do anything, which is exactly what the owner hit (2026-10-18).
            // All four modes stay listed: the car may well switch its own theme from a CAN illumination
            // signal (the owner reports the built-in and third-party navigation going dark in tunnels), it
            // is just not reaching Android as either a `TYPE_LIGHT` sensor or a `uiMode` night flag on this
            // firmware. Hiding the automatic modes would be wrong; the note below says what to do instead
            // until the real channel is found.
            val ambientSupported = ambientSensorAvailable
            val nightModes = CarPlayNightMode.entries.toList()
            val nightMode = AirPlayPersistence.loadCarPlayNightMode(this)
            // Follows the chosen mode, not the hardware: the threshold and delay are still the settings a
            // reader would configure for 跟随光感, and hiding them would make the option look broken.
            val ambientControls = column().apply {
                visibility = if (nightMode == CarPlayNightMode.AMBIENT) View.VISIBLE else View.GONE
            }
            choice(
                card,
                getString(R.string.carplay_night_mode),
                nightModes.map { getString(nightModeLabel(it)) },
                nightModes.indexOf(nightMode).coerceAtLeast(0),
                reconnects = false,
            ) { index ->
                AirPlayPersistence.saveCarPlayNightMode(this, nightModes[index])
                // Re-render at once: without this the page kept its old palette until some other action
                // happened to rebuild it, so picking 固定夜间 looked like it did nothing (owner report).
                render()
            }
            if (!ambientSupported) {
                card.addView(label(getString(R.string.carplay_night_auto_unavailable), 14, MUTED).apply {
                    setPadding(0, 0, 0, dp(18))
                })
            }
            card.addView(label(getString(R.string.carplay_night_hint), 14, MUTED))
            card.addView(label(getString(R.string.carplay_night_time_note), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            ambientControls.addView(label(getString(R.string.carplay_night_ambient_hint), 14, MUTED).apply {
                setPadding(0, 0, 0, dp(18))
            })
            ambientLightThresholdControl(ambientControls)
            nightDelaySettingControl(ambientControls, R.string.ambient_delay_title, R.string.ambient_delay_hint,
                0..60, 2, R.string.ambient_delay_summary, { AirPlayPersistence.loadAmbientDelaySeconds(this) },
                save = { AirPlayPersistence.saveAmbientDelaySeconds(this, it) })
            card.addView(ambientControls)
            card.addView(button(getString(R.string.picture_adjustments), false) {
                startActivity(Intent(this, CarPlayHostActivity::class.java)
                    .putExtra("picture_controls", true).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }, matchButton(0, 56).apply { bottomMargin = dp(24) })
            carPlaySizeControl(card)
            resolutionSettingControl(
                card, R.string.resolution, R.string.custom_resolution_hint,
                CarPlayDisplayScale.MIN_PERCENT..CarPlayDisplayScale.MAX_PERCENT, 100,
                R.string.custom_resolution_summary,
                { AirPlayPersistence.loadDisplayScalePercent(this) }, reconnects = true,
                save = { AirPlayPersistence.saveDisplayScalePercent(this, it) },
            )
            val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            choice(card, getString(R.string.music_buffer), listOf(getString(R.string.s_300_ms_default), getString(R.string.s_500_ms), getString(R.string.s_1000_ms_most_stable)),
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
            }
            toggle(card, getString(R.string.main_buffered_audio), getString(R.string.main_buffered_audio_description),
                AirPlayPersistence.loadMainBufferedAudio(this)) {
                AirPlayPersistence.saveMainBufferedAudio(this, it)
                reconnectForClusterMap()
            }
            choice(card, getString(R.string.frame_rate), listOf(getString(R.string.s_30_fps_lighter_load), getString(R.string.s_60_fps_smoother_motion)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            toggle(card, getString(R.string.right_hand_drive), getString(R.string.place_carplay_s_controls_closer_to_the_driver), AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) }
            carPlayDockControl(card)
            addSystemBarControls(
                hideTopBar = AirPlayPersistence.loadHideTopBar(this),
                hideBottomBar = AirPlayPersistence.loadHideBottomBar(this),
                onHideTopBarChanged = { AirPlayPersistence.saveHideTopBar(this, it) },
                onHideBottomBarChanged = { AirPlayPersistence.saveHideBottomBar(this, it) },
            ) { label, checked, onChanged ->
                toggle(card, getString(label), getString(R.string.hide_the_car_s_system_bars_while_carplay_is_open), checked, save = onChanged)
            }
        }
        section(content, getString(R.string.car_button_in_carplay), R.drawable.ic_dp_car) { card -> carButtonCard = card; carButtonControls(card) }
        section(content, getString(R.string.audio_routing), R.drawable.ic_dp_audio) { card ->
            toggle(card, getString(R.string.contrib_audio_home_toggle_audio_focus), getString(R.string.contrib_audio_home_toggle_audio_focus_desc), AirPlayPersistence.loadAudioFocusEnabled(this)) { AirPlayPersistence.saveAudioFocusEnabled(this, it) }
            if (resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)) {
                toggle(card, getString(R.string.advanced_audio_channel_mapping),
                    getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                    AirPlayPersistence.loadAdvancedAudioChannelMapping(this)) {
                    AirPlayPersistence.saveAdvancedAudioChannelMapping(this, it)
                }
            }
            mediaChannelControl(card)
            navigationChannelControl(card)
            audioDecoderControl(card)
            // The head unit can keep the microphone for its own voice assistant, and CarPlay voice
            // input then fails silently; the hint below says so. The live AVAILABLE/BLOCKED badge is not
            // probed from this screen any more:
            //
            // on this ROM (CS11, Android 4.4 / Dalvik) constructing a Thread from inside this settings
            // lambda dies in `java.lang.Thread.create` with a NullPointerException and takes the whole
            // settings screen with it — measured on the car 2026-10-18 with all three forms
            // (`Thread(r, name)`, `Thread(r)` and `HandlerThread(name)`), so it is not the naming. The
            // microphone check still reaches the diagnostic report. Losing the live badge is cheaper than
            // losing the settings screen.
            card.addView(label(getString(R.string.microphone_status_hint), 13, MUTED))
        }
        section(content, getString(R.string.location), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.report_location_to_iphone),
                getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                AirPlayPersistence.loadLocationReportingEnabled(this), save = ::onLocationReportingChanged)
            card.addView(label(getString(R.string.location_reporting_reconnects), 14, MUTED))
        }
        // The iPhone's instrument-cluster stream (stream 111), shown as the centre-screen card and, where a
        // launcher mirrors it, on the launcher screens. This head unit exposes no cluster projection display,
        // so DiPlay always asks for the standard virtual 1280x720 stream.
        section(content, getString(R.string.carplay_map_on_instrument_cluster_experimental), R.drawable.ic_dp_dashboard) { card ->
            val clusterMapEnabled = AirPlayPersistence.loadClusterMapEnabled(this)
            toggle(card, getString(R.string.carplay_map_on_instrument_cluster_experimental),
                getString(R.string.shows_the_iphone_s_cluster_map_virtual_stream_description),
                clusterMapEnabled) {
                AirPlayPersistence.saveClusterMapEnabled(this, it)
                render()
                reconnectForClusterMap()
            }
            if (clusterMapEnabled) {
                toggle(card, getString(R.string.center_map_card),
                    getString(R.string.center_map_card_virtual_description),
                    AirPlayPersistence.loadCenterMapOverlay(this)) {
                    AirPlayPersistence.saveCenterMapOverlay(this, it)
                    if (it && !CenterMapOverlay.permitted(this)) openOverlayPermission()
                    render()
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    toggle(card, getString(R.string.center_map_follows_dashboard), getString(R.string.center_map_follows_dashboard_description),
                        AirPlayPersistence.loadCenterMapFollowsDashboard(this)) {
                        AirPlayPersistence.saveCenterMapFollowsDashboard(this, it)
                    }
                    toggle(card, getString(R.string.center_map_auto_hide), getString(R.string.center_map_auto_hide_description),
                        AirPlayPersistence.loadCenterMapAutoHide(this)) {
                        AirPlayPersistence.saveCenterMapAutoHide(this, it)
                    }
                    val overlay = CenterMapOverlay.permitted(this)
                    card.addView(label(if (overlay) getString(R.string.center_map_overlay_allowed)
                        else getString(R.string.center_map_overlay_missing, packageName), 14, if (overlay) MUTED else WARNING))
                    val usage = HomeScreenMonitor.hasAccess(this)
                    card.addView(label(if (usage) getString(R.string.center_map_auto_hide_active)
                        else getString(R.string.center_map_auto_hide_needed), 14, if (usage) MUTED else WARNING))
                    if (!usage) {
                        card.addView(button(getString(R.string.btn_auto_apply_permissions), false) { autoApplyPermissions() }, matchButton(8, 54))
                    }
                }
                val contents = CarPlayClusterDisplay.Content.entries
                val content = AirPlayPersistence.loadClusterContent(this)
                choice(card, getString(R.string.dashboard_shows), listOf(
                    getString(R.string.dashboard_content_map),
                    getString(R.string.dashboard_content_turn_card),
                    getString(R.string.dashboard_content_map_with_turn_card),
                    getString(R.string.dashboard_content_map_with_custom_turn_card),
                ), contents.indexOf(content).coerceAtLeast(0), reconnects = false) {
                    val next = contents[it]
                    val request = ++clusterContentRequestVersion
                    AirPlayPersistence.saveClusterContent(this, next)
                    render()
                    if (content.url != next.url) {
                        // The iPhone's own contents switch live; our own card over the map is set up at
                        // connection, so it reconnects.
                        val controller = CarPlayBackgroundSession.snapshot()?.controller
                        if (CarPlayClusterDisplay.usesCustomTurnCard(next) || controller == null) {
                            reconnectForClusterMap()
                        } else controller.showDashboardContent(next.url) { applied ->
                            runOnUiThread {
                                if (!applied && request == clusterContentRequestVersion &&
                                    !isFinishing && !isDestroyed &&
                                    AirPlayPersistence.loadClusterContent(this) == next &&
                                    CarPlayBackgroundSession.snapshot()?.controller === controller) {
                                    reconnectForClusterMap()
                                }
                            }
                        }
                    }
                }
                wheelKeyControls(card)
            }
        }
        section(content, getString(R.string.permissions_and_connection_help), R.drawable.ic_dp_permissions) { card ->
            card.addView(label(getString(R.string.nearby_devices_connects_your_iphone_microphone_enables_sir), 16, MUTED))
            card.addView(button(getString(R.string.app_permissions), false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 60))
            card.addView(button(getString(R.string.bluetooth_settings), false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(10, 60))
            card.addView(button(getString(R.string.wireless_connection_help), false) { wirelessHelp() }, matchButton(10, 60))
        }
        section(content, getString(R.string.about), R.drawable.ic_dp_about) { card ->
            card.addView(button(getString(R.string.about_diplay), false) { page = "about"; render() }, matchButton(0, 60))
            // An explicit way out, here rather than on the separate about page: this row is the one the
            // driver reaches through 设置 → 关于 (owner correction, 2026-10-18). Without it the only way to
            // stop DiPlay is the car's task killer, and a background CarPlay session keeps the hotspot and
            // the decoder alive after the driver has finished with it.
            card.addView(label(getString(R.string.exit_application_description), 15, MUTED).apply {
                setPadding(0, dp(16), 0, dp(10))
            })
            card.addView(button(getString(R.string.exit_application), primary = false) { exitDiPlay() }, matchButton(0, 60))
        }
        languageSettings(content)
    }

    private fun about(content: LinearLayout) {
        content.addView(label(getString(R.string.diplay), 40, TEXT, true))
        content.addView(label(getString(R.string.carplay_at_home_in_your_car), 20, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, "${getString(R.string.about_public_preview_prefix)}${version()}") { card ->
            card.addView(label(getString(R.string.an_independent_carplay_receiver_for_android_head_units_wir), 17, TEXT))
        }
        section(content, getString(R.string.made_possible_by_open_source)) { card ->
            card.addView(label(getString(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay), 16, MUTED))
        }    }

    /**
     * Ends DiPlay for real.
     *
     * `finish()` alone only closes the settings page: the wireless session, the hotspot and the codec
     * threads all live in this process, so the user would still be connected with no window to see. Removing
     * the task and then ending the process is what "exit the application" has to mean here, and it is the
     * same thing the in-CarPlay exit does (`CarPlayHostActivity.exitApplication`).
     */
    private fun exitDiPlay() {
        Log.i("DiPlay-Exit", "exit requested from the about page")
        // `finishAffinity` is API 16, so it is safe on the API 19 head unit; the API 21+ task removal that
        // CarPlayHostActivity uses is not needed here because the process is about to end anyway.
        finishAffinity()
        // Give the task a moment to leave the screen before the process goes, so the launcher is not left
        // drawing a window that has already died.
        handler.postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, EXIT_KILL_DELAY_MILLIS)
    }

    // An opted-in connection prepares the hotspot in the controller instead of stopping at this reminder.
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false &&
            !(CarHotspotSettings.enabled(this) && CarHotspotTethering.permitted(this))

    // CS11/4.4: the firmware keeps a usable hotspot configuration which the controller adopts for the
    // connection itself, so MANUAL setup never has to block the user on credentials typed by hand.
    private fun carHotspotConfiguration(): com.shilapi.xcertplay.network.LegacySoftApControl.UsableApConfiguration? =
        if (AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL) {
            com.shilapi.xcertplay.network.LegacySoftApControl.readUsableStored(this)
        } else {
            null
        }

    private fun carHotspotSelfConfigured(): Boolean = carHotspotConfiguration() != null


    private fun nightModeLabel(mode: CarPlayNightMode): Int = when (mode) {
        CarPlayNightMode.SYSTEM -> R.string.carplay_night_system
        CarPlayNightMode.AMBIENT -> R.string.carplay_night_ambient
        CarPlayNightMode.DAY -> R.string.carplay_night_day
        CarPlayNightMode.NIGHT -> R.string.carplay_night_night
    }


    /**
     * Opens every preference file this screen can reach while `onCreate` is still running.
     *
     * Measured on the car 2026-10-18 (CS11, Android 4.4.3 / API 19): the first `getSharedPreferences` call
     * for a file that is not loaded yet makes the framework's `SharedPreferencesImpl` start its own loader
     * thread, and on this ROM that thread construction fails —
     * `Caused by: java.lang.NullPointerException at java.lang.Thread.create(Thread.java:403)`
     * `at android.app.SharedPreferencesImpl.startLoadFromDisk(SharedPreferencesImpl.java:88)`. It is thrown
     * out of `onResume` (`startCarHotspotOnLaunch` -> `startupHotspotEligible`) and takes DiPlay with it.
     *
     * Creating the same threads during `onCreate` succeeds on this ROM (a probe of all ten construction
     * forms reported OK there), so warming the files up front keeps the later, fatal path on the cache.
     * This is a workaround for a firmware defect, not a design choice: see `docs/_digest/89`.
     */
    private fun warmUpPreferences() {
        for (name in PREFERENCE_FILES) {
            runCatching { getSharedPreferences(name, MODE_PRIVATE) }
                .onFailure { Log.w("DiPlay-Prefs", "warm up $name failed", it) }
        }
    }



    private fun hotspotSettingsControls(parent: LinearLayout) {
        if (AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) return
        // CS11/4.4: the hotspot is started through reflected setWifiApEnabled, which needs no ADB.
        val legacy = com.shilapi.xcertplay.network.LegacySoftApControl.supported()
        if (!legacy) {
            Log.i("DiPlay-Hotspot", "settings hidden: no car hotspot support detected")
            return
        }
        val controls = column().apply { visibility = View.GONE }
        hotspotControls = controls
        parent.addView(controls)
        hotspotCheckTarget = controls
        hotspotCheckLegacy = legacy
        // The thread is started from a plain method, not from inside this `section(...)` lambda.
        //
        // Measured on the car 2026-10-18 (CS11, Android 4.4 / Dalvik): constructing a Thread from inside an
        // inline lambda's body dies in `java.lang.Thread.create` with a NullPointerException and takes the
        // screen with it, while the same construction in an ordinary method is fine (every other thread in
        // this app is started that way and works). `docs/_digest/89` records the reproduction.
        startHotspotEligibilityCheck()
    }

    private var hotspotCheckTarget: LinearLayout? = null
    private var hotspotCheckLegacy = false

    private fun startHotspotEligibilityCheck() {
        com.shilapi.xcertplay.compat.WorkerThreads.start("diplay-hotspot-adb-check") { runHotspotEligibilityCheck() }
    }

    private fun runHotspotEligibilityCheck() {
        val controls = hotspotCheckTarget
        val legacy = hotspotCheckLegacy
        if (controls == null) return
        val access = runCatching { CarHotspotSetup.check(applicationContext) }
            .onFailure { Log.w("DiPlay-Hotspot", "settings ADB check failed", it) }
            .getOrDefault(LocalAdb.Access.UNREACHABLE)
        Log.i("DiPlay-Hotspot", "settings eligibility: legacy=$legacy adb=$access visible=${CarHotspotSettings.visible(access, legacy)}")
        runOnUiThread {
            if (hotspotControls !== controls || isFinishing || isDestroyed) return@runOnUiThread
            renderHotspotControls(controls, access, legacy)
        }
    }

    private fun renderHotspotControls(controls: LinearLayout, access: LocalAdb.Access, legacy: Boolean) {
        controls.removeAllViews()
        adbSwitches.keys.retainAll(setOf(R.string.open_after_the_car_starts))
        adbStatus = null
        controls.visibility = if (CarHotspotSettings.visible(access, legacy)) View.VISIBLE else View.GONE
        if (controls.visibility == View.GONE) return
        if (AirPlayPersistence.loadWirelessHotspotMode(this) != WirelessHotspotMode.MANUAL) {
            controls.visibility = View.GONE
            return
        }
        section(controls, getString(R.string.auto_car_hotspot_title), R.drawable.ic_dp_permissions) { card ->
            // Legacy firmware needs no ADB, so the toggle saves directly instead of requesting a grant.
            adbToggle(card, R.string.auto_car_hotspot_title, R.string.auto_car_hotspot_description,
                read = { CarHotspotSettings.enabled(this) },
                needsAdb = { !CarHotspotSetup.Permission.HOTSPOT.granted(this) },
                permissions = {
                    buildList {
                        add(CarHotspotSetup.Permission.HOTSPOT)
                        if (AirPlayPersistence.loadAutoStartOnBoot(this@DiPlayActivity)) {
                            add(CarHotspotSetup.Permission.BOOT_LAUNCH)
                        }
                    }
                }) {
                CarHotspotSettings.setEnabled(this, it)
                if (!it) startupHotspotCancelled = true
            }
        }
    }

    private fun adbToggle(parent: LinearLayout, title: Int, description: Int, read: () -> Boolean,
        needsAdb: () -> Boolean = { true },
        permissions: () -> List<CarHotspotSetup.Permission> = { emptyList() }, save: (Boolean) -> Unit) {
        val control = toggle(parent, getString(title), getString(description), read(),
            enabled = !adbSwitchChangePending) { enabled ->
            if (updatingAdbSwitches || adbSwitchChangePending) return@toggle
            if (enabled && needsAdb()) requestAdbSwitchChange(permissions()) { save(true) }
            else save(enabled)
        }
        adbSwitches[title] = control to read
    }

    private fun requestAdbSwitchChange(permissions: List<CarHotspotSetup.Permission>, save: () -> Unit) {
        if (adbSwitchChangePending) {
            updateAdbSwitches()
            return
        }
        val app = applicationContext
        adbSwitchChangePending = true
        updateAdbSwitches()
        adbStatus?.setText(R.string.adb_checking_may_ask)
        if (page == "settings") render()
        com.shilapi.xcertplay.compat.WorkerThreads.start("diplay-adb-grant") {
            var access = LocalAdb.Access.UNREACHABLE
            val ready = runCatching {
                access = CarHotspotSetup.grant(app, permissions)
                access == LocalAdb.Access.READY && permissions.all { it.granted(app) }
            }.getOrDefault(false)
            runOnUiThread {
                adbSwitchChangePending = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val message = if (ready) {
                    if (permissions.isEmpty()) R.string.adb_access_ready else R.string.hotspot_permission_granted
                } else when (access) {
                    LocalAdb.Access.NOT_APPROVED -> R.string.adb_not_approved
                    LocalAdb.Access.UNREACHABLE -> R.string.adb_off
                    LocalAdb.Access.UNSUPPORTED -> R.string.adb_pairing_only
                    else -> R.string.hotspot_permission_failed
                }
                adbStatus?.setText(if (ready) R.string.adb_access_ready else message)
                if (ready) save()
                updateAdbSwitches()
                toast(getString(message))
            }
        }
    }

    private fun updateAdbSwitches() {
        updatingAdbSwitches = true
        for ((control, read) in adbSwitches.values) {
            control.isChecked = read()
            control.isEnabled = !adbSwitchChangePending
        }
        updatingAdbSwitches = false
    }

    private fun startCarHotspotOnLaunch() {
        if (!startupHotspotEligible()) return
        val app = applicationContext
        com.shilapi.xcertplay.compat.WorkerThreads.start("diplay-hotspot-startup") {
            val result = CarHotspotTethering.enable(app,
                isCancelled = { startupHotspotCancelled || !startupHotspotEligible() },
                log = { Log.i("DiPlay-Hotspot", it) },
            )
            runOnUiThread {
                if (isFinishing || isDestroyed || result == CarHotspotTethering.Result.CANCELLED) return@runOnUiThread
                hotspotStartupResult = result
                if (page == "home") render()
            }
        }
    }

    private fun startupHotspotEligible(): Boolean =
        CarHotspotSetup.shouldStartOnLaunch(applicationContext, CarPlayBackgroundSession.hasSession(),
            allowCredentiallessStart = com.shilapi.xcertplay.network.LegacySoftApControl.supported())

    private fun hotspotResultText(result: CarHotspotTethering.Result): String = getString(when (result) {
        CarHotspotTethering.Result.READY -> R.string.hotspot_control_on
        CarHotspotTethering.Result.PERMISSION_REQUIRED -> R.string.hotspot_control_missing
        CarHotspotTethering.Result.UNSUPPORTED -> R.string.hotspot_control_unsupported
        CarHotspotTethering.Result.TIMED_OUT -> R.string.hotspot_control_timeout
        else -> R.string.hotspot_control_failed
    })

    private fun carHotspotOffDialog() {
        AlertDialog.Builder(this).setTitle(getString(R.string.car_hotspot_is_off))
            .setMessage(getString(R.string.msg_car_hotspot_connect, AirPlayPersistence.loadManualHotspotSsid(this)))
            .setPositiveButton(getString(R.string.open_car_settings)) { _, _ -> openCarWifiSettings() }
            .setNeutralButton(getString(R.string.connect)) { _, _ -> connect(true) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    // Some firmware shows the tether screen as a dialog and closes it unless the car home screen is on
    // top, so the home screen goes first.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        if (packageManager.resolveActivity(hotspot, 0) == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun openCarClientWifiSettings() {
        openSystem(Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    private fun connectionSetup(content: LinearLayout) {
        content.addView(label(getString(R.string.connection_setup), 34, TEXT, true))
        content.addView(label(getString(R.string.set_up_once_your_details_stay_saved_for_the_next_drive_cha), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, getString(R.string.s_1_choose_your_connection)) { card -> wirelessLinkControls(card) }
        section(content, getString(R.string.s_2_pair_your_iphone)) { card ->
            card.addView(label(getString(R.string.keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car), 16, MUTED))
            card.addView(button("${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}", false) { choosePhone() }, matchButton(12, 60))
            card.addView(button(getString(R.string.review_app_permissions), false) {
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, matchButton(12, 60))
        }
        section(content, getString(R.string.s_3_connect)) { card ->
            card.addView(label(getString(R.string.return_from_car_settings_to_diplay_then_connect_accept_the), 16, MUTED))
            card.addView(button(getString(R.string.connect_phone), true) { connect(true) }, matchButton(12, 60))
        }
        section(content, getString(R.string.prefer_a_cable)) { card ->
            card.addView(label(getString(R.string.use_a_usb_data_cable_and_the_car_s_usb_data_port_unlock_yo), 16, MUTED))
            card.addView(button(getString(R.string.connect_with_usb), false) { connect(false) }, matchButton(12, 60))
        }
    }

    private fun wirelessLinkControls(parent: LinearLayout) {
        val mode = AirPlayPersistence.loadWirelessHotspotMode(this)
        val modes = listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.EXISTING_WIFI)
        val titles = listOf(getString(R.string.built_in_car_hotspot), getString(R.string.wifi_direct), getString(R.string.existing_wifi_title))
        val descriptions = listOf(
            getString(R.string.hotspot_mode_manual_desc),
            getString(R.string.hotspot_mode_p2p_desc),
            getString(R.string.existing_wifi_description)
        )
        val wide = resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row().apply { gravity = Gravity.TOP } else column()
        parent.addView(choices)
        modes.forEachIndexed { index, candidate ->
            val option = column()
            choices.addView(option, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(16)
            } else LinearLayout.LayoutParams(-1, -2))
            option.addView(button("${if (mode == candidate) "✓  " else ""}${titles[index]}", mode == candidate) {
                // Manual mode needs nothing typed any more: the SSID and passphrase are read out of the
                // firmware's AP configuration. The only other mode with input is existing-Wi-Fi, whose
                // credentials belong to a router DiPlay has no way to discover.
                if (candidate == WirelessHotspotMode.EXISTING_WIFI) {
                    askHotspotCredentials(existingWifi = true) { ssid, password ->
                        AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                        applyWirelessLink(candidate)
                    }
                } else {
                    applyWirelessLink(candidate)
                }
            }, matchButton(12, 60))
            option.addView(label(descriptions[index], 15, MUTED).apply { setPadding(0, dp(6), 0, dp(12)) })
        }
        if (mode == WirelessHotspotMode.MANUAL) {
            val storedInCar = carHotspotConfiguration()
            parent.addView(label(getString(R.string.hotspot_setup), 22, TEXT, true))
            // The SSID and passphrase are read out of the firmware's own AP configuration and pushed to the
            // iPhone over Bluetooth, so step 2 of the old instructions ("copy the name and password in below")
            // no longer exists and the edit button that asked for them is gone (docs/_digest/84).
            parent.addView(
                label(getString(R.string.s_1_open_car_hotspot_settings_turn_the_hotspot_on_and_sele), 16, MUTED)
                    .apply { setPadding(0, dp(8), 0, dp(12)) },
            )
            parent.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(0, 60))
            parent.addView(
                label(
                    when {
                        storedInCar != null -> getString(R.string.hotspot_details_read_from_car, storedInCar.ssid)
                        carHotspotOff() -> getString(R.string.hotspot_details_off)
                        else -> getString(R.string.turn_on_the_car_hotspot_in_the_car_settings_then_connect_again)
                    },
                    15,
                    if (storedInCar != null) MUTED else WARNING,
                ).apply { setPadding(0, dp(12), 0, 0) },
            )
        } else if (mode == WirelessHotspotMode.EXISTING_WIFI) {
            parent.addView(label(getString(R.string.existing_wifi_instructions), 16, MUTED))
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
            parent.addView(button(getString(R.string.existing_wifi_details), false) {
                askHotspotCredentials(existingWifi = true) { ssid, password ->
                    AirPlayPersistence.saveExistingWifiCredentials(this, ssid, password)
                    toast(getString(R.string.saved_for_your_next_connection))
                }
            }, matchButton(12, 60))
        } else {
            parent.addView(label(getString(R.string.turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic), 16, MUTED))
            wifiDirectChannelControl(parent)
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
        }
    }

    private fun wifiDirectChannelLabel(channel: Int): String = if (channel == WifiP2pChannels.AUTO) {
        getString(R.string.auto)
    } else {
        getString(R.string.wifi_direct_channel_choice, channel,
            getString(if (channel < 36) R.string.s_2_4_ghz else R.string.s_5_ghz))
    }

    private fun wifiDirectChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.wifi_direct_channel_summary, wifiDirectChannelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadWifiP2pPreferredChannel(this)), false) {}
        control.setOnClickListener {
            val choices = listOf(WifiP2pChannels.AUTO) + WifiP2pChannels.channels
            val current = AirPlayPersistence.loadWifiP2pPreferredChannel(this)
            var selection = current
            AlertDialog.Builder(this).setTitle(R.string.wifi_direct_channel_title)
                .setSingleChoiceItems(choices.map(::wifiDirectChannelLabel).toTypedArray(),
                    choices.indexOf(current)) { _, which -> selection = choices[which] }
                .setPositiveButton(R.string.save) { _, _ ->
                    if (selection != current) {
                        AirPlayPersistence.saveWifiP2pPreferredChannel(this, selection)
                        control.text = summary(selection)
                        toast(getString(R.string.saved_for_your_next_connection))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        parent.addView(control, matchButton(12, 60))
        parent.addView(label(getString(R.string.wifi_direct_channel_description), 15, MUTED).apply {
            setPadding(0, dp(6), 0, dp(12))
        })
    }

    private fun mediaChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.contrib_audio_home_choice_summary, getString(R.string.contrib_audio_home_media_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadMediaAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadMediaAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_media_channel_label),
                current = current,
                navigation = false,
                onApply = { value -> applyMediaChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(12, 60))
    }

    private fun navigationChannelControl(parent: LinearLayout) {
        val summary: (Int) -> String = {
            getString(R.string.contrib_audio_home_choice_summary, getString(R.string.contrib_audio_home_nav_channel_label), channelLabel(it))
        }
        val control = button(summary(AirPlayPersistence.loadNavigationAudioChannel(this)), false) {}
        control.setOnClickListener {
            val current = AirPlayPersistence.loadNavigationAudioChannel(this)
            showChannelDialog(
                title = getString(R.string.contrib_audio_home_nav_channel_label),
                current = current,
                navigation = true,
                onApply = { value -> applyNavigationChannel(value, current, control, summary) },
            )
        }
        parent.addView(control, matchButton(10, 60))
        parent.addView(label(getString(R.string.contrib_audio_home_nav_channel_note), 14, MUTED).apply {
            setPadding(0, dp(8), 0, dp(18))
        })
    }

    private fun showChannelDialog(title: String, current: Int, navigation: Boolean, onApply: (Int) -> Unit) {
        val preview = AudioChannelPreview { channel ->
            toast(getString(R.string.contrib_audio_home_channel_preview_unavailable, channel))
        }
        val channels = AirPlayPersistence.AUDIO_CHANNELS
        val labels = channels.map(Int::toString).toTypedArray()
        var selection = current.coerceIn(channels.first, channels.last)
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels, selection) { _, which ->
                selection = which
                preview.play(which, navigation)
            }
            .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) getString(R.string.apply_and_reconnect) else getString(R.string.save)) { _, _ ->
                onApply(selection)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setOnDismissListener { preview.close() }
            .show()
    }

    private fun applyMediaChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveMediaAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyNavigationChannel(value: Int, previous: Int, control: Button, summary: (Int) -> String) {
        if (value == previous) return
        AirPlayPersistence.saveNavigationAudioChannel(this, value)
        control.text = summary(value)
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun channelLabel(value: Int): String = value.toString()

    /**
     * 音频解码器选择（docs/_digest/79 A1 的设置项）。
     *
     * 平台码始终可选；Freescale 那项**先探测再显示**——厂商库不在这台车机上、或入口符号缺失时，
     * 选项显示为不可用并附上原因，而不是让用户选中一个注定失败的开关。
     * 判定依据是 `FreescaleAacBridge`：shim 加载 → `/system/lib` 的 `dlopen` → `aacd_query_dec_mem` 返回 0。
     */
    private fun audioDecoderControl(card: android.widget.LinearLayout) {
        val ready = com.shilapi.xcertplay.media.FreescaleAacBridge.isAvailable()
        val freescaleLabel = if (ready) {
            getString(R.string.audio_decoder_freescale)
        } else {
            getString(
                R.string.audio_decoder_freescale,
            ) + " · " + getString(
                R.string.audio_decoder_unavailable,
                com.shilapi.xcertplay.media.FreescaleAacBridge.reason(),
            )
        }
        // 顺序与 AudioDecoderChoice 的 ordinal 对齐（MEDIACODEC=0, FREESCALE=1）。
        val options = listOf(getString(R.string.audio_decoder_platform), freescaleLabel)
        val current = AirPlayPersistence.loadAudioDecoder(this).ordinal.coerceIn(0, options.lastIndex)
        choice(
            card,
            getString(R.string.audio_decoder),
            options,
            current,
            // 解码器在下次建流时才生效，所以要重连才有意义。
            reconnects = true,
            // 厂商解码器不具备条件时不让选，避免用户以为切换失败是 bug。
            enabled = ready || current == 0,
        ) { index ->
            AirPlayPersistence.saveAudioDecoder(
                this,
                com.shilapi.xcertplay.media.AudioDecoderChoice.entries[
                    index.coerceIn(0, com.shilapi.xcertplay.media.AudioDecoderChoice.entries.lastIndex)
                ],
            )
        }
        card.addView(label(getString(R.string.audio_decoder_description), 13, MUTED))
    }

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { getString(it.messageResource()) }

    /**
     * The same check against the owner's saved form, which is the fallback only.
     *
     * The primary source is the firmware's own AP configuration ([carHotspotConfiguration]); this exists so a
     * head unit whose AP configuration cannot be read still has the older typed-in path, and so a genuinely
     * broken saved pair is reported instead of failing later inside the controller.
     */
    private fun manualHotspotError(): String? = hotspotError(storedSsid(), storedPassword())

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password))
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
    }

    private fun askHotspotCredentials(existingWifi: Boolean = false, done: (String, String) -> Unit) {

        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fields.addView(label(getString(if (existingWifi) R.string.existing_wifi_instructions else R.string.copy_these_from_the_car_s_hotspot_settings_use_5_ghz_if_av), 16, MUTED))
        // CS11/4.4: offer what the car itself stores, so the fields never open blank on that firmware.
        val storedInCar = if (existingWifi) null else com.shilapi.xcertplay.network.LegacySoftApControl.readUsableStored(this)
        val ssid = EditText(this).apply {
            hint = getString(if (existingWifi) R.string.existing_wifi_ssid else R.string.hotspot_name)
            setText(if (existingWifi) AirPlayPersistence.loadExistingWifiSsid(this@DiPlayActivity)
                else storedSsid().ifEmpty { storedInCar?.ssid.orEmpty() })
            setSingleLine()
        }
        val password = EditText(this).apply {
            hint = getString(if (existingWifi) R.string.existing_wifi_password else R.string.hotspot_password)
            setText(if (existingWifi) AirPlayPersistence.loadExistingWifiPassphrase(this@DiPlayActivity)
                else storedPassword().ifEmpty { storedInCar?.password.orEmpty() })
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ssid.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        fun hideKeyboard() {
            val token = password.windowToken ?: ssid.windowToken
            (this.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
            ssid.clearFocus(); password.clearFocus()
        }
        ssid.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
        }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        fields.addView(ssid); fields.addView(password)
        fields.addView(CheckBox(this).apply {
            text = getString(R.string.show_password)
            setOnCheckedChangeListener { _, checked ->
                password.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        })
        val error = label("", 14, WARNING)
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        fields.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle(getString(if (existingWifi) R.string.existing_wifi_details else R.string.car_hotspot_details))
            .setView(ScrollView(this).apply { addView(fields) })
            .setPositiveButton(getString(R.string.save_details), null).setNegativeButton(getString(R.string.cancel)) { _, _ -> hideKeyboard() }
            .setNeutralButton(getString(R.string.hide_keyboard), null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = if (existingWifi) ssid.text.toString() else ssid.text.toString().trim()

                val secret = password.text.toString()
                val problem = hotspotError(name, secret)
                if (problem != null) error.text = problem
                else { hideKeyboard(); dialog.dismiss(); done(name, secret) }
            }
        }
        dialog.show()
    }

    // "Left 20 %", "Centre · default", "Down 10 %": a signed step reads as a direction and a distance.

    private fun promptEnableUsbAutoConfirm() {
        if (UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)) {
            toast(getString(R.string.usb_auto_confirm_status_on))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.usb_auto_confirm_title))
            .setMessage(getString(R.string.usb_auto_confirm_dialog_msg))
            .setPositiveButton(getString(R.string.btn_auto_apply_permissions)) { _, _ ->
                autoApplyPermissions()
            }
            .setNeutralButton(getString(R.string.btn_open_accessibility_setting)) { _, _ ->
                if (!UsbAutoConfirmService.openSettings(this)) {
                    toast(getString(R.string.wheel_keys_no_settings))
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                render()
            }
            .setOnCancelListener {
                render()
            }
            .show()
    }

    private fun cancelUsbPermissionSetup() {
        val operation = usbPermissionOperation
        usbPermissionOperation = null
        operation?.cancel()
        val dialog = usbPermissionDialog
        usbPermissionDialog = null
        dialog?.dismiss()
    }

    private fun autoApplyPermissions() {
        if (usbPermissionOperation != null || isFinishing || isDestroyed) return
        val operation = usbPermissionOperationFactory(applicationContext)
        usbPermissionOperation = operation
        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.auto_grant_title)
            .setMessage(R.string.auto_grant_msg)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelUsbPermissionSetup() }
            .create()
        usbPermissionDialog = progress
        progress.setOnCancelListener { cancelUsbPermissionSetup() }
        progress.setOnDismissListener {
            if (usbPermissionDialog === progress) cancelUsbPermissionSetup()
        }
        progress.show()
        com.shilapi.xcertplay.compat.WorkerThreads.start("diplay-auto-permission") {
            val result = operation.run()
            handler.post {
                if (usbPermissionOperation !== operation || operation.isCancelled || isFinishing || isDestroyed) {
                    return@post
                }
                usbPermissionOperation = null
                usbPermissionDialog = null
                progress.dismiss()
                render()
                if (result.complete) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.auto_grant_success_title)
                        .setMessage(R.string.auto_grant_success_msg)
                        .setPositiveButton(R.string.close, null)
                        .show()
                } else {
                    val reason = when (result.access) {
                        LocalAdb.Access.NOT_APPROVED -> getString(R.string.auto_grant_confirm_msg)
                        LocalAdb.Access.UNSUPPORTED -> getString(R.string.adb_pairing_only)
                        else -> getString(R.string.auto_grant_incomplete)
                    }
                    showManualPermissionDialog(reason)
                }
            }
        }
    }

    private fun showManualPermissionDialog(reason: String) {
        val body = column().apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }
        body.addView(label(reason, 15, MUTED).apply { setPadding(0, 0, 0, dp(12)) })

        val autoConfirmOn = UsbPermissionSetup.Permission.ACCESSIBILITY.granted(this)
        body.addView(button(if (autoConfirmOn) getString(R.string.usb_auto_confirm_status_on) else getString(R.string.btn_open_accessibility_setting), false) {
            if (!UsbAutoConfirmService.openSettings(this)) toast(getString(R.string.wheel_keys_no_settings))
        }, matchButton(0, 56))

        val overlayOn = CenterMapOverlay.permitted(this)
        body.addView(button(if (overlayOn) getString(R.string.center_map_overlay_allowed) else getString(R.string.btn_open_overlay_setting), false) {
            openOverlayPermission()
        }, matchButton(10, 56))

        UsbPermissionSetup.snapshot(this).forEach { (permission, granted) ->
            val title = getString(when (permission) {
                UsbPermissionSetup.Permission.ACCESSIBILITY -> R.string.usb_auto_confirm_title
                UsbPermissionSetup.Permission.USAGE -> R.string.center_map_auto_hide
                UsbPermissionSetup.Permission.OVERLAY -> R.string.btn_open_overlay_setting
            })
            body.addView(label("$title: ${getString(if (granted) R.string.permission_enabled_ready else R.string.permission_not_enabled)}", 14, if (granted) MUTED else WARNING))
        }
        val adbCmd = UsbPermissionSetup.manualCommand(packageName)
        body.addView(label(getString(R.string.manual_grant_cmd_hint), 14, MUTED).apply { setPadding(0, dp(12), 0, dp(6)) })
        body.addView(label(adbCmd, 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0x22FFFFFF)
        })
        body.addView(button(getString(R.string.copy_command), false) {
            systemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                android.content.ClipData.newPlainText("DiPlay ADB Command", adbCmd)
            )
            toast(getString(R.string.copied_to_the_car_clipboard_run_the_command_on_your_comput))
        }, matchButton(8, 50))

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permissions_and_connection_help))
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(getString(R.string.close)) { _, _ -> render() }
            .show()
    }

    /** Steering-wheel keys for the dashboard map zoom and the CarPlay joystick: the switches, the key service and the keys. */
    private fun wheelKeyControls(card: LinearLayout) {
        toggle(card, getString(R.string.wheel_map_zoom), getString(R.string.wheel_map_zoom_description),
            WheelZoomSettings.enabled(this)) {
            WheelZoomSettings.setEnabled(this, it)
            render()
        }
        toggle(card, getString(R.string.wheel_joystick), getString(R.string.wheel_joystick_description),
            WheelZoomSettings.joystick(this)) {
            WheelZoomSettings.setJoystick(this, it)
            render()
        }
        val zoom = WheelZoomSettings.enabled(this)
        val joystick = WheelZoomSettings.joystick(this)
        if (!zoom && !joystick) return
        val connected = WheelKeyService.connected()
        card.addView(label(getString(when {
            connected -> R.string.wheel_keys_service_on
            WheelKeyService.enabledInSettings(this) -> R.string.wheel_keys_service_starting
            else -> R.string.wheel_keys_service_off
        }), 14, if (connected) MUTED else WARNING))
        if (!connected) {
            card.addView(button(getString(R.string.wheel_keys_enable_adb), false) {
                com.shilapi.xcertplay.compat.WorkerThreads.start("diplay-wheel-keys-enable") {
                    val access = WheelKeyService.enableOverAdb(this)
                    runOnUiThread {
                        if (access != com.shilapi.xcertplay.adb.LocalAdb.Access.READY) {
                            toast(getString(R.string.wheel_keys_adb_failed, access.name))
                        }
                        render()
                    }
                }
            }, matchButton(10, 56))
            card.addView(button(getString(R.string.wheel_keys_open_settings), false) {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .onFailure { toast(getString(R.string.wheel_keys_no_settings)) }
            }, matchButton(10, 56))
        }
        if (zoom) {
            val behaviours = WheelZoomSettings.Behaviour.entries
            choice(card, getString(R.string.wheel_zoom_behaviour),
                listOf(getString(R.string.wheel_zoom_behaviour_toggle), getString(R.string.wheel_zoom_behaviour_timed)),
                behaviours.indexOf(WheelZoomSettings.behaviour(this)), reconnects = false) {
                WheelZoomSettings.setBehaviour(this, behaviours[it])
            }
        }
        if (joystick) {
            toggle(card, getString(R.string.wheel_joystick_auto_off), getString(R.string.wheel_joystick_auto_off_description),
                WheelZoomSettings.joystickAutoOff(this)) { WheelZoomSettings.setJoystickAutoOff(this, it) }
        }
        for (role in WheelZoomSettings.Role.entries) {
            // A key can serve both: the mode key goes back in the joystick, the zoom keys move it.
            val zoomName = when (role) {
                WheelZoomSettings.Role.MODE -> R.string.wheel_key_role_mode
                WheelZoomSettings.Role.ZOOM_IN -> R.string.wheel_key_role_zoom_in
                WheelZoomSettings.Role.ZOOM_OUT -> R.string.wheel_key_role_zoom_out
                else -> null
            }.takeIf { zoom }
            val joystickName = when (role) {
                WheelZoomSettings.Role.MODE -> R.string.wheel_key_role_back
                WheelZoomSettings.Role.ZOOM_IN, WheelZoomSettings.Role.PREVIOUS -> R.string.wheel_key_role_previous
                WheelZoomSettings.Role.ZOOM_OUT, WheelZoomSettings.Role.NEXT -> R.string.wheel_key_role_next
                WheelZoomSettings.Role.JOYSTICK -> R.string.wheel_key_role_joystick
                WheelZoomSettings.Role.SELECT -> R.string.wheel_key_role_select
            }.takeIf { joystick }
            val names = listOfNotNull(zoomName, joystickName)
            if (names.isEmpty()) continue
            val name = names.joinToString(" · ") { getString(it) }
            lateinit var assign: android.widget.Button
            assign = button(getString(R.string.wheel_key_assign, name, WheelZoomSettings.key(this, role).toString()), false) {
                val started = WheelKeyService.learn(role, cancelled = {
                    runOnUiThread { assign.text = getString(R.string.wheel_key_assign, name, WheelZoomSettings.key(this, role).toString()) }
                }) { _, key ->
                    runOnUiThread { assign.text = getString(R.string.wheel_key_assign, name, key.toString()) }
                }
                if (started) assign.text = getString(R.string.wheel_key_press, name)
                else toast(getString(R.string.wheel_keys_service_off))
            }
            card.addView(assign, matchButton(10, 56))
        }
    }

    private fun hasPreciseLocation() =
        hasPermissionCompat(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun reconnectForClusterMap() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun onLocationReportingChanged(enabled: Boolean) {
        if (enabled && !hasPreciseLocation()) {
            // Keep the switch off until precise location is actually granted.
            render()
            locationPermission.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
            return
        }
        applyLocationReporting(enabled)
    }

    private fun applyLocationReporting(enabled: Boolean) {
        if (AirPlayPersistence.loadLocationReportingEnabled(this) == enabled) return
        AirPlayPersistence.saveLocationReportingEnabled(this, enabled)
        render()
        // Location support is advertised during iAP2 identification, so both enabling and
        // disabling it require a new session. The host also refreshes its location service type.
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
        else toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        startupHotspotCancelled = true
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resolutionSettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val title = getString(titleId)
        fun summary() = getString(R.string.contrib_audio_home_choice_summary, title, getString(summaryId, load()))
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(this).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(load().toString())
            }
            fields.addView(input)
            fields.addView(label(getString(hintId), 14, MUTED))
            val dialog = AlertDialog.Builder(this).setTitle(title).setView(fields)
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.resolution_reset_defaults), null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = getString(R.string.resolution_number_error, range.first, range.last)
                    } else {
                        val changed = value != load()
                        save(value)
                        control.text = summary()
                        dialog.dismiss()
                        if (changed && reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(default.toString())
                    input.error = null
                }
            }
            showResolutionSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showResolutionSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        dialog.show()
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    private fun nightDelaySettingControl(
        parent: LinearLayout,
        titleId: Int,
        hintId: Int,
        range: IntRange,
        default: Int,
        summaryId: Int,
        load: () -> Int,
        reconnects: Boolean = false,
        save: (Int) -> Unit,
    ) {
        val title = getString(titleId)
        fun summary() = getString(R.string.contrib_audio_home_choice_summary, title, getString(summaryId, load()))
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            val input = EditText(this).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(load().toString())
            }
            fields.addView(input)
            fields.addView(label(getString(hintId), 14, MUTED))
            val dialog = AlertDialog.Builder(this).setTitle(title).setView(fields)
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.ambient_light_reset_defaults), null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim().toIntOrNull()
                    if (value == null || value !in range) {
                        input.error = getString(R.string.custom_number_error, range.first, range.last)
                    } else {
                        val changed = value != load()
                        save(value)
                        control.text = summary()
                        dialog.dismiss()
                        if (changed && reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(default.toString())
                    input.error = null
                }
            }
            showNightModeSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun ambientLightThresholdControl(parent: LinearLayout) {
        val title = getString(R.string.ambient_light_threshold_title)
        fun summary(): String = getString(
            R.string.contrib_audio_home_choice_summary,
            title,
            getString(R.string.ambient_light_threshold_summary, AirPlayPersistence.loadAmbientLightThreshold(this).lux),
        )

        val control = button(summary(), false) {}
        control.setOnClickListener {
            val fields = column().apply { setPadding(dp(24), dp(8), dp(24), dp(8)) }
            fields.addView(label(getString(R.string.ambient_light_threshold_value), 16, MUTED))
            val input = EditText(this).apply {
                setSingleLine()
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(AirPlayPersistence.loadAmbientLightThreshold(this@DiPlayActivity).lux.toString())
            }
            fields.addView(input)
            fields.addView(label(getString(R.string.ambient_light_threshold_hint), 14, MUTED))
            val dialog = AlertDialog.Builder(this)
                .setTitle(title)
                .setView(fields)
                .setPositiveButton(getString(R.string.save), null)
                .setNegativeButton(getString(R.string.cancel), null)
                .setNeutralButton(getString(R.string.ambient_light_reset_defaults), null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener saveThreshold@{
                    val lux = input.text.toString().trim().toIntOrNull()
                    if (lux == null || !AmbientLightThreshold.isValid(lux)) {
                        input.error = getString(R.string.ambient_light_threshold_error)
                        return@saveThreshold
                    }
                    AirPlayPersistence.saveAmbientLightThreshold(this, AmbientLightThreshold(lux))
                    control.text = summary()
                    dialog.dismiss()
                }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    input.setText(AmbientLightThreshold.DEFAULT_LUX.toString())
                    input.error = null
                }
            }
            showNightModeSettingsDialog(dialog, fields)
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(space(12))
    }

    private fun showNightModeSettingsDialog(dialog: AlertDialog, fields: LinearLayout) {
        dialog.show()
        // AlertDialog replaces the custom view's parameters with MATCH_PARENT. Keep numeric
        // content at its natural height, including on vendor dialog layouts with weighted panels.
        fields.layoutParams = fields.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        val decor = dialog.window?.decorView ?: return
        var panel = fields.parent as? ViewGroup
        while (panel != null && panel !== decor) {
            val params = panel.layoutParams
            if (params is LinearLayout.LayoutParams && params.weight > 0f) {
                panel.layoutParams = params.apply {
                    weight = 0f
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                break
            }
            panel = panel.parent as? ViewGroup
        }
        dialog.window?.let { window ->
            window.setLayout(window.attributes.width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Run another traversal after the platform has finished its initial button measurement.
        decor.post { if (dialog.isShowing) decor.requestLayout() }
    }

    /** Where CarPlay puts its dock; between the two fixed edges it moves at once, otherwise on reconnect. */
    private fun carPlayDockControl(card: LinearLayout) {
        val docks = CarPlayDock.entries
        choice(card, getString(R.string.carplay_dock), listOf(
            getString(R.string.carplay_dock_automatic),
            getString(R.string.carplay_dock_driver_side),
            getString(R.string.carplay_dock_bottom),
        ), docks.indexOf(CarPlayDock.load(this)), reconnects = false) {
            val from = CarPlayDock.load(this)
            val to = docks[it]
            CarPlayDock.save(this, to)
            val session = CarPlayBackgroundSession.snapshot()
            val areas = session?.display?.viewAreas
            val target = to.edge?.let { areas?.withDock(it) }
            if (CarPlayDock.movesLive(from, to) && areas != null && target != null) {
                if (session.controller.showViewArea(target)) areas.use(target)
            } else {
                reconnectForClusterMap()
            }
        }
        card.addView(label(getString(R.string.carplay_dock_hint), 14, MUTED).apply { setPadding(0, 0, 0, dp(18)) })
    }

    private fun carButtonControls(parent: LinearLayout) {
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)?.let { BitmapFactory.decodeFile(it.absolutePath) }
        val preview = row().apply { gravity = Gravity.CENTER_VERTICAL }
        preview.addView(ImageView(this).apply {
            setImageBitmap(custom ?: BitmapFactory.decodeResource(resources, R.raw.ic_car_home))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(SURFACE, BORDER)
            if (Build.VERSION.SDK_INT >= 21) clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(16) })
        val text = column()
        text.addView(label(getString(R.string.car_button_icon), 18, TEXT, true))
        text.addView(label(getString(if (custom != null) R.string.car_button_icon_custom else R.string.default_icon), 14, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        preview.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(preview)
        parent.addView(button(getString(R.string.choose_image), false) {
            runCatching { iconPicker.launch("image/*") }.onFailure { toast(getString(R.string.this_head_unit_has_no_image_picker)) }
        }, matchButton(16, 60))
        if (custom != null) parent.addView(button(getString(R.string.default_icon), false) {
            AirPlayPersistence.clearCustomAirPlayIcon(this)
            refreshCarButton()
            carButtonSaved()
        }, matchButton(10, 60))
        val name = AirPlayPersistence.loadOemLabel(this)
        parent.addView(button("${getString(R.string.car_button_name)} · $name", false) {
            textInput(getString(R.string.car_button_name), name, secret = false) {
                AirPlayPersistence.saveOemLabel(this, it)
                refreshCarButton()
                carButtonSaved()
            }
        }, matchButton(10, 60))
        parent.addView(label(getString(R.string.car_button_description), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
    }

    private fun carButtonSaved() {
        if (CarPlayBackgroundSession.hasSession()) toast(getString(R.string.car_button_saved_next_connection))
    }

    // Rebuilds only this card: render() would scroll the page back to the top.
    private fun refreshCarButton() {
        val card = carButtonCard ?: return
        card.removeViews(1, card.childCount - 1)
        carButtonControls(card)
    }

    private fun carPlaySizeControl(parent: LinearLayout) {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val current = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        choice(parent, getString(R.string.carplay_size), sizes.map { it.localizedLabel(this) }, sizes.indexOf(current)) {
            AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
        }
        parent.addView(label(getString(R.string.changes_the_size_of_carplay_icons_and_text_applying_a_size), 14, MUTED).apply {
            setPadding(0, 0, 0, dp(18))
        })
    }

    private fun connect(wireless: Boolean) {
        startupHotspotCancelled = true
        if (setupError != null) { toast(setupError!!); return }
        // CS11/4.4: the hotspot's SSID and passphrase are read out of the firmware's own AP configuration
        // (docs/_digest/84), so there is nothing for the owner to type. What can still block a connection is
        // the car having no usable hotspot configuration at all, and that is what this checks - the saved
        // form is only a fallback for a unit whose AP configuration cannot be read.
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            !carHotspotSelfConfigured() &&
            manualHotspotError() != null) {
            // Fallback path only: the firmware had no readable AP configuration, so the owner's saved form
            // is all there is and it has to be valid. The dialog is the same one the mode used to show.
            askHotspotCredentials { ssid, password ->
                saveHotspotCredentials(ssid, password)
                connect(true)
            }
            return
        }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = systemServiceOrNull(BluetoothManager::class.java)?.adapter ?: android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            AlertDialog.Builder(this).setTitle(getString(R.string.turn_on_bluetooth))
                .setMessage(getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.later), null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setTitle(getString(R.string.pair_your_iphone))
                .setMessage(getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.got_it), null).show(); return
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.choose_your_iphone))
            .setItems(devices.map { device ->
                val name = device.name ?: getString(R.string.paired_device)
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton(getString(R.string.pair_another)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }.show()
    }

    private fun wirelessHelp() {
        AlertDialog.Builder(this).setTitle(getString(R.string.wireless_connection_help))
            .setMessage(getString(R.string.pair_your_iphone_with_the_car_s_bluetooth_keep_wi_fi_on_an))
            .setPositiveButton(getString(R.string.got_it), null)
            .setNeutralButton(getString(R.string.reset_carplay_wi_fi)) { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        AlertDialog.Builder(this).setTitle(getString(R.string.reset_carplay_wi_fi_2))
            .setMessage(getString(R.string.this_ends_the_existing_wi_fi_direct_connection_including_o))
            .setPositiveButton(getString(R.string.reset_and_connect)) { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    // CS11: WifiP2pManager.Channel.close 是 API27+；低版本无法关闭（句柄随进程释放）
    private fun closeP2pChannel(channel: android.net.wifi.p2p.WifiP2pManager.Channel) {
        if (Build.VERSION.SDK_INT >= 27) channel.close()
    }

    private fun resetWirelessGroup() {
        val manager = systemServiceOrNull(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast(getString(R.string.this_head_unit_does_not_support_wi_fi_direct)); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { closeP2pChannel(channel); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { closeP2pChannel(channel); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        closeP2pChannel(channel); toast(getString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { closeP2pChannel(channel); toast(getString(R.string.could_not_reset_wi_fi_direct_close_the_other_projection_ap)) }
                })
            }
        } catch (_: SecurityException) {
            closeP2pChannel(channel); permissionHelp(getString(R.string.wireless_permissions), getString(R.string.allow_nearby_devices_and_on_older_android_versions_locatio))
        }
    }

    private fun refreshStatus() {
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            setupError != null -> getString(R.string.setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            running -> getString(R.string.connecting_to_your_iphone)
            DiPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${DiPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)
        }
        if (lastRunning != running) {
            connectButton?.text = if (running) getString(R.string.open_carplay) else getString(R.string.connect_phone)
            disconnectButton?.visibility = if (running) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
        }
        connectButton?.isEnabled = setupError == null
    }

    private fun reportFileName() = "DiPlay-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        if (exportInProgress) return
        runCatching { export.launch(reportFileName()) }.onFailure { exportDiagnostics() }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = getString(R.string.saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        com.shilapi.xcertplay.compat.WorkerThreads.start("diplay-export") {
            val result = runCatching {
                val report = buildString {
                    appendLine("DiPlay ${version()} · private beta diagnostic report")
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
                    appendLine("Authentication: local experimental beta identity; no remote fallback")
                    appendLine("CarPlay setup: ${if (setupError == null) "ready" else "authentication unavailable"}")
                    appendLine("Saved video preference (may differ from active session): H.264; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScalePercent(appContext)}%")
                    appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
                    appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
                    appendLine()
                    appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    appendLine("--- Last received boot and app-launch result ---")
                    appendLine(StartupDiagnosticSnapshot.report(appContext))
                    appendLine("Startup settings: openAfterBoot=${AirPlayPersistence.loadAutoStartOnBoot(appContext)} " +
                        "connectWhenOpened=${DiPlayPreferences.autoConnect(appContext)}")
                    appendLine()
                    appendLine("--- Recent own-app process exits (Android 11+) ---")
                    appendLine(ProcessExitDiagnostics.report(appContext))
                    appendLine()
                    for (name in SessionLogFile.REPORT_NAMES) {
                        val file = File(appContext.filesDir, "logs/$name")
                        if (file.isFile) {
                            appendLine("--- $name ---")
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                val savedReport = if (uri != null) {
                    DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                    DiagnosticExportStore.SavedReport(uri)
                } else DiagnosticExportStore.saveWithoutPicker(appContext, fileName, report)
                savedReport to report
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
                if (result.isSuccess) {
                    val (savedReport, report) = result.getOrThrow()
                    AlertDialog.Builder(this).setTitle(getString(R.string.diagnostic_report_saved))
                        .setMessage(when {
                            savedReport.savedInApp -> getString(R.string.diagnostic_report_saved_in_app)
                            savedReport.savedPath != null -> getString(R.string.diagnostic_report_saved_to_path, savedReport.savedPath)
                            uri == null -> "Downloads/DiPlay/$fileName"
                            else -> getString(R.string.your_report_was_saved_to_the_selected_location)
                        })
                        .setPositiveButton(getString(R.string.view_diagnostic_report)) { _, _ -> showDiagnosticReport(report) }
                        .setNegativeButton(getString(R.string.done), null)
                        .setNeutralButton(getString(R.string.share)) { _, _ ->
                            runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedReport.uri)
                                    clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), savedReport.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, getString(R.string.share_diagnostic_report)))
                            }.onFailure { showDiagnosticReport(report) }
                        }.show()
                } else {
                    AlertDialog.Builder(this).setTitle(getString(R.string.could_not_save_the_report))
                        .setMessage(getString(R.string.check_that_storage_is_available_or_choose_another_save_loc))
                        .setPositiveButton(getString(R.string.choose_location)) { _, _ -> chooseReportDestination() }
                        .setNegativeButton(getString(R.string.close), null).show()
                }
            }
        }
    }

    private fun showDiagnosticReport(report: String) {
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.diagnostic_report_copy_hint), 14, MUTED))
        body.addView(label(report, 13, TEXT).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        })
        AlertDialog.Builder(this).setTitle(getString(R.string.view_diagnostic_report))
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(getString(R.string.close), null).show()
    }
    private fun permissionHelp(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(getString(R.string.app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(getString(R.string.later), null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(getString(R.string.open_this_setting_from_your_car_s_settings_app)) } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun playTestTone(streamType: Int) {
        toneStop?.let { handler.removeCallbacks(it) }
        toneStop = null
        testToneTrack?.let { runCatching { it.stop(); it.release() } }
        testToneTrack = null
        var candidate: AudioTrack? = null
        val track = try {
            val pcm = assets.open("navigation_test.pcm").use { it.readBytes() }
            AudioTrack(streamType, 44100, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.size, AudioTrack.MODE_STREAM).also {
                candidate = it
                check(it.state == AudioTrack.STATE_INITIALIZED)
                check(it.write(pcm, 0, pcm.size) == pcm.size)
                it.play()
            }
        } catch (error: Exception) {
            val state = candidate?.state ?: AudioTrack.STATE_UNINITIALIZED
            candidate?.let { runCatching { it.release() } }
            Log.w("DiPlay", "playTestTone streamType=$streamType unavailable", error)
            toast(getString(R.string.audio_stream_unavailable, streamType, state))
            return
        }
        Log.i("DiPlay", "playTestTone streamType=$streamType state=${track.state} playState=${track.playState}")
        testToneTrack = track
        val stop = Runnable {
            track.stop()
            track.release()
            if (testToneTrack === track) testToneTrack = null
            toneStop = null
        }
        toneStop = stop
        handler.postDelayed(stop, 4500)
    }

    private val channelButtons = mutableListOf<Button>()

    private fun paintChannel(index: Int, selected: Boolean) {
        val target = channelButtons.getOrNull(index) ?: return
        target.isSelected = selected
        // Selected is the inverted primary fill; unselected is the same secondary control every other button
        // uses. It used to fill with SURFACE, the card's own colour, so an unselected channel had no visible
        // box at all while a selected one was a white block — the "merges" versus "jarring" pair again.
        target.setTextColor(if (selected) BG else TEXT)
        target.background = ripple(if (selected) rounded(ACCENT, ACCENT) else secondaryControl())
    }

    private fun channelSelector(): ViewGroup {
        channelButtons.clear()
        val grid = GridLayout(this).apply {
            columnCount = 7
            rowCount = 3
            setPadding(0, dp(8), 0, dp(8))
        }
        for (i in 0..20) {
            val btn = Button(this).apply {
                text = i.toString()
                isAllCaps = false
                textSize = 16f
                minHeight = dp(48)
                // Set here as well as in paintChannel: the grid is built before any selection is painted, so
                // without this an unselected channel would render as bare text with no box.
                setTextColor(TEXT)
                background = ripple(secondaryControl())
                if (Build.VERSION.SDK_INT >= 21) stateListAnimator = null
                setOnClickListener {
                    val previous = navigationStreamType
                    navigationStreamType = i
                    if (previous != i) {
                        paintChannel(previous, false)
                        paintChannel(i, true)
                    }
                    playTestTone(i)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(48)
                columnSpec = if (Build.VERSION.SDK_INT >= 21) GridLayout.spec(GridLayout.UNDEFINED, 1f)
                // CS11: spec(int,float) 与 spec(int,int,float)（带权重）都是 API21+；4.4 只能用
                // API14 的 spec(int,int)，退化为等宽而不是加权等宽。
                else GridLayout.spec(GridLayout.UNDEFINED, 1)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(btn, params)
            channelButtons.add(btn)
            paintChannel(i, i == navigationStreamType)
        }
        return grid
    }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun languageSettings(content: LinearLayout) {
        section(content, getString(R.string.language_section_title), R.drawable.ic_dp_language) { card ->
            card.addView(label(getString(R.string.language_hint), 14, MUTED))
            val current = AppLocale.preference(this)
            val languageButton = button("${getString(R.string.language_app_language)} · ${AppLocale.displayName(this, current)}", false) { }
            languageButton.setOnClickListener { AppLocale.showPicker(this) }
            card.addView(languageButton, matchButton(12, 60))
        }
    }

    private fun section(parent: LinearLayout, title: String, icon: Int? = null, build: (LinearLayout) -> Unit) {
        val card = card()
        // Tagged so the settings category filter counts sections and not the page's own title or subtitle.
        card.tag = "diplay-settings-section"
        // A section is a micro-label in this system rather than a headline with an icon: 12 sp, letterspaced,
        // secondary colour and uppercased where the script has a case, with a hairline under it. The icon
        // parameter stays because every call site passes one, but the mark is a rule now (docs/_digest/60).
        val heading = column().apply { setPadding(0, 0, 0, dp(14)) }
        heading.addView(
            label(title.uppercase(), 12, MUTED, true).apply {
                if (Build.VERSION.SDK_INT >= 21) letterSpacing = .2f
            },
        )
        heading.addView(
            View(this).apply { setBackgroundColor(BORDER) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10) },
        )
        card.addView(heading)
        build(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }
    private fun toggle(parent: LinearLayout, title: String, description: String, value: Boolean, enabled: Boolean = true, save: (Boolean) -> Unit): Switch {
        val result = SettingsWidgets.createSwitchRow(
            context = this,
            label = title,
            description = description,
            checked = value,
            theme = nightPalette,
            contentDescription = title,
            enabled = enabled,
            onChanged = save,
        )
        parent.addView(result.rowView)
        return result.switch
    }
    // [announcesReconnect] labels a choice whose [save] reconnects by itself.
    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, reconnects: Boolean = true,
        announcesReconnect: Boolean = reconnects, enabled: Boolean = true, save: (Int) -> Unit) {
        var selection = current
        val button = button("$title · ${options[selection]}", false) {}.apply { isEnabled = enabled }
        button.setOnClickListener {
            var pendingSelection = selection
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(getString(if (announcesReconnect && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
                    if (pendingSelection != selection) {
                        selection = pendingSelection
                        save(selection)
                        button.text = "$title · ${options[selection]}"
                        if (reconnects && CarPlayBackgroundSession.hasSession()) {
                            connect(AirPlayPersistence.loadWirelessEnabled(this))
                        }
                    }
                }.setNegativeButton(getString(R.string.cancel), null).show()
        }
        parent.addView(button, matchButton(0, 60)); parent.addView(space(12))
    }
    /**
     * A card, from the shared factory.
     *
     * This used to build its own `GradientDrawable`, which is exactly how the page kept a palette of its own
     * while the overlay was fixed separately: two implementations of "a card", so a change to one never
     * reached the other (docs/_digest/84).
     */
    private fun card() = SettingsWidgets.createCard(this, nightPalette)
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 18f; setTextColor(if (primary) BG else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        // Two tiers, and nothing in between.
        //
        // The secondary tier used to be transparent with a white hairline under it, which on a card read as
        // "no button at all"; the two places that did fill it used `SURFACE`, the card's own colour, so they
        // read as "no button at all" a different way. The owner's words were that a button's background either
        // "merges with the container" or is "jarringly white" (docs/_digest/84). A control needs to be a
        // filled, outlined box: [RAISED] lifts it 1.22:1 off the card and [CONTROL_BORDER] gives it an edge.
        background = if (primary) {
            ripple(rounded(ACCENT, ACCENT))
        } else {
            ripple(secondaryControl())
        }
        setPadding(dp(16), 0, dp(16), 0); minHeight = if (primary) dp(56) else dp(52)
        if (Build.VERSION.SDK_INT >= 21) stateListAnimator = null
        setOnClickListener { click() }
    }
    /** A single bottom hairline: the separator this system uses instead of outlining every row. */
    private class RowDivider(private val color: Int, private val thickness: Int) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint().apply { this.color = color }
        override fun draw(canvas: android.graphics.Canvas) {
            canvas.drawRect(
                0f,
                (bounds.height() - thickness).toFloat(),
                bounds.width().toFloat(),
                bounds.height().toFloat(),
                paint,
            )
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }
    // 4 dp: squared enough to read as technical, which is the register the brand's graphics live in.
    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(4).toFloat(); setStroke(dp(1), stroke) }

    /**
     * The background of any control that is neither the page nor the primary action: buttons, unselected
     * chips. One definition, so the two tiers cannot drift apart again.
     */
    private fun secondaryControl() = rounded(RAISED, CONTROL_BORDER)
    // CS11: RippleDrawable 是 API21+；4.4 回退为静态圆角底
    private fun ripple(content: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable =
        if (Build.VERSION.SDK_INT >= 21) android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x336F9FD9), content, null) else content
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    // Rounded, not truncated: below 160 dpi dp(1) became 0 and every border vanished.
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    /**
     * The cabin's theme, resolved once per render from the owner's own day/night setting.
     *
     * `CarPlayNightMode` is the single source of truth: it already drives CarPlay's night mode itself, so
     * hanging this page off the same setting keeps the two in step. `DAY`/`NIGHT` are explicit, `SYSTEM`
     * follows Android's own `uiMode`, and `AMBIENT` falls back to `SYSTEM` here because the light sensor is
     * owned by the host activity's controller — this page is a flat surface with no video on it, so being a
     * few seconds behind a cloud is not worth a second sensor listener.
     */
    private val nightPalette: SettingsTheme get() = SettingsTheme.page(resolvedNight)

    /**
     * Whether the page should be dark, with every one of the four modes actually honoured.
     *
     * Before this, `SYSTEM` and `AMBIENT` both read `Configuration.uiMode`, so on the head unit — whose
     * `uiMode` never reports night — picking either one left the page light no matter how dark the cabin
     * was (owner report, 2026-10-18). `AMBIENT` now follows the light sensor against the saved threshold,
     * and `SYSTEM` falls back to `uiMode` only when there is no sensor or no reading yet.
     */
    private val resolvedNight: Boolean
        get() = when (AirPlayPersistence.loadCarPlayNightMode(this)) {
            CarPlayNightMode.DAY -> false
            CarPlayNightMode.NIGHT -> true
            CarPlayNightMode.SYSTEM -> systemNight
            CarPlayNightMode.AMBIENT ->
                if (ambientSensorAvailable) ambientNight else systemNight
        }

    private val systemNight: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val ambientSensorAvailable: Boolean
        get() = runCatching { ambientLight.available }.getOrDefault(false)

    /** Last sensor reading decided the page is dark. Flipped by [onAmbientLux]. */
    private var ambientNight = false
    private var ambientListening = false
    private var lastRenderedNight: Boolean? = null

    private val ambientLight by lazy { AndroidAmbientLight(this) }

    private fun onAmbientLux(lux: Float) {
        if (!lux.isFinite() || lux < 0f) return
        val threshold = AirPlayPersistence.loadAmbientLightThreshold(this).lux
        val night = lux < threshold
        if (night == ambientNight) return
        ambientNight = night
        if (page == "settings" || page == "about") render()
    }

    /**
     * Starts or stops the light sensor for the page palette, and re-renders when the resolved mode moved
     * while the screen was not visible (the settings page can sit behind CarPlay for a long time).
     */
    private fun syncNightModeWithScreen() {
        val mode = AirPlayPersistence.loadCarPlayNightMode(this)
        val wantListening = mode == CarPlayNightMode.AMBIENT && ambientSensorAvailable
        if (wantListening && !ambientListening) {
            ambientListening = ambientLight.start(::onAmbientLux)
        } else if (!wantListening && ambientListening) {
            ambientLight.stop()
            ambientListening = false
        }
        val rendered = lastRenderedNight
        if (rendered != null && rendered != resolvedNight) render()
    }

    /**
     * The page palette. These read the resolved theme on each access rather than being constants, because
     * the same binary draws a light page in daylight and a dark one at night; a `val` snapshot taken at
     * class-init would pin whichever mode the process happened to start in.
     *
     * Three borders, not one, because nothing here can cast a shadow — see [SettingsTheme]. [BORDER]
     * outlines cards and controls, which is the boundary the driver actually reads.
     */
    private val BG get() = nightPalette.background
    private val SURFACE get() = nightPalette.surface
    private val RAISED get() = nightPalette.surfaceElevated
    private val DIVIDER get() = nightPalette.divider
    private val BORDER get() = nightPalette.cardBorder
    private val CONTROL_BORDER get() = nightPalette.controlBorder
    private val ACCENT get() = nightPalette.accent
    private val TEXT get() = nightPalette.textPrimary
    private val MUTED get() = nightPalette.textSecondary
    private val SUCCESS get() = nightPalette.success
    private val WARNING get() = nightPalette.warning

    companion object {
        private const val VEHICLE_VALIDATION_RETRY_MILLIS = 500L
        private const val ADB_KEY_SAVE_WAIT_MILLIS = 500L

        /** Lets the task leave the screen before the process ends; see `exitDiPlay`. */
        private const val EXIT_KILL_DELAY_MILLIS = 250L

        /** Every preference file the app opens after `onCreate`; see `warmUpPreferences`. */
        private val PREFERENCE_FILES = listOf(
            "diplay",
            "diplay_car_hotspot",
            "diplay_phone_wake",
            "carplay_picture",
            "carplay_wifi_p2p",
            "carplay_wifi_p2p_success",
        )
    }
}
