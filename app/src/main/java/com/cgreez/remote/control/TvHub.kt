package com.cgreez.remote.control

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.InputDevice
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** What a programmed remote button does. type: "app" (launch TV app), "key" (TV remote key) or "cmd". */
data class KeyAction(val type: String, val value: String, val label: String)

/** A remote button the user has programmed. [id] identifies the physical button. */
data class CustomKey(val id: String, val keyName: String, val action: KeyAction)

/**
 * Process-wide state shared by the screen, the background service and the key-capture service.
 * Holds the TV connection, reconnects automatically, and turns remote key presses into TV commands.
 */
object TvHub : TvClient.Callback {

    interface Listener {
        fun onHubStatus(text: String, isError: Boolean)
        fun onHubState()
        fun onHubApps() {}
    }

    /**
     * What a remote button does. [go] runs when the button is pressed, and with [repeat] it keeps
     * running while the button is held. A button with a [long] action is different: a quick tap
     * runs [go] when the button is released, and holding it for [longDelay] runs [long] once
     * instead (and [go] is skipped). With [longRepeat] set, [long] isn't a one-off: once it has
     * fired, it keeps firing again every [interval] for as long as the button stays down - used
     * for OK, which has no TV button code of its own to send once and be done with.
     */
    private class Act(
        val repeat: Boolean,
        val long: (() -> Unit)? = null,
        val longDelay: Long = LONG_PRESS_MS,
        val longRepeat: Boolean = false,
        val delay: Long = 380L,
        val interval: Long = 110L,
        val go: () -> Unit
    )

    @Volatile var listener: Listener? = null
    val client: TvClient by lazy { TvClient(this) }

    private lateinit var prefs: SharedPreferences
    private lateinit var appCtx: Context
    private lateinit var iconDir: File
    private val handler = Handler(Looper.getMainLooper())
    private val repeater = HoldRepeater(handler)
    private var heldId: String? = null
    // The input device (the remote) whose button is being held; if it vanishes, the release is never coming.
    private var downDev = -1

    /** How long a button with a long-press action (Back) must be held before the long action runs. */
    private const val LONG_PRESS_MS = 600L
    /** How long a direct volume/channel command may take to be answered before the key press is sent instead. */
    private const val DIRECT_REPLY_MS = 700L
    private const val DEFAULT_OK_HOLD_MS = 400L
    private const val DEFAULT_OK_HOLD_REPEAT_MS = 60L
    // The button that is down right now and is waiting to turn out to be a tap or a hold.
    private var longId: String? = null
    private var longAct: Act? = null
    private var longFired = false
    private val longTask = Runnable { onLongPress() }
    private val iconMem = LruCache<String, Bitmap>(80)

    @Volatile var connected = false
        private set
    @Volatile var wantConnected = false
        private set
    @Volatile var learning = false
        private set
    @Volatile var autoStart = false
        private set
    @Volatile var muted = false
        private set
    @Volatile var keepScreenOn = false
        private set
    /** When true, a remote button that wakes the phone's screen is undone straight away. */
    @Volatile var guardScreen = true
        private set
    /** When true, volume / channel / mute are sent as TV remote key presses instead of direct commands. */
    @Volatile var forceKeys = false
        private set
    /** How long OK must be held before it counts as a hold instead of a tap. Lower = more sensitive (triggers sooner). */
    @Volatile var okHoldMs = DEFAULT_OK_HOLD_MS
        private set
    /** What a held OK does: 0 = fast repeated OK (default), 1 = held pointer click, 2 = one single OK. */
    @Volatile var okHoldMode = 0
        private set
    val okHoldRepeat: Boolean get() = okHoldMode == 0
    /** How often OK is resent in the fast-repeat mode, in milliseconds. */
    @Volatile var okHoldRepeatMs = DEFAULT_OK_HOLD_REPEAT_MS
        private set
    /** Colour theme: dark or light, plus the accent colour name. */
    @Volatile var themeDark = true
        private set
    @Volatile var themeAccent = "blue"
        private set
    @Volatile var onboarded = false
        private set
    @Volatile var lastRemoteKeyAt = 0L
        private set
    @Volatile var lastRemotePowerAt = 0L
        private set
    @Volatile var apps: List<TvApp> = emptyList()
        private set

    /** Uptime of the last volume-down press that came from the TV remote (used to stop the lock-screen camera shortcut). */
    @Volatile var lastRemoteVolDownAt = 0L
        private set

