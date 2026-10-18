package com.btwalkie.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small text log kept in a file (it survives the app being killed). It shows, for example,
 * when the call-waiting service started or stopped and why a call did not connect.
 */
object CallLog {
    private const val FILE_NAME = "btwalkie.log"
    private const val MAX_BYTES = 30000
    private val lock = Any()

    fun add(context: Context, message: String) {
        try {
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            val line = stamp + "  " + message + "\n"
            synchronized(lock) {
                val file = File(context.applicationContext.filesDir, FILE_NAME)
                if (file.exists() && file.length() > MAX_BYTES) {
                    val keep = file.readText().takeLast(MAX_BYTES / 2)
                    file.writeText(keep.substringAfter('\n', keep))
                }
                file.appendText(line)
            }
        } catch (e: Exception) {
            // logging must never break the app
        }
    }

    fun read(context: Context): String {
        return try {
            synchronized(lock) {
                val file = File(context.applicationContext.filesDir, FILE_NAME)
                if (!file.exists()) {
                    "(nothing logged yet)"
                } else {
                    file.readText().lines().takeLast(80).joinToString("\n")
                }
            }
        } catch (e: Exception) {
            "(could not read the log)"
        }
    }
}
