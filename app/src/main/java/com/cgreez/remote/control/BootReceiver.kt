package com.cgreez.remote.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the background connection after the phone reboots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TvHub.init(context)
        if (TvHub.autoStart) {
            try {
                context.startForegroundService(Intent(context, RemoteService::class.java))
            } catch (e: Exception) {
                TvHub.start()
            }
        }
    }
}
