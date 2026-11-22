package com.cgreez.remote.control

import android.bluetooth.BluetoothDevice
import android.content.Context

/**
 * The Bluetooth keyboard feature has been removed: it could not run together with the Bluetooth
 * remote on Android. This empty stand-in only keeps the old function names so the screens that
 * still mention them keep compiling. Nothing here touches Bluetooth.
 */
object BtKeyboard {
    @Volatile var onChange: (() -> Unit)? = null
    @Volatile var remoteBusy: (() -> Boolean)? = null

    val connected: Boolean get() = false

    fun status(): String = "Removed"
    fun start(ctx: Context) {}
    fun stop() {}
    fun pairedDevices(ctx: Context): List<BluetoothDevice> = emptyList()
    fun nameOf(d: BluetoothDevice): String = ""
    fun connect(d: BluetoothDevice) {}
    fun press(): Boolean = false
    fun release() {}
    fun yieldToRemote() {}
    fun hasPermission(ctx: Context): Boolean = true
}