    var ip = ""
    var mac = ""
    var lastKey = ""
    val remoteNames = mutableSetOf<String>()
    val customKeys = LinkedHashMap<String, CustomKey>()

    @Volatile private var capture: ((String, String) -> Unit)? = null
    private var playing = true
    private val keyFallback: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var learnBackup: Set<String> = emptySet()
    private var retryDelay = 4000L
    private val retry = Runnable { doConnect() }

    /** Apps shown first, in this order, when the TV has them. */
    private val favoriteApps = listOf(
        "netflix", "youtube.leanback.v4", "amazon", "com.disney.disneyplus-prod",
        "spotify-beehive", "com.apple.appletv", "hbomax", "com.webos.app.browser"
    )

    /** Choices offered when programming a button. */
    val keyChoices: List<Pair<String, String>> = listOf(
        "Home" to "HOME", "Back" to "BACK", "Menu" to "MENU", "Exit" to "EXIT", "Info" to "INFO",
        "OK" to "ENTER", "Up" to "UP", "Down" to "DOWN", "Left" to "LEFT", "Right" to "RIGHT",
        "Guide" to "GUIDE", "Red" to "RED", "Green" to "GREEN", "Yellow" to "YELLOW", "Blue" to "BLUE"
    )
    val commandChoices: List<Pair<String, String>> = listOf(
        "Volume up" to "volup", "Volume down" to "voldown", "Mute" to "mute",
        "Channel up" to "chup", "Channel down" to "chdown", "Play / Pause" to "playpause",
        "Stop" to "stop", "Rewind" to "rew", "Fast-forward" to "ff", "Power (TV on/off)" to "power"
    )

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        val app = ctx.applicationContext
        appCtx = app
        prefs = app.getSharedPreferences("lg", Context.MODE_PRIVATE)
        iconDir = File(app.cacheDir, "icons")
        iconDir.mkdirs()
        ip = prefs.getString("ip", "") ?: ""
        mac = prefs.getString("mac", "") ?: ""
        autoStart = prefs.getBoolean("auto", false)
        keepScreenOn = prefs.getBoolean("keep_on", false)
        guardScreen = prefs.getBoolean("guard", true)
        forceKeys = prefs.getBoolean("force_keys", false)
        okHoldMs = prefs.getLong("ok_hold_ms", DEFAULT_OK_HOLD_MS)
        okHoldMode = prefs.getInt("ok_hold_mode2", 1)
        okHoldRepeatMs = prefs.getLong("ok_hold_pace", DEFAULT_OK_HOLD_REPEAT_MS)
        themeDark = prefs.getBoolean("theme_dark", true)
        themeAccent = prefs.getString("theme_accent", "blue") ?: "blue"
        remoteNames.addAll(prefs.getStringSet("remote_names", emptySet()) ?: emptySet())
        onboarded = prefs.getBoolean("onboarded", false) || remoteNames.isNotEmpty()
        loadCustomKeys()
        apps = loadCachedApps()
        watchReleaseLosers(app)
        watchInputDevices(app)
    }

    /**
     * If any input device disappears (for example the Bluetooth remote drops out mid-press), its
     * key release will never arrive, so whatever was being held is let go now instead of
     * repeating on its own.
     */
    /**
     * Screen off or a Bluetooth device dropping can swallow the "released" event of a held button.
     * Either one lets go of whatever is held right away, so nothing keeps pressing on its own.
     */
    private fun watchReleaseLosers(app: Context) {
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                stopHold()
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(r, f)
            }
        } catch (e: Exception) {
        }
    }

    private fun watchInputDevices(app: Context) {
        val im = app.getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return
        im.registerInputDeviceListener(object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
            }
            override fun onInputDeviceChanged(deviceId: Int) {}
            override fun onInputDeviceRemoved(deviceId: Int) {
                stopHold()
            }
        }, handler)
    }

    fun saveSettings(newIp: String, newMac: String) {
        val ipChanged = newIp != ip
        ip = newIp
        mac = newMac
        prefs.edit().putString("ip", ip).putString("mac", mac).apply()
        // Already trying to reach the old address: switch to the new one right away.
        if (ipChanged && wantConnected) doConnect()
    }

    fun setKeepScreenOn(on: Boolean) {
        keepScreenOn = on
        prefs.edit().putBoolean("keep_on", on).apply()
    }

    fun setGuardScreen(on: Boolean) {
        guardScreen = on
        prefs.edit().putBoolean("guard", on).apply()
    }

    fun setForceKeys(on: Boolean) {
        forceKeys = on
        prefs.edit().putBoolean("force_keys", on).apply()
    }

    /** Hold sensitivity: how long OK must be held before it's a hold, in milliseconds. */
    fun setOkHoldMs(ms: Long) {
        okHoldMs = ms
        prefs.edit().putLong("ok_hold_ms", ms).apply()
    }

    fun setOkHoldMode(mode: Int) {
        okHoldMode = mode
        prefs.edit().putInt("ok_hold_mode2", mode).apply()
    }

    /** How often OK is resent in the fast-repeat mode, in milliseconds. */
    fun setOkHoldRepeatMs(ms: Long) {
        okHoldRepeatMs = ms
        prefs.edit().putLong("ok_hold_pace", ms).apply()
    }

    fun setTheme(dark: Boolean, accent: String) {
        themeDark = dark
        themeAccent = accent
        prefs.edit().putBoolean("theme_dark", dark).putString("theme_accent", accent).apply()
    }

    fun markOnboarded() {
        onboarded = true
        prefs.edit().putBoolean("onboarded", true).apply()
    }

    fun forgetPairing() {
        prefs.edit().remove("key_$ip").apply()
    }

    // ---- Connection ----
    fun start() {
        autoStart = true
        prefs.edit().putBoolean("auto", true).apply()
        if (wantConnected && (connected || client.isConnecting())) return
        wantConnected = true
        retryDelay = 4000L
        doConnect()
    }

    private fun doConnect() {
        if (!wantConnected) return
        handler.removeCallbacks(retry)
        if (ip.isBlank()) {
            wantConnected = false
            onStatus("Enter the TV's IP address first.", true)
            handler.post { listener?.onHubState() }
            return
        }
        client.connect(ip, prefs.getString("key_$ip", "") ?: "")
    }

    fun stop(userInitiated: Boolean = false) {
        if (userInitiated) {
            autoStart = false
            prefs.edit().putBoolean("auto", false).apply()
        }
        wantConnected = false
        handler.removeCallbacks(retry)
        stopHold()
        client.close()
        connected = false
        handler.post { listener?.onHubState() }
    }

    override fun onStatus(text: String, isError: Boolean) {
        handler.post { listener?.onHubStatus(text, isError) }
    }

    override fun onConnected(clientKey: String) {
        prefs.edit().putString("key_$ip", clientKey).apply()
        connected = true
        retryDelay = 4000L
        keyFallback.clear()
        handler.post { listener?.onHubState() }
        refreshMute()
        detectSoundOutput()
        loadApps()
    }

    override fun onDisconnected() {
        connected = false
        handler.post {
            stopHold()
            listener?.onHubState()
        }
        if (wantConnected) {
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, retryDelay)
            retryDelay = minOf(retryDelay * 2, 30000L)
        }
    }

    /** Lets go of everything held, from outside this file (screen off, service interrupted...). */
    fun releaseAll() {
        stopHold()
    }

    /** False when the device that pressed the held button is gone (remote dropped): no release will come. */
    private fun holdAlive(): Boolean = downDev < 0 || InputDevice.getDevice(downDev) != null

    private var ptrDown = false
    private val pointerSafety = Runnable { pointerUp() }

    /** Lets go of a held pointer click. Sent twice so one lost message can't leave it held on the TV. */
    private var jitter = 1
    private val pointerKeep = object : Runnable {
        override fun run() {
            if (!ptrDown) return
            jitter = -jitter
            client.pointerMove(jitter, 0, true)
            handler.postDelayed(this, 100L)
        }
    }

    private fun pointerUp() {
        handler.removeCallbacks(pointerSafety)
        handler.removeCallbacks(pointerKeep)
        if (!ptrDown) return
        ptrDown = false
        client.pointerHold(false)
        handler.postDelayed({ if (!ptrDown) client.pointerHold(false) }, 150L)
    }

    private fun stopHold() {
        pointerUp()
        repeater.stop()
        downDev = -1
        heldId = null
        handler.removeCallbacks(longTask)
        longId = null
        longAct = null
        longFired = false
    }

    /**
     * The button that was waiting to turn out to be a tap or a hold is let go (or another button
     * came first). A tap runs its normal action now; a hold already ran its long action.
     */
    private fun settleLong() {
        pointerUp() // a held OK ends the moment the button is let go
        handler.removeCallbacks(longTask)
        val act = longAct
        val held = longFired
        longId = null
        longAct = null
        longFired = false
        if (act != null && !held) act.go()
    }

    /**
     * The long-press button has been held long enough: runs its long action once. If the action
     * is [Act.longRepeat] (OK), that one run is just the start - it keeps being re-run every
     * [Act.interval] until the button is released, which [stopHold] (called on release) stops.
     */
    private fun onLongPress() {
        val act = longAct ?: return
        if (longFired) return
        if (!holdAlive()) { // the remote dropped while the button was down: nothing to hold
            stopHold()
            return
        }
        longFired = true
        act.long?.invoke()
        if (act.longRepeat) {
            heldId = longId
            repeater.start(initialDelay = act.interval, interval = act.interval) {
                if (holdAlive()) act.long?.invoke() else stopHold()
            }
        }
    }

    // ---- TV commands (used by the screen buttons, the remote and programmed buttons) ----
    fun pressKey(name: String) = client.button(name)

    /** True while the Bluetooth remote (or another external keyboard) is connected to the phone. */
    fun remoteConnected(): Boolean {
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (!likelyExternal(d)) continue
            if (remoteNames.isEmpty() || isRemote(d)) return true
        }
        return false
    }

    /**
     * A held OK begins. Mode 0 (default): the caller keeps resending OK very fast, which many TV
     * apps read as a held key. Mode 1: press and hold the TV's pointer click until released.
     * Mode 2: one single OK. Returns true only when a real pointer hold started.
     */
    fun okHoldStart(): Boolean {
        if (okHoldMode == 1 && client.pointerMove(1, 0, false)) {
            // Wake the TV cursor first, then press and hold the pointer button on it.
            client.pointerMove(-1, 0, false)
            ptrDown = true
            handler.removeCallbacks(pointerSafety)
            handler.postDelayed(pointerSafety, 8000L)
            handler.removeCallbacks(pointerKeep)
            handler.postDelayed({
                if (ptrDown) {
                    client.pointerHold(true)
                    handler.postDelayed(pointerKeep, 100L)
                }
            }, 80L)
            return true
        }
        client.button("ENTER")
        return false
    }

    /** The held OK is let go. */
    fun okHoldEnd() {
        pointerUp()
    }
    fun volumeUp() = adjust("audio/volumeUp", "VOLUMEUP")
    fun volumeDown() = adjust("audio/volumeDown", "VOLUMEDOWN")
    fun channelUp() = adjust("tv/channelUp", "CHANNELUP")
    fun channelDown() = adjust("tv/channelDown", "CHANNELDOWN")

    /**
     * Volume, channel and mute: tries the TV's direct command first. If the TV refuses it (this
     * happens with external speakers / ARC, or when the TV is on an HDMI input) the same action is
     * sent as a normal remote-key press, which the TV then handles like its own remote would.
     */
    private fun adjust(uri: String, button: String, payload: JSONObject? = null) {
        if (forceKeys || button in keyFallback) {
            client.button(button)
            return
        }
        // The direct command can fail silently (socket half-closed, TV not answering). Then the
        // press used to be lost until another button woke things up. Now it is never lost: if the
        // command can't be sent, or gets no answer in time, the same press goes out as a key.
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        val sent = client.request(uri, payload) { p ->
            if (settled.compareAndSet(false, true)) {
                val ok = p == null || p.optBoolean("returnValue", true)
                if (!ok) {
                    keyFallback.add(button)
                    client.button(button)
                }
            }
        }
        if (!sent) {
            if (settled.compareAndSet(false, true)) client.button(button)
            return
        }
        handler.postDelayed({
            if (settled.compareAndSet(false, true)) client.button(button)
        }, DIRECT_REPLY_MS)
    }

    private fun detectSoundOutput() {
        client.request("com.webos.service.apiadapter/audio/getSoundOutput") { p ->
            val out = p?.optString("soundOutput").orEmpty()
            if (out.isNotEmpty() && out != "tv_speaker") {
                keyFallback.addAll(listOf("VOLUMEUP", "VOLUMEDOWN", "MUTE"))
            }
        }
    }
    fun rewind() = client.request("media.controls/rewind")
    fun fastForward() = client.request("media.controls/fastForward")
    fun stopMedia() = client.request("media.controls/stop")
    fun turnOff() = client.request("system/turnOff")
    fun launchApp(id: String) = client.request("system.launcher/launch", JSONObject().put("id", id))

    fun toggleMute() {
        muted = !muted
        adjust("audio/setMute", "MUTE", JSONObject().put("mute", muted))
        handler.post { listener?.onHubState() }
    }

    fun playPause() {
        playing = !playing
        client.request(if (playing) "media.controls/play" else "media.controls/pause")
    }

    /** TV on/off: switches the TV off when connected, otherwise tries to wake it. */
    fun powerToggle() {
        if (connected) turnOff() else wake()
    }

    /** Shows [text] as a pop-up message on the TV screen. */
    fun sendMessage(text: String) {
        if (!connected) {
            onStatus("Connect to the TV first.", true)
            return
        }
        client.toast(text) { ok, err ->
            if (ok) onStatus("Message sent to the TV.", false)
            else onStatus("The TV refused the message: $err", true)
        }
    }

    private fun refreshMute() {
        client.request("audio/getVolume") { p ->
            val vs = p?.optJSONObject("volumeStatus")
            val m: Boolean? = if (p != null && p.has("muted")) {
                p.optBoolean("muted")
            } else if (vs != null && vs.has("muteStatus")) {
                vs.optBoolean("muteStatus")
            } else {
                null
            }
            if (m != null) {
                muted = m
                handler.post { listener?.onHubState() }
            }
        }
    }

    // ---- Installed TV apps and their icons ----
    private fun loadApps() {
        client.fetchApps { list ->
            if (list.isNotEmpty()) {
                val sorted = list.sortedWith(compareBy<TvApp>({ favRank(it.id) }, { it.title.lowercase() }))
                apps = sorted
                saveApps(sorted)
                handler.post { listener?.onHubApps() }
            }
        }
    }

    private fun favRank(id: String): Int {
        val i = favoriteApps.indexOf(id)
        return if (i < 0) 1000 else i
    }

    private fun saveApps(list: List<TvApp>) {
        val arr = JSONArray()
        for (a in list) arr.put(JSONObject().put("id", a.id).put("t", a.title).put("i", a.icon).put("b", a.bg))
        prefs.edit().putString("apps_cache", arr.toString()).apply()
    }

    private fun loadCachedApps(): List<TvApp> {
        val raw = prefs.getString("apps_cache", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<TvApp>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(TvApp(o.getString("id"), o.optString("t"), o.optString("i"), o.optString("b")))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Gets an app's real icon (memory, then disk, then from the TV). Calls back on the main thread. */
    fun loadIcon(app: TvApp, onResult: (Bitmap?) -> Unit) {
        val cached = iconMem.get(app.id)
        if (cached != null) {
            onResult(cached)
            return
        }
        val name = (app.id + "_" + app.icon.hashCode()).replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png"
        val file = File(iconDir, name)
        Thread {
            val disk: Bitmap? = if (file.exists()) {
                try { BitmapFactory.decodeFile(file.path) } catch (e: Exception) { null }
            } else {
                null
            }
            if (disk != null) {
                iconMem.put(app.id, disk)
                handler.post { onResult(disk) }
            } else if (app.icon.isEmpty()) {
                handler.post { onResult(null) }
            } else {
                client.fetchIcon(app.icon) { bmp ->
                    if (bmp != null) {
                        iconMem.put(app.id, bmp)
                        try {
                            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        } catch (e: Exception) {
                        }
                    }
                    handler.post { onResult(bmp) }
                }
            }
        }.start()
    }

    // ---- Programmed buttons ----
    private fun loadCustomKeys() {
        customKeys.clear()
        val raw = prefs.getString("custom_keys", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                val action = KeyAction(o.getString("t"), o.getString("v"), o.optString("l"))
                customKeys[id] = CustomKey(id, o.optString("n"), action)
            }
        } catch (e: Exception) {
        }
    }

    private fun saveCustomKeys() {
        val arr = JSONArray()
        for (k in customKeys.values) {
            arr.put(
                JSONObject().put("id", k.id).put("n", k.keyName)
                    .put("t", k.action.type).put("v", k.action.value).put("l", k.action.label)
            )
        }
        prefs.edit().putString("custom_keys", arr.toString()).apply()
    }

    fun setCustomKey(id: String, keyName: String, action: KeyAction) {
        customKeys[id] = CustomKey(id, keyName, action)
        saveCustomKeys()
    }

    fun removeCustomKey(id: String) {
        customKeys.remove(id)
        saveCustomKeys()
    }

    /** Waits for the next press on the TV remote and reports its id and name. Keys are not sent to the TV meanwhile. */
    fun beginCapture(cb: (String, String) -> Unit) {
        capture = cb
    }

    fun cancelCapture() {
        capture = null
    }

    private fun keyId(e: KeyEvent): String =
        if (e.keyCode != KeyEvent.KEYCODE_UNKNOWN) "k${e.keyCode}" else "s${e.scanCode}"

    private fun keyName(e: KeyEvent): String {
        if (e.keyCode == KeyEvent.KEYCODE_UNKNOWN) return "Button ${e.scanCode}"
        val n = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_").replace('_', ' ')
        return if (n.all { it.isDigit() }) "Key $n" else n
    }

    // ---- Wake on LAN ----
    fun wake() {
        val hex = mac.replace(Regex("[^0-9A-Fa-f]"), "")
        if (hex.length != 12) {
            onStatus("Enter the TV's MAC address (12 hex digits) to wake it.", true)
            return
        }
        val target = ip
        Thread {
            try {
                val macBytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val data = ByteArray(6 + 16 * 6)
                for (i in 0 until 6) data[i] = 0xFF.toByte()
                for (i in 0 until 16) System.arraycopy(macBytes, 0, data, 6 + i * 6, 6)
                val targets = mutableListOf("255.255.255.255")
                if (target.count { it == '.' } == 3) targets.add(target.substringBeforeLast('.') + ".255")
                DatagramSocket().use { s ->
                    s.broadcast = true
                    for (t in targets) for (port in intArrayOf(9, 7)) {
                        s.send(DatagramPacket(data, data.size, InetAddress.getByName(t), port))
                    }
                }
                onStatus("Wake signal sent. The app will connect when the TV is up.", false)
            } catch (e: Exception) {
                onStatus("Could not send wake signal: ${e.message}", true)
            }
        }.start()
    }

    // ---- Choosing / learning which Bluetooth device is the remote ----
    private val builtinHints = listOf(
        "gpio", "kpd", "keypad", "fpc", "finger", "uinput", "headset", "touch", "qpnp", "pmic",
        "virtual", "sec_", "proximity"
    )

    /** True for a keyboard-type input device that is probably not one of the phone's own buttons. */
    fun likelyExternal(dev: InputDevice?): Boolean {
        if (dev == null || dev.isVirtual) return false
        if ((dev.sources and InputDevice.SOURCE_KEYBOARD) == 0) return false
        val n = (dev.name ?: "").lowercase()
        if (n.isBlank()) return false
        return builtinHints.none { n.contains(it) }
    }

    fun isExternalInput(e: KeyEvent): Boolean = likelyExternal(InputDevice.getDevice(e.deviceId))

    /** A remote as the user sees it. A remote often shows up as several devices with one shared name start. */
    data class RemoteGroup(val name: String, val parts: Int)

    private fun baseNames(names: Collection<String>): List<String> {
        val bases = ArrayList<String>()
        for (n in names.distinct().sortedBy { it.length }) {
            if (bases.none { n.startsWith(it, true) }) bases.add(n)
        }
        return bases
    }

    /** Bluetooth remotes (and other external keyboards) connected to the phone right now. */
    fun detectRemotes(): List<RemoteGroup> {
        val names = ArrayList<String>()
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (likelyExternal(d)) names.add(d.name)
        }
        return baseNames(names).map { b -> RemoteGroup(b, names.count { it.startsWith(b, true) }) }
    }

    fun setRemoteNames(names: Collection<String>) {
        remoteNames.clear()
        remoteNames.addAll(baseNames(names))
        persistRemote()
        markOnboarded()
        handler.post { listener?.onHubState() }
    }

    fun startLearning() {
        learnBackup = HashSet(remoteNames)
        remoteNames.clear()
        learning = true
    }

    /** Ends learning. Keeps what was seen; returns false (and keeps the old remote) if nothing was seen. */
    fun finishLearning(): Boolean {
        learning = false
        val seen = ArrayList(remoteNames)
        return if (seen.isEmpty()) {
            remoteNames.addAll(learnBackup)
            false
        } else {
            setRemoteNames(seen)
            true
        }
    }

    fun cancelLearning() {
        learning = false
        remoteNames.clear()
        remoteNames.addAll(learnBackup)
    }

    private fun persistRemote() {
        prefs.edit().putStringSet("remote_names", HashSet(remoteNames)).apply()
    }

    private fun noteKey(event: KeyEvent, dev: InputDevice?) {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return
        lastKey = "${KeyEvent.keyCodeToString(event.keyCode)} (scan ${event.scanCode}) from ${dev?.name ?: "unknown"}"
        if (learning && likelyExternal(dev)) remoteNames.add(dev!!.name)
        handler.post { listener?.onHubState() }
    }

    /** A device counts as the remote when its name matches the chosen remote's name. */
    fun isRemote(dev: InputDevice?): Boolean {
        if (dev == null || dev.isVirtual) return false
        val name = dev.name
        if (name.isNullOrBlank() || remoteNames.isEmpty()) return false
        return remoteNames.any { n -> name.startsWith(n, true) || n.startsWith(name, true) }
    }

    private fun isPowerKey(code: Int): Boolean =
        code == KeyEvent.KEYCODE_TV_POWER || code == KeyEvent.KEYCODE_POWER ||
            code == KeyEvent.KEYCODE_STB_POWER || code == KeyEvent.KEYCODE_SLEEP ||
            code == KeyEvent.KEYCODE_SOFT_SLEEP || code == KeyEvent.KEYCODE_WAKEUP

    // ---- Key handling: returns true when the key was taken by the TV remote ----
    fun handleKey(event: KeyEvent): Boolean {
        val dev = InputDevice.getDevice(event.deviceId)
        noteKey(event, dev)
        val remote = isRemote(dev)
        val code = event.keyCode
        val isDown = event.action == KeyEvent.ACTION_DOWN
        if (remote && isDown) {
            lastRemoteKeyAt = SystemClock.uptimeMillis()
            if (isPowerKey(code)) lastRemotePowerAt = lastRemoteKeyAt
        }
        // A release must always be seen while something is held, before any other check can skip
        // it (remote briefly reconnected under a new id, TV link dropped, settings changed...).
        // Otherwise the hold keeps repeating on its own.
        if (!isDown && (heldId != null || longId != null)) {
            val upId = keyId(event)
            val mine = heldId == upId || longId == upId
            if (heldId == upId) stopHold()
            if (longId == upId) settleLong()
            // Taken only when it was the remote's button (or the remote changed its name on reconnect).
            if (mine && (remote || dev == null || likelyExternal(dev))) return true
            if (remote || dev == null) return true
        }
        val capNow = capture
        if (capNow != null && !learning && (remote || likelyExternal(dev))) {
            if (isDown && event.repeatCount == 0) {
                capture = null
                val cid = keyId(event)
                val cname = keyName(event)
                handler.post { capNow(cid, cname) }
            }
            return true
        }
        if (learning || !remote) return false
        if (isDown && code == KeyEvent.KEYCODE_VOLUME_DOWN) lastRemoteVolDownAt = SystemClock.uptimeMillis()

        // "Program a button" is waiting for a press: take it instead of sending it to the TV.
        val cap = capture
        if (cap != null) {
            if (isDown && event.repeatCount == 0) {
                capture = null
                val id = keyId(event)
                val name = keyName(event)
                handler.post { cap(id, name) }
            }
            return true
        }

        // While the TV connection is wanted, the remote controls the TV only, never the phone.
        if (!wantConnected) return false

        if (!connected) {
            if (isPowerKey(code) && isDown && event.repeatCount == 0) wake()
            return true
        }

        val id = keyId(event)
        if (!isDown) {
            if (heldId == id) stopHold()
            if (longId == id) settleLong() // released: a tap sends its normal key, a hold already sent its own
            return true
        }
        // Android does not send repeats for keys we take, so holding is driven by our own timers.
        if (event.repeatCount > 0) return true

        val act = resolve(event) ?: return true
        settleLong() // another button was waiting to be a tap or a hold: it counts as a tap now
        stopHold()
        downDev = event.deviceId
        if (act.long != null) {
            // Wait to see whether this is a tap (sent on release) or a hold (the long action).
            longId = id
            longAct = act
            longFired = false
            handler.postDelayed(longTask, act.longDelay)
            return true
        }
        act.go()
        if (act.repeat) {
            heldId = id
            repeater.start(initialDelay = act.delay, interval = act.interval) {
                if (holdAlive()) act.go() else stopHold()
            }
        }
        return true
    }

    private fun resolve(e: KeyEvent): Act? {
        val custom = customKeys[keyId(e)]
        if (custom != null) return actFor(custom.action)
        return defaultAct(e.keyCode)
    }

    private fun actFor(a: KeyAction): Act = when (a.type) {
        "app" -> Act(false) { launchApp(a.value) }
        "key" -> tvKeyAct(a.value)
        else -> cmdAct(a.value)
    }

    /**
     * Sends one TV remote key. The rules follow a real LG remote:
     *  - OK is a tap = OK (sent on release). Held past [okHoldMs] (Settings' "Hold sensitivity")
     *    it starts a hold: a single soft OK by default, or, with [okHoldRepeat] turned on in
     *    Settings, OK resent every [okHoldRepeatMs] for as long as it's held.
     *  - Back is a tap = Back. Held for [LONG_PRESS_MS] it sends Exit once instead (no Back, Back, Back).
     *  - Arrows and the other keys keep repeating while held.
     */
    private fun tvKeyAct(name: String): Act = when (name) {
        "ENTER" -> Act(false, long = { okHoldStart() }, longDelay = okHoldMs, longRepeat = okHoldRepeat, interval = okHoldRepeatMs) {
            client.button("ENTER")
        }
        "BACK" -> Act(false, long = { client.button("EXIT") }) { client.button("BACK") }
        else -> Act(true) { client.button(name) }
    }

    private fun cmdAct(name: String): Act = when (name) {
        "volup" -> Act(true) { volumeUp() }
        "voldown" -> Act(true) { volumeDown() }
        "mute" -> Act(false) { toggleMute() }
        "chup" -> Act(true) { channelUp() }
        "chdown" -> Act(true) { channelDown() }
        "playpause" -> Act(false) { playPause() }
        "stop" -> Act(false) { stopMedia() }
        "rew" -> Act(true) { rewind() }
        "ff" -> Act(true) { fastForward() }
        "power" -> Act(false) { powerToggle() }
        else -> Act(false) { }
    }

    /** Built-in mapping of Android key codes to TV commands. */
    private fun defaultAct(code: Int): Act? {
        fun key(n: String) = tvKeyAct(n)
        fun once(n: String) = Act(false) { client.button(n) }
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> key("UP")
            KeyEvent.KEYCODE_DPAD_DOWN -> key("DOWN")
            KeyEvent.KEYCODE_DPAD_LEFT -> key("LEFT")
            KeyEvent.KEYCODE_DPAD_RIGHT -> key("RIGHT")
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> key("ENTER")
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> key("BACK")
            KeyEvent.KEYCODE_HOME -> key("HOME")
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> key("MENU")
            KeyEvent.KEYCODE_INFO -> key("INFO")
            KeyEvent.KEYCODE_GUIDE -> key("GUIDE")
            KeyEvent.KEYCODE_PROG_RED -> key("RED")
            KeyEvent.KEYCODE_PROG_GREEN -> key("GREEN")
            KeyEvent.KEYCODE_PROG_YELLOW -> key("YELLOW")
            KeyEvent.KEYCODE_PROG_BLUE -> key("BLUE")
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> once((code - KeyEvent.KEYCODE_0).toString())
            // Google Assistant / search button opens the TV's web browser
            KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH ->
                Act(false) { launchApp("com.webos.app.browser") }
            KeyEvent.KEYCODE_VOLUME_UP -> Act(true) { volumeUp() }
            KeyEvent.KEYCODE_VOLUME_DOWN -> Act(true) { volumeDown() }
            KeyEvent.KEYCODE_VOLUME_MUTE -> Act(false) { toggleMute() }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> Act(true) { channelUp() }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> Act(true) { channelDown() }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> Act(false) { playPause() }
            KeyEvent.KEYCODE_MEDIA_PLAY -> Act(false) { playing = true; client.request("media.controls/play") }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> Act(false) { playing = false; client.request("media.controls/pause") }
            KeyEvent.KEYCODE_MEDIA_STOP -> Act(false) { stopMedia() }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Act(true) { fastForward() }
            KeyEvent.KEYCODE_MEDIA_REWIND -> Act(true) { rewind() }
            KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_STB_POWER,
            KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_SOFT_SLEEP, KeyEvent.KEYCODE_WAKEUP ->
                Act(false) { powerToggle() }
            else -> null
        }
    }
}
