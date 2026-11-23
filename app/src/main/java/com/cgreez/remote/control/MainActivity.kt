package com.cgreez.remote.control

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), TvHub.Listener {

    companion object {
        @Volatile private var reopenSettings = false
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private var settingsCol: LinearLayout? = null

    private lateinit var statusView: TextView
    private lateinit var connectView: View
    private lateinit var remoteView: View
    private lateinit var settingsView: View
    private lateinit var ipField: EditText
    private lateinit var macField: EditText
    private lateinit var msgField: EditText
    private lateinit var muteIcon: ImageView
    private lateinit var appsRow: LinearLayout
    private lateinit var infoView: TextView
    private lateinit var keysList: LinearLayout
    private lateinit var stopBtn: View
    private lateinit var keepOnSwitch: Switch
    private lateinit var guardSwitch: Switch
    private lateinit var forceSwitch: Switch
    private lateinit var bannerView: TextView
    private var shownConnected: Boolean? = null
    private var shownApps = ""
    private var statusWanted = ""
    private var onboardingShown = false
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvHub.init(this)
        Pal.apply(TvHub.themeDark, TvHub.themeAccent)
        window.statusBarColor = Pal.bg
        window.navigationBarColor = Pal.bg
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(buildUi())
        applyBarIcons()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        onHubState()
        onHubApps()
        if (reopenSettings) {
            reopenSettings = false
            openSettings(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyBarIcons() {
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (Pal.dark) 0 else mask, mask)
        } else if (Build.VERSION.SDK_INT >= 23) {
            var f = window.decorView.systemUiVisibility
            f = if (Pal.dark) {
                f and (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv())
            } else {
                f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            window.decorView.systemUiVisibility = f
        }
    }

    /** Switches the home-screen icon to the chosen accent colour (one launcher alias per colour). */
    private fun applyLauncherIcon(accent: String) {
        try {
            for (n in Pal.accentNames) {
                val cn = android.content.ComponentName(this, "com.cgreez.remote.control.Icon${n.replaceFirstChar { it.uppercase() }}")
                val want = if (n == accent) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                if (packageManager.getComponentEnabledSetting(cn) != want) {
                    packageManager.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP)
                }
            }
        } catch (e: Exception) {
        }
    }

    /** Saves the theme and rebuilds the screens with a soft cross-fade. */
    private fun changeTheme(dark: Boolean, accent: String) {
        if (dark == TvHub.themeDark && accent == TvHub.themeAccent) return
        TvHub.setTheme(dark, accent)
        applyLauncherIcon(accent)
        reopenSettings = true
        recreate()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onStart() {
        super.onStart()
        TvHub.listener = this
        applyKeepOn()
        onHubState()
        onHubApps()
        if (!TvHub.onboarded && !onboardingShown) {
            onboardingShown = true
            window.decorView.post { showRemoteDialog() }
        }
    }

    override fun onStop() {
        TvHub.listener = null
        TvHub.cancelCapture()
        super.onStop()
    }

    // Keys from the learned Bluetooth remote also work while this screen is open.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (TvHub.handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (settingsView.visibility == View.VISIBLE) closeSettings() else super.onBackPressed()
    }

    private fun applyKeepOn() {
        if (TvHub.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---- TvHub.Listener ----
    override fun onHubStatus(text: String, isError: Boolean) {
        setStatus(text, if (isError) Pal.danger else Pal.muted)
    }

    private fun setStatus(text: String, color: Int) {
        statusView.setTextColor(color)
        // Compare with the text we are heading to, not the one still on screen mid-fade,
        // so a quick second message can never leave an old one showing.
        if (statusWanted == text) return
        statusWanted = text
        statusView.animate().cancel()
        statusView.animate().alpha(0f).setDuration(80).withEndAction {
            statusView.text = statusWanted
            statusView.animate().alpha(1f).setDuration(160).start()
        }.start()
    }

    override fun onHubState() {
        val c = TvHub.connected
        stopBtn.visibility = if (TvHub.wantConnected) View.VISIBLE else View.GONE
        if (shownConnected != c) {
            val first = shownConnected == null
            shownConnected = c
            swapScreens(c, !first)
        }
        if (c) setStatus("Connected  ·  ${TvHub.ip}", Pal.accent)
        muteIcon.setImageResource(if (TvHub.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        muteIcon.setColorFilter(if (TvHub.muted) Pal.danger else Pal.accent)
        updateBanner()

        val remote = if (TvHub.remoteNames.isEmpty()) "not chosen yet" else TvHub.remoteNames.joinToString()
        val access = if (accessibilityOn()) "on" else "OFF – tap Key access"
        val last = if (TvHub.lastKey.isEmpty()) "none yet" else TvHub.lastKey
        infoView.text = "Remote: $remote\nKey access: $access\nLast key: $last"
    }

    /** Fades and slides the connect screen / remote screen in. */
    private fun swapScreens(connected: Boolean, animate: Boolean) {
        val show = if (connected) remoteView else connectView
        val hide = if (connected) connectView else remoteView
        hide.animate().cancel()
        hide.visibility = View.GONE
        show.animate().cancel()
        show.visibility = View.VISIBLE
        if (animate) {
            show.alpha = 0f
            show.translationY = dp(18).toFloat()
            show.scaleX = 0.98f
            show.scaleY = 0.98f
            show.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(340)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        } else {
            show.alpha = 1f
            show.translationY = 0f
            show.scaleX = 1f
            show.scaleY = 1f
        }
    }

    /** The warning strip: shown while no remote is chosen or Key access is off. */
    private fun updateBanner() {
        val text: String? = when {
            TvHub.remoteNames.isEmpty() -> "No remote chosen yet. Tap here to choose your remote."
            !accessibilityOn() ->
                "Key access is off, so your remote can't control the TV while the app is closed. Tap here to turn it on."
            else -> null
        }
        if (text == null) {
            if (bannerView.visibility == View.VISIBLE) {
                bannerView.animate().cancel()
                bannerView.animate().alpha(0f).setDuration(180)
                    .withEndAction { bannerView.visibility = View.GONE }.start()
            }
        } else {
            bannerView.text = text
            if (bannerView.visibility != View.VISIBLE) {
                bannerView.animate().cancel()
                bannerView.alpha = 0f
                bannerView.visibility = View.VISIBLE
                bannerView.animate().alpha(1f).setDuration(240).start()
            } else if (bannerView.alpha < 1f) {
                bannerView.animate().alpha(1f).setDuration(120).start()
            }
        }
    }

    override fun onHubApps() {
        fillApps()
    }

    private fun accessibilityOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.contains("$packageName/")
    }

    // ---- Actions ----
    private fun connectTv() {
        val ip = ipField.text.toString().trim()
        if (!Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(ip)) {
            onHubStatus("Enter the TV's IP address, like 192.168.1.x (all four numbers).", true)
            return
        }
        TvHub.saveSettings(ip, macField.text.toString().trim())
        onHubStatus("Connecting. Accept the prompt on the TV if it appears.", false)
        try {
            startForegroundService(Intent(this, RemoteService::class.java))
        } catch (e: Exception) {
            TvHub.start()
        }
    }

    /** Opens a system settings screen; some phones do not have every one, so a miss is not a crash. */
    private fun openSystem(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "This phone has no such settings screen.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopTv() {
        stopService(Intent(this, RemoteService::class.java))
        TvHub.stop(true)
        onHubStatus("Stopped. The remote no longer controls the TV.", false)
    }

    private fun sendMsg() {
        val t = msgField.text.toString().trim()
        if (t.isEmpty()) return
        TvHub.sendMessage(t)
        msgField.setText("")
    }

    // ---- Building blocks ----
    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun field(hintText: String): EditText {
        val e = EditText(this)
        e.hint = hintText
        e.setHintTextColor(Pal.muted)
        e.setTextColor(Pal.text)
        e.textSize = 15f
        e.setSingleLine(true)
        e.background = roundRect(Pal.surface, dpf(26))
        e.setPadding(dp(20), dp(14), dp(20), dp(14))
        return e
    }

    private fun lp(w: Int, h: Int, top: Int = 0, bottom: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h, weight)
        p.topMargin = dp(top)
        p.bottomMargin = dp(bottom)
        return p
    }

    /** Puts each view in its own equal-width cell, centred. */
    private fun evenRow(vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            val w = v.layoutParams.width
            val h = v.layoutParams.height
            val cell = FrameLayout(this)
            cell.addView(v, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
            r.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        return r
    }

    // ---- Screen ----
    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(Pal.bg)

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setPadding(dp(20), dp(18), dp(20), 0)

        // Top bar: name + status on the left, settings on the right.
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        val titleCol = LinearLayout(this)
        titleCol.orientation = LinearLayout.VERTICAL
        titleCol.addView(label("CGeez RC", 22f, Pal.text, true))
        statusView = label("Not connected", 12f, Pal.muted)
        statusView.maxLines = 2
        titleCol.addView(statusView, lp(WRAP, WRAP, 2))
        bar.addView(titleCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(iconButton(R.drawable.ic_tune, 44, 22, Pal.surface, Pal.accent, "Settings") { openSettings() })
        main.addView(bar, lp(MATCH, WRAP, 0, 10))

        bannerView = TextView(this)
        bannerView.textSize = 12.5f
        bannerView.setTextColor(Pal.text)
        bannerView.setPadding(dp(16), dp(12), dp(16), dp(12))
        val border = roundRect(Pal.surface, dpf(18))
        border.setStroke(dp(1), Pal.danger)
        bannerView.background = border
        bannerView.visibility = View.GONE
        bannerView.setOnClickListener {
            if (TvHub.remoteNames.isEmpty()) showRemoteDialog() else showKeyAccessDialog()
        }
        main.addView(bannerView, lp(MATCH, WRAP, 0, 10))

        connectView = buildConnect()
        remoteView = buildRemote()
        main.addView(connectView, lp(MATCH, 0, 0, 0, 1f))
        main.addView(remoteView, lp(MATCH, 0, 0, 0, 1f))

        settingsView = buildSettings()
        settingsView.visibility = View.GONE

        root.addView(main, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(settingsView, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    private fun buildConnect(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(0, dp(28), 0, dp(24))

        val badge = FrameLayout(this)
        badge.background = circle(Pal.surface)
        val tvIcon = ImageView(this)
        tvIcon.setImageResource(R.drawable.ic_tv)
        tvIcon.setColorFilter(Pal.accent)
        badge.addView(tvIcon, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        col.addView(badge, lp(dp(88), dp(88), 0, 18))

        col.addView(label("Connect to your TV", 20f, Pal.text, true))
        val sub = label("Phone and TV on the same Wi-Fi. Find the IP in the TV's network settings.", 13f, Pal.muted)
        sub.gravity = Gravity.CENTER
        col.addView(sub, lp(MATCH, WRAP, 6, 22))

        ipField = field("192.168.1.x")
        ipField.setText(TvHub.ip)
        macField = field("TV MAC address (optional, for Wake)")
        macField.setText(TvHub.mac)
        col.addView(ipField, lp(MATCH, WRAP, 0, 10))
        col.addView(macField, lp(MATCH, WRAP, 0, 18))

        col.addView(pillButton("Connect", Pal.accent, Pal.onAccent) { connectTv() }, lp(MATCH, WRAP, 0, 10))
        val wake = pillButton("Wake TV", Pal.surface, Pal.text) {
            TvHub.saveSettings(ipField.text.toString().trim(), macField.text.toString().trim())
            TvHub.wake()
        }
        col.addView(wake, lp(MATCH, WRAP, 0, 10))
        stopBtn = pillButton("Stop", Pal.surface, Pal.danger) { stopTv() }
        col.addView(stopBtn, lp(MATCH, WRAP))

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        return scroll
    }

    private fun buildRemote(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, dp(6), 0, dp(24))

        val icon = Pal.accent

        // Power and mute
        val power = iconButton(R.drawable.ic_power, 54, 26, Pal.surface, Pal.danger, "Power") {
            TvHub.turnOff()
        }
        val mute = iconButton(R.drawable.ic_volume_up, 54, 26, Pal.surface, icon, "Mute") {
            TvHub.toggleMute()
        }
        muteIcon = mute.iconOf()
        col.addView(evenRow(power, mute), lp(MATCH, WRAP, 0, 14))

        // Direction pad
        val side = minOf(dp(244), resources.displayMetrics.widthPixels - dp(72))
        val pad = DPadView(this) { name -> TvHub.pressKey(name) }
        val padLp = lp(side, side, 0, 18)
        padLp.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(pad, padLp)

        // Touchpad for the TV cursor (long-press menus)
        col.addView(TouchpadView(this), lp(MATCH, dp(110), 0, 18))

        // Back / Home / Exit
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        fun navBtn(res: Int, desc: String, key: String) {
            val b = iconButton(res, 54, 24, Pal.surface, icon, desc, cornerDp = 20, hold = true, repeat = true) {
                TvHub.pressKey(key)
            }
            val p = LinearLayout.LayoutParams(0, dp(54), 1f)
            p.leftMargin = dp(5)
            p.rightMargin = dp(5)
            nav.addView(b, p)
        }
        navBtn(R.drawable.ic_back, "Back", "BACK")
        navBtn(R.drawable.ic_home, "Home", "HOME")
        navBtn(R.drawable.ic_exit, "Exit", "EXIT")
        col.addView(nav, lp(MATCH, WRAP, 0, 18))

        // Volume | Menu + Info | Channel
        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.VERTICAL
        mid.gravity = Gravity.CENTER
        mid.addView(
            iconButton(R.drawable.ic_menu, 54, 24, Pal.surface, icon, "Menu", hold = true, repeat = true) { TvHub.pressKey("MENU") },
            lp(dp(54), dp(54), 0, 14)
        )
        mid.addView(
            iconButton(R.drawable.ic_info, 54, 24, Pal.surface, icon, "Info", hold = true, repeat = true) { TvHub.pressKey("INFO") },
            lp(dp(54), dp(54))
        )
        val vol = stepper("VOL", { TvHub.volumeUp() }, { TvHub.volumeDown() })
        val ch = stepper("CH", { TvHub.channelUp() }, { TvHub.channelDown() })
        val trio = LinearLayout(this)
        trio.orientation = LinearLayout.HORIZONTAL
        trio.gravity = Gravity.CENTER_VERTICAL
        trio.addView(vol, LinearLayout.LayoutParams(0, dp(156), 1f))
        trio.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
        trio.addView(ch, LinearLayout.LayoutParams(0, dp(156), 1f))
        for (i in 0 until trio.childCount) {
            val v = trio.getChildAt(i)
            val p = v.layoutParams as LinearLayout.LayoutParams
            if (v === vol || v === ch) {
                p.leftMargin = dp(20)
                p.rightMargin = dp(20)
            }
        }
        col.addView(trio, lp(MATCH, WRAP, 0, 18))

        // Media
        col.addView(
            evenRow(
                iconButton(R.drawable.ic_rewind, 54, 24, Pal.surface, icon, "Rewind", hold = true, repeat = true) { TvHub.rewind() },
                iconButton(R.drawable.ic_play_pause, 54, 24, Pal.surface, icon, "Play or pause") { TvHub.playPause() },
                iconButton(R.drawable.ic_forward, 54, 24, Pal.surface, icon, "Fast-forward", hold = true, repeat = true) { TvHub.fastForward() }
            ),
            lp(MATCH, WRAP, 0, 22)
        )

        // Apps
        col.addView(label("Apps", 12f, Pal.muted, true), lp(WRAP, WRAP, 0, 10))
        appsRow = LinearLayout(this)
        appsRow.orientation = LinearLayout.HORIZONTAL
        val appsScroll = HorizontalScrollView(this)
        appsScroll.isHorizontalScrollBarEnabled = false
        appsScroll.addView(appsRow, FrameLayout.LayoutParams(WRAP, WRAP))
        col.addView(appsScroll, lp(MATCH, WRAP, 0, 22))

        // Message to show on the TV
        col.addView(label("Message on TV", 12f, Pal.muted, true), lp(WRAP, WRAP, 0, 10))
        val msgRow = LinearLayout(this)
        msgRow.orientation = LinearLayout.HORIZONTAL
        msgRow.gravity = Gravity.CENTER_VERTICAL
        msgField = field("Write a message, e.g. Nice!!")
        msgField.imeOptions = EditorInfo.IME_ACTION_SEND
        msgField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMsg()
                true
            } else {
                false
            }
        }
        msgRow.addView(msgField, LinearLayout.LayoutParams(0, WRAP, 1f))
        val send = iconButton(R.drawable.ic_send, 52, 22, Pal.accent, Pal.onAccent, "Send message") { sendMsg() }
        val sp = LinearLayout.LayoutParams(dp(52), dp(52))
        sp.leftMargin = dp(10)
        msgRow.addView(send, sp)
        col.addView(msgRow, lp(MATCH, WRAP))

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        return scroll
    }

    /** Tall pill with + on top, a label, and - at the bottom. Both halves repeat while held. */
    private fun stepper(name: String, plus: () -> Unit, minus: () -> Unit): LinearLayout {
        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.VERTICAL
        pill.gravity = Gravity.CENTER_HORIZONTAL
        pill.background = roundRect(Pal.surface, dpf(36))
        fun half(res: Int, desc: String, act: () -> Unit): FrameLayout {
            val b = iconButton(res, 54, 24, Color.TRANSPARENT, Pal.accent, desc, hold = true, repeat = true, onPress = act)
            b.layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            return b
        }
        pill.addView(half(R.drawable.ic_add, "$name up", plus))
        val tag = label(name, 11f, Pal.muted, true)
        tag.gravity = Gravity.CENTER
        pill.addView(tag, LinearLayout.LayoutParams(MATCH, WRAP))
        pill.addView(half(R.drawable.ic_remove, "$name down", minus))
        return pill
    }

    private fun fillApps() {
        val list: List<TvApp> = if (TvHub.apps.isEmpty()) {
            listOf(
                TvApp("netflix", "Netflix", "", ""),
                TvApp("youtube.leanback.v4", "YouTube", "", ""),
                TvApp("amazon", "Prime Video", "", "")
            )
        } else {
            TvHub.apps
        }
        val key = list.joinToString(",") { it.id }
        if (key == shownApps && appsRow.childCount > 0) return
        shownApps = key
        appsRow.removeAllViews()
        list.forEachIndexed { i, a ->
            val tile = appTile(a, 60, 70) { TvHub.launchApp(a.id) }
            tile.alpha = 0f
            appsRow.addView(tile)
            tile.animate().alpha(1f).setStartDelay(minOf(i, 10) * 35L).setDuration(240).start()
        }
    }

    // ---- Settings page ----
    private fun card(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = roundRect(Pal.surface, dpf(22))
        c.setPadding(dp(18), dp(16), dp(18), dp(16))
        return c
    }

    private fun buildSettings(): View {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setBackgroundColor(Pal.bg)
        page.setPadding(dp(20), dp(18), dp(20), 0)
        page.isClickable = true

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.addView(iconButton(R.drawable.ic_back, 44, 22, Pal.surface, Pal.accent, "Close settings") { closeSettings() })
        val t = label("Settings", 20f, Pal.text, true)
        val tp = LinearLayout.LayoutParams(WRAP, WRAP)
        tp.leftMargin = dp(14)
        bar.addView(t, tp)
        page.addView(bar, lp(MATCH, WRAP, 0, 14))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, 0, 0, dp(28))

        // Appearance
        val themeCard = card()
        themeCard.addView(label("Appearance", 14f, Pal.text, true))
        themeCard.addView(
            segmented(listOf("Dark", "Light"), if (TvHub.themeDark) 0 else 1) { i ->
                changeTheme(i == 0, TvHub.themeAccent)
            },
            lp(MATCH, WRAP, 12, 14)
        )
        themeCard.addView(label("Colour", 12f, Pal.muted, true), lp(WRAP, WRAP, 0, 8))
        val swRow = LinearLayout(this)
        swRow.orientation = LinearLayout.HORIZONTAL
        for (n in Pal.accentNames) {
            val sw = swatch(n, n == TvHub.themeAccent) { changeTheme(TvHub.themeDark, n) }
            swRow.addView(sw, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        themeCard.addView(swRow)
        col.addView(themeCard, lp(MATCH, WRAP, 0, 14))
        settingsCol = col

        // Remote
        val remoteCard = card()
        remoteCard.addView(label("Bluetooth remote", 14f, Pal.text, true))
        infoView = label("", 12f, Pal.muted)
        remoteCard.addView(infoView, lp(MATCH, WRAP, 6, 12))
        fun toolRow(first: Pair<String, () -> Unit>, second: Pair<String, () -> Unit>): LinearLayout {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val a = LinearLayout.LayoutParams(0, WRAP, 1f)
            a.rightMargin = dp(6)
            val b = LinearLayout.LayoutParams(0, WRAP, 1f)
            row.addView(pillButton(first.first, Pal.key, Pal.text, 42, 13f, first.second), a)
            row.addView(pillButton(second.first, Pal.key, Pal.text, 42, 13f, second.second), b)
            return row
        }
        remoteCard.addView(
            toolRow("Find remote" to { showRemoteDialog() }, "Learn" to { showLearnDialog() }),
            lp(MATCH, WRAP, 0, 8)
        )
        remoteCard.addView(
            toolRow(
                "Key access" to { openSystem(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                "Battery" to {
                    openSystem(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                }
            ),
            lp(MATCH, WRAP)
        )
        col.addView(remoteCard, lp(MATCH, WRAP, 0, 14))

        // Programmable buttons
        val keysCard = card()
        keysCard.addView(label("Program remote buttons", 14f, Pal.text, true))
        keysCard.addView(
            label("Make any button on your remote open a TV app or press a TV key. Good for a special button such as COSMOTE.", 12f, Pal.muted),
            lp(MATCH, WRAP, 6, 12)
        )
        keysList = LinearLayout(this)
        keysList.orientation = LinearLayout.VERTICAL
        keysCard.addView(keysList, lp(MATCH, WRAP, 0, 8))
        keysCard.addView(pillButton("Program a button", Pal.key, Pal.accent, 46) { programNewKey() }, lp(MATCH, WRAP))
        col.addView(keysCard, lp(MATCH, WRAP, 0, 14))

        // Options
        val optCard = card()
        val (keepRow, keepSw) = switchRow(
            "Keep screen on", "While CGeez RC is open on the phone", TvHub.keepScreenOn
        ) { on ->
            TvHub.setKeepScreenOn(on)
            applyKeepOn()
        }
        keepOnSwitch = keepSw
        val (guardRow, guardSw) = switchRow(
            "Keep phone screen off", "If a remote button wakes the phone, turn the screen off again", TvHub.guardScreen
        ) { on -> TvHub.setGuardScreen(on) }
        guardSwitch = guardSw
        val (forceRow, forceSw) = switchRow(
            "Volume via TV keys", "Turn on if volume or channel buttons don't change anything", TvHub.forceKeys
        ) { on -> TvHub.setForceKeys(on) }
        forceSwitch = forceSw
        optCard.addView(guardRow)
        optCard.addView(forceRow, lp(MATCH, WRAP, 16))
        optCard.addView(keepRow, lp(MATCH, WRAP, 16))
        col.addView(optCard, lp(MATCH, WRAP, 0, 14))

        // TV
        val tvCard = card()
        tvCard.addView(label("TV", 14f, Pal.text, true))
        val tvRow = LinearLayout(this)
        tvRow.orientation = LinearLayout.HORIZONTAL
        val dis = pillButton("Disconnect", Pal.key, Pal.danger, 42) {
            stopTv()
            closeSettings()
        }
        val forget = pillButton("Forget pairing", Pal.key, Pal.text, 42) {
            TvHub.forgetPairing()
            stopTv()
            onHubStatus("Pairing forgotten. Connect again to re-pair.", false)
            closeSettings()
        }
        val dp1 = LinearLayout.LayoutParams(0, WRAP, 1f)
        dp1.rightMargin = dp(6)
        tvRow.addView(dis, dp1)
        tvRow.addView(forget, LinearLayout.LayoutParams(0, WRAP, 1f))
        tvCard.addView(tvRow, lp(MATCH, WRAP, 12))
        col.addView(tvCard, lp(MATCH, WRAP))

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        page.addView(scroll, lp(MATCH, 0, 0, 0, 1f))
        return page
    }

    private fun openSettings(animate: Boolean = true) {
        refreshKeys()
        keepOnSwitch.isChecked = TvHub.keepScreenOn
        guardSwitch.isChecked = TvHub.guardScreen
        forceSwitch.isChecked = TvHub.forceKeys
        onHubState()
        settingsView.animate().cancel()
        settingsView.visibility = View.VISIBLE
        val cards = settingsCol
        if (!animate) {
            settingsView.alpha = 1f
            settingsView.translationY = 0f
            return
        }
        settingsView.alpha = 0f
        settingsView.translationY = dp(28).toFloat()
        settingsView.animate().alpha(1f).translationY(0f).setDuration(300)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
        if (cards != null) {
            for (i in 0 until cards.childCount) {
                val c = cards.getChildAt(i)
                c.animate().cancel()
                c.alpha = 0f
                c.translationY = dp(24).toFloat()
                c.animate().alpha(1f).translationY(0f).setStartDelay(60L + i * 45L).setDuration(340)
                    .setInterpolator(DecelerateInterpolator(1.6f)).start()
            }
        }
    }

    private fun closeSettings() {
        TvHub.cancelCapture()
        if (settingsView.visibility != View.VISIBLE) return
        settingsView.animate().cancel()
        settingsView.animate().alpha(0f).translationY(dp(28).toFloat()).setDuration(200)
            .withEndAction { settingsView.visibility = View.GONE }.start()
    }

    private fun refreshKeys() {
        keysList.removeAllViews()
        val items = TvHub.customKeys.values.toList()
        if (items.isEmpty()) {
            keysList.addView(label("No buttons programmed yet.", 12f, Pal.muted), lp(WRAP, WRAP, 0, 4))
            return
        }
        for (k in items) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val txt = LinearLayout(this)
            txt.orientation = LinearLayout.VERTICAL
            txt.addView(label(k.keyName, 14f, Pal.text, true))
            txt.addView(label("→ ${k.action.label}", 12f, Pal.accent), lp(WRAP, WRAP, 1))
            txt.isClickable = true
            txt.setOnClickListener { chooseAction(k.id, k.keyName) }
            row.addView(txt, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(iconButton(R.drawable.ic_close, 40, 18, Pal.key, Pal.muted, "Remove") {
                TvHub.removeCustomKey(k.id)
                refreshKeys()
            })
            keysList.addView(row, lp(MATCH, WRAP, 0, 10))
        }
    }

    private fun switchRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): Pair<LinearLayout, Switch> {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val text = LinearLayout(this)
        text.orientation = LinearLayout.VERTICAL
        text.addView(label(title, 14f, Pal.text, true))
        text.addView(label(subtitle, 12f, Pal.muted), lp(WRAP, WRAP, 2))
        row.addView(text, LinearLayout.LayoutParams(0, WRAP, 1f))
        val sw = Switch(this)
        sw.isChecked = checked
        val on = intArrayOf(android.R.attr.state_checked)
        sw.thumbTintList = ColorStateList(arrayOf(on, intArrayOf()), intArrayOf(Pal.accent, Pal.muted))
        sw.trackTintList = ColorStateList(arrayOf(on, intArrayOf()), intArrayOf(Pal.withAlpha(Pal.accent, 0x66), Pal.line))
        sw.setOnCheckedChangeListener { _, isOn -> onChange(isOn) }
        row.addView(sw)
        return Pair(row, sw)
    }

    /**
     * A labelled slider for a millisecond value, snapped to [step]. [valueLabel] formats the
     * current value for display (e.g. "0.40s"); [onChange] is called as the user drags.
     */
    private fun seekRow(
        title: String,
        subtitle: String,
        min: Int,
        max: Int,
        step: Int,
        initial: Int,
        valueLabel: (Int) -> String,
        onChange: (Int) -> Unit
    ): Pair<LinearLayout, SeekBar> {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(label(title, 14f, Pal.text, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        val valueView = label(valueLabel(initial), 13f, Pal.accent, true)
        head.addView(valueView)
        row.addView(head)
        row.addView(label(subtitle, 12f, Pal.muted), lp(WRAP, WRAP, 2))
        val bar = SeekBar(this)
        val steps = (max - min) / step
        bar.max = steps
        bar.progress = ((initial - min).toFloat() / step).toInt().coerceIn(0, steps)
        bar.progressTintList = ColorStateList.valueOf(Pal.accent)
        bar.progressBackgroundTintList = ColorStateList.valueOf(Pal.line)
        bar.thumbTintList = ColorStateList.valueOf(Pal.accent)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                val v = min + p * step
                valueView.text = valueLabel(v)
                if (fromUser) onChange(v)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        row.addView(bar, lp(MATCH, WRAP, 2))
        return Pair(row, bar)
    }

    /** A row of pill choices; the selected one is filled with the accent colour. */
    private fun segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val views = ArrayList<TextView>()
        fun paint(sel: Int) {
            views.forEachIndexed { i, v ->
                val on = i == sel
                v.background = android.graphics.drawable.RippleDrawable(
                    rippleColor(), btnShape(if (on) Pal.accent else Pal.key, dpf(40)), null
                )
                v.setTextColor(if (on) Pal.onAccent else Pal.text)
            }
        }
        options.forEachIndexed { i, name ->
            val b = pillButton(name, Pal.key, Pal.text, 42, 13f) {
                paint(i)
                onPick(i)
            }
            views.add(b)
            val p = LinearLayout.LayoutParams(0, WRAP, 1f)
            if (i > 0) p.leftMargin = dp(6)
            row.addView(b, p)
        }
        paint(selected)
        return row
    }

    /** A round colour choice for the theme; the chosen one gets a ring and grows a little. */
    private fun swatch(name: String, chosen: Boolean, onPick: () -> Unit): FrameLayout {
        val cell = FrameLayout(this)
        val dot = View(this)
        val g = circle(Pal.swatch(name, Pal.dark))
        if (chosen) g.setStroke(dp(3), Pal.text)
        dot.background = g
        dot.scaleX = if (chosen) 1f else 0.82f
        dot.scaleY = dot.scaleX
        cell.addView(dot, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        cell.contentDescription = name
        cell.isClickable = true
        cell.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            dot.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120)
                .setInterpolator(OvershootInterpolator(2f)).start()
            onPick()
        }
        return cell
    }

    // ---- Choosing and learning the remote ----
    private fun remoteDialogBody(): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(22), dp(6), dp(22), dp(4))
        return col
    }

    /** First-run / "Find remote": shows the connected Bluetooth remote with a picture and asks for OK. */
    private fun showRemoteDialog() {
        if (isFinishing || isDestroyed) return
        val groups = TvHub.detectRemotes()
        val col = remoteDialogBody()
        val img = ImageView(this)
        img.setImageResource(R.drawable.img_remote)
        col.addView(img, lp(dp(78), dp(140), 4, 12))
        val builder = dialogBuilder().setView(col)

        if (groups.isEmpty()) {
            builder.setTitle("Connect your remote")
            col.addView(
                label(
                    "No Bluetooth remote is connected to this phone yet.\n\n" +
                        "1. Put your remote in pairing mode (see its manual).\n" +
                        "2. Open Bluetooth settings and choose it.\n" +
                        "3. Come back and tap Check again.\n\n" +
                        "Already paired? Press any button on the remote to wake it, then tap Check again.",
                    13f, Pal.text
                )
            )
            builder.setPositiveButton("Bluetooth settings") { _, _ ->
                TvHub.markOnboarded()
                openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            builder.setNeutralButton("Check again") { _, _ ->
                window.decorView.postDelayed({ showRemoteDialog() }, 150L)
            }
            builder.setNegativeButton("Skip") { _, _ -> TvHub.markOnboarded() }
        } else {
            var selected = 0
            if (groups.size == 1) {
                builder.setTitle("Remote found")
                val name = label(groups[0].name, 16f, Pal.accent, true)
                name.gravity = Gravity.CENTER
                col.addView(name, lp(MATCH, WRAP, 0, 6))
                val sub = label("Tap OK to use this remote for your TV.", 13f, Pal.text)
                sub.gravity = Gravity.CENTER
                col.addView(sub)
            } else {
                builder.setTitle("Which one is your remote?")
                val rg = RadioGroup(this)
                groups.forEachIndexed { i, g ->
                    val rb = RadioButton(this)
                    rb.id = 1000 + i
                    rb.text = g.name
                    rb.setTextColor(Pal.text)
                    rb.textSize = 15f
                    rb.buttonTintList = ColorStateList.valueOf(Pal.accent)
                    rg.addView(rb, RadioGroup.LayoutParams(MATCH, WRAP))
                }
                rg.check(1000)
                rg.setOnCheckedChangeListener { _, id -> selected = (id - 1000).coerceIn(0, groups.size - 1) }
                col.addView(rg)
            }
            builder.setPositiveButton("OK") { _, _ ->
                TvHub.setRemoteNames(listOf(groups[selected].name))
                Toast.makeText(this, "Remote saved: ${groups[selected].name}", Toast.LENGTH_SHORT).show()
                if (!accessibilityOn()) showKeyAccessDialog()
            }
            builder.setNegativeButton("Not now") { _, _ -> TvHub.markOnboarded() }
        }
        builder.show()
    }

    private fun showKeyAccessDialog() {
        dialogBuilder()
            .setTitle("Turn on key access")
            .setMessage(
                "So your remote can control the TV when this app is closed or the phone is locked, " +
                    "Android needs \"Key access\" (Accessibility) switched on for CGeez RC. " +
                    "It only reads button presses from your remote, never your screen.\n\n" +
                    "Next screen: Installed apps > CGeez RC > turn it on.\n\n" +
                    "If it is greyed out: Settings > Apps > CGeez RC > ⋮ > Allow restricted settings, then try again."
            )
            .setPositiveButton("Open settings") { _, _ ->
                openSystem(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /** Small window that tells what to press, shows what the app sees, and counts down. */
    private fun showLearnDialog() {
        TvHub.startLearning()
        val col = remoteDialogBody()
        val img = ImageView(this)
        img.setImageResource(R.drawable.img_remote)
        col.addView(img, lp(dp(58), dp(104), 0, 10))
        col.addView(
            label(
                "1. Make sure your remote is paired in Bluetooth settings.\n" +
                    "2. Now press the arrow keys, OK, Back and the volume buttons on the remote. " +
                    "Keep pressing for a few seconds.",
                13f, Pal.text
            ),
            lp(MATCH, WRAP, 0, 12)
        )
        val seen = label("Waiting for your remote…", 13f, Pal.muted, true)
        val timer = label("", 12f, Pal.muted)
        col.addView(seen, lp(MATCH, WRAP, 0, 4))
        col.addView(timer, lp(MATCH, WRAP))

        var cancelled = false
        val dlg = dialogBuilder()
            .setTitle("Learn your remote")
            .setView(col)
            .setPositiveButton("Done", null)
            .setNegativeButton("Cancel") { _, _ -> cancelled = true }
            .setOnCancelListener { cancelled = true }
            .setOnKeyListener { _, _, e ->
                TvHub.handleKey(e)
                TvHub.isExternalInput(e) // keep the remote's buttons from pressing the dialog's own buttons
            }
            .create()

        val total = 12000L
        val t0 = SystemClock.uptimeMillis()
        val ticker = object : Runnable {
            override fun run() {
                val left = total - (SystemClock.uptimeMillis() - t0)
                val names = TvHub.remoteNames.toList()
                if (names.isNotEmpty()) {
                    seen.text = "Seen: ${names.joinToString()}"
                    seen.setTextColor(Pal.accent)
                }
                timer.text = "${left / 1000 + 1} s left"
                if (left <= 0) dlg.dismiss() else ui.postDelayed(this, 200L)
            }
        }
        dlg.setOnDismissListener {
            ui.removeCallbacks(ticker)
            if (cancelled) {
                TvHub.cancelLearning()
            } else {
                val ok = TvHub.finishLearning()
                Toast.makeText(
                    this,
                    if (ok) "Remote saved: ${TvHub.remoteNames.joinToString()}"
                    else "No remote buttons seen. Pair the remote first, then try again.",
                    Toast.LENGTH_LONG
                ).show()
                onHubState()
                if (ok && !accessibilityOn()) showKeyAccessDialog()
            }
        }
        dlg.show()
        ui.post(ticker)
    }

    // ---- Programming a remote button ----
    private fun dialogBuilder(): AlertDialog.Builder =
        AlertDialog.Builder(
            this,
            if (Pal.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert
            else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        )

    private fun programNewKey() {
        val dlg = dialogBuilder()
            .setTitle("Press a button on your remote")
            .setMessage("Press the button you want to program (for example the COSMOTE button).\n\nIf nothing happens, choose your remote with Find remote and check that Key access is on.")
            .setNegativeButton("Cancel") { _, _ -> TvHub.cancelCapture() }
            .setOnCancelListener { TvHub.cancelCapture() }
            .setOnKeyListener { _, _, event -> TvHub.handleKey(event) }
            .create()
        TvHub.beginCapture { id, name ->
            dlg.dismiss()
            chooseAction(id, name)
        }
        dlg.show()
    }

    private fun chooseAction(keyId: String, keyName: String) {
        val options = arrayOf("Open a TV app", "Press a TV key", "TV command")
        dialogBuilder()
            .setTitle("What should $keyName do?")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickApp { app -> save(keyId, keyName, KeyAction("app", app.id, app.title)) }
                    1 -> pickFrom("Press a TV key", TvHub.keyChoices) { label, v ->
                        save(keyId, keyName, KeyAction("key", v, label))
                    }
                    else -> pickFrom("TV command", TvHub.commandChoices) { label, v ->
                        save(keyId, keyName, KeyAction("cmd", v, label))
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save(keyId: String, keyName: String, action: KeyAction) {
        TvHub.setCustomKey(keyId, keyName, action)
        refreshKeys()
        Toast.makeText(this, "$keyName → ${action.label}", Toast.LENGTH_SHORT).show()
    }

    private fun pickFrom(title: String, choices: List<Pair<String, String>>, onPick: (String, String) -> Unit) {
        val names = choices.map { it.first }.toTypedArray()
        dialogBuilder()
            .setTitle(title)
            .setItems(names) { _, which -> onPick(choices[which].first, choices[which].second) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Grid of the TV's installed apps with their real icons. */
    private fun pickApp(onPick: (TvApp) -> Unit) {
        val list = TvHub.apps
        if (list.isEmpty()) {
            Toast.makeText(this, "Connect to the TV once so its apps can be listed.", Toast.LENGTH_LONG).show()
            return
        }
        var dialog: AlertDialog? = null
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(8), dp(12), dp(8))
        for (chunk in list.chunked(4)) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            for (a in chunk) {
                val tile = appTile(a, 56, 68) {
                    dialog?.dismiss()
                    onPick(a)
                }
                row.addView(tile, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            for (i in chunk.size until 4) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            col.addView(row, lp(MATCH, WRAP, 0, 12))
        }
        val scroll = ScrollView(this)
        scroll.addView(col)
        val d = dialogBuilder()
            .setTitle("Choose an app")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .create()
        dialog = d
        d.show()
    }
}
