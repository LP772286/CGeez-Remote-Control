package com.cgreez.remote.control

import android.os.Handler
import android.os.SystemClock

/**
 * Repeats an action while a button is held: the caller runs the action once on press, then
 * [start] repeats it after [initialDelay] every [interval] ms until [stop].
 * A safety limit stops it if the release is never seen (for example the remote disconnects).
 */
class HoldRepeater(private val handler: Handler) {
    private var task: Runnable? = null
    private var startedAt = 0L

    fun start(initialDelay: Long = 380L, interval: Long = 110L, action: () -> Unit) {
        stop()
        startedAt = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                if (SystemClock.uptimeMillis() - startedAt > 20000L) {
                    stop()
                    return
                }
                action()
                handler.postDelayed(this, interval)
            }
        }
        task = r
        handler.postDelayed(r, initialDelay)
    }

    fun stop() {
        val t = task
        if (t != null) handler.removeCallbacks(t)
        task = null
    }
}
