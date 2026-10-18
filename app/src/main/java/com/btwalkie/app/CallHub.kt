package com.btwalkie.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** What the phone is doing right now. */
enum class Phase { IDLE, OUTGOING, INCOMING, ACTIVE }

/**
 * Shared state between the background service (which owns the call) and the screen.
 * The service changes the values and calls changed(); the screen redraws itself.
 */
object CallHub {
    @Volatile var phase: Phase = Phase.IDLE
    @Volatile var peerName: String = ""
    @Volatile var startedAt: Long = 0L
    @Volatile var message: String = ""
    @Volatile var listening: Boolean = false
    @Volatile var muted: Boolean = false
    @Volatile var speaker: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /** Tell the screen that something changed. Safe to call from any thread. */
    fun changed() {
        handler.post {
            for (l in listeners) l()
        }
    }

    // "Receive calls" switch, remembered between app starts and reboots.
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("btwalkie", Context.MODE_PRIVATE).getBoolean("enabled", true)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("btwalkie", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", value).apply()
    }
}
