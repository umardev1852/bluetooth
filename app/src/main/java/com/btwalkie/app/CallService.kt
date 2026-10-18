package com.btwalkie.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.btwalkie.app.Protocol.MSG_ACCEPT
import com.btwalkie.app.Protocol.MSG_BUSY
import com.btwalkie.app.Protocol.MSG_HELLO
import com.btwalkie.app.Protocol.MSG_LIST
import com.btwalkie.app.Protocol.MSG_REJECT
import com.btwalkie.app.Protocol.MSG_ROUTE
import com.btwalkie.app.Protocol.MSG_UNREACHABLE
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Owns everything about calls, so calls keep working when the screen is off.
 *
 * - Always listens for incoming Bluetooth calls (while "Receive calls" is on).
 * - Calling a paired phone connects to it directly; the other phone rings.
 * - Handshake bytes: caller sends HELLO, the other phone answers ACCEPT, REJECT or BUSY.
 * - Long range: a call can go through other phones (a route). A phone in the middle connects to the
 *   next phone and passes all bytes along (see Protocol). Relayed calls use 8 kHz to save bandwidth.
 * - After ACCEPT both phones stream voice in both directions (16 kHz, u-law compressed,
 *   20 ms frames) with a small jitter buffer on the playing side.
 */
@SuppressLint("MissingPermission")
class CallService : Service() {

    companion object {
        const val ACTION_START = "com.btwalkie.app.START"
        const val ACTION_CALL = "com.btwalkie.app.CALL"
        const val ACTION_ACCEPT = "com.btwalkie.app.ACCEPT"
        const val ACTION_REJECT = "com.btwalkie.app.REJECT"
        const val ACTION_HANGUP = "com.btwalkie.app.HANGUP"
        const val ACTION_STOP = "com.btwalkie.app.STOP"
        const val EXTRA_ROUTE = "route" // addresses of the phones to call through, last one is the target
        const val EXTRA_NAMES = "names"

        private const val FG_ID = 1
        private const val INCOMING_ID = 2
        private const val CH_STATUS = "status"
        private const val CH_INCOMING = "incoming"
        private const val RING_TIMEOUT_MS = 40_000L

        private val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        /** Standard G.711 u-law: 16-bit sample to 8-bit code (half the Bluetooth data). */
        private fun encodeUlaw(sample: Int): Byte {
            var s = sample
            var sign = 0
            if (s < 0) {
                s = -s
                sign = 0x80
            }
            if (s > 32635) s = 32635
            s += 0x84
            var exponent = 7
            var mask = 0x4000
            while (exponent > 0 && (s and mask) == 0) {
                exponent--
                mask = mask shr 1
            }
            val mantissa = (s shr (exponent + 3)) and 0x0F
            return (sign or (exponent shl 4) or mantissa).inv().toByte()
        }

        private fun decodeUlaw(code: Int): Short {
            val u = code.inv() and 0xFF
            val sign = u and 0x80
            val exponent = (u shr 4) and 0x07
            val mantissa = u and 0x0F
            var s = ((mantissa shl 3) + 0x84) shl exponent
            s -= 0x84
            return (if (sign != 0) -s else s).toShort()
        }

        private val DECODE_TABLE = ShortArray(256) { decodeUlaw(it) }
    }

    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var audioManager: AudioManager
    private lateinit var notifications: NotificationManager
    private var adapter: BluetoothAdapter? = null

    @Volatile private var running = false
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var callSocket: BluetoothSocket? = null
    @Volatile private var relaySocket: BluetoothSocket? = null // the connection to the next phone (relay only)
    @Volatile private var sampleRate = 16000 // changes per call: 16 kHz direct, 8 kHz through relays
    @Volatile private var frameSamples = 320 // 20 ms of audio at the rate above
    @Volatile private var relayedCall = false
    @Volatile private var callEpoch = 0 // changes whenever a call starts or ends
    @Volatile private var foregroundStarted = false
    @Volatile private var relistenNow = false
    private var listenThread: Thread? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var ringback: ToneGenerator? = null
    private var timeoutRunnable: Runnable? = null
    private val effects = mutableListOf<AudioEffect>()
    private val frames = ArrayBlockingQueue<ByteArray>(64)

