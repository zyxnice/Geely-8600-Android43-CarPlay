// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.hud.GeelyHudTrialStatus
import com.shilapi.xcertplay.compat.AnwPowerState
import com.shilapi.xcertplay.compat.BindingState
import com.shilapi.xcertplay.compat.GeelyBluetoothDiagnostics
import com.shilapi.xcertplay.compat.GeelyBluetoothSnapshot
import com.shilapi.xcertplay.compat.ReadState
import com.shilapi.xcertplay.compat.systemService
import com.shilapi.xcertplay.hud.BydAdbAccess
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.network.CarHotspotConfiguration
import com.shilapi.xcertplay.network.CarHotspotFailure
import com.shilapi.xcertplay.network.CarHotspotReadResult
import com.shilapi.xcertplay.network.CarHotspotStartResult
import com.shilapi.xcertplay.network.CarHotspotState
import com.shilapi.xcertplay.network.CarHotspotTools
import com.shilapi.xcertplay.network.GeelyFactoryHotspot
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.transport.EvChargingConnectors
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private enum class DiPlaySettingsCategory(val title: Int) {
    CONNECTION(R.string.diplay_connection_startup),
    DISPLAY_AUDIO(R.string.diplay_display_audio),
    NAVIGATION(R.string.diplay_hud_navigation),
    ADVANCED(R.string.diplay_advanced_diagnostics),
}

private enum class CarHotspotAction { READ_CONFIGURATION, ENABLE, READ_STATE }

