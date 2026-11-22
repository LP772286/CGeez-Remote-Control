package com.cgreez.remote.control

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.annotation.TargetApi
import java.util.concurrent.Executor

/**
 * Lets the phone act as a Bluetooth keyboard for the TV, only to give OK a *real* hold.
 *
 * LG's Wi-Fi remote channel can only send a complete OK tap - never "key down ... key up" - so
 * the TV can't see a long press through it. A Bluetooth keyboard can: [press] sends the Enter key
 * down and keeps it down, [release] sends the key up, and the TV does its own long-press and
 * auto-repeat handling exactly as it does for a real remote or keyboard.
 *
 * Everything else (taps, arrows, volume, apps) keeps using Wi-Fi. This is opt-in in Settings, and
 * every call is a harmless no-op on phones or situations where it isn't available.
 * All calls are expected on the main thread.
 */
object BtKeyboard {

    private val main = Handler(Looper.getMainLooper())

    /** Called (on the main thread) whenever [status] may have changed, so the screen can refresh. */
    @Volatile var onChange: (() -> Unit)? = null

    private var hid: Any? = null // a Hid, only ever created on Android 9+
    private var wanted = false
    private var note = "Off"

    /** True while a TV (or other computer) is connected to the phone's Bluetooth keyboard. */
    val connected: Boolean
        get() = impl()?.host != null

    /** Short human-readable state for Settings. */
    fun status(): String {
        if (!wanted) return "Off"
        val h = impl() ?: return note
        val host = h.host
        if (host != null) return "Connected to ${h.nameOf(host)}"
        return if (h.registered) {
            "Ready. Pair the phone from the TV's Bluetooth settings, or tap \"Connect a paired TV\"."
        } else {
            note
        }
    }

    /** Starts (or restarts) the Bluetooth keyboard. Needs the Bluetooth permission on Android 12+. */
    fun start(ctx: Context) {
        wanted = true
        stopImpl()
        if (Build.VERSION.SDK_INT < 28) {
            note = "Needs Android 9 or newer."
            changed()
            return
        }
        if (!hasPermission(ctx)) {
            note = "Waiting for Bluetooth permission."
            changed()
            return
        }
        val adapter = (ctx.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            note = "This phone has no Bluetooth."
            changed()
            return
        }
        if (!adapter.isEnabled) {
            note = "Turn Bluetooth on, then switch this off and on again."
            changed()
            return
        }
        note = "Starting..."
        val h = Hid(ctx.applicationContext, adapter)
        hid = h
        if (!h.open()) {
            note = "This phone doesn't allow Bluetooth keyboard mode."
            stopImpl()
        }
        changed()
    }

    fun stop() {
        wanted = false
        stopImpl()
        note = "Off"
        changed()
    }

    /** Bluetooth devices this phone is already paired with (the TV will be one of them if paired before). */
    @SuppressLint("MissingPermission")
    fun pairedDevices(ctx: Context): List<BluetoothDevice> {
        if (!hasPermission(ctx)) return emptyList()
        val adapter = (ctx.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return try {
            adapter?.bondedDevices?.toList().orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    fun nameOf(d: BluetoothDevice): String = impl()?.nameOf(d) ?: safeName(d)

    /** Asks the keyboard to connect to an already-paired device. */
    fun connect(d: BluetoothDevice) {
        impl()?.connect(d)
    }

    /** Presses Enter and keeps it down until [release]. Returns false when there is no TV connected. */
    fun press(): Boolean = impl()?.press() ?: false

    /** Lets go of Enter. Always safe to call. */
    fun release() {
        impl()?.release()
    }

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun impl(): Hid? = if (Build.VERSION.SDK_INT >= 28) hid as? Hid else null

    private fun stopImpl() {
        impl()?.close()
        hid = null
    }

    private fun changed() {
        main.post { onChange?.invoke() }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(d: BluetoothDevice): String = try {
        d.name ?: d.address
    } catch (e: SecurityException) {
        "TV"
    }

    @TargetApi(28)
    @SuppressLint("MissingPermission")
    private class Hid(private val ctx: Context, private val adapter: BluetoothAdapter) {
        var host: BluetoothDevice? = null
        var registered = false
        private var proxy: BluetoothHidDevice? = null
        private var keyDown = false
        private val executor = Executor { r -> main.post(r) }

        // Releases the key by itself if the release is never seen (for example the app is closed mid-hold).
        private val safety = Runnable { release() }

        private val callback = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                this@Hid.registered = registered
                if (registered) {
                    host = pluggedDevice ?: try {
                        proxy?.connectedDevices?.firstOrNull()
                    } catch (e: SecurityException) {
                        null
                    }
                } else {
                    host = null
                    note = "This phone doesn't allow Bluetooth keyboard mode."
                }
                changed()
            }

            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> host = device
                    BluetoothProfile.STATE_DISCONNECTED -> if (device == host) {
                        host = null
                        keyDown = false
                        main.removeCallbacks(safety)
                    }
                }
                changed()
            }
        }

        private val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                val h = p as BluetoothHidDevice
                proxy = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "CGeez RC", "Remote OK hold", "CGeez",
                    BluetoothHidDevice.SUBCLASS1_KEYBOARD, DESCRIPTOR
                )
                val ok = try {
                    h.registerApp(sdp, null, null, executor, callback)
                } catch (e: Exception) {
                    false
                }
                if (!ok) {
                    note = "This phone doesn't allow Bluetooth keyboard mode."
                    changed()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                proxy = null
                host = null
                registered = false
                keyDown = false
                changed()
            }
        }

        fun open(): Boolean = try {
            adapter.getProfileProxy(ctx, listener, BluetoothProfile.HID_DEVICE)
        } catch (e: Exception) {
            false
        }

        fun close() {
            release()
            main.removeCallbacks(safety)
            val p = proxy
            if (p != null) {
                try {
                    p.unregisterApp()
                } catch (e: Exception) {
                }
                try {
                    adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, p)
                } catch (e: Exception) {
                }
            }
            proxy = null
            host = null
            registered = false
        }

        fun connect(d: BluetoothDevice) {
            try {
                proxy?.connect(d)
            } catch (e: Exception) {
            }
        }

        fun nameOf(d: BluetoothDevice): String = safeName(d)

        fun press(): Boolean {
            val p = proxy ?: return false
            val h = host ?: return false
            if (keyDown) return true
            val ok = try {
                p.sendReport(h, REPORT_ID, report(KEY_ENTER))
            } catch (e: Exception) {
                false
            }
            if (!ok) return false
            keyDown = true
            main.removeCallbacks(safety)
            main.postDelayed(safety, SAFETY_MS)
            return true
        }

        fun release() {
            if (!keyDown) return
            keyDown = false
            main.removeCallbacks(safety)
            val p = proxy ?: return
            val h = host ?: return
            try {
                p.sendReport(h, REPORT_ID, report(0))
            } catch (e: Exception) {
            }
        }

        /** One standard keyboard report: [modifiers, reserved, up to six keys held]. */
        private fun report(key: Int): ByteArray = byteArrayOf(0, 0, key.toByte(), 0, 0, 0, 0, 0)
    }

    private const val REPORT_ID = 1
    private const val KEY_ENTER = 0x28
    private const val SAFETY_MS = 15000L

    /** A standard boot-style keyboard HID report descriptor (report id 1, 8-byte input report). */
    private val DESCRIPTOR: ByteArray = intArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, REPORT_ID,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
        0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,
        0x95, 0x01, 0x75, 0x03, 0x91, 0x01,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
        0xC0
    ).map { it.toByte() }.toByteArray()
}
