package com.cgreez.remote.control

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Receives hardware key presses system-wide (app closed, other apps open, phone locked) and hands
 * them to TvHub. Only keys from the Bluetooth TV remote are taken; the phone's own buttons behave
 * normally. It does not read the screen.
 *
 * Screen guard: Android wakes the phone for any key from an external device before apps can stop
 * it, and treats a power key from the remote as the phone's own power key. So when the screen
 * changes state right after a remote button, the change is undone (screen locked again, or woken
 * again) so the phone is left exactly as it was.
 */
class RemoteKeyService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var receiverOn = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> guard(true)
                Intent.ACTION_SCREEN_OFF -> guard(false)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        TvHub.init(this)
        if (!receiverOn) {
            val f = IntentFilter(Intent.ACTION_SCREEN_ON)
            f.addAction(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, f)
            }
            receiverOn = true
        }
        startBackground()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (receiverOn) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (e: Exception) {
            }
            receiverOn = false
        }
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        TvHub.init(this)
        if (!TvHub.wantConnected && TvHub.autoStart) startBackground()
        return TvHub.handleKey(event)
    }

    private fun startBackground() {
        if (!TvHub.autoStart || TvHub.wantConnected) return
        try {
            startForegroundService(Intent(this, RemoteService::class.java))
        } catch (e: Exception) {
            TvHub.start()
        }
    }

    /** The screen just turned on/off. Waits a moment for the key event to arrive, then decides. */
    private fun guard(nowOn: Boolean) {
        if (!TvHub.guardScreen || !TvHub.wantConnected) return
        handler.postDelayed({ evaluate(nowOn) }, 120L)
        handler.postDelayed({ evaluate(nowOn) }, 450L)
    }

    private fun evaluate(nowOn: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - TvHub.lastRemoteKeyAt > 1500L) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isInteractive != nowOn) return // already changed again (for example by the user)
        if (nowOn) {
            // A remote button woke the phone: lock it again.
            if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else if (now - TvHub.lastRemotePowerAt < 1500L) {
            // The remote's power key switched the phone off: wake it again.
            wakeScreen(pm)
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen(pm: PowerManager) {
        try {
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "cgreez:rewake"
            )
            wl.acquire(1500L)
        } catch (e: Exception) {
        }
    }

    /**
     * Many phones open the camera from the lock screen when volume-down is pressed twice quickly.
     * That shortcut runs inside Android before apps can block it, so when it fires right after a
     * volume-down from the TV remote, the camera is closed again straight away.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (!pkg.contains("camera", ignoreCase = true)) return
        if (SystemClock.uptimeMillis() - TvHub.lastRemoteVolDownAt > 3000L) return
        val km = getSystemService(KeyguardManager::class.java)
        if (km != null && km.isKeyguardLocked) performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun onInterrupt() {}
}
