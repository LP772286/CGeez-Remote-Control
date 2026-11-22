package com.cgreez.remote.control

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** An app installed on the TV. [icon] is the icon URL the TV reports, [bg] its brand colour (may be empty). */
data class TvApp(val id: String, val title: String, val icon: String, val bg: String)

/** Talks to an LG webOS TV over its local WebSocket API (wss, port 3001). */
class TvClient(private val cb: Callback) {

    interface Callback {
        fun onStatus(text: String, isError: Boolean)
        fun onConnected(clientKey: String)
        fun onDisconnected()
    }

    // The TV uses a self-signed certificate, so certificate checks are skipped.
    // This is only used for the connection to the TV on your own network.
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val http: OkHttpClient = run {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private val perms = listOf(
        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN", "TEST_PROTECTED",
        "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
        "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV",
        "CONTROL_POWER", "CONTROL_TV_SCREEN", "CONTROL_TV_STANBY", "CONTROL_MOUSE_AND_KEYBOARD",
        "CONTROL_INPUT_TEXT", "CONTROL_BLUETOOTH", "READ_APP_STATUS", "READ_CURRENT_CHANNEL",
        "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE", "READ_RUNNING_APPS",
        "READ_TV_CHANNEL_LIST", "READ_POWER_STATE", "READ_COUNTRY_INFO", "READ_INSTALLED_APPS",
        "WRITE_NOTIFICATION_TOAST", "WRITE_NOTIFICATION_ALERT", "READ_SETTINGS",
        "CHECK_BLUETOOTH_DEVICE", "SEARCH", "TEST_SECURE", "UPDATE_FROM_REMOTE_APP"
    )

    private val iconHttp: OkHttpClient by lazy {
        http.newBuilder().readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    }

    @Volatile private var host = ""
    @Volatile private var ptrOpening = false
    @Volatile private var ws: WebSocket? = null
    @Volatile private var ptr: WebSocket? = null
    @Volatile private var generation = 0
    private val nextId = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, (JSONObject?) -> Unit>()
    private var key = ""

    fun connect(ip: String, savedKey: String) {
        close()
        key = savedKey
        host = ip
        val gen = ++generation
        val req = try {
            Request.Builder().url("wss://$ip:3001").build()
        } catch (e: Exception) {
            // A malformed IP address must never crash the app.
            cb.onStatus("That IP address is not valid. Enter the TV's IP like 192.168.1.x.", true)
            return
        }
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) return
                sendRegister(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                try {
                    handle(text)
                } catch (e: Exception) {
                    // One bad message from the TV must not take the app down.
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen != generation) return
                cb.onStatus("Disconnected from the TV.", true)
                cb.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation) return
                cb.onStatus("Could not reach the TV: ${t.message}. Check the IP, that the TV is on, and that you are on the same Wi-Fi.", true)
                cb.onDisconnected()
            }
        })
    }

    fun isConnecting(): Boolean = ws != null

    fun close() {
        generation++
        ws?.close(1000, null)
        ptr?.close(1000, null)
        ws = null
        ptr = null
        ptrOpening = false
        pending.clear()
    }

    private fun sendRegister(socket: WebSocket) {
        val permArray = JSONArray()
        perms.forEach { permArray.put(it) }
        val manifest = JSONObject()
            .put("manifestVersion", 1)
            .put("appVersion", "1.1")
            .put("permissions", permArray)
        val payload = JSONObject()
            .put("forcePairing", false)
            .put("pairingType", "PROMPT")
            .put("manifest", manifest)
        if (key.isNotEmpty()) payload.put("client-key", key)
        val msg = JSONObject()
            .put("type", "register")
            .put("id", "register_0")
            .put("payload", payload)
        socket.send(msg.toString())
    }

    private fun handle(text: String) {
        val m = try { JSONObject(text) } catch (e: Exception) { return }
        val type = m.optString("type")
        val id = m.optString("id")
        when {
            type == "registered" -> {
                val k = m.optJSONObject("payload")?.optString("client-key").orEmpty()
                if (k.isNotEmpty()) key = k
                openPointer()
                cb.onConnected(key)
            }
            type == "response" && id == "register_0" -> {
                val pairing = m.optJSONObject("payload")?.optString("pairingType").orEmpty()
                if (pairing == "PROMPT") {
                    cb.onStatus("Accept the pairing prompt on the TV with its joystick.", false)
                }
            }
            type == "error" && id == "register_0" ->
                cb.onStatus("TV refused pairing: ${m.optString("error")}", true)
            type == "error" -> {
                val err = m.optString("error")
                val waiting = pending.remove(id)
                if (waiting != null) {
                    waiting(JSONObject().put("returnValue", false).put("errorText", err))
                } else {
                    cb.onStatus("TV error: $err", true)
                }
            }
            type == "response" -> pending.remove(id)?.invoke(m.optJSONObject("payload"))
        }
    }

    private fun openPointer() {
        ptrOpening = true
        request("com.webos.service.networkinput/getPointerInputSocket") { p ->
            val path = p?.optString("socketPath").orEmpty()
            if (path.isEmpty()) {
                ptrOpening = false
                cb.onStatus("The TV gave no button channel, so the D-pad may not work.", true)
                return@request
            }
            val pReq = try {
                Request.Builder().url(path).build()
            } catch (e: Exception) {
                ptrOpening = false
                cb.onStatus("The TV gave an invalid button channel address.", true)
                return@request
            }
            ptr = http.newWebSocket(pReq, object : WebSocketListener() {
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (webSocket === ptr) { ptr = null; ptrOpening = false }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (webSocket === ptr) { ptr = null; ptrOpening = false }
                }
            })
            ptrOpening = false
        }
    }

    /** Sends a command to the TV. Returns false when it could not be sent (socket gone or closing). */
    fun request(uri: String, payload: JSONObject? = null, onResult: ((JSONObject?) -> Unit)? = null): Boolean {
        val id = "r${nextId.incrementAndGet()}"
        if (onResult != null) pending[id] = onResult
        val m = JSONObject().put("type", "request").put("id", id).put("uri", "ssap://$uri")
        if (payload != null) m.put("payload", payload)
        val sent = ws?.send(m.toString()) ?: false
        if (!sent) pending.remove(id)
        return sent
    }

    /** Remote-control button: UP, DOWN, LEFT, RIGHT, ENTER, BACK, HOME, MENU, EXIT, INFO... */
    fun button(name: String) {
        val p = ptr
        if (p == null) {
            if (!ptrOpening) {
                cb.onStatus("Button channel not ready, retrying. Press again in a second.", true)
                openPointer()
            }
            return
        }
        // If the Wi-Fi stalls, presses pile up and would all arrive in a burst after the button
        // was let go, which looks like the remote pressing on its own. Drop them instead.
        if (p.queueSize() > 150L) return
        p.send("type:button\nname:$name\n\n")
    }

    /**
     * Presses (true) or releases (false) the TV's pointer click, as a mouse button held down.
     * This is the only "held" input LG's Wi-Fi channel has. Returns false when it couldn't be sent.
     */
    fun pointerHold(down: Boolean): Boolean {
        val p = ptr ?: return false
        return p.send("type:move\ndx:0\ndy:0\ndown:${if (down) 1 else 0}\n\n")
    }

    /** Shows a pop-up message on the TV screen. */
    fun toast(message: String, onResult: (Boolean, String) -> Unit) {
        request("system.notifications/createToast", JSONObject().put("message", message)) { p ->
            val ok = p == null || p.optBoolean("returnValue", true)
            onResult(ok, p?.optString("errorText").orEmpty())
        }
    }

    private fun JSONObject.str(k: String): String {
        val v = optString(k, "")
        return if (v == "null") "" else v
    }

    /** Asks the TV for its installed apps (with icon URLs). Calls back on a background thread. */
    fun fetchApps(onResult: (List<TvApp>) -> Unit) {
        fun parse(arr: JSONArray?): List<TvApp> {
            val out = ArrayList<TvApp>()
            if (arr == null) return out
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.str("id")
                val title = o.str("title")
                if (id.isEmpty() || title.isEmpty() || !seen.add(id)) continue
                var icon = o.str("largeIcon")
                if (icon.isEmpty()) icon = o.str("icon")
                out.add(TvApp(id, title, icon, o.str("bgColor")))
            }
            return out
        }
        request("com.webos.applicationManager/listLaunchPoints") { p ->
            val list = parse(p?.optJSONArray("launchPoints"))
            if (list.isNotEmpty()) {
                onResult(list)
            } else {
                request("com.webos.applicationManager/listApps") { q ->
                    onResult(parse(q?.optJSONArray("apps")))
                }
            }
        }
    }

    private fun resolveIcon(u: String): String? {
        if (u.isEmpty()) return null
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        if (u.startsWith("/") && host.isNotEmpty()) return "http://$host:3000$u"
        return null
    }

    /** Downloads an app icon from the TV. Calls back on a background thread, null when it fails. */
    fun fetchIcon(url: String, onResult: (Bitmap?) -> Unit) {
        val full = resolveIcon(url)
        if (full == null) {
            onResult(null)
            return
        }
        val req = try {
            Request.Builder().url(full).build()
        } catch (e: Exception) {
            onResult(null)
            return
        }
        iconHttp.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                var bmp: Bitmap? = null
                try {
                    if (response.isSuccessful) bmp = BitmapFactory.decodeStream(response.body?.byteStream())
                } catch (e: Exception) {
                    bmp = null
                } finally {
                    response.close()
                }
                onResult(bmp)
            }
        })
    }
}