private sealed class CarHotspotActionResult {
    class Read(val result: CarHotspotReadResult) : CarHotspotActionResult()
    class Start(val result: CarHotspotStartResult) : CarHotspotActionResult()
    class State(val state: CarHotspotState?) : CarHotspotActionResult()
}

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var page = "home"
    private var pendingCarHotspotSetup = false
    private var setupError: String? = null
    private var setupComplete = false
    private var pendingConnection: Boolean? = null
    private var pendingAutoConnect = false
    private val setupExecutor = Executors.newSingleThreadExecutor { Thread(it, "diplay-setup") }
    private var connectionView: DiPlayConnectionView? = null
    private var renderedConnection: HomeConnectionState? = null
    private var settingsCategory = DiPlaySettingsCategory.CONNECTION
    private var pageScroll: ScrollView? = null
    private var scrollKey: String? = null
    private var geelyHudSwitch: Switch? = null
    private var geelyHudStatus: TextView? = null
    private var geelyHudSyncing = false
    private var pendingWireless = false
    private var geelyPhoneLookupDialog: AlertDialog? = null
    private var geelyPhoneLookupGeneration = 0
    private var bluetoothProbeRequest: GeelyBluetoothDiagnostics.Request? = null
    private var bluetoothProbeDialog: AlertDialog? = null
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress = false
    private var navigationStreamType = 14
    private var testToneTrack: AudioTrack? = null
    private var toneStop: Runnable? = null
    private var exportButton: Button? = null
    private var adbStatus: TextView? = null
    private var adbCheckGeneration = 0
    private var hotspotToolsStatusView: TextView? = null
    private var hotspotToolsMessage: String? = null
    private var carHotspotEnabledSnapshot: Boolean? = null
    private var hotspotOperationGeneration = 0
    private var hotspotOperationTimeout: Runnable? = null
    private var hotspotTimedOutGeneration: Int? = null
    private var hotspotOperationCancellation: AtomicBoolean? = null
    private var activityResumed = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            choosePhone()
        } else {
            pendingWireless = false
            permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
        }
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation()) return@registerForActivityResult reconnectForLocation()
        AirPlayPersistence.saveLocationReportingEnabled(this, false)
        render()
        permissionHelp(getString(R.string.location), getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p))
    }
    private val export = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let(::exportDiagnostics)
    }

    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startupStartedAt = android.os.SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        languagePreferenceAtCreate = AppLocale.preference(this)
        com.shilapi.xcertplay.hud.BydNavigationOutputs.onAppOpened(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = BG
            window.navigationBarColor = BG
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.statusBars())
        }
        pendingConnection = savedInstanceState?.takeIf { it.containsKey("pending_connection") }?.getBoolean("pending_connection")
        pendingAutoConnect = savedInstanceState?.getBoolean("pending_auto_connect") ?: false
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        settingsCategory = DiPlaySettingsCategory.entries.getOrElse(
            savedInstanceState?.getInt("settings_category") ?: 0,
        ) { DiPlaySettingsCategory.CONNECTION }
        render()
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") navigateWhenBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        prepareAuthentication()
        android.util.Log.i("DiPlayStartup", "onCreateMs=${android.os.SystemClock.elapsedRealtime() - startupStartedAt}")
    }

    private fun prepareAuthentication() {
        val activity = WeakReference(this)
        val appContext = applicationContext
        val mainHandler = handler
        setupExecutor.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val error = runCatching { DiPlayBootstrap.ensure(appContext) }.exceptionOrNull()
            android.util.Log.i("DiPlayStartup", "authenticationMs=${android.os.SystemClock.elapsedRealtime() - startedAt} thread=background success=${error == null}")
            mainHandler.post {
                val target = activity.get() ?: return@post
                if (target.isFinishing || target.isDestroyed) return@post
                target.onAuthenticationPrepared(error)
            }
        }
    }

    private fun onAuthenticationPrepared(error: Throwable?) {
        setupComplete = true
        setupError = error?.let {
            android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
            getString(R.string.setup_error_auth)
        }
        if (setupError != null) {
            pendingConnection = null
            pendingAutoConnect = false
            render()
        } else {
            refreshStatus()
            resumePendingConnection()
        }
    }

    private fun resumePendingConnection() {
        if (!activityResumed || !setupComplete || setupError != null) return
        val wireless = pendingConnection
        pendingConnection = null
        if (wireless != null) {
            pendingAutoConnect = false
            connect(wireless)
        } else if (pendingAutoConnect) {
            pendingAutoConnect = false
            if (DiPlayPreferences.autoConnect(this) && !CarPlayBackgroundSession.hasSession() &&
                intent.getStringExtra("page") == null) {
                connect(GeelyFactoryHotspot.enabled(this) || AirPlayPersistence.loadWirelessEnabled(this))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        page = intent.getStringExtra("page") ?: "home"; render()
        handleWirelessRecovery()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putInt("settings_category", settingsCategory.ordinal)
        outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup)
        pendingConnection?.let { outState.putBoolean("pending_connection", it) }
        outState.putBoolean("pending_auto_connect", pendingAutoConnect)
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
    }

    override fun onStop() {
        if (!isChangingConfigurations && geelyPhoneLookupDialog != null) cancelGeelyPhoneLookup()
        activityResumed = false
        hotspotOperationCancellation?.set(true)
        hotspotOperationGeneration++
        hotspotOperationTimeout?.let(handler::removeCallbacks)
        hotspotOperationTimeout = null
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) CenterMapOverlay.scheduleShow()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); handler.post(tick)
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && (page == "home" || page == "settings" || page == "connection")) render()
        if (initialLaunch) {
            initialLaunch = false
            if (pendingConnection == null && setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                pendingAutoConnect = true
            }
        }
        resumePendingConnection()
        if (intent.getBooleanExtra(EXTRA_OFFER_WIRELESS_SWITCH, false)) {
            intent.removeExtra(EXTRA_OFFER_WIRELESS_SWITCH)
            if (CarPlayBackgroundSession.connectionTarget()?.wireless == false) selectTransport(true)
        }
        if (AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL) {
            submitCarHotspotAction(CarHotspotAction.READ_STATE)
        }
    }
    override fun onPause() { activityResumed = false; handler.removeCallbacks(tick); super.onPause() }

    override fun onDestroy() {
        setupExecutor.shutdown()
        cancelGeelyPhoneLookup()
        cancelBluetoothStatusProbe()
        hotspotOperationGeneration++
        hotspotOperationCancellation?.set(true)
        hotspotOperationTimeout?.let(handler::removeCallbacks)
        hotspotOperationTimeout = null
        hotspotToolsStatusView = null
        super.onDestroy()
    }

    private fun navigateWhenBack() {
        page = if (page == "connection" || page == "about") "settings" else "home"
        render()
    }

    private fun openPage(target: String) {
        if (page == target) return
        pendingAutoConnect = false
        page = target
        render()
    }

    private fun pageContent() = column().apply {
        val inset = dp(if (diPlayLayoutWidth() >= 620) 28 else 12)
        setPadding(inset, dp(18), inset, dp(22))
    }

    private fun pageScroll(content: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(content, FrameLayout.LayoutParams(-1, -2))
    }

    private fun render() {
        val newScrollKey = if (page == "settings") "settings:$settingsCategory" else page
        val scrollPosition = if (newScrollKey == scrollKey) pageScroll?.scrollY ?: 0 else 0
        scrollKey = newScrollKey
        connectionView = null
        renderedConnection = null
        geelyHudSwitch = null
        geelyHudStatus = null
        exportButton = null
        adbStatus = null
        hotspotToolsStatusView = null

        val root = column().apply { setBackgroundColor(BG) }
        root.addView(diPlayPageHeader(
            connectionSelected = page == "home",
            settingsSelected = page != "home",
            onConnection = { openPage("home") },
            onSettings = { openPage("settings") },
            onCarHome = { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) },
        ))
        val body = if (page == "settings") {
            settings()
        } else {
            val content = pageContent()
            if (page != "home") setupError?.let { content.addView(label(it, 16, WARNING)) }
            when (page) {
                "connection" -> connectionSetup(content)
                "about" -> about(content)
                else -> {
                    connectionView = DiPlayConnectionView(this, ::selectTransport).also(content::addView)
                }
            }
            pageScroll(content).also { pageScroll = it }
        }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (scrollPosition > 0) pageScroll?.let { scroll -> scroll.post { scroll.scrollTo(0, scrollPosition) } }
        refreshStatus()
    }

    private fun selectTransport(wireless: Boolean) {
        val connection = CarPlayBackgroundSession.connectionTarget()
        when {
            CarPlayBackgroundSession.isStopping() -> return
            !CarPlayBackgroundSession.hasSession() -> connect(wireless)
            connection?.wireless == wireless -> openProjection(wireless)
            else -> {
                val mode = if (wireless) "Wi-Fi" else "USB"
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.diplay_switch_transport_title, mode))
                    .setMessage(R.string.diplay_switch_transport_hint)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.connect_phone) { _, _ -> connect(wireless) }
                    .show()
            }
        }
    }

    private fun disconnectFromHome() {
        pendingAutoConnect = false
        pendingConnection = null
        CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } }
        refreshStatus()
    }

    private fun chooseHomePhone() {
        pendingAutoConnect = false
        pendingWireless = CarPlayBackgroundSession.connectionTarget()?.wireless == true
        choosePhone()
    }

    private fun settings(): View {
        val wide = diPlayLayoutWidth() >= 820
        val configuration = resources.configuration
        // A short viewport must be able to scroll past the categories even with enlarged text.
        val pinCategories = wide || configuration.screenHeightDp / configuration.fontScale.coerceAtLeast(1f) >= 420
        val content = pageContent()
        val controls = if (pinCategories) column() else content
        val categories = DiPlaySettingsCategory.entries
        for (group in categories.chunked(if (wide) 1 else 2)) {
            val buttons = row()
            group.forEachIndexed { index, category ->
                val style = if (settingsCategory == category) DiPlayButtonStyle.SELECTED else DiPlayButtonStyle.NORMAL
                buttons.addView(
                    diPlayButton(getString(category.title), style) {
                        if (settingsCategory != category) {
                            settingsCategory = category
                            render()
                        }
                    },
                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                        if (index > 0) marginStart = dp(8)
                    },
                )
            }
            controls.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        if (!pinCategories) content.addView(space(8))
        when (settingsCategory) {
            DiPlaySettingsCategory.CONNECTION -> connectionSettings(content)
            DiPlaySettingsCategory.DISPLAY_AUDIO -> displayAndAudioSettings(content)
            DiPlaySettingsCategory.NAVIGATION -> navigationSettings(content)
            DiPlaySettingsCategory.ADVANCED -> advancedSettings(content)
        }
        val scroll = pageScroll(content).also { pageScroll = it }
        if (!wide) {
            if (!pinCategories) return scroll
            val inset = dp(if (diPlayLayoutWidth() >= 620) 28 else 12)
            controls.setPadding(inset, dp(12), inset, 0)
            return column().apply {
                addView(controls)
                addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
        return row().apply {
            val sidebar = pageScroll(controls).apply {
                setPadding(dp(28), dp(18), 0, dp(18))
                clipToPadding = false
            }
            val sidebarWidth = (configuration.screenWidthDp * .24f).toInt().coerceIn(195, 292)
            addView(sidebar, LinearLayout.LayoutParams(dp(sidebarWidth), -1))
            addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun connectionSettings(content: LinearLayout) {
        section(content, getString(R.string.diplay_connection_startup), R.drawable.ic_dp_connection) { card ->
            val autoConnectHint = if (GeelyFactoryHotspot.enabled(this)) {
                R.string.geely_wifi_auto_connect_hint
            } else {
                R.string.use_your_last_connection_type_and_selected_iphone
            }
            toggle(card, getString(R.string.connect_when_diplay_opens), getString(autoConnectHint),
                DiPlayPreferences.autoConnect(this), compact = true) {
                DiPlayPreferences.saveAutoConnect(this, it)
            }
            toggle(card, getString(R.string.open_after_the_car_starts),
                getString(R.string.availability_depends_on_your_head_unit_s_startup_settings),
                AirPlayPersistence.loadAutoStartOnBoot(this), compact = true) {
                AirPlayPersistence.saveAutoStartOnBoot(this, it)
            }
            toggle(card, getString(R.string.diplay_car_bluetooth),
                getString(R.string.diplay_use_paired_car_phones),
                AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(this), compact = true) {
                AirPlayPersistence.saveGeelyBluetoothConnectionEnabled(this, it)
                refreshStatus()
            }
            card.addView(diPlayButton(getString(R.string.open_connection_setup)) { openPage("connection") },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    private fun displayAndAudioSettings(content: LinearLayout) {
        content.addView(label(getString(R.string.apply_reconnects_carplay_for_size_resolution_music_buffer),
            15, MUTED).apply { setPadding(0, 0, 0, dp(12)) })
        section(content, getString(R.string.display_and_performance), R.drawable.ic_dp_display) { card ->
            toggle(
                card,
                getString(R.string.carplay_show_live_fps),
                getString(R.string.carplay_show_live_fps_description),
                AirPlayPersistence.loadShowFps(this),
                compact = true,
            ) { AirPlayPersistence.saveShowFps(this, it) }
            carPlaySizeControl(card)
            choice(card, getString(R.string.resolution), listOf(getString(R.string.resolution_native), getString(R.string.s_80_lighter_load), getString(R.string.s_60_lightest_load)), listOf(10, 8, 6).indexOf(AirPlayPersistence.loadDisplayScaleTenths(this)).coerceAtLeast(0)) { AirPlayPersistence.saveDisplayScaleTenths(this, listOf(10, 8, 6)[it]) }
            val bufferPresets = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
            choice(card, getString(R.string.music_buffer), listOf(getString(R.string.s_300_ms_default), getString(R.string.s_500_ms), getString(R.string.s_1000_ms_most_stable)),
                bufferPresets.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) {
                AirPlayPersistence.saveMediaBufferMillis(this, bufferPresets[it])
            }
            choice(card, getString(R.string.frame_rate), listOf(getString(R.string.s_30_fps_lighter_load), getString(R.string.s_60_fps_smoother_motion)), if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) }
            toggle(card, getString(R.string.efficient_video), getString(R.string.use_hevc_leave_off_for_the_widest_head_unit_compatibility), AirPlayPersistence.loadHevcEnabled(this)) { AirPlayPersistence.saveHevcEnabled(this, it) }
            toggle(card, getString(R.string.right_hand_drive), getString(R.string.place_carplay_s_controls_closer_to_the_driver), AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) }
            toggle(card, getString(R.string.full_screen), getString(R.string.hide_the_car_s_system_bars_while_carplay_is_open), AirPlayPersistence.loadHideTopBar(this) && AirPlayPersistence.loadHideBottomBar(this)) {
                AirPlayPersistence.saveHideTopBar(this, it); AirPlayPersistence.saveHideBottomBar(this, it)
            }
        }
        section(content, getString(R.string.audio_routing)) { card ->
            geelyAudioControl(card)
            if (!AirPlayPersistence.loadGeelyAudioRouting(this) || com.shilapi.xcertplay.media.GeelyAudioCapabilities.detect() == null) {
                toggle(card, getString(R.string.contrib_audio_home_toggle_audio_focus), getString(R.string.contrib_audio_home_toggle_audio_focus_desc), AirPlayPersistence.loadAudioFocusEnabled(this)) { AirPlayPersistence.saveAudioFocusEnabled(this, it) }
            }
            if (resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)) {
                toggle(card, getString(R.string.advanced_audio_channel_mapping),
                    getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                    AirPlayPersistence.loadAdvancedAudioChannelMapping(this)) {
                    AirPlayPersistence.saveAdvancedAudioChannelMapping(this, it)
                }
            }
            mediaChannelControl(card)
            navigationChannelControl(card)
        }
    }

    private fun navigationSettings(content: LinearLayout) {
        section(content, getString(R.string.location), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.report_location_to_iphone),
                getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                AirPlayPersistence.loadLocationReportingEnabled(this)) {
                AirPlayPersistence.saveLocationReportingEnabled(this, it)
                if (it && !hasPreciseLocation()) {
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else {
                    reconnectForLocation()
                }
            }
        }
        if (com.shilapi.xcertplay.hud.GeelyHudTrialSettings.available(this)) {
            section(content, getString(R.string.geely_hud_trial_title), R.drawable.ic_dp_navigation) { card ->
                geelyHudSwitch = toggle(card, getString(R.string.geely_hud_trial_toggle), getString(R.string.geely_hud_trial_description),
                    com.shilapi.xcertplay.hud.GeelyHudTrialSettings.enabled(this)) { enabled ->
                    if (geelyHudSyncing) return@toggle
                    com.shilapi.xcertplay.hud.GeelyHudTrialSettings.setEnabled(this, enabled)
                    refreshGeelyHudStatus()
                }
                geelyHudStatus = label("", 14, MUTED).also { card.addView(it) }
                refreshGeelyHudStatus()
            }
        }
        if (com.shilapi.xcertplay.hud.BydOutputSettings.available(this)) section(content, getString(R.string.byd_navigation), R.drawable.ic_dp_navigation) { card ->
            toggle(card, getString(R.string.navigation_on_hud_and_instrument_cluster),
                getString(R.string.show_phone_navigation_arrows_distance_and_street_names_on),
                com.shilapi.xcertplay.hud.BydOutputSettings.enabled(this)) { com.shilapi.xcertplay.hud.BydOutputSettings.setEnabled(this, it) }
            if (ClusterMapPresentation.findDisplay(this) != null) {
                toggle(card, getString(R.string.carplay_map_on_instrument_cluster_experimental),
                    getString(R.string.shows_the_iphone_s_cluster_map_on_the_instrument_cluster_c),
                    AirPlayPersistence.loadClusterMapEnabled(this)) {
                    AirPlayPersistence.saveClusterMapEnabled(this, it)
                    reconnectForClusterMap()
                }
                toggle(card, getString(R.string.center_map_card), getString(R.string.center_map_card_description),
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
                }
                toggle(card, getString(R.string.launcher_map_sharing), getString(R.string.launcher_map_sharing_description),
                    AirPlayPersistence.loadLauncherMapSharing(this)) {
                    AirPlayPersistence.saveLauncherMapSharing(this, it)
                }
                if (AirPlayPersistence.loadCenterMapOverlay(this)) {
                    val overlay = CenterMapOverlay.permitted(this)
                    card.addView(label(if (overlay) getString(R.string.center_map_overlay_allowed)
                        else getString(R.string.center_map_overlay_missing, packageName), 14, if (overlay) MUTED else WARNING))
                    val usage = HomeScreenMonitor.hasAccess(this)
                    card.addView(label(if (usage) getString(R.string.center_map_usage_allowed)
                        else getString(R.string.center_map_usage_missing, packageName), 14, if (usage) MUTED else WARNING))
                }
                if (DiLink51ClusterLayout.supported()) {
                    val automatic = DiLink51ClusterLayout.automatic(this)
                    toggle(card, getString(R.string.follow_instrument_theme_and_map_card),
                        getString(R.string.show_the_side_map_only_when_its_card_is_open_and_switch_to), automatic) {
                        DiLink51ClusterLayout.saveAutomatic(this, it)
                        render()
                        reconnectForClusterMap()
                    }
                    val allowed = DiLink51ClusterMonitor.hasAccess(this)
                    card.addView(label(if (allowed) getString(R.string.usage_access_enabled)
                        else getString(R.string.usage_access_setup_needed_for_automatic_mode), 14, if (allowed) MUTED else WARNING))
                    card.addView(button(getString(R.string.automatic_map_setup_adb), false) { showClusterAccessSetup() }, matchButton(10, 56))
                    if (!automatic) {
                        val themes = DiLink51ClusterLayout.Theme.entries
                        choice(card, getString(R.string.instrument_theme), themes.map { it.localizedLabel(this) }, themes.indexOf(DiLink51ClusterLayout.theme(this))) {
                            DiLink51ClusterLayout.saveTheme(this, themes[it])
                            reconnectForClusterMap()
                        }
                        card.addView(label(getString(R.string.manual_mode_match_the_cluster_theme_here_the_map_cannot_fo), 14, MUTED))
                    }
                    val contrasts = DiLink51ClusterLayout.Contrast.entries
                    choice(card, getString(R.string.instrument_contrast), contrasts.map { it.localizedLabel(this) }, contrasts.indexOf(DiLink51ClusterLayout.contrast(this))) {
                        DiLink51ClusterLayout.saveContrast(this, contrasts[it])
                        reconnectForClusterMap()
                    }
                } else {
                    val sizes = CarPlayClusterDisplay.scalePresets
                    val contents = CarPlayClusterDisplay.Content.entries
                    val content = AirPlayPersistence.loadClusterContent(this)
                    val turnCard = content == CarPlayClusterDisplay.Content.TURN_CARD
                    choice(card, getString(R.string.dashboard_shows), listOf(
                        getString(R.string.dashboard_content_map),
                        getString(R.string.dashboard_content_turn_card),
                        getString(R.string.dashboard_content_map_with_turn_card),
                    ), contents.indexOf(content)) {
                        AirPlayPersistence.saveClusterContent(this, contents[it])
                        render()
                    }
                    choice(card, getString(if (turnCard) R.string.turn_card_size else R.string.cluster_map_size),
                        listOf(getString(R.string.cluster_size_standard), getString(R.string.cluster_size_larger), getString(R.string.cluster_size_largest)),
                        sizes.indexOf(AirPlayPersistence.loadClusterMapScalePercent(this)).coerceAtLeast(0)) {
                        AirPlayPersistence.saveClusterMapScalePercent(this, sizes[it])
                    }
                    val across = CarPlayClusterDisplay.horizontalSteps.toList()
                    choice(card, getString(if (turnCard) R.string.turn_card_horizontal else R.string.car_marker_horizontal), across.map { markerStepLabel(it, getString(R.string.marker_left), getString(R.string.marker_right)) },
                        across.indexOf(AirPlayPersistence.loadClusterMarkerHorizontalStep(this)).coerceAtLeast(0)) {
                        AirPlayPersistence.saveClusterMarkerHorizontalStep(this, across[it])
                    }
                    val upDown = CarPlayClusterDisplay.verticalSteps.toList()
                    choice(card, getString(if (turnCard) R.string.turn_card_vertical else R.string.car_marker_vertical), upDown.map { markerStepLabel(it, getString(R.string.marker_up), getString(R.string.marker_down)) },
                        upDown.indexOf(AirPlayPersistence.loadClusterMarkerVerticalStep(this)).coerceAtLeast(0)) {
                        AirPlayPersistence.saveClusterMarkerVerticalStep(this, upDown[it])
                    }
                    card.addView(button(getString(if (turnCard) R.string.reset_turn_card_to_centre else R.string.reset_car_marker_to_centre), false) {
                        AirPlayPersistence.saveClusterMarkerHorizontalStep(this, 0)
                        AirPlayPersistence.saveClusterMarkerVerticalStep(this, 0)
                        render()
                        reconnectForClusterMap()
                    }, matchButton(10, 56))
                    toggle(card, getString(R.string.dashboard_map_only_in_small_and_full_navi),
                        getString(R.string.dashboard_map_only_in_small_and_full_navi_description),
                        BydOutputSettings.clusterStreamPause(this)) {
                        BydOutputSettings.setClusterStreamPause(this, it)
                        if (it) checkAdbAccess(mayAsk = true)
                    }
                }
            }
            toggle(card, getString(R.string.car_battery_for_the_iphone),
                getString(R.string.car_battery_for_the_iphone_description),
                BydOutputSettings.batteryToIphone(this)) {
                BydOutputSettings.setBatteryToIphone(this, it)
                if (it) {
                    checkAdbAccess(mayAsk = true, reconnectWhenReady = CarPlayBackgroundSession.hasSession())
                } else if (CarPlayBackgroundSession.hasSession()) {
                    connect(AirPlayPersistence.loadWirelessEnabled(this))
                }
            }
            val connectors = EvChargingConnectors.entries
            choice(card, getString(R.string.charging_connectors), connectors.map { it.localizedLabel(this) },
                connectors.indexOf(BydOutputSettings.chargingConnectors(this))) {
                BydOutputSettings.setChargingConnectors(this, connectors[it])
            }
            val lowCharge = BydOutputSettings.lowChargePresets
            choice(card, getString(R.string.low_charge_warning), lowCharge.map {
                    getString(if (it == BydOutputSettings.DEFAULT_LOW_CHARGE_PERCENT) R.string.percent_default else R.string.percent_value, it)
                },
                lowCharge.indexOf(BydOutputSettings.lowChargePercent(this)).coerceAtLeast(0), reconnects = false) {
                BydOutputSettings.setLowChargePercent(this, lowCharge[it])
            }
            toggle(card, getString(R.string.wheel_speed_for_tunnels),
                getString(R.string.wheel_speed_for_tunnels_description),
                BydOutputSettings.wheelSpeedToIphone(this)) {
                BydOutputSettings.setWheelSpeedToIphone(this, it)
                if (it) checkAdbAccess(mayAsk = true)
                if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
            }
            toggle(card, getString(R.string.video_while_parked),
                getString(R.string.video_while_parked_description),
                BydOutputSettings.videoWhileParked(this)) {
                BydOutputSettings.setVideoWhileParked(this, it)
                if (it) checkAdbAccess(mayAsk = true)
                if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
            }
            toggle(card, getString(R.string.cluster_song),
                getString(R.string.cluster_song_description),
                BydOutputSettings.clusterSong(this)) {
                BydOutputSettings.setClusterSong(this, it)
                if (it) checkAdbAccess(mayAsk = true)
                BydNavigationOutputs.clusterSongChanged(it)
            }
            adbStatus = label("", 14, MUTED).also { status ->
                card.addView(status)
            }
            if (BydOutputSettings.clusterStreamPause(this) || BydOutputSettings.batteryToIphone(this) ||
                BydOutputSettings.wheelSpeedToIphone(this) || BydOutputSettings.videoWhileParked(this) ||
                BydOutputSettings.clusterSong(this))
                checkAdbAccess(mayAsk = false)
            card.addView(button(getString(R.string.check_adb_access), false) { checkAdbAccess(mayAsk = true) }, matchButton(10, 56))
            card.addView(button(getString(R.string.apply_and_reconnect), false) {
                if (BydOutputSettings.batteryToIphone(this)) {
                    checkAdbAccess(mayAsk = true, reconnectWhenReady = true)
                } else {
                    connect(AirPlayPersistence.loadWirelessEnabled(this))
                }
            }, matchButton(10, 56))
        }
    }

    private fun advancedSettings(content: LinearLayout) {
        if (com.shilapi.xcertplay.compat.GeelyMediaPerformance.load(this).stage != "off") {
            section(content, getString(R.string.display_and_performance), R.drawable.ic_dp_display) { card ->
                geelyPerformanceControls(card)
            }
        }
        section(content, getString(R.string.diagnostics), R.drawable.ic_dp_diagnostics) { card ->
            var vendorCheckButton: Button? = null
            toggle(
                card,
                getString(R.string.bt_stack_opt_in_title),
                getString(R.string.bt_stack_opt_in_description),
                AirPlayPersistence.loadGeelyBluetoothDiagnosticsEnabled(this),
            ) { enabled ->
                AirPlayPersistence.saveGeelyBluetoothDiagnosticsEnabled(this, enabled)
                vendorCheckButton?.isEnabled = enabled
                if (!enabled) cancelBluetoothStatusProbe()
            }
            vendorCheckButton = button(getString(R.string.bt_stack_check_vendor_button), false) {
                if (AirPlayPersistence.loadGeelyBluetoothDiagnosticsEnabled(this)) {
                    val adapter = systemService(BluetoothManager::class.java, "bluetooth")?.adapter
                    val enabled = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
                    showBluetoothStackStatus(adapterPresent = adapter != null, adapterEnabled = enabled, manual = true)
                }
            }.apply { isEnabled = AirPlayPersistence.loadGeelyBluetoothDiagnosticsEnabled(this@DiPlayActivity) }
            card.addView(vendorCheckButton, matchButton(10, 60))
            exportButton = button(if (exportInProgress) getString(R.string.saving_report) else getString(R.string.save_diagnostic_report), false) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportDiagnostics()
                else chooseReportDestination()
            }.apply { isEnabled = !exportInProgress }
            card.addView(exportButton, matchButton(10, 60))
            if (Build.VERSION.SDK_INT >= 19) card.addView(button(getString(R.string.choose_save_location), false) { chooseReportDestination() }, matchButton(10, 60))
            val destination = if (Build.VERSION.SDK_INT < 19) getString(R.string.legacy_report_destination) else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getString(R.string.reports_save_to_downloads_diplay) else getString(R.string.choose_where_to_save_your_report)
            card.addView(label(destination + getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden), 14, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        }
        section(content, getString(R.string.permissions_and_connection_help), R.drawable.ic_dp_permissions) { card ->
            card.addView(label(getString(R.string.nearby_devices_connects_your_iphone_microphone_enables_sir), 16, MUTED))
            card.addView(button(getString(R.string.app_permissions), false) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }, matchButton(16, 60))
            card.addView(button(getString(R.string.bluetooth_settings), false) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, matchButton(10, 60))
            card.addView(button(getString(R.string.wireless_connection_help), false) { wirelessHelp() }, matchButton(10, 60))
        }
        section(content, getString(R.string.about), R.drawable.ic_dp_about) { card ->
            card.addView(button(getString(R.string.about_diplay), false) { page = "about"; render() }, matchButton(0, 60))
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
        }
    }

    private fun usesGeelyFactoryHotspot(): Boolean =
        GeelyFactoryHotspot.enabled(this) &&
            AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL

    // The cached AP state is refreshed on a background worker; connection checks stay on the UI thread.
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            carHotspotEnabledSnapshot == false

    private fun carHotspotOffDialog() {
        AlertDialog.Builder(this).setTitle(getString(R.string.car_hotspot_is_off))
            .setMessage(getString(R.string.msg_car_hotspot_connect, AirPlayPersistence.loadManualHotspotSsid(this)))
            .setPositiveButton(getString(R.string.open_car_settings)) { _, _ -> openCarWifiSettings() }
            .setNeutralButton(getString(R.string.connect)) { _, _ -> connect(true) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun confirmEnableCarHotspot() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.hotspot_tools_enable_title))
            .setMessage(getString(R.string.hotspot_tools_enable_warning))
            .setPositiveButton(getString(R.string.hotspot_tools_enable_confirm)) { _, _ ->
                submitCarHotspotAction(CarHotspotAction.ENABLE)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun submitCarHotspotAction(action: CarHotspotAction) {
        if (!hotspotOperationBusy.compareAndSet(false, true)) {
            if (action != CarHotspotAction.READ_STATE) {
                setHotspotToolsMessage(getString(R.string.hotspot_tools_busy))
            }
            return
        }

        val requestId = ++hotspotOperationGeneration
        val cancelled = AtomicBoolean(false)
        hotspotOperationCancellation = cancelled
        hotspotTimedOutGeneration = null
        val ownerRef = WeakReference(this)
        val operationTimeoutMillis = if (action == CarHotspotAction.READ_STATE) {
            HOTSPOT_STATE_UI_TIMEOUT_MILLIS
        } else {
            HOTSPOT_OPERATION_UI_TIMEOUT_MILLIS
        }
        val timeout = Runnable {
            val owner = ownerRef.get() ?: return@Runnable
            if (owner.hotspotOperationGeneration == requestId && owner.activityResumed) {
                cancelled.set(true)
                owner.hotspotTimedOutGeneration = requestId
                if (action != CarHotspotAction.READ_STATE) {
                    owner.setHotspotToolsMessage(owner.getString(R.string.hotspot_tools_pending_timeout))
                }
            }
        }
        hotspotOperationTimeout = timeout
        handler.postDelayed(timeout, operationTimeoutMillis)

        val appContext = applicationContext
        hotspotToolsExecutor.execute {
            val outcome = try {
                when (action) {
                    CarHotspotAction.READ_CONFIGURATION -> CarHotspotActionResult.Read(
                        CarHotspotTools.read(appContext),
                    )
                    CarHotspotAction.ENABLE -> CarHotspotActionResult.Start(
                        CarHotspotTools.start(
                            appContext,
                            timeoutMillis = CarHotspotTools.DEFAULT_START_TIMEOUT_MILLIS,
                            cancelled = cancelled::get,
                        ),
                    )
                    CarHotspotAction.READ_STATE -> CarHotspotActionResult.State(
                        CarHotspotTools.readState(appContext),
                    )
                }
            } catch (_: Throwable) {
                when (action) {
                    CarHotspotAction.READ_CONFIGURATION -> CarHotspotActionResult.Read(
                        CarHotspotReadResult.Failure(CarHotspotFailure.OPERATION_FAILED),
                    )
                    CarHotspotAction.ENABLE -> CarHotspotActionResult.Start(
                        CarHotspotStartResult.Failure(CarHotspotFailure.OPERATION_FAILED),
                    )
                    CarHotspotAction.READ_STATE -> CarHotspotActionResult.State(null)
                }
            }
            hotspotOperationBusy.set(false)
            hotspotMainHandler.post {
                val owner = ownerRef.get() ?: return@post
                if (owner.isFinishing || owner.isDestroyed || !owner.activityResumed ||
                    owner.hotspotOperationGeneration != requestId ||
                    owner.hotspotTimedOutGeneration == requestId) return@post
                owner.handler.removeCallbacks(timeout)
                owner.hotspotOperationTimeout = null
                owner.hotspotOperationCancellation = null
                owner.completeCarHotspotAction(outcome)
            }
        }
    }

    private fun completeCarHotspotAction(outcome: CarHotspotActionResult) {
        when (outcome) {
            is CarHotspotActionResult.Read -> when (val result = outcome.result) {
                is CarHotspotReadResult.Available -> {
                    persistCarHotspotConfiguration(result.configuration)
                    pendingCarHotspotSetup = false
                    AirPlayPersistence.saveWirelessHotspotMode(this, WirelessHotspotMode.MANUAL)
                    hotspotToolsMessage = formatHotspotConfiguration(result.configuration)
                    render()
                }
                is CarHotspotReadResult.Failure ->
                    setHotspotToolsMessage(hotspotFailureMessage(result.reason))
            }
            is CarHotspotActionResult.Start -> when (val result = outcome.result) {
                is CarHotspotStartResult.AlreadyEnabled -> {
                    carHotspotEnabledSnapshot = true
                    persistCarHotspotConfiguration(result.configuration)
                    setHotspotToolsMessage(getString(R.string.hotspot_tools_already_enabled))
                }
                is CarHotspotStartResult.Enabled -> {
                    carHotspotEnabledSnapshot = true
                    persistCarHotspotConfiguration(result.configuration)
                    setHotspotToolsMessage(getString(R.string.hotspot_tools_enabled))
                }
                is CarHotspotStartResult.Failure ->
                    setHotspotToolsMessage(hotspotFailureMessage(result.reason))
            }
            is CarHotspotActionResult.State -> {
                val wasEnabled = carHotspotEnabledSnapshot
                carHotspotEnabledSnapshot = outcome.state?.let { it == CarHotspotState.ENABLED }
                if (wasEnabled != carHotspotEnabledSnapshot &&
                    (page == "home" || page == "settings" || page == "connection")) {
                    render()
                }
            }
        }
    }

    private fun persistCarHotspotConfiguration(configuration: CarHotspotConfiguration) {
        AirPlayPersistence.saveManualHotspotProfile(
            this,
            configuration.ssid,
            configuration.passphrase,
            configuration.security,
            configuration.band ?: ManualHotspotBand.AUTO,
            configuration.channel ?: 0,
        )
    }

    private fun formatHotspotConfiguration(configuration: CarHotspotConfiguration): String {
        val security = getString(
            if (configuration.security == ManualHotspotSecurity.OPEN) {
                R.string.hotspot_tools_security_open
            } else {
                R.string.hotspot_tools_security_wpa
            },
        )
        val band = when (configuration.band) {
            ManualHotspotBand.GHZ_2_4 -> getString(R.string.hotspot_tools_band_24)
            ManualHotspotBand.GHZ_5 -> getString(R.string.hotspot_tools_band_5)
            ManualHotspotBand.AUTO -> getString(R.string.hotspot_tools_band_auto)
            null -> getString(R.string.hotspot_tools_unavailable)
        }
        val channel = configuration.channel?.let {
            getString(R.string.hotspot_tools_channel_value, it)
        } ?: getString(R.string.hotspot_tools_unavailable)
        return getString(R.string.hotspot_tools_read_result, configuration.ssid, security, band, channel)
    }

    private fun hotspotFailureMessage(failure: CarHotspotFailure): String = getString(when (failure) {
        CarHotspotFailure.CONFIGURATION_UNAVAILABLE -> R.string.hotspot_tools_configuration_unavailable
        CarHotspotFailure.UNSUPPORTED_CONFIGURATION -> R.string.hotspot_tools_configuration_unsupported
        CarHotspotFailure.PERMISSION_DENIED -> R.string.hotspot_tools_permission_denied
        CarHotspotFailure.STATE_UNAVAILABLE -> R.string.hotspot_tools_state_unavailable
        CarHotspotFailure.STATE_TRANSITION_IN_PROGRESS -> R.string.hotspot_tools_transition_in_progress
        CarHotspotFailure.FIRMWARE_REPORTED_FAILURE -> R.string.hotspot_tools_firmware_failed
        CarHotspotFailure.REQUEST_REJECTED -> R.string.hotspot_tools_request_rejected
        CarHotspotFailure.START_TIMED_OUT -> R.string.hotspot_tools_start_timeout
        CarHotspotFailure.OPERATION_CANCELLED -> R.string.hotspot_tools_operation_cancelled
        CarHotspotFailure.OPERATION_INTERRUPTED -> R.string.hotspot_tools_operation_cancelled
        CarHotspotFailure.OPERATION_FAILED -> R.string.hotspot_tools_operation_failed
    })

    private fun setHotspotToolsMessage(message: String) {
        hotspotToolsMessage = message
        hotspotToolsStatusView?.apply {
            text = message
            contentDescription = getString(R.string.hotspot_tools_status_content_description, message)
        }
    }

    // BYD maps the AOSP tether action to its own hotspot screen; other firmware falls back to Wi-Fi settings.
    // BYD shows that screen as a dialog and closes it unless its own settings or the car home screen is on top,
    // so the home screen goes first.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun openCarClientWifiSettings() {
        val wifi = Intent(Settings.ACTION_WIFI_SETTINGS)
        if (packageManager.resolveActivity(wifi, 0)?.activityInfo?.packageName == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        openSystem(wifi)
    }

    private fun connectionSetup(content: LinearLayout) {
        content.addView(label(getString(R.string.connection_setup), 34, TEXT, true))
        content.addView(label(getString(R.string.set_up_once_your_details_stay_saved_for_the_next_drive_cha), 17, MUTED).apply { setPadding(0, dp(8), 0, dp(24)) })
        section(content, getString(R.string.s_1_choose_your_connection)) { card -> wirelessLinkControls(card) }
        section(content, getString(R.string.s_2_pair_your_iphone)) { card ->
            val geelyConnection = AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(this)
            card.addView(label(getString(if (geelyConnection) R.string.geely_bt_factory_pairing_instructions else R.string.keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car), 16, MUTED))
            card.addView(button(selectedPhoneButtonLabel(), false) { choosePhone() }, matchButton(12, 60))
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
        val mode = if (pendingCarHotspotSetup) WirelessHotspotMode.MANUAL else AirPlayPersistence.loadWirelessHotspotMode(this)
        val p2pSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2
        val modes = if (p2pSupported) {
            listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.WIFI_P2P)
        } else {
            listOf(WirelessHotspotMode.MANUAL)
        }
        val titles = modes.map { candidate ->
            getString(if (candidate == WirelessHotspotMode.MANUAL) R.string.built_in_car_hotspot else R.string.wifi_direct)
        }
        val descriptions = modes.map { candidate ->
            getString(if (candidate == WirelessHotspotMode.MANUAL) R.string.hotspot_mode_manual_desc else R.string.hotspot_mode_p2p_desc)
        }
        val wide = resources.configuration.screenWidthDp >= 850
        val choices = if (wide) row().apply { gravity = Gravity.TOP } else column()
        parent.addView(choices)
        modes.forEachIndexed { index, candidate ->
            val option = column()
            choices.addView(option, if (wide) LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index > 0) marginStart = dp(16)
            } else LinearLayout.LayoutParams(-1, -2))
            option.addView(button("${if (mode == candidate) "✓  " else ""}${titles[index]}", mode == candidate) {
                if (candidate == WirelessHotspotMode.MANUAL) {
                    pendingCarHotspotSetup = true
                    render()
                } else {
                    pendingCarHotspotSetup = false
                    applyWirelessLink(candidate)
                }
            }, matchButton(12, 60))
            option.addView(label(descriptions[index], 15, MUTED).apply { setPadding(0, dp(6), 0, dp(12)) })
        }
        if (p2pSupported && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            parent.addView(label(getString(R.string.hotspot_tools_p2p_legacy), 14, MUTED).apply {
                setPadding(0, dp(4), 0, dp(8))
            })
        }
        if (!p2pSupported) {
            parent.addView(label(getString(R.string.hotspot_tools_p2p_unavailable), 14, MUTED).apply {
                setPadding(0, dp(4), 0, dp(8))
            })
        }
        if (mode == WirelessHotspotMode.MANUAL) {
            parent.addView(label(getString(R.string.hotspot_setup), 22, TEXT, true))
            parent.addView(label(getString(R.string.s_1_open_car_hotspot_settings_turn_the_hotspot_on_and_sele), 16, MUTED).apply { setPadding(0, dp(8), 0, dp(12)) })
            parent.addView(button(getString(R.string.open_car_hotspot_settings), false) { openCarWifiSettings() }, matchButton(0, 60))
            val readHotspotButton = button(getString(R.string.hotspot_tools_read_button), false) {
                submitCarHotspotAction(CarHotspotAction.READ_CONFIGURATION)
            }.apply { contentDescription = getString(R.string.hotspot_tools_read_button) }
            parent.addView(readHotspotButton, matchButton(12, 60))
            val enableHotspotButton = button(getString(R.string.hotspot_tools_enable_button), true) {
                confirmEnableCarHotspot()
            }.apply { contentDescription = getString(R.string.hotspot_tools_enable_button) }
            parent.addView(enableHotspotButton, matchButton(12, 60))
            parent.addView(label(getString(R.string.hotspot_tools_5ghz_unavailable), 14, MUTED).apply {
                setPadding(0, dp(10), 0, 0)
            })
            val hotspotStatus = label(hotspotToolsMessage ?: getString(R.string.hotspot_tools_idle), 14, MUTED).apply {
                hotspotToolsStatusView = this
                contentDescription = getString(
                    R.string.hotspot_tools_status_content_description,
                    hotspotToolsMessage ?: getString(R.string.hotspot_tools_idle),
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }
                setPadding(0, dp(8), 0, dp(4))
            }
            parent.addView(hotspotStatus)
            parent.addView(button(if (pendingCarHotspotSetup) getString(R.string.save_hotspot_details_and_use_this_mode) else "${getString(R.string.edit_saved_hotspot_prefix)}${storedSsid()}", false) {
                askHotspotCredentials { ssid, password ->
                    saveHotspotCredentials(ssid, password)
                    pendingCarHotspotSetup = false
                    applyWirelessLink(WirelessHotspotMode.MANUAL)
                }
            }, matchButton(12, 60))
            parent.addView(label(if (pendingCarHotspotSetup) getString(R.string.finish_setup_save_your_hotspot_details_to_use_this_mode) else if (carHotspotOff()) getString(R.string.hotspot_details_off) else getString(R.string.hotspot_details_saved), 15, if (carHotspotOff()) WARNING else MUTED).apply { setPadding(0, dp(12), 0, 0) })
        } else {
            parent.addView(label(getString(if (Build.VERSION.SDK_INT < 23) R.string.hotspot_tools_p2p_legacy_help else R.string.turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic), 16, MUTED))
            parent.addView(button(getString(R.string.open_car_wi_fi_settings), false) { openCarClientWifiSettings() }, matchButton(12, 60))
        }
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
        parent.addView(control, matchButton(0, 60))
    }

    private fun geelyAudioControl(parent: LinearLayout) {
        val capabilities = com.shilapi.xcertplay.media.GeelyAudioCapabilities.detect()
        val selected = AirPlayPersistence.loadGeelyAudioRouting(this)
        val alert = AirPlayPersistence.loadGeelyNavigationAlert(this)
        fun summary(): String = getString(R.string.geely_audio_profile) + " · " + getString(
            if (!selected || capabilities == null) R.string.geely_audio_generic
            else if (alert) R.string.geely_audio_alert else R.string.geely_audio_speech)
        val control = button(summary(), false) {}
        control.setOnClickListener {
            val labels = if (capabilities == null) {
                listOf(getString(R.string.geely_audio_generic))
            } else {
                listOf(getString(R.string.geely_audio_generic), getString(R.string.geely_audio_speech)) +
                    if (capabilities.navigationAlert != null) listOf(getString(R.string.geely_audio_alert)) else emptyList()
            }
            var pending = if (capabilities == null || !selected) 0 else if (alert && labels.size > 2) 2 else 1
            AlertDialog.Builder(this).setTitle(getString(R.string.geely_audio_profile))
                .setSingleChoiceItems(labels.toTypedArray(), pending) { _, which -> pending = which }
                .setPositiveButton(getString(R.string.save)) { _, _ ->
                    if (pending != 0 && capabilities == null) {
                        toast(getString(R.string.geely_audio_unavailable))
                    } else {
                        AirPlayPersistence.saveGeelyAudioRouting(this, pending != 0)
                        AirPlayPersistence.saveGeelyNavigationAlert(this, pending == 2)
                        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
                        render()
                    }
                }.setNegativeButton(getString(R.string.cancel), null).show()
        }
        parent.addView(control, matchButton(0, 60))
        parent.addView(label(getString(if (capabilities == null) R.string.geely_audio_unavailable else R.string.geely_audio_detected), 14, MUTED))
        parent.addView(label(getString(R.string.geely_audio_hint), 14, MUTED))
        if (capabilities != null) parent.addView(button(getString(R.string.geely_audio_preview), false) {
            val routes = listOf(capabilities.carPlay, capabilities.navigationSpeech) + listOfNotNull(capabilities.navigationAlert)
            val labels = listOf(getString(R.string.geely_audio_test_media), getString(R.string.geely_audio_test_speech)) +
                if (capabilities.navigationAlert != null) listOf(getString(R.string.geely_audio_test_alert)) else emptyList()
            val preview = AudioChannelPreview(this) { channel -> toast(getString(R.string.contrib_audio_home_channel_preview_unavailable, channel)) }
            AlertDialog.Builder(this).setTitle(getString(R.string.geely_audio_preview))
                .setSingleChoiceItems(labels.toTypedArray(), -1) { _, which ->
                    preview.play(routes[which], which != 0,
                        if (which == 0) android.media.AudioManager.AUDIOFOCUS_GAIN
                        else android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                }.setNegativeButton(getString(R.string.close), null)
                .setOnDismissListener { preview.close() }.show()
        }, matchButton(8, 60))
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
        parent.addView(control, matchButton(0, 60))
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

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { getString(it.messageResource()) }

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotProfile(
            this,
            ssid,
            password,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password),
            ManualHotspotBand.AUTO,
            0,
        )
    }

    private fun askHotspotCredentials(done: (String, String) -> Unit) {
        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fields.addView(label(getString(R.string.copy_these_from_the_car_s_hotspot_settings_use_5_ghz_if_av), 16, MUTED))
        val ssid = EditText(this).apply { hint = getString(R.string.hotspot_name); setText(storedSsid()); setSingleLine() }
        val password = EditText(this).apply {
            hint = getString(R.string.hotspot_password); setText(storedPassword()); setSingleLine()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fields.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.car_hotspot_details))
            .setView(ScrollView(this).apply { addView(fields) })
            .setPositiveButton(getString(R.string.save_details), null).setNegativeButton(getString(R.string.cancel)) { _, _ -> hideKeyboard() }
            .setNeutralButton(getString(R.string.hide_keyboard), null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = ssid.text.toString().trim()
                val secret = password.text.toString()
                val problem = hotspotError(name, secret)
                if (problem != null) error.text = problem
                else { hideKeyboard(); dialog.dismiss(); done(name, secret) }
            }
        }
        dialog.show()
    }

    // "Left 20 %", "Centre · default", "Down 10 %": a signed step reads as a direction and a distance.
    private fun markerStepLabel(step: Int, negative: String, positive: String): String = when {
        step == 0 -> getString(R.string.marker_centre_default)
        step < 0 -> "$negative ${-step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
        else -> "$positive ${step * CarPlayClusterDisplay.MARKER_STEP_PERCENT} %"
    }

    private fun showClusterAccessSetup() {
        val command = "adb shell appops set $packageName GET_USAGE_STATS allow"
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.one_time_setup_on_this_car), 20, TEXT, true))
        body.addView(label(getString(R.string.usage_access_lets_diplay_follow_the_instrument_theme_and_m), 15, MUTED))
        body.addView(label(getString(R.string.s_1_connect_a_computer_with_adb_installed_to_the_car_using), 16, TEXT))
        body.addView(label(command, 16, TEXT).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, dp(16))
        })
        body.addView(button(getString(R.string.copy_command), false) {
            systemService(android.content.ClipboardManager::class.java, "clipboard")?.setPrimaryClip(
                android.content.ClipData.newPlainText(getString(R.string.clipboard_usage_access), command))
            toast(getString(R.string.copied_to_the_car_clipboard_run_the_command_on_your_comput))
        }, matchButton(0, 56))
        body.addView(label(getString(R.string.cluster_adb_multi_device, packageName), 14, MUTED))
        body.addView(label(getString(R.string.s_3_tap_check_and_enable_below_this_enables_the_cluster_ma), 16, TEXT))
        val status = label(if (DiLink51ClusterMonitor.hasAccess(this)) getString(R.string.permission_enabled_ready) else getString(R.string.permission_not_enabled), 16, TEXT)
        body.addView(status)
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.automatic_cluster_map_setup))
            .setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton(getString(R.string.close), null)
            .setPositiveButton(getString(R.string.check_and_enable), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (DiLink51ClusterMonitor.hasAccess(this)) {
                    AirPlayPersistence.saveClusterMapEnabled(this, true)
                    DiLink51ClusterLayout.saveAutomatic(this, true)
                    dialog.dismiss()
                    render()
                    toast(getString(R.string.automatic_map_enabled_open_the_cluster_map_card_or_select))
                    reconnectForClusterMap()
                } else {
                    status.text = getString(R.string.still_waiting_for_usage_access_check_that_the_command_ran)
                }
            }
        }
        dialog.show()
    }

    // The car's approval dialog for DiPlay's ADB key opens only from here, never while driving.
    private fun checkAdbAccess(mayAsk: Boolean, reconnectWhenReady: Boolean = false) {
        val status = adbStatus ?: return
        val generation = ++adbCheckGeneration
        status.setTextColor(MUTED)
        status.text = getString(if (mayAsk) R.string.adb_checking_may_ask else R.string.adb_checking)
        Thread({
            val result = runCatching { BydAdbAccess.check(applicationContext, mayAsk) }.getOrNull()
            runOnUiThread {
                if (adbStatus !== status || generation != adbCheckGeneration || isFinishing || isDestroyed) return@runOnUiThread
                status.setTextColor(if (result?.state == BydAdbAccess.State.READY) MUTED else WARNING)
                status.text = adbStatusText(result)
                if (reconnectWhenReady && BydOutputSettings.batteryToIphone(this) &&
                    result?.state == BydAdbAccess.State.READY && result.batteryPercent != null) {
                    connect(AirPlayPersistence.loadWirelessEnabled(this))
                }
            }
        }, "diplay-adb-check").start()
    }

    private fun adbStatusText(result: BydAdbAccess.Status?): String = when (result?.state) {
        null -> getString(R.string.adb_check_failed)
        BydAdbAccess.State.READY -> listOfNotNull(
            getString(R.string.adb_access_ready),
            result.dashboardMode?.let {
                getString(if (result.dashboardShowsMap) R.string.adb_dashboard_sends_map else R.string.adb_dashboard_does_not_send_map,
                    it.localizedLabel(this))
            },
            result.batteryPercent?.let { getString(R.string.adb_battery_reading, it.roundToInt(), result.rangeKm ?: 0) }
                ?: getString(R.string.adb_battery_unreadable).takeIf { BydOutputSettings.batteryToIphone(this) },
            getString(R.string.adb_battery_reconnect).takeIf {
                BydOutputSettings.batteryToIphone(this) && result.batteryPercent != null
            },
        ).joinToString(" ")
        BydAdbAccess.State.NOT_APPROVED -> getString(R.string.adb_not_approved)
        BydAdbAccess.State.ADB_OFF -> getString(R.string.adb_off)
        BydAdbAccess.State.PAIRING_ONLY -> getString(R.string.adb_pairing_only)
    }

    private fun hasPreciseLocation() =
        com.shilapi.xcertplay.compat.ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // The location component is part of the iAP2 identification, so a running session reconnects.
    private fun reconnectForLocation() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    // The cluster screen is described at connection time, so a running session reconnects over
    // its current link. The position choices need no call: getString(R.string.apply_and_reconnect) already does it.
    private fun reconnectForClusterMap() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
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
        if (CarPlayBackgroundSession.isStopping()) return
        pendingAutoConnect = false
        if (!setupComplete) {
            pendingConnection = wireless
            refreshStatus()
            return
        }
        val useFactoryHotspot = wireless && usesGeelyFactoryHotspot()
        if (wireless && !useFactoryHotspot && pendingCarHotspotSetup) { toast(getString(R.string.save_your_hotspot_details_in_connection_setup_first)); page = "connection"; render(); return }
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && !useFactoryHotspot && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            hotspotError(storedSsid(), storedPassword()) != null) {
            pendingCarHotspotSetup = true
            page = "connection"
            render()
            toast(getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings))
            return
        }
        if (wireless && !useFactoryHotspot && carHotspotOff()) { carHotspotOffDialog(); return }
        val useGeelyBluetooth = AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(this)
        if (wireless && !DiPlayPreferences.phoneSelectedForTransport(this, useGeelyBluetooth)) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && com.shilapi.xcertplay.compat.ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            openProjection(wireless)
        }
        if (CarPlayBackgroundSession.hasSession()) {
            CarPlayBackgroundSession.stop {
                runOnUiThread { if (!isFinishing && !isDestroyed) open() }
            }
            refreshStatus()
        } else open()
    }
    private fun openProjection(wireless: Boolean = AirPlayPersistence.loadWirelessEnabled(this)) {
        startActivity(
            Intent(this, CarPlayHostActivity::class.java)
                .putExtra(EXTRA_TRANSPORT_WIRELESS, wireless)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }
    private fun choosePhone() {
        if (AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(this)) {
            chooseGeelyPhone()
            return
        }
        if (Build.VERSION.SDK_INT >= 31 && com.shilapi.xcertplay.compat.ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = systemService(BluetoothManager::class.java, "bluetooth")?.adapter
        if (adapter == null || !adapter.isEnabled) {
            pendingWireless = false
            showBluetoothStackStatus(adapterPresent = adapter != null, adapterEnabled = false)
            return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            showPairingHelp(geelyBluetooth = false)
            return
        }
        showPairedPhones(devices.map { PairedPhone(it.address, it.name.orEmpty()) }, geelyBluetooth = false)
    }

    /** Reads only the already-paired H52 device list, off the UI thread and without Android adapter access. */
    private fun chooseGeelyPhone() {
        if (geelyPhoneLookupDialog != null) return
        if (!geelyPhoneLookupBusy.compareAndSet(false, true)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.geely_bt_picker_loading_title))
                .setMessage(getString(R.string.geely_bt_picker_busy_message))
                .setPositiveButton(getString(R.string.got_it), null)
                .show()
            return
        }
        val generation = ++geelyPhoneLookupGeneration
        val activity = WeakReference(this)
        val appContext = applicationContext
        val progress = AlertDialog.Builder(this)
            .setTitle(getString(R.string.geely_bt_picker_loading_title))
            .setMessage(getString(R.string.geely_bt_picker_loading_message))
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                invalidateGeelyPhoneLookup()
            }
            .create()
        geelyPhoneLookupDialog = progress
        progress.setOnCancelListener { invalidateGeelyPhoneLookup() }
        progress.show()

        geelyPhoneLookupExecutor.execute {
            val result: Result<List<com.shilapi.xcertplay.transport.AnwPairedDevice>> = try {
                Result.success(com.shilapi.xcertplay.transport.AnwBluetoothBackend(appContext).pairedDevices())
            } catch (error: Throwable) {
                Result.failure(error)
            } finally {
                geelyPhoneLookupBusy.set(false)
            }
            geelyPhoneLookupMainHandler.post {
                val target = activity.get() ?: return@post
                if (target.geelyPhoneLookupGeneration != generation ||
                    target.geelyPhoneLookupDialog == null || target.isFinishing || target.isDestroyed
                ) return@post
                target.geelyPhoneLookupDialog?.dismiss()
                target.geelyPhoneLookupDialog = null
                if (!AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(target)) {
                    target.pendingWireless = false
                    return@post
                }
                result.fold(
                    onSuccess = target::showGeelyPairedPhones,
                    onFailure = { target.showGeelyPhoneLookupFailure(it) },
                )
            }
        }
    }

    private fun invalidateGeelyPhoneLookup() {
        geelyPhoneLookupGeneration++
        geelyPhoneLookupDialog = null
        pendingWireless = false
    }

    private fun cancelGeelyPhoneLookup() {
        geelyPhoneLookupGeneration++
        geelyPhoneLookupDialog?.let { dialog ->
            dialog.setOnCancelListener(null)
            dialog.dismiss()
        }
        geelyPhoneLookupDialog = null
        pendingWireless = false
    }

    private fun showGeelyPairedPhones(devices: List<com.shilapi.xcertplay.transport.AnwPairedDevice>) {
        val usable = devices.filter { it.address.isNotBlank() }.sortedBy { it.name.trim().lowercase(Locale.ROOT) }
        if (usable.isEmpty()) {
            showPairingHelp(geelyBluetooth = true)
            return
        }
        showPairedPhones(usable.map { PairedPhone(it.address, it.name) }, geelyBluetooth = true)
    }

    private data class PairedPhone(val address: String, val name: String)

    private fun showPairingHelp(geelyBluetooth: Boolean) {
        pendingWireless = false
        AlertDialog.Builder(this).setTitle(R.string.pair_your_iphone)
            .setMessage(if (geelyBluetooth) R.string.geely_bt_picker_empty_message
                else R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c)
            .setPositiveButton(if (geelyBluetooth) R.string.car_home else R.string.open_bluetooth) { _, _ ->
                if (geelyBluetooth) startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                else openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPairedPhones(phones: List<PairedPhone>, geelyBluetooth: Boolean) {
        val connectAfterSelection = pendingWireless
        pendingWireless = false
        val target = CarPlayBackgroundSession.connectionTarget()?.takeIf { it.wireless }
        val switching = connectAfterSelection && target != null
        val selected = DiPlayPreferences.phoneAddress(this)
            .takeIf { DiPlayPreferences.phoneSelectedForTransport(this, geelyBluetooth) }
        val entries = phones.map { it.copy(name = it.name.trim().ifBlank { getString(R.string.paired_device) }) }
        val counts = entries.groupingBy { it.name }.eachCount()
        val list = column().apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }
        if (switching) list.addView(label(getString(R.string.diplay_switch_phone_hint), 16, TEXT)
            .apply { setPadding(0, 0, 0, dp(12)) })
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.choose_your_iphone)
            .setView(pageScroll(list))
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.pair_another) { _, _ -> showPairingHelp(geelyBluetooth) }
            .create()
        for (phone in entries) {
            val name = if ((counts[phone.name] ?: 0) > 1) {
                "${phone.name} · ${phone.address.takeLast(5)}"
            } else phone.name
            val current = target?.phoneAddress.equals(phone.address, ignoreCase = true) && CarPlayBackgroundSession.active
            val saved = selected.equals(phone.address, ignoreCase = true)
            val title = when {
                current -> "$name · ${getString(R.string.diplay_phone_current)}"
                switching -> getString(R.string.diplay_switch_to_phone, name)
                saved -> "$name · ${getString(R.string.diplay_phone_selected)}"
                else -> name
            }
            val style = if (current || saved) DiPlayButtonStyle.SELECTED else DiPlayButtonStyle.NORMAL
            val button = diPlayButton(title, style) {
                dialog.dismiss()
                if (CarPlayBackgroundSession.isStopping() ||
                    AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(this) != geelyBluetooth) return@diPlayButton
                DiPlayPreferences.savePhone(this, phone.address, phone.name, geelyBluetooth)
                render()
                if (connectAfterSelection) {
                    val session = CarPlayBackgroundSession.connectionTarget()
                    if (session?.wireless == true && session.phoneAddress.equals(phone.address, ignoreCase = true)) {
                        openProjection(true)
                    } else connect(true)
                }
            }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
            list.addView(button, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        dialog.show()
    }

    private fun showGeelyPhoneLookupFailure(error: Throwable) {
        val detail = error.message
            ?.replace(Regex("[\\r\\n\\t]+"), " ")
            ?.take(160)
            ?.takeIf { it.isNotBlank() }
            ?.let { "${error.javaClass.simpleName}: $it" }
            ?: error.javaClass.simpleName
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.geely_bt_picker_failed_title))
            .setMessage(getString(R.string.geely_bt_picker_failed_message, detail))
            .setPositiveButton(getString(R.string.legacy_report_retry)) { _, _ -> choosePhone() }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }
            .create()
        dialog.setOnCancelListener { pendingWireless = false }
        dialog.show()
    }

    private fun selectedPhoneButtonLabel(): String =
        if (DiPlayPreferences.phoneSelectedForTransport(
                this,
                AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(this),
            )
        ) "${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}"
        else getString(R.string.choose_iphone)

    private fun showBluetoothStackStatus(adapterPresent: Boolean, adapterEnabled: Boolean, manual: Boolean = false) {
        if (!GeelyBluetoothDiagnosticsOptIn.enabled(this)) {
            showAndroidBluetoothUnavailable(adapterPresent)
            return
        }
        if (bluetoothProbeRequest != null) return
        val progress = AlertDialog.Builder(this)
            .setTitle(getString(R.string.bt_stack_checking_title))
            .setMessage(getString(R.string.bt_stack_checking_message))
            .setNegativeButton(getString(R.string.bt_stack_check_cancel)) { _, _ ->
                pendingWireless = false
                bluetoothProbeRequest?.cancel()
                bluetoothProbeRequest = null
                bluetoothProbeDialog = null
            }
            .create()
        progress.setOnCancelListener {
            pendingWireless = false
            bluetoothProbeRequest?.cancel()
            bluetoothProbeRequest = null
            bluetoothProbeDialog = null
        }
        bluetoothProbeDialog = progress
        progress.show()

        val startedRequest = GeelyBluetoothDiagnosticsOptIn.runIfEnabled(applicationContext) {
            GeelyBluetoothDiagnostics(applicationContext).query { snapshot ->
            if (bluetoothProbeDialog === progress) {
                bluetoothProbeDialog = null
                progress.dismiss()
            }
            bluetoothProbeRequest = null
            if (!GeelyBluetoothDiagnosticsOptIn.enabled(this) || isFinishing || isDestroyed) return@query

            val adapterLabel = getString(
                when {
                    !adapterPresent -> R.string.bt_stack_android_unavailable
                    adapterEnabled -> R.string.bt_stack_android_on
                    else -> R.string.bt_stack_android_off
                },
            )
            val serviceLabel = when (snapshot.bindingState) {
                BindingState.CONNECTED -> getString(R.string.bt_stack_anw_service_ready)
                BindingState.NOT_RUNNING -> getString(R.string.bt_stack_anw_service_not_running)
                BindingState.ACCESS_DENIED -> getString(R.string.bt_stack_anw_service_blocked)
                BindingState.BIND_TIMEOUT -> getString(R.string.bt_stack_anw_service_timeout)
                BindingState.READ_TIMEOUT -> getString(R.string.bt_stack_anw_service_read_timeout)
                else -> getString(R.string.bt_stack_anw_service_unknown)
            }
            val powerLabel = if (snapshot.power.state == ReadState.OK) {
                getString(when (snapshot.power.value) {
                    AnwPowerState.ON -> R.string.bt_stack_anw_on
                    AnwPowerState.OFF -> R.string.bt_stack_anw_off
                    AnwPowerState.TURNING_ON -> R.string.bt_stack_anw_turning_on
                    AnwPowerState.TURNING_OFF -> R.string.bt_stack_anw_turning_off
                    AnwPowerState.UNKNOWN, null -> R.string.bt_stack_anw_unknown
                })
            } else getString(R.string.bt_stack_anw_unknown)
            val ecarxLabel = if (snapshot.ecarxEnabled.state == ReadState.OK) {
                getString(if (snapshot.ecarxEnabled.value == true) R.string.bt_stack_ecarx_enabled else R.string.bt_stack_ecarx_not_ready)
            } else getString(R.string.bt_stack_anw_unknown)
            val vendorReportedOn = snapshot.power.value == AnwPowerState.ON || snapshot.ecarxEnabled.value == true

            GeelyBluetoothDiagnosticSnapshotStore.save(
                applicationContext,
                when {
                    !adapterPresent -> "UNAVAILABLE"
                    adapterEnabled -> "ON"
                    else -> "OFF"
                },
                snapshot,
            )
            val factorySelected = AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(this)
            val statusSummary = getString(
                R.string.bt_stack_summary,
                adapterLabel,
                serviceLabel,
                powerLabel,
                ecarxLabel,
            )
            val summary = (if (factorySelected) statusSummary.substringBefore("\n\n") else statusSummary) + "\n\n" + getString(when {
                factorySelected -> R.string.geely_bt_connection_status_note
                adapterEnabled -> R.string.bt_stack_android_on_note
                vendorReportedOn -> R.string.bt_stack_vendor_on_next_step
                else -> R.string.bt_stack_unknown_next_step
            })
            val saveReport = manual || (!adapterEnabled && vendorReportedOn)
            AlertDialog.Builder(this)
                .setTitle(getString(when {
                    manual || adapterEnabled -> R.string.bt_stack_status_title
                    vendorReportedOn -> R.string.bt_stack_vendor_on_title
                    else -> R.string.bt_stack_result_title
                }))
                .setMessage(summary)
                .setPositiveButton(
                    getString(when {
                        saveReport && Build.VERSION.SDK_INT < 19 -> R.string.bt_stack_copy_report
                        saveReport -> R.string.bt_stack_save_report
                        adapterEnabled -> R.string.done
                        else -> R.string.bt_stack_open_android_settings
                    }),
                ) { _, _ ->
                    if (saveReport && Build.VERSION.SDK_INT < 19) copyBluetoothDiagnosticSummary()
                    else if (saveReport) chooseReportDestination()
                    else if (!adapterEnabled) openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }
                .setNegativeButton(getString(R.string.later)) { _, _ -> pendingWireless = false }
                .show()
            }
        }
        if (startedRequest == null) {
            bluetoothProbeDialog = null
            progress.dismiss()
            showAndroidBluetoothUnavailable(adapterPresent)
        } else bluetoothProbeRequest = startedRequest
    }

    private fun showAndroidBluetoothUnavailable(adapterPresent: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.bt_stack_result_title))
            .setMessage(getString(
                R.string.bt_stack_standard_unavailable_message,
                getString(if (adapterPresent) R.string.bt_stack_android_off else R.string.bt_stack_android_unavailable),
            ))
            .setPositiveButton(getString(R.string.bt_stack_open_android_settings)) { _, _ ->
                openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            .setNegativeButton(getString(R.string.later)) { _, _ -> pendingWireless = false }
            .show()
    }

    private fun cancelBluetoothStatusProbe() {
        val request = bluetoothProbeRequest
        bluetoothProbeRequest = null
        request?.cancel()
        val dialog = bluetoothProbeDialog
        bluetoothProbeDialog = null
        dialog?.dismiss()
    }

    private fun copyBluetoothDiagnosticSummary() {
        val text = GeelyBluetoothDiagnosticSnapshotStore.report(
            applicationContext,
            AirPlayPersistence.loadGeelyBluetoothDiagnosticsEnabled(applicationContext),
        )
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        if (clipboard == null) {
            toast(getString(R.string.bt_stack_copy_failed))
            return
        }
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.bt_stack_copy_report), text))
        toast(getString(R.string.bt_stack_report_copied))
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

    /** WifiP2pManager.Channel.close is API 27; older units release it with the reference. */
    private fun closeP2pChannel(channel: android.net.wifi.p2p.WifiP2pManager.Channel?) {
        if (channel == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        runCatching { channel.close() }
    }

    private fun resetWirelessGroup() {
        val manager = systemService(android.net.wifi.p2p.WifiP2pManager::class.java, "wifip2p")
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

    private fun refreshGeelyHudStatus() {
        if (geelyHudSwitch == null) return
        geelyHudSyncing = true
        try {
            geelyHudSwitch?.isChecked = com.shilapi.xcertplay.hud.GeelyHudTrialSettings.enabled(this)
        } finally { geelyHudSyncing = false }
        val text = when (com.shilapi.xcertplay.hud.GeelyHudTrialSettings.status()) {
            GeelyHudTrialStatus.OFF -> R.string.geely_hud_status_off
            GeelyHudTrialStatus.WAITING -> R.string.geely_hud_status_waiting
            GeelyHudTrialStatus.SENT -> R.string.geely_hud_status_sent
            GeelyHudTrialStatus.RECEIVER_UNVERIFIED -> R.string.geely_hud_status_receiver
            GeelyHudTrialStatus.STALE -> R.string.geely_hud_status_stale
            GeelyHudTrialStatus.SEND_FAILED -> R.string.geely_hud_status_send_failed
            GeelyHudTrialStatus.CLEAR_FAILED -> R.string.geely_hud_status_clear_failed
            GeelyHudTrialStatus.RECONNECT_REQUIRED -> R.string.geely_hud_status_reconnect
        }
        geelyHudStatus?.text = getString(text)

    }

    private enum class HomePhase { READY, PREPARING, FAILED, CONNECTING, CONNECTED, DISCONNECTING }

    private data class HomeConnectionState(
        val phase: HomePhase,
        val wireless: Boolean?,
        val phone: String?,
        val guidance: String?,
        val hotspotOff: Boolean,
        val queued: Boolean,
    )

    private fun refreshStatus() {
        refreshGeelyHudStatus()
        val view = connectionView ?: return
        val target = CarPlayBackgroundSession.connectionTarget()
        val phase = when {
            CarPlayBackgroundSession.isStopping() -> HomePhase.DISCONNECTING
            CarPlayBackgroundSession.active -> HomePhase.CONNECTED
            CarPlayBackgroundSession.hasSession() -> HomePhase.CONNECTING
            setupError != null -> HomePhase.FAILED
            !setupComplete -> HomePhase.PREPARING
            else -> HomePhase.READY
        }
        val useGeelyBluetooth = AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(this)
        val preferredPhone = DiPlayPreferences.phoneName(this)
            .takeIf { DiPlayPreferences.phoneSelectedForTransport(this, useGeelyBluetooth) }
        val hotspotOff = phase == HomePhase.READY && !usesGeelyFactoryHotspot() && carHotspotOff()
        val guidance = when (phase) {
            HomePhase.CONNECTED -> target?.wireless?.let {
                getString(if (it) R.string.diplay_via_wifi else R.string.diplay_via_usb)
            }
            HomePhase.FAILED -> setupError
            HomePhase.READY -> if (hotspotOff) {
                getString(R.string.msg_car_hotspot_off, AirPlayPersistence.loadManualHotspotSsid(this))
            } else getString(R.string.diplay_select_mode_hint)
            else -> null
        }
        val state = HomeConnectionState(
            phase, target?.wireless ?: pendingConnection,
            if (target != null) target.phoneName else preferredPhone, guidance, hotspotOff,
            queued = pendingConnection != null,
        )
        if (state == renderedConnection) return
        renderedConnection = state

        val status = getString(when (phase) {
            HomePhase.READY -> R.string.diplay_disconnected
            HomePhase.PREPARING -> R.string.preparing_mfi_authentication
            HomePhase.FAILED -> R.string.setup_needs_attention
            HomePhase.CONNECTING -> R.string.connecting_to_your_iphone
            HomePhase.CONNECTED -> R.string.carplay_connected
            HomePhase.DISCONNECTING -> R.string.diplay_disconnecting
        })
        val actions = buildList {
            when (phase) {
                HomePhase.CONNECTED, HomePhase.CONNECTING -> {
                    val resumeTitle = if (phase == HomePhase.CONNECTED) {
                        R.string.diplay_continue_carplay
                    } else R.string.diplay_continue_connection
                    add(DiPlayConnectionView.Action(resumeTitle, DiPlayButtonStyle.PRIMARY) {
                        openProjection(state.wireless ?: AirPlayPersistence.loadWirelessEnabled(this@DiPlayActivity))
                    })
                    if (state.wireless == true) {
                        add(DiPlayConnectionView.Action(R.string.diplay_switch_phone, click = ::chooseHomePhone))
                    }
                    add(DiPlayConnectionView.Action(R.string.disconnect, DiPlayButtonStyle.DISCONNECT, ::disconnectFromHome))
                }
                HomePhase.READY, HomePhase.FAILED ->
                    add(DiPlayConnectionView.Action(R.string.choose_iphone, click = ::chooseHomePhone))
                HomePhase.PREPARING -> if (state.queued) {
                    add(DiPlayConnectionView.Action(R.string.cancel, click = ::disconnectFromHome))
                } else Unit
                HomePhase.DISCONNECTING -> Unit
            }
            if (state.hotspotOff) {
                add(DiPlayConnectionView.Action(R.string.open_car_hotspot_settings, click = ::openCarWifiSettings))
            }
        }
        val tone = when {
            phase == HomePhase.CONNECTED -> Color.rgb(166, 238, 197)
            phase == HomePhase.FAILED || hotspotOff -> WARNING
            else -> MUTED
        }
        val title = state.phone ?: if (phase == HomePhase.CONNECTED) "iPhone" else null
        view.show(state.wireless, status, title, state.guidance, tone, actions,
            transportEnabled = phase != HomePhase.FAILED && phase != HomePhase.DISCONNECTING)
    }
    private fun reportFileName() = "DiPlay-诊断报告-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            exportDiagnostics()
            return
        }
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, reportFileName())
        }
        runCatching { export.launch(intent) }.onFailure {
            toast(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                getString(R.string.this_head_unit_could_not_open_a_save_location_please_try_s)
                else getString(R.string.this_head_unit_has_no_available_file_picker_to_save_the_re))
        }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = getString(R.string.saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        val setupStatus = if (!setupComplete) "preparing authentication" else if (setupError == null) "ready" else "authentication unavailable"
        Thread({
            val result = runCatching {
                val report = buildString {
                    appendLine("DiPlay ${version()} · private beta diagnostic report")
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
                    appendLine("Saved Bluetooth backend preference (not active-link proof): ${if (AirPlayPersistence.loadGeelyBluetoothConnectionEnabled(appContext)) "H52-ANW" else "Android"}")
                    appendLine("Authentication: local experimental beta identity; no remote fallback")
                    appendLine("CarPlay setup: $setupStatus")
                    appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScaleTenths(appContext) * 10}%")
                    appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
                    appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
                    appendLine()
                    appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    appendLine("--- Last received boot and app-launch result ---")
                    appendLine(StartupDiagnosticSnapshot.report(appContext))
                    appendLine()
                    appendLine("--- Bluetooth stacks (read-only; separate reports) ---")
                    appendLine(GeelyBluetoothDiagnosticSnapshotStore.report(
                        appContext,
                        AirPlayPersistence.loadGeelyBluetoothDiagnosticsEnabled(appContext),
                    ))
                    appendLine("Startup settings: openAfterBoot=${AirPlayPersistence.loadAutoStartOnBoot(appContext)} " +
                        "connectWhenOpened=${DiPlayPreferences.autoConnect(appContext)}")
                    appendLine()
                    WirelessDisconnectReport.appendTo(this, appContext)
                    for (name in SessionLogFile.REPORT_NAMES) {
                        val file = File(appContext.filesDir, "logs/$name")
                        if (file.isFile) {
                            appendLine("--- $name ---")
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                if (uri != null) { DiagnosticExportStore.write(appContext.contentResolver, uri, report); uri }
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    DiagnosticExportStore.saveToDownloads(appContext.contentResolver, fileName, report)
                } else if (Build.VERSION.SDK_INT < 19) {
                    DiagnosticExportStore.saveLegacy(appContext, fileName, report)
                } else error("A save location is required")
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
                if (result.isSuccess) {
                    val savedUri = result.getOrThrow()
                    AlertDialog.Builder(this).setTitle(getString(R.string.diagnostic_report_saved))
                        .setMessage(if (Build.VERSION.SDK_INT < 19) savedUri.path else if (uri == null) "Downloads/DiPlay/$fileName" else getString(R.string.your_report_was_saved_to_the_selected_location))
                        .setPositiveButton(getString(R.string.done), null)
                        .setNeutralButton(getString(R.string.share)) { _, _ ->
                            runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedUri)
                                    clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), savedUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, getString(R.string.share_diagnostic_report)))
                            }.onFailure { toast(getString(R.string.report_saved_open_it_from_your_file_manager_to_share_it)) }
                        }.show()
                } else {
                    AlertDialog.Builder(this).setTitle(getString(R.string.could_not_save_the_report))
                        .setMessage(getString(if (Build.VERSION.SDK_INT < 19) R.string.legacy_report_failed else R.string.check_that_storage_is_available_or_choose_another_save_loc))
                        .setPositiveButton(getString(if (Build.VERSION.SDK_INT < 19) R.string.legacy_report_retry else R.string.choose_location)) { _, _ -> chooseReportDestination() }
                        .setNegativeButton(getString(R.string.close), null).show()
                }
            }
        }, "diplay-export").start()
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

    /** ImageView.setImageTintList and CompoundButton.setButtonTintList are API 21. */
    private fun ImageView.tintCompat(color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) imageTintList = ColorStateList.valueOf(color)
    }

    private fun Switch.tintButtonCompat(color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) buttonTintList = ColorStateList.valueOf(color)
    }

    private fun paintChannel(index: Int, selected: Boolean) {
        val target = channelButtons.getOrNull(index) ?: return
        target.isSelected = selected
        target.setTextColor(if (selected) BG else TEXT)
        target.background = ripple(rounded(if (selected) ACCENT else SURFACE, if (selected) ACCENT else BORDER))
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stateListAnimator = null
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
                columnSpec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                } else {
                    GridLayout.spec(GridLayout.UNDEFINED)
                }
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
        section(content, getString(R.string.language_section_title)) { card ->
            card.addView(label(getString(R.string.language_hint), 14, MUTED))
            val current = AppLocale.preference(this)
            val languageButton = button("${getString(R.string.language_app_language)} · ${AppLocale.displayName(this, current)}", false) { }
            languageButton.setOnClickListener { AppLocale.showPicker(this) }
            card.addView(languageButton, matchButton(12, 60))
        }
    }

    private fun section(parent: LinearLayout, title: String, icon: Int? = null, build: (LinearLayout) -> Unit) {
        val card = card()
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(16)) }
        if (icon != null) heading.addView(ImageView(this).apply {
            setImageResource(icon); tintCompat(ACCENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
        heading.addView(label(title, 22, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(heading)
        build(card)
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
    }
    private fun geelyPerformanceControls(card: LinearLayout) {
        val settings = com.shilapi.xcertplay.compat.GeelyMediaPerformance
        val options = settings.load(this)
        if (options.stage == "off") return
        val nextConnection = "修改后请断开 CarPlay 并重新打开连接，当前播放保持原设置。"
        toggle(card, "8600 基础流畅度优化（${options.stage}）", nextConnection, options.network) {
            settings.save(this, settings.NETWORK, it)
        }
        toggle(card, "优先处理画面（实验）", "可能影响音乐连续性；默认关闭。$nextConnection", options.urgentVideo) {
            settings.save(this, settings.URGENT_VIDEO, it)
        }
        if (options.stage == "B" || options.stage == "C") {
            toggle(card, "音视频快速解密（实验）", "不支持时自动使用原方式。$nextConnection", options.nativeMedia) {
                settings.save(this, settings.NATIVE_MEDIA, it)
            }
        }
        if (options.stage == "C") {
            var queueEnabled = options.videoQueue
            lateinit var recoverySwitch: Switch
            toggle(card, "画面接收优化（实验）", "限制画面积压，关闭可对照原版接收方式。$nextConnection", options.videoQueue) {
                queueEnabled = it
                settings.save(this, settings.VIDEO_QUEUE, it)
                if (!it) recoverySwitch.isChecked = false
                recoverySwitch.isEnabled = it
            }
            recoverySwitch = toggle(card, "卡顿快速恢复（实验）", "需开启画面接收优化；首次对照请保持关闭。$nextConnection", options.softVideoRecovery) {
                settings.save(this, settings.SOFT_VIDEO_RECOVERY, queueEnabled && it)
            }
            recoverySwitch.isEnabled = queueEnabled
        }
    }

    private fun toggle(
        parent: LinearLayout,
        title: String,
        description: String,
        value: Boolean,
        compact: Boolean = false,
        save: (Boolean) -> Unit,
    ): Switch {
        val inset = dp(if (compact) 4 else 12)
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, inset, 0, inset) }
        val text = column(); text.addView(label(title, 18, TEXT, true)); text.addView(label(description, 14, MUTED).apply { setPadding(0, dp(6), dp(16), 0) })
        line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val control = Switch(this).apply {
            contentDescription = title
            isChecked = value
            minHeight = dp(56)
            if (compact) styleConnectionSwitch(this) else tintButtonCompat(ACCENT)
            setOnCheckedChangeListener { _, checked -> save(checked) }
        }
        line.addView(control)
        parent.addView(line)
        if (compact) {
            text.setOnClickListener { if (control.isEnabled) control.performClick() }
            parent.addView(View(this).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        return control
    }

    private fun styleConnectionSwitch(control: Switch) {
        fun track(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(16).toFloat()
            setSize(dp(64), dp(32))
        }
        fun thumb(color: Int) = InsetDrawable(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setSize(dp(24), dp(24))
        }, dp(4))
        control.textOn = ""
        control.textOff = ""
        control.switchMinWidth = dp(64)
        control.thumbDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), thumb(Color.rgb(23, 60, 61)))
            addState(intArrayOf(), thumb(TEXT))
        }
        control.trackDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), track(ACCENT))
            addState(intArrayOf(), track(BORDER))
        }
    }

    private fun choice(parent: LinearLayout, title: String, options: List<String>, current: Int, reconnects: Boolean = true, save: (Int) -> Unit) {
        var selection = current
        val button = button("$title · ${options[selection]}", false) {}
        button.setOnClickListener {
            var pendingSelection = selection
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), selection) { _, index -> pendingSelection = index }
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
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
    private fun card() = column().apply { background = rounded(SURFACE, BORDER); setPadding(dp(24), dp(24), dp(24), dp(24)) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private var TextView.trackedSpacing: Float
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) letterSpacing else 0f
        set(value) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) letterSpacing = value }
    private fun ripple(content: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x336F9FD9), content, null)
        } else {
            content
        }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 18f; setTextColor(if (primary) BG else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = ripple(rounded(if (primary) ACCENT else SURFACE, if (primary) ACCENT else BORDER))
        setPadding(dp(16), 0, dp(16), 0); minHeight = dp(56)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stateListAnimator = null
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat(); setStroke(dp(1), stroke) }
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(
        -1, if (resources.configuration.fontScale > 1.15f) -2 else dp(height),
    ).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private val hotspotOperationBusy = AtomicBoolean(false)
        private val hotspotToolsExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-car-hotspot-tools").apply { isDaemon = true }
        }
        private val hotspotMainHandler = Handler(Looper.getMainLooper())
        private const val HOTSPOT_STATE_UI_TIMEOUT_MILLIS = 8_000L
        private const val HOTSPOT_OPERATION_UI_TIMEOUT_MILLIS = 20_000L
        private val geelyPhoneLookupBusy = AtomicBoolean(false)
        private val geelyPhoneLookupExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-h52-paired-phones").apply { isDaemon = true }
        }
        private val geelyPhoneLookupMainHandler = Handler(Looper.getMainLooper())
        private val BG = DiPlayBackground
        private val SURFACE = DiPlaySurface
        private val BORDER = DiPlayBorder
        private val ACCENT = DiPlayAccent
        private val TEXT = DiPlayText
        private val MUTED = DiPlayMuted
        private val WARNING = Color.rgb(255, 196, 128)
    }
}