    // ---------------------------------------------------------------- service basics

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        createChannels()
        CallHub.init(this)
        CallHub.log("Service created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val action = intent?.action
        CallHub.log("Command: " + (action ?: "restart"))
        if (action == ACTION_STOP) {
            CallHub.setEnabled(this, false)
            shutdown()
            return START_NOT_STICKY
        }
        running = true
        if (listenThread?.isAlive != true) {
            startListening() // also revives a listener that has died
        }
        when (action) {
            ACTION_CALL -> {
                val route = intent?.getStringArrayExtra(EXTRA_ROUTE)
                val names = intent?.getStringArrayExtra(EXTRA_NAMES)
                if (route != null && route.isNotEmpty()) {
                    placeCall(route.toList(), names?.toList() ?: route.toList())
                }
            }
            ACTION_ACCEPT -> acceptCall()
            ACTION_REJECT -> rejectCall()
            ACTION_HANGUP -> hangUp()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // ignore
        }
        endCall(callEpoch, "")
        CallHub.listening = false
        CallHub.changed()
        super.onDestroy()
    }

    private fun shutdown() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // ignore
        }
        endCall(callEpoch, "")
        CallHub.listening = false
        CallHub.changed()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun isCurrent(epoch: Int): Boolean = callEpoch == epoch

    private fun nameOf(device: BluetoothDevice): String =
        try {
            device.name ?: device.address
        } catch (e: SecurityException) {
            device.address
        }

    // ---------------------------------------------------------------- notifications

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val status = NotificationChannel(CH_STATUS, "Call status", NotificationManager.IMPORTANCE_LOW)
        val incoming = NotificationChannel(CH_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
        incoming.setSound(null, null) // the service plays the ringtone itself
        incoming.enableVibration(false)
        notifications.createNotificationChannel(status)
        notifications.createNotificationChannel(incoming)
    }

    private fun openAppIntent(requestCode: Int): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, requestCode, i, PI_FLAGS)
    }

    private fun serviceIntent(requestCode: Int, action: String): PendingIntent {
        val i = Intent(this, CallService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, i, PI_FLAGS)
    }

    private fun buildStatusNotification(): Notification {
        val b = NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openAppIntent(0))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        when (CallHub.phase) {
            Phase.IDLE -> {
                b.setContentTitle("BT Walkie")
                b.setContentText(if (CallHub.listening) "Ready to receive calls" else "Waiting for Bluetooth...")
                b.addAction(0, "Turn off", serviceIntent(10, ACTION_STOP))
            }
            Phase.OUTGOING -> {
                b.setContentTitle("Calling " + CallHub.peerName)
                b.addAction(0, "Hang up", serviceIntent(11, ACTION_HANGUP))
            }
            Phase.INCOMING -> {
                b.setContentTitle("Incoming call")
                b.setContentText(CallHub.peerName)
            }
            Phase.ACTIVE -> {
                b.setContentTitle("On a call")
                b.setContentText(CallHub.peerName)
                b.addAction(0, "Hang up", serviceIntent(11, ACTION_HANGUP))
            }
            Phase.RELAY -> {
                b.setContentTitle("Passing a call along")
                b.setContentText("Keep this phone close to both phones")
                b.addAction(0, "Stop", serviceIntent(11, ACTION_HANGUP))
            }
        }
        return b.build()
    }

    private fun foregroundType(withMic: Boolean): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (withMic) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return t
    }

    /** Keeps the service alive. While calling or talking it also holds "microphone" rights. */
    private fun goForeground() {
        val withMic = CallHub.phase == Phase.OUTGOING || CallHub.phase == Phase.ACTIVE
        val n = buildStatusNotification()
        try {
            ServiceCompat.startForeground(this, FG_ID, n, foregroundType(withMic))
            foregroundStarted = true
        } catch (e: Exception) {
            CallHub.log("Foreground (mic=" + withMic + ") failed: " + e.javaClass.simpleName)
            try {
                ServiceCompat.startForeground(this, FG_ID, n, foregroundType(false))
                foregroundStarted = true
            } catch (e2: Exception) {
                CallHub.log("Foreground failed: " + e2.javaClass.simpleName + " " + e2.message)
                // Never stop a listener that is already running just because a refresh failed.
                if (!foregroundStarted) stopSelf()
            }
        }
    }

    private fun refreshStatus() {
        try {
            notifications.notify(FG_ID, buildStatusNotification())
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun showIncomingNotification() {
        val acceptIntent = Intent(this, MainActivity::class.java)
        acceptIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        acceptIntent.putExtra(MainActivity.EXTRA_ACCEPT, true)
        val accept = PendingIntent.getActivity(this, 2, acceptIntent, PI_FLAGS)
        val open = openAppIntent(1)
        val n = NotificationCompat.Builder(this, CH_INCOMING)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Incoming call")
            .setContentText(CallHub.peerName)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, "Accept", accept)
            .addAction(0, "Decline", serviceIntent(3, ACTION_REJECT))
            .build()
        try {
            notifications.notify(INCOMING_ID, n)
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---------------------------------------------------------------- waiting for calls

    private fun startListening() {
        val t = Thread { listenLoop() }
        listenThread = t
        t.start()
    }

    private fun listenLoop() {
        try {
            while (running) {
                val bt = adapter
                if (bt == null || !bt.isEnabled || !hasBtPermission()) {
                    setListening(false)
                    sleepQuietly(2000)
                    continue
                }
                var server: BluetoothServerSocket? = null
                try {
                    server = bt.listenUsingRfcommWithServiceRecord("BtWalkie", Protocol.APP_UUID)
                    serverSocket = server
                    relistenNow = false
                    setListening(true)
                    CallHub.log("Listening for calls")
                    while (running) {
                        val s = server.accept() // blocks until another phone connects
                        CallHub.log("Connection from " + nameOf(s.remoteDevice))
                        try {
                            onIncomingSocket(s)
                        } catch (e: Exception) {
                            CallHub.log("Incoming error: " + e.javaClass.simpleName + " " + e.message)
                        }
                    }
                } catch (e: IOException) {
                    CallHub.log("Listening stopped: " + e.message)
                } catch (e: SecurityException) {
                    CallHub.log("Listening blocked: permission")
                } catch (e: Exception) {
                    CallHub.log("Listening error: " + e.javaClass.simpleName + " " + e.message)
                } finally {
                    try {
                        server?.close()
                    } catch (e: IOException) {
                        // ignore
                    }
                    serverSocket = null
                    setListening(false)
                }
                if (running && !relistenNow) sleepQuietly(1500)
                relistenNow = false
            }
        } finally {
            CallHub.log("Listener thread ended")
        }
    }

    /** Re-opens the listening socket so the next call always finds a fresh, working one. */
    private fun restartServer() {
        relistenNow = true
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            // ignore
        }
    }

    private fun setListening(value: Boolean) {
        if (CallHub.listening != value) {
            CallHub.listening = value
            refreshStatus()
            CallHub.changed()
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            // ignore
        }
    }

    private fun closeQuietly(s: BluetoothSocket?) {
        try {
            s?.close()
        } catch (e: IOException) {
            // ignore
        }
    }

    private fun useRate(rate: Int) {
        if (rate == Protocol.RATE_8K) {
            sampleRate = 8000
            frameSamples = 160
        } else {
            sampleRate = 16000
            frameSamples = 320
        }
    }

    /** A phone connected. Read what it wants on its own thread, so the accept loop stays free. */
    private fun onIncomingSocket(s: BluetoothSocket) {
        Thread { handleIncoming(s) }.start()
    }

    private fun handleIncoming(s: BluetoothSocket) {
        // A connection that says nothing for 8 seconds is closed, so it can never block later calls.
        val watchdog = Runnable { closeQuietly(s) }
        main.postDelayed(watchdog, 8000)
        var stream: InputStream? = null
        var type = -1
        var header: Protocol.Header? = null
        try {
            val input = s.inputStream
            stream = input
            type = input.read()
            if (type == MSG_ROUTE || type == MSG_LIST) header = Protocol.readHeader(input)
        } catch (e: IOException) {
            type = -1
        }
        main.removeCallbacks(watchdog)
        val inp = stream
        if (inp == null || type < 0) {
            closeQuietly(s)
            return
        }
        try {
            when (type) {
                MSG_HELLO -> startIncomingCall(s, inp, "", Protocol.RATE_16K, false)
                MSG_ROUTE -> {
                    val h = header
                    if (h == null) {
                        closeQuietly(s)
                    } else if (h.rest.isEmpty()) {
                        startIncomingCall(s, inp, h.name, h.rate, true) // this phone is the target
                    } else {
                        startRelay(s, h) // this phone is in the middle
                    }
                }
                MSG_LIST -> {
                    val h = header
                    if (h == null) closeQuietly(s) else answerList(s, inp, h)
                }
                else -> closeQuietly(s)
            }
        } catch (e: Exception) {
            CallHub.log("Incoming error: " + e.javaClass.simpleName + " " + e.message)
            closeQuietly(s)
        }
    }

    private fun refuseBusy(s: BluetoothSocket) {
        CallHub.log("Refused a call: busy (" + CallHub.phase + ")")
        try {
            s.outputStream.write(MSG_BUSY)
            s.outputStream.flush()
            Thread.sleep(300)
        } catch (e: Exception) {
            // ignore
        }
        closeQuietly(s)
    }

    private fun tellUnreachable(s: BluetoothSocket) {
        try {
            s.outputStream.write(MSG_UNREACHABLE)
            s.outputStream.flush()
            Thread.sleep(300)
        } catch (e: Exception) {
            // ignore
        }
    }

    /** A call for this phone: ring, and wait until the user answers or the other side gives up. */
    private fun startIncomingCall(
        s: BluetoothSocket,
        inp: InputStream,
        originName: String,
        rate: Int,
        relayed: Boolean
    ) {
        val epoch = synchronized(lock) {
            if (CallHub.phase == Phase.IDLE && callSocket == null) {
                callEpoch++
                callSocket = s
                useRate(rate)
                relayedCall = relayed
                CallHub.phase = Phase.INCOMING
                CallHub.peerName = if (originName.isNotEmpty()) originName else nameOf(s.remoteDevice)
                CallHub.via = if (relayed) "Long range call" else ""
                CallHub.message = ""
                callEpoch
            } else {
                -1
            }
        }
        if (epoch < 0) {
            refuseBusy(s)
            return
        }
        try {
            CallHub.log("Incoming call from " + CallHub.peerName + (if (relayed) " (through relay)" else "") + ", ringing")
            startRinging()
            showIncomingNotification()
            refreshStatus()
            scheduleTimeout(epoch, RING_TIMEOUT_MS, "Missed call from " + CallHub.peerName)
            CallHub.changed()
            receiveLoop(inp, epoch) // returns when the other phone hangs up
            endCall(epoch, endedByOtherMessage())
        } catch (e: IOException) {
            endCall(epoch, endedByOtherMessage())
        } catch (e: Exception) {
            CallHub.log("Incoming error: " + e.javaClass.simpleName + " " + e.message)
            endCall(epoch, "Call failed")
        }
    }

    // ---------------------------------------------------------------- long range: this phone in the middle

    /** Connects to another phone, trying a few times. [alive] lets the caller cancel early. */
    private fun connectWithRetry(
        address: String,
        maxAttempts: Int,
        maxMillis: Long,
        onSocket: (BluetoothSocket) -> Unit,
        alive: () -> Boolean
    ): BluetoothSocket? {
        val bt = adapter ?: return null
        val device: BluetoothDevice = try {
            bt.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val began = System.currentTimeMillis()
        var attempt = 0
        while (attempt < maxAttempts && System.currentTimeMillis() - began < maxMillis && alive()) {
            var current: BluetoothSocket? = null
            try {
                val c = device.createRfcommSocketToServiceRecord(Protocol.APP_UUID)
                current = c
                onSocket(c)
                c.connect()
                return c
            } catch (e: IOException) {
                CallHub.log("Connect attempt " + (attempt + 1) + " failed: " + e.message)
                closeQuietly(current)
                sleepQuietly(1000)
            }
            attempt++
        }
        return null
    }

    /** Copies bytes both ways between two phones until either one hangs up. */
    private fun bridge(a: BluetoothSocket, b: BluetoothSocket, wanted: () -> Boolean, onEnd: () -> Unit) {
        Thread { pump(a, b, wanted, onEnd) }.start()
        Thread { pump(b, a, wanted, onEnd) }.start()
    }

    private fun pump(from: BluetoothSocket, to: BluetoothSocket, wanted: () -> Boolean, onEnd: () -> Unit) {
        val buf = ByteArray(512)
        try {
            val input = from.inputStream
            val output = to.outputStream
            while (wanted()) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (e: IOException) {
            // one side dropped
        } finally {
            closeQuietly(from)
            closeQuietly(to)
            onEnd()
        }
    }

    /** This phone is in the middle: connect to the next phone and pass everything along. */
    private fun startRelay(s: BluetoothSocket, h: Protocol.Header) {
        val epoch = synchronized(lock) {
            if (CallHub.phase == Phase.IDLE && callSocket == null) {
                callEpoch++
                callSocket = s
                CallHub.phase = Phase.RELAY
                CallHub.peerName = "Relaying a call"
                CallHub.via = ""
                CallHub.message = ""
                callEpoch
            } else {
                -1
            }
        }
        if (epoch < 0) {
            refuseBusy(s)
            return
        }
        CallHub.log("Relaying a call from " + nameOf(s.remoteDevice) + " to the next phone")
        acquireWakeLock()
        refreshStatus()
        CallHub.changed()

        val next = connectWithRetry(h.rest[0], 3, 15000L, { relaySocket = it }) { isCurrent(epoch) }
        if (next == null || !isCurrent(epoch)) {
            closeQuietly(next)
            if (isCurrent(epoch)) tellUnreachable(s)
            endCall(epoch, "Relay: could not reach the next phone")
            return
        }
        try {
            next.outputStream.write(Protocol.frame(MSG_ROUTE, h.rate, h.rest.drop(1), h.name))
            next.outputStream.flush()
        } catch (e: IOException) {
            tellUnreachable(s)
            endCall(epoch, "Relay: could not reach the next phone")
            return
        }
        CallHub.log("Relay connected to the next phone")
        bridge(s, next, { isCurrent(epoch) }) { endCall(epoch, "Relay ended") }
    }

    private fun looksLikePhone(d: BluetoothDevice): Boolean {
        val major = d.bluetoothClass?.majorDeviceClass ?: return true
        return major == BluetoothClass.Device.Major.PHONE ||
            major == BluetoothClass.Device.Major.COMPUTER ||
            major == BluetoothClass.Device.Major.UNCATEGORIZED ||
            major == BluetoothClass.Device.Major.MISC
    }

    /** Someone asks which phones are paired with this phone (or with a phone further along the route). */
    private fun answerList(s: BluetoothSocket, inp: InputStream, h: Protocol.Header) {
        if (h.rest.isEmpty()) {
            // This phone is the one being asked: send the phones paired with it (not the one asking).
            val askedBy = try {
                s.remoteDevice.address
            } catch (e: Exception) {
                ""
            }
            val peers = ArrayList<Protocol.Peer>()
            val bt = adapter
            if (bt != null && hasBtPermission()) {
                for (d in bt.bondedDevices) {
                    if (d.address == askedBy || !looksLikePhone(d)) continue
                    peers.add(Protocol.Peer(nameOf(d), d.address))
                }
            }
            CallHub.log("Sending the list of " + peers.size + " paired phones")
            try {
                s.outputStream.write(Protocol.peersFrame(peers))
                s.outputStream.flush()
                // Wait for the other side to close first, so the list is never cut off.
                val watchdog = Runnable { closeQuietly(s) }
                main.postDelayed(watchdog, 8000)
                try {
                    inp.read()
                } catch (e: IOException) {
                    // closed by the other side
                }
                main.removeCallbacks(watchdog)
            } catch (e: IOException) {
                // the other phone went away
            }
            closeQuietly(s)
            return
        }
        // The question is for a phone further along: pass it on and bring the answer back.
        val next = if (CallHub.phase == Phase.IDLE) {
            connectWithRetry(h.rest[0], 2, 8000L, { }) { true }
        } else {
            null
        }
        if (next == null) {
            try {
                s.outputStream.write(0) // an empty list
                s.outputStream.flush()
                Thread.sleep(300)
            } catch (e: Exception) {
                // ignore
            }
            closeQuietly(s)
            return
        }
        try {
            next.outputStream.write(Protocol.frame(MSG_LIST, Protocol.RATE_16K, h.rest.drop(1), ""))
            next.outputStream.flush()
        } catch (e: IOException) {
            closeQuietly(next)
            closeQuietly(s)
            return
        }
        bridge(s, next, { true }) { }
    }

    private fun endedByOtherMessage(): String =
        if (CallHub.phase == Phase.INCOMING) "Missed call from " + CallHub.peerName else "Call ended"

    // ---------------------------------------------------------------- making a call

    private fun placeCall(route: List<String>, names: List<String>) {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            CallHub.message = "Turn Bluetooth on first."
            CallHub.changed()
            return
        }
        if (!hasMicPermission()) {
            CallHub.message = "Allow the microphone permission first."
            CallHub.changed()
            return
        }
        if (route.size > Protocol.MAX_ROUTE) {
            CallHub.message = "Too many phones in between."
            CallHub.changed()
            return
        }
        val device: BluetoothDevice = try {
            bt.getRemoteDevice(route[0])
        } catch (e: IllegalArgumentException) {
            return
        }
        val relayed = route.size > 1
        val epoch = synchronized(lock) {
            if (CallHub.phase != Phase.IDLE || callSocket != null) {
                CallHub.message = if (CallHub.phase == Phase.RELAY) {
                    "This phone is passing a call along for others."
                } else {
                    "Please wait a moment, the last call is still closing."
                }
                CallHub.log("Call refused: phase " + CallHub.phase + ", socket " + (callSocket != null))
                CallHub.changed()
                return
            }
            callEpoch++
            useRate(if (relayed) Protocol.RATE_8K else Protocol.RATE_16K)
            relayedCall = relayed
            CallHub.phase = Phase.OUTGOING
            CallHub.peerName = if (relayed) (names.lastOrNull() ?: route.last()) else nameOf(device)
            CallHub.via = if (relayed) "via " + names.dropLast(1).joinToString(", ") else ""
            CallHub.message = ""
            CallHub.muted = false
            CallHub.speaker = false
            callEpoch
        }
        CallHub.log("Calling " + CallHub.peerName + (if (relayed) " " + CallHub.via else ""))
        goForeground()
        startRingback()
        CallHub.changed()
        Thread { runOutgoing(device, route, epoch) }.start()
    }

    private fun myName(): String =
        try {
            adapter?.name ?: Build.MODEL
        } catch (e: Exception) {
            Build.MODEL
        }

    private fun runOutgoing(device: BluetoothDevice, route: List<String>, epoch: Int) {
        var sock: BluetoothSocket? = null
        val began = System.currentTimeMillis()
        var attempt = 0
        while (attempt < 6 && System.currentTimeMillis() - began < 20000) {
            if (!isCurrent(epoch)) return
            try {
                val candidate = device.createRfcommSocketToServiceRecord(Protocol.APP_UUID)
                synchronized(lock) {
                    if (callEpoch != epoch) {
                        try {
                            candidate.close()
                        } catch (e: IOException) {
                            // ignore
                        }
                        return
                    }
                    callSocket = candidate
                }
                candidate.connect()
                sock = candidate
                break
            } catch (e: IOException) {
                CallHub.log("Connect attempt " + (attempt + 1) + " failed: " + e.message)
                try {
                    callSocket?.close()
                } catch (e2: IOException) {
                    // ignore
                }
                try {
                    device.fetchUuidsWithSdp() // refresh what the other phone offers
                } catch (e3: Exception) {
                    // ignore
                }
                sleepQuietly(1500)
            }
            attempt++
        }
        val s = sock
        if (s == null) {
            CallHub.log("Could not connect after " + attempt + " attempts")
            endCall(
                epoch,
                if (route.size > 1) {
                    "Could not connect to the phone in the middle. Is it close, with Bluetooth on?"
                } else {
                    "Could not connect. Is Bluetooth on and BT Walkie installed on the other phone?"
                }
            )
            return
        }
        try {
            val out = s.outputStream
            val inp = s.inputStream
            if (route.size == 1) {
                out.write(MSG_HELLO)
            } else {
                out.write(Protocol.frame(MSG_ROUTE, Protocol.RATE_8K, route.drop(1), myName()))
            }
            out.flush()
            // A call through other phones needs more time: the middle phone has to connect first.
            scheduleTimeout(epoch, RING_TIMEOUT_MS + (if (route.size > 1) 25000L else 5000L), "No answer")
            when (inp.read()) {
                MSG_ACCEPT -> {
                    val ok = synchronized(lock) {
                        if (callEpoch != epoch) {
                            false
                        } else {
                            CallHub.phase = Phase.ACTIVE
                            CallHub.startedAt = System.currentTimeMillis()
                            true
                        }
                    }
                    if (!ok) return
                    cancelTimeout()
                    stopRingback()
                    beginAudio(s, epoch)
                    receiveLoop(inp, epoch)
                    endCall(epoch, "Call ended")
                }
                MSG_REJECT -> endCall(epoch, "Call declined")
                MSG_BUSY -> endCall(epoch, "The other phone is busy")
                MSG_UNREACHABLE -> endCall(epoch, "Could not reach the last phone. Is it close to the phone in the middle?")
                -1 -> endCall(epoch, "No answer")
                else -> endCall(epoch, "Call failed")
            }
        } catch (e: IOException) {
            endCall(
                epoch,
                if (CallHub.phase == Phase.ACTIVE) "Call ended" else "Could not reach the other phone"
            )
        } catch (e: Exception) {
            CallHub.log("Outgoing error: " + e.javaClass.simpleName + " " + e.message)
            endCall(epoch, "Call failed")
        }
    }

    // ---------------------------------------------------------------- answering and ending

    private fun acceptCall() {
        val picked: Pair<BluetoothSocket, Int> = synchronized(lock) {
            val cs = callSocket
            if (CallHub.phase != Phase.INCOMING || cs == null) return
            CallHub.phase = Phase.ACTIVE
            CallHub.startedAt = System.currentTimeMillis()
            CallHub.muted = false
            CallHub.speaker = false
            Pair(cs, callEpoch)
        }
        val sock = picked.first
        val epoch = picked.second
        CallHub.log("Call accepted")
        cancelTimeout()
        stopRinging()
        notifications.cancel(INCOMING_ID)
        goForeground() // now also with microphone rights
        CallHub.changed()
        Thread {
            try {
                sock.outputStream.write(MSG_ACCEPT)
                sock.outputStream.flush()
            } catch (e: IOException) {
                endCall(epoch, "Call failed")
                return@Thread
            }
            beginAudio(sock, epoch)
        }.start()
    }

    private fun rejectCall() {
        val picked: Pair<BluetoothSocket, Int> = synchronized(lock) {
            val cs = callSocket
            if (CallHub.phase != Phase.INCOMING || cs == null) return
            Pair(cs, callEpoch)
        }
        Thread {
            try {
                picked.first.outputStream.write(MSG_REJECT)
                picked.first.outputStream.flush()
                Thread.sleep(200)
            } catch (e: Exception) {
                // ignore
            }
            endCall(picked.second, "")
        }.start()
    }

    private fun hangUp() {
        when (CallHub.phase) {
            Phase.INCOMING -> rejectCall()
            Phase.OUTGOING, Phase.ACTIVE -> endCall(callEpoch, "Call ended")
            Phase.RELAY -> endCall(callEpoch, "Relay stopped")
            Phase.IDLE -> {
            }
        }
    }

    /** Ends the call that belongs to [epoch]. Does nothing if that call is already over. */
    private fun endCall(epoch: Int, message: String) {
        var wasActive = false
        var sock: BluetoothSocket? = null
        var relay: BluetoothSocket? = null
        synchronized(lock) {
            if (callEpoch != epoch) return
            callEpoch++
            sock = callSocket
            callSocket = null
            relay = relaySocket
            relaySocket = null
            CallHub.via = ""
            wasActive = CallHub.phase == Phase.ACTIVE
            CallHub.phase = Phase.IDLE
            CallHub.message = message
            CallHub.startedAt = 0L
        }
        CallHub.log("Call ended" + (if (message.isEmpty()) "" else ": " + message))
        try {
            sock?.close()
        } catch (e: IOException) {
            // ignore
        }
        try {
            relay?.close()
        } catch (e: IOException) {
            // ignore
        }
        cancelTimeout()
        stopRinging()
        stopRingback()
        notifications.cancel(INCOMING_ID)
        frames.clear()
        releaseWakeLock()
        if (wasActive) {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            beep()
        }
        CallHub.muted = false
        CallHub.speaker = false
        if (running) {
            restartServer() // fresh listening socket for the next call
            goForeground() // drop microphone rights
            if (!CallHub.isEnabled(this)) {
                // The user turned "Receive calls" off; this service only ran for one outgoing call.
                main.post { shutdown() }
            }
        }
        CallHub.changed()
    }

    // ---------------------------------------------------------------- voice

    private fun beginAudio(s: BluetoothSocket, epoch: Int) {
        frames.clear()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
        acquireWakeLock()
        refreshStatus()
        CallHub.changed()
        val out = s.outputStream
        Thread { runSender(out, epoch) }.start()
        Thread { runPlayer(epoch) }.start()
    }

    /** Reads voice frames from the other phone and queues them for playing. */
    private fun receiveLoop(inp: InputStream, epoch: Int) {
        val buf = ByteArray(frameSamples) // u-law: one byte per sample
        while (isCurrent(epoch)) {
            if (!readFully(inp, buf)) return
            if (CallHub.phase == Phase.ACTIVE) {
                val copy = buf.copyOf()
                if (!frames.offer(copy)) {
                    frames.poll() // too much waiting: drop the oldest so delay never builds up
                    frames.offer(copy)
                }
            }
        }
    }

    /** Reads exactly buffer.size bytes so a voice frame is never split. */
    private fun readFully(inp: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val n = inp.read(buffer, offset, buffer.size - offset)
            if (n < 0) return false
            offset += n
        }
        return true
    }

    /** Microphone -> Bluetooth. */
    private fun runSender(out: OutputStream, epoch: Int) {
        var recorder: AudioRecord? = null
        val rate = sampleRate
        val samples = frameSamples
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, samples * 2 * 4)
            )
            recorder = r
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                endCall(epoch, "Could not open the microphone.")
                return
            }
            enableEffects(r.audioSessionId)
            r.startRecording()
            val pcm = ShortArray(samples)
            val wire = ByteArray(samples)
            while (isCurrent(epoch)) {
                var got = 0
                while (got < samples && isCurrent(epoch)) {
                    val n = r.read(pcm, got, samples - got)
                    if (n < 0) throw IOException("microphone stopped")
                    got += n
                }
                if (got < samples) break
                val muted = CallHub.muted
                for (i in 0 until samples) {
                    wire[i] = encodeUlaw(if (muted) 0 else pcm[i].toInt())
                }
                out.write(wire)
            }
        } catch (e: IOException) {
            endCall(epoch, "Call ended")
        } catch (e: Exception) {
            endCall(epoch, "Microphone problem")
        } finally {
            try {
                recorder?.stop()
            } catch (e: Exception) {
                // ignore
            }
            recorder?.release()
            for (fx in effects) {
                try {
                    fx.release()
                } catch (e: Exception) {
                    // ignore
                }
            }
            effects.clear()
        }
    }

    /** Echo cancelling, noise reduction and auto volume, where the phone supports them. */
    private fun enableEffects(session: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                val fx = AcousticEchoCanceler.create(session)
                if (fx != null) {
                    fx.setEnabled(true)
                    effects.add(fx)
                }
            }
        } catch (e: Throwable) {
            // not supported
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                val fx = NoiseSuppressor.create(session)
                if (fx != null) {
                    fx.setEnabled(true)
                    effects.add(fx)
                }
            }
        } catch (e: Throwable) {
            // not supported
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                val fx = AutomaticGainControl.create(session)
                if (fx != null) {
                    fx.setEnabled(true)
                    effects.add(fx)
                }
            }
        } catch (e: Throwable) {
            // not supported
        }
    }

    /** Queue -> speaker, with a small jitter buffer for smooth sound. */
    private fun runPlayer(epoch: Int) {
        var track: AudioTrack? = null
        val rate = sampleRate
        val samples = frameSamples
        val relayed = relayedCall
        val startFrames = if (relayed) 5 else 3 // frames to collect before playing (a relayed call is bumpier)
        val maxLag = if (relayed) 20 else 12 // more than this many waiting frames: skip ahead
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, samples * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            t.play()
            // Let a few frames pile up first (about 60 ms) so the start is not choppy.
            var waited = 0
            while (isCurrent(epoch) && frames.size < startFrames && waited < 400) {
                Thread.sleep(20)
                waited += 20
            }
            val pcm = ShortArray(samples)
            while (isCurrent(epoch)) {
                val f = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
                while (frames.size > maxLag) frames.poll() // more than about 240 ms late: skip ahead
                for (i in 0 until samples) {
                    pcm[i] = DECODE_TABLE[f[i].toInt() and 0xFF]
                }
                t.write(pcm, 0, samples)
            }
        } catch (e: Exception) {
            // call is over or audio failed
        } finally {
            try {
                track?.stop()
            } catch (e: Exception) {
                // ignore
            }
            track?.release()
        }
    }

    // ---------------------------------------------------------------- sounds, timers, wake lock

    private fun startRinging() {
        main.post {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val r = RingtoneManager.getRingtone(applicationContext, uri)
                if (r != null) {
                    r.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
                    r.play()
                    ringtone = r
                }
            } catch (e: Exception) {
                // no ringtone available
            }
            vibrateRing()
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrateRing() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            vibrator = v
            val pattern = longArrayOf(0, 700, 900)
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                v.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            // no vibrator
        }
    }

    private fun stopRinging() {
        main.post {
            try {
                ringtone?.stop()
            } catch (e: Exception) {
                // ignore
            }
            ringtone = null
            try {
                vibrator?.cancel()
            } catch (e: Exception) {
                // ignore
            }
            vibrator = null
        }
    }

    private fun startRingback() {
        main.post {
            try {
                val t = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
                t.startTone(ToneGenerator.TONE_SUP_RINGTONE)
                ringback = t
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun stopRingback() {
        main.post {
            try {
                ringback?.stopTone()
                ringback?.release()
            } catch (e: Exception) {
                // ignore
            }
            ringback = null
        }
    }

    private fun beep() {
        main.post {
            try {
                val t = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
                t.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                main.postDelayed({
                    try {
                        t.release()
                    } catch (e: Exception) {
                        // ignore
                    }
                }, 600)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun scheduleTimeout(epoch: Int, delayMs: Long, message: String) {
        val r = Runnable { endCall(epoch, message) }
        main.post {
            timeoutRunnable?.let { main.removeCallbacks(it) }
            timeoutRunnable = r
            main.postDelayed(r, delayMs)
        }
    }

    private fun cancelTimeout() {
        main.post {
            timeoutRunnable?.let { main.removeCallbacks(it) }
            timeoutRunnable = null
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BtWalkie:call")
            wl.setReferenceCounted(false)
            wl.acquire(4 * 60 * 60 * 1000L)
            wakeLock = wl
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun releaseWakeLock() {
        try {
            val wl = wakeLock
            if (wl != null && wl.isHeld) wl.release()
        } catch (e: Exception) {
            // ignore
        }
        wakeLock = null
    }
}
