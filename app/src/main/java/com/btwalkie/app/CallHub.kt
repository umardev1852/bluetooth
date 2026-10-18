package com.btwalkie.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** What the phone is doing right now. */
enum class Phase { IDLE, OUTGOING, INCOMING, ACTIVE, RELAY }

/**
 * Shared state between the background service (which owns the call) and the screen.
 * The service changes the values and calls changed(); the screen redraws itself.
 * It also keeps a small event log (saved to disk) to find out why a call did not work.
 */
object CallHub {
    @Volatile var phase: Phase = Phase.IDLE
    @Volatile var peerName: String = ""
    @Volatile var via: String = "" // e.g. "via Redmi 9" for a long range call
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

    // ---------------------------------------------------------------- event log

    private val logLines = ArrayList<String>()
    private var appContext: Context? = null

    /** Loads the saved log. Call once from the service and the screen. */
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val saved = app.getSharedPreferences("btwalkie", Context.MODE_PRIVATE).getString("log", "") ?: ""
        synchronized(logLines) {
            if (logLines.isEmpty() && saved.isNotEmpty()) logLines.addAll(saved.split("\n"))
        }
        log("App process started")
    }

    fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        var joined = ""
        synchronized(logLines) {
            logLines.add(time + "  " + message)
            while (logLines.size > 60) logLines.removeAt(0)
            joined = logLines.joinToString("\n")
        }
        val app = appContext
        if (app != null) {
            try {
                app.getSharedPreferences("btwalkie", Context.MODE_PRIVATE)
                    .edit().putString("log", joined).apply()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    /** Newest line first. */
    fun logText(): String =
        synchronized(logLines) { logLines.asReversed().joinToString("\n") }
}
