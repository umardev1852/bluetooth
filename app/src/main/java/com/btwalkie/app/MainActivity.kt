package com.btwalkie.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays
import java.util.UUID

/**
 * Two-way voice over a direct Bluetooth (RFCOMM) link between two paired phones.
 *
 * One phone "waits for a call" (server), the other "calls a paired phone" (client).
 * After the link is up, each phone streams its microphone to the other phone's
 * speaker. Works only within Bluetooth range.
 *
 * The screen is built in code (no layout XML), so this file plus the manifest is all
 * the app needs.
 */
@SuppressLint("MissingPermission") // permissions are requested in requirePermissions()
class MainActivity : AppCompatActivity() {

    companion object {
        // Both phones must use the same UUID. Any random UUID works.
        private val APP_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_BYTES = 640 // 20 ms of 16-bit mono audio
    }

    private lateinit var statusText: TextView
    private lateinit var hostButton: Button
    private lateinit var connectButton: Button
    private lateinit var hangUpButton: Button
    private lateinit var muteBox: CheckBox
    private lateinit var speakerBox: CheckBox

    private lateinit var audioManager: AudioManager
    private var adapter: BluetoothAdapter? = null

    private var serverSocket: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null

    @Volatile private var inCall = false
    @Volatile private var muted = false
    @Volatile private var speakerOn = false

    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var pendingAction: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            pendingAction?.invoke()
        } else {
            setStatus("Permission denied. The app needs microphone and Bluetooth access.")
        }
        pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

        if (adapter == null) {
            setStatus("This phone has no Bluetooth.")
            hostButton.isEnabled = false
            connectButton.isEnabled = false
        }

        muteBox.setOnCheckedChangeListener { _, checked -> muted = checked }
        speakerBox.setOnCheckedChangeListener { _, checked ->
            speakerOn = checked
            if (inCall) audioManager.isSpeakerphoneOn = checked
        }

        hostButton.setOnClickListener { requirePermissions { startHosting() } }
        connectButton.setOnClickListener { requirePermissions { showDevicePicker() } }
        hangUpButton.setOnClickListener { endCall("Call ended") }
    }

    override fun onDestroy() {
        endCall("Stopped")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- screen (built in code)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rowParams(topMarginDp: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(topMarginDp)
        return params
    }

    private fun buildUi() {
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(24), dp(24), dp(24), dp(24))

        val title = TextView(this)
        title.text = "Voice calls over Bluetooth"
        title.textSize = 22f
        title.setTypeface(title.typeface, Typeface.BOLD)
        column.addView(title, rowParams(0))

        val howTo = TextView(this)
        howTo.text = "1. Pair the two phones first in your phone's Bluetooth settings.\n" +
            "2. On phone A tap \"Wait for a call\".\n" +
            "3. On phone B tap \"Call a paired phone\" and pick phone A.\n\n" +
            "Both phones must stay within Bluetooth range."
        column.addView(howTo, rowParams(12))

        statusText = TextView(this)
        statusText.text = "Ready"
        statusText.textSize = 16f
        statusText.setTypeface(statusText.typeface, Typeface.BOLD)
        column.addView(statusText, rowParams(20))

        hostButton = Button(this)
        hostButton.text = "Wait for a call"
        column.addView(hostButton, rowParams(20))

        connectButton = Button(this)
        connectButton.text = "Call a paired phone"
        column.addView(connectButton, rowParams(8))

        hangUpButton = Button(this)
        hangUpButton.text = "Hang up"
        hangUpButton.isEnabled = false
        column.addView(hangUpButton, rowParams(8))

        muteBox = CheckBox(this)
        muteBox.text = "Mute my microphone"
        column.addView(muteBox, rowParams(16))

        speakerBox = CheckBox(this)
        speakerBox.text = "Use loudspeaker"
        column.addView(speakerBox, rowParams(0))

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.keepScreenOn = true
        scroll.addView(column)
        setContentView(scroll)
    }

    // ---------------------------------------------------------------- permissions

    private fun requirePermissions(action: () -> Unit) {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ---------------------------------------------------------------- connecting

    private fun bluetoothReady(): BluetoothAdapter? {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            setStatus("Turn Bluetooth on first.")
            return null
        }
        return bt
    }

    private fun startHosting() {
        val bt = bluetoothReady() ?: return
        if (inCall) return
        showBusyButtons()
        setStatus("Waiting for the other phone to call...")

        Thread {
            try {
                val server = bt.listenUsingRfcommWithServiceRecord("BtWalkie", APP_UUID)
                serverSocket = server
                val connected = server.accept() // blocks until the other phone connects
                try {
                    server.close()
                } catch (closeError: IOException) {
                    // ignore
                }
                serverSocket = null
                beginCall(connected)
            } catch (e: IOException) {
                // If the user pressed Hang up, serverSocket is already null and the UI is reset.
                if (serverSocket != null) endCall("Could not start waiting for a call.")
            }
        }.start()
    }

    private fun showDevicePicker() {
        val bt = bluetoothReady() ?: return
        if (inCall) return
        val paired: List<BluetoothDevice> = bt.bondedDevices.toList()
        if (paired.isEmpty()) {
            setStatus("No paired phones. Pair them in Bluetooth settings first.")
            return
        }
        val names = paired.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Call which phone?")
            .setItems(names) { _, index -> connectTo(paired[index]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectTo(device: BluetoothDevice) {
        if (inCall) return
        showBusyButtons()
        setStatus("Calling ${device.name ?: device.address}...")

        Thread {
            try {
                val s = device.createRfcommSocketToServiceRecord(APP_UUID)
                s.connect()
                beginCall(s)
            } catch (e: IOException) {
                endCall("Could not connect. Is the other phone waiting for a call?")
            }
        }.start()
    }

    // ---------------------------------------------------------------- the call

    private fun beginCall(connected: BluetoothSocket) {
        socket = connected
        inCall = true

        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = speakerOn

        setStatus("Connected to ${connected.remoteDevice.name ?: connected.remoteDevice.address}")
        runOnUiThread { showBusyButtons() }

        startSender(connected.outputStream)
        startReceiver(connected.inputStream)
    }

    /** Microphone -> Bluetooth. */
    private fun startSender(out: OutputStream) {
        Thread {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // enables echo cancellation where supported
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, CHUNK_BYTES * 4)
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                endCall("Could not open the microphone.")
                return@Thread
            }

            val buffer = ByteArray(CHUNK_BYTES)
            try {
                recorder.startRecording()
                while (inCall) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) continue
                    if (muted) Arrays.fill(buffer, 0, n, 0.toByte())
                    out.write(buffer, 0, n)
                }
            } catch (e: IOException) {
                // link dropped
            } catch (e: IllegalStateException) {
                // recorder stopped
            } finally {
                try {
                    recorder.stop()
                } catch (e: IllegalStateException) {
                    // already stopped
                }
                recorder.release()
                endCall("Call ended")
            }
        }.start()
    }

    /** Bluetooth -> speaker. */
    private fun startReceiver(input: InputStream) {
        Thread {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, CHUNK_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val buffer = ByteArray(CHUNK_BYTES)
            try {
                track.play()
                while (inCall && readFully(input, buffer)) {
                    track.write(buffer, 0, buffer.size)
                }
            } catch (e: IOException) {
                // link dropped
            } catch (e: IllegalStateException) {
                // track stopped
            } finally {
                try {
                    track.stop()
                } catch (e: IllegalStateException) {
                    // already stopped
                }
                track.release()
                endCall("The other phone disconnected.")
            }
        }.start()
    }

    /** Reads exactly buffer.size bytes so audio samples never get split. */
    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) return false
            offset += n
        }
        return true
    }

    private fun endCall(message: String) {
        synchronized(this) {
            inCall = false
            try {
                socket?.close()
            } catch (e: IOException) {
                // ignore
            }
            socket = null
            try {
                serverSocket?.close()
            } catch (e: IOException) {
                // ignore
            }
            serverSocket = null
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = previousAudioMode
        }
        setStatus(message)
        runOnUiThread { showIdleButtons() }
    }

    // ---------------------------------------------------------------- UI helpers

    private fun setStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    private fun showBusyButtons() {
        hostButton.isEnabled = false
        connectButton.isEnabled = false
        hangUpButton.isEnabled = true
    }

    private fun showIdleButtons() {
        val hasBluetooth = adapter != null
        hostButton.isEnabled = hasBluetooth
        connectButton.isEnabled = hasBluetooth
        hangUpButton.isEnabled = false
    }
}
