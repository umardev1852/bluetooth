package com.btwalkie.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.IOException

/**
 * Asks a phone (possibly through other phones) which phones it is paired with, so the caller
 * can pick a phone that is out of its own Bluetooth range.
 */
@SuppressLint("MissingPermission")
object RelayClient {

    /** Lets the screen cancel a question that is still running. */
    class Request {
        @Volatile var cancelled = false
        @Volatile var socket: BluetoothSocket? = null

        fun cancel() {
            cancelled = true
            try {
                socket?.close()
            } catch (e: IOException) {
                // ignore
            }
        }
    }

    /**
     * [route] is the chain of phones to go through; the LAST one is asked. The answer is delivered
     * on the main thread: either a list of phones, or an error text.
     */
    fun listPeers(
        context: Context,
        route: List<String>,
        done: (List<Protocol.Peer>?, String) -> Unit
    ): Request {
        val req = Request()
        val ui = Handler(Looper.getMainLooper())
        val app = context.applicationContext
        Thread {
            var peers: List<Protocol.Peer>? = null
            var error = ""
            var socket: BluetoothSocket? = null
            try {
                val bt = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                if (bt == null || !bt.isEnabled) {
                    error = "Turn Bluetooth on first."
                } else {
                    val device = bt.getRemoteDevice(route[0])
                    var attempt = 0
                    while (socket == null && attempt < 3 && !req.cancelled) {
                        try {
                            val candidate = device.createRfcommSocketToServiceRecord(Protocol.APP_UUID)
                            req.socket = candidate
                            candidate.connect()
                            socket = candidate
                        } catch (e: IOException) {
                            try {
                                req.socket?.close()
                            } catch (e2: IOException) {
                                // ignore
                            }
                            try {
                                Thread.sleep(1000)
                            } catch (e3: InterruptedException) {
                                // ignore
                            }
                        }
                        attempt++
                    }
                    val s = socket
                    if (s == null) {
                        error = "Could not connect to that phone. Is it close, with Bluetooth on?"
                    } else {
                        s.outputStream.write(
                            Protocol.frame(Protocol.MSG_LIST, Protocol.RATE_16K, route.drop(1), "")
                        )
                        s.outputStream.flush()
                        peers = Protocol.readPeers(s.inputStream)
                        if (peers == null) {
                            error = "That phone did not answer. Install the newest BT Walkie on it too."
                        }
                    }
                }
            } catch (e: Exception) {
                error = "Bluetooth problem: " + e.javaClass.simpleName
            } finally {
                try {
                    socket?.close()
                } catch (e: IOException) {
                    // ignore
                }
            }
            if (!req.cancelled) {
                val result = peers
                val message = error
                ui.post { done(result, message) }
            }
        }.start()
        return req
    }
}
