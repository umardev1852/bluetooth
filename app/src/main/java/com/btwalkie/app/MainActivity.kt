package com.btwalkie.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * The screens: Home (list of paired phones with a Call button) and the Call screen
 * (calling / incoming / talking). Everything is drawn in code; the actual calling is
 * done by CallService so it keeps working when this screen is closed or the phone is locked.
 */
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACCEPT = "accept_call"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private val colorBg = Color.parseColor("#0F1417")
    private val colorCard = Color.parseColor("#1A2126")
    private val colorAccent = Color.parseColor("#00BFA5")
    private val colorDanger = Color.parseColor("#E53935")
    private val colorMuted = Color.parseColor("#9AA7B0")
    private val colorAmber = Color.parseColor("#FFB300")
    private val colorOff = Color.parseColor("#2A343B")

    private val ui = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private var timerView: TextView? = null
    private var showLog = false

    // Long range: the phones in the middle chosen so far. The first one is paired with THIS phone.
    private val chain = ArrayList<Protocol.Peer>()
    private var listRequest: RelayClient.Request? = null

    private val tick = object : Runnable {
        override fun run() {
            updateTimer()
            ui.postDelayed(this, 1000)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startEngine()
        render()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallHub.init(this)
        volumeControlStream = AudioManager.STREAM_VOICE_CALL // volume keys change call volume
        window.statusBarColor = colorBg
        window.navigationBarColor = colorBg
        handleIntent(intent)
        ensurePermissions()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        CallHub.addListener(listener)
        ui.removeCallbacks(tick)
        ui.post(tick)
        render()
    }

    override fun onStop() {
        CallHub.removeListener(listener)
        ui.removeCallbacks(tick)
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null && intent.getBooleanExtra(EXTRA_ACCEPT, false)) {
            intent.removeExtra(EXTRA_ACCEPT)
            sendToService(CallService.ACTION_ACCEPT, null)
        }
    }

    // ---------------------------------------------------------------- permissions and service

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)

    private fun ensurePermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) wanted.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter { !granted(it) }
        if (missing.isEmpty()) {
            startEngine()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /** Starts the background service that waits for incoming calls. */
    private fun startEngine() {
        if (CallHub.isEnabled(this) && hasBtPermission()) {
            sendToService(CallService.ACTION_START, null)
        }
    }

    private fun sendToService(action: String, address: String?) {
        val i = Intent(this, CallService::class.java)
        i.action = action
        try {
            ContextCompat.startForegroundService(this, i)
        } catch (e: Exception) {
            toast("Could not start. Open the app again.")
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun startCall(device: BluetoothDevice) {
        if (!granted(Manifest.permission.RECORD_AUDIO) || !hasBtPermission()) {
            toast("Please allow the permissions first.")
            ensurePermissions()
            return
        }
        val bt = bluetoothAdapter()
        if (bt == null || !bt.isEnabled) {
            toast("Turn Bluetooth on first.")
            return
        }
        sendCall(listOf(device.address), listOf(device.name ?: device.address))
    }

    /** [route] is the list of phones to go through; the last one is the phone to talk to. */
    private fun sendCall(route: List<String>, names: List<String>) {
        val i = Intent(this, CallService::class.java)
        i.action = CallService.ACTION_CALL
        i.putExtra(CallService.EXTRA_ROUTE, route.toTypedArray())
        i.putExtra(CallService.EXTRA_NAMES, names.toTypedArray())
        try {
            ContextCompat.startForegroundService(this, i)
        } catch (e: Exception) {
            toast("Could not start. Open the app again.")
        }
    }

    // ---------------------------------------------------------------- long range (through other phones)

    private fun startLongRange() {
        if (!granted(Manifest.permission.RECORD_AUDIO) || !hasBtPermission()) {
            toast("Please allow the permissions first.")
            ensurePermissions()
            return
        }
        val bt = bluetoothAdapter()
        if (bt == null || !bt.isEnabled) {
            toast("Turn Bluetooth on first.")
            return
        }
        val paired = bt.bondedDevices.toList()
        if (paired.isEmpty()) {
            toast("No paired phones yet.")
            return
        }
        val names = paired.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Which phone is in the middle?")
            .setItems(names) { _, index ->
                chain.clear()
                chain.add(Protocol.Peer(names[index], paired[index].address))
                askForPhones()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Asks the last phone of the chain which phones it is paired with. */
    private fun askForPhones() {
        val middle = chain.last().name
        val dialog = AlertDialog.Builder(this)
            .setTitle("Please wait")
            .setMessage("Asking " + middle + " which phones it is paired with...")
            .setNegativeButton("Cancel") { _, _ -> listRequest?.cancel() }
            .setCancelable(false)
            .create()
        dialog.show()
        listRequest = RelayClient.listPeers(this, chain.map { it.address }) { peers, error ->
            dialog.dismiss()
            if (peers == null) {
                CallHub.message = error
                CallHub.changed()
            } else {
                showPeerPicker(peers)
            }
        }
    }

    private fun showPeerPicker(peers: List<Protocol.Peer>) {
        val here = chain.last().name
        if (peers.isEmpty()) {
            CallHub.message = here + " has no other paired phones. Pair it with the phone you want to reach first."
            CallHub.changed()
            return
        }
        val names = peers.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Phones paired with " + here)
            .setItems(names) { _, index -> choosePeerAction(peers[index]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun choosePeerAction(peer: Protocol.Peer) {
        val path = chain.joinToString(" > ") { it.name } + " > " + peer.name
        val builder = AlertDialog.Builder(this)
            .setTitle(peer.name)
            .setMessage("Call through: " + path + "\n\nEvery extra phone adds a little delay.")
            .setPositiveButton("Call") { _, _ -> startRelayedCall(peer) }
            .setNegativeButton("Cancel", null)
        if (chain.size < Protocol.MAX_ROUTE - 1) {
            builder.setNeutralButton("Go further") { _, _ ->
                chain.add(peer)
                askForPhones()
            }
        }
        builder.show()
    }

    private fun startRelayedCall(target: Protocol.Peer) {
        val route = chain.map { it.address } + target.address
        val names = chain.map { it.name } + target.name
        sendCall(route, names)
    }

    // ---------------------------------------------------------------- drawing helpers

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    private fun lp(width: Int, height: Int, topDp: Int, weight: Float): LinearLayout.LayoutParams {
        val p = if (weight > 0f) {
            LinearLayout.LayoutParams(width, height, weight)
        } else {
            LinearLayout.LayoutParams(width, height)
        }
        p.topMargin = dp(topDp)
        return p
    }

    private fun label(value: String, sizeSp: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = value
        t.textSize = sizeSp
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun pill(text: String, background: Int, textColor: Int, onClick: () -> Unit): TextView {
        val t = label(text, 16f, textColor, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(22), dp(14), dp(22), dp(14))
        t.background = rounded(background, 28)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    private fun initialOf(name: String): String {
        val c = name.trim().firstOrNull() ?: return "?"
        return c.uppercaseChar().toString()
    }

    private fun avatar(name: String, sizeDp: Int, textSp: Float): TextView {
        val t = label(initialOf(name), textSp, Color.WHITE, true)
        t.gravity = Gravity.CENTER
        val circle = GradientDrawable()
        circle.shape = GradientDrawable.OVAL
        circle.setColor(colorAccent)
        t.background = circle
        t.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return t
    }

    // ---------------------------------------------------------------- screens

    private fun render() {
        timerView = null
        if (CallHub.phase == Phase.IDLE) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(buildHome())
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(buildCallScreen())
            updateTimer()
        }
    }

    private fun buildHome(): View {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(colorBg)
        scroll.isFillViewport = true

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(32), dp(20), dp(28))

        col.addView(label("BT Walkie", 30f, Color.WHITE, true), lp(MATCH, WRAP, 0, 0f))
        col.addView(
            label("Talk over Bluetooth. No internet, no SIM.", 15f, colorMuted, false),
            lp(MATCH, WRAP, 4, 0f)
        )

        // Status
        val bt = bluetoothAdapter()
        val enabled = CallHub.isEnabled(this)
        val statusText: String
        val statusColor: Int
        if (!hasBtPermission()) {
            statusText = "Permission needed. Tap here to allow."
            statusColor = colorAmber
        } else if (bt == null) {
            statusText = "This phone has no Bluetooth."
            statusColor = colorDanger
        } else if (!bt.isEnabled) {
            statusText = "Bluetooth is off. Turn it on to call or receive calls."
            statusColor = colorDanger
        } else if (!enabled) {
            statusText = "Receiving calls is turned off."
            statusColor = colorAmber
        } else if (CallHub.listening) {
            statusText = "Ready to receive calls"
            statusColor = colorAccent
        } else {
            statusText = "Starting..."
            statusColor = colorMuted
        }
        val status = label(statusText, 15f, statusColor, true)
        status.setPadding(dp(16), dp(12), dp(16), dp(12))
        status.background = rounded(colorCard, 16)
        status.setOnClickListener {
            if (!hasBtPermission() || !granted(Manifest.permission.RECORD_AUDIO)) ensurePermissions()
        }
        col.addView(status, lp(MATCH, WRAP, 20, 0f))

        if (CallHub.message.isNotEmpty()) {
            val msg = label(CallHub.message, 15f, Color.WHITE, false)
            msg.setPadding(dp(16), dp(12), dp(16), dp(12))
            msg.background = rounded(colorOff, 16)
            col.addView(msg, lp(MATCH, WRAP, 10, 0f))
        }

        // Paired phones
        col.addView(label("Paired phones", 14f, colorMuted, true), lp(MATCH, WRAP, 26, 0f))
        val devices: List<BluetoothDevice> =
            if (bt != null && hasBtPermission()) {
                bt.bondedDevices.sortedBy {
                    if (it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE) 0 else 1
                }
            } else {
                emptyList()
            }
        if (devices.isEmpty()) {
            col.addView(
                label(
                    "No paired phones yet. Pair both phones in Bluetooth settings first, then come back here.",
                    15f, Color.WHITE, false
                ),
                lp(MATCH, WRAP, 10, 0f)
            )
        }
        for (d in devices) {
            val name = d.name ?: d.address
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = rounded(colorCard, 20)
            row.setPadding(dp(14), dp(12), dp(14), dp(12))
            row.addView(avatar(name, 44, 18f))

            val names = LinearLayout(this)
            names.orientation = LinearLayout.VERTICAL
            names.addView(label(name, 16f, Color.WHITE, true))
            names.addView(label(d.address, 12f, colorMuted, false))
            val namesParams = lp(0, WRAP, 0, 1f)
            namesParams.leftMargin = dp(12)
            row.addView(names, namesParams)

            row.addView(pill("Call", colorAccent, Color.WHITE) { startCall(d) })
            col.addView(row, lp(MATCH, WRAP, 10, 0f))
        }

        // Long range
        col.addView(
            pill("Long range: call through another phone", colorOff, Color.WHITE) { startLongRange() },
            lp(MATCH, WRAP, 18, 0f)
        )
        col.addView(
            label(
                "Phone too far? Use a phone in between that is paired with both of you. It passes the call along. " +
                    "All of you need the newest BT Walkie.",
                12f, colorMuted, false
            ),
            lp(MATCH, WRAP, 6, 0f)
        )

        // Settings
        col.addView(
            pill("Bluetooth settings", colorOff, Color.WHITE) {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            },
            lp(MATCH, WRAP, 22, 0f)
        )
        col.addView(
            pill(
                if (enabled) "Turn off receiving calls" else "Turn on receiving calls",
                colorOff, Color.WHITE
            ) {
                if (enabled) {
                    CallHub.setEnabled(this, false)
                    sendToService(CallService.ACTION_STOP, null)
                } else {
                    CallHub.setEnabled(this, true)
                    ensurePermissions()
                    startEngine()
                }
                render()
            },
            lp(MATCH, WRAP, 10, 0f)
        )
        col.addView(
            pill("Fix: calls not ringing when app is closed", colorOff, Color.WHITE) { openAutostartSettings() },
            lp(MATCH, WRAP, 10, 0f)
        )
        col.addView(
            pill(if (showLog) "Hide debug log" else "Show debug log", colorOff, Color.WHITE) {
                showLog = !showLog
                render()
            },
            lp(MATCH, WRAP, 10, 0f)
        )
        if (showLog) {
            val logView = label(CallHub.logText(), 11f, colorMuted, false)
            logView.typeface = Typeface.MONOSPACE
            logView.setTextIsSelectable(true)
            logView.setPadding(dp(12), dp(10), dp(12), dp(10))
            logView.background = rounded(colorCard, 12)
            col.addView(logView, lp(MATCH, WRAP, 10, 0f))
        }
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= 23 && !power.isIgnoringBatteryOptimizations(packageName)) {
            col.addView(
                pill("Allow running in background", colorOff, Color.WHITE) { askBatteryException() },
                lp(MATCH, WRAP, 10, 0f)
            )
        }

        col.addView(
            label(
                "Tip: open this app once on both phones after installing. After that, keep Bluetooth on: the other phone rings even if the app is closed or the screen is off. " +
                    "Range is about 10 metres.",
                13f, colorMuted, false
            ),
            lp(MATCH, WRAP, 22, 0f)
        )

        scroll.addView(col)
        return scroll
    }

    /** Opens the phone maker's "auto start" page if it has one (Xiaomi, Oppo, Vivo, Huawei, Tecno...). */
    private fun openAutostartSettings() {
        toast("Find BT Walkie and turn on Autostart / Allow background activity.")
        val candidates = listOf(
            Pair("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Pair("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            Pair("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            Pair("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            Pair("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            Pair("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Pair("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity")
        )
        for (c in candidates) {
            try {
                val i = Intent()
                i.component = ComponentName(c.first, c.second)
                startActivity(i)
                return
            } catch (e: Exception) {
                // this phone does not have that page; try the next one
            }
        }
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
        } catch (e: Exception) {
            // ignore
        }
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryException() {
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:$packageName")
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast("Open Settings > Battery and allow BT Walkie to run in background.")
            }
        }
    }

    private fun buildCallScreen(): View {
        val phase = CallHub.phase
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setBackgroundColor(colorBg)
        col.setPadding(dp(24), dp(72), dp(24), dp(44))

        val name = CallHub.peerName
        col.addView(avatar(name, 124, 54f))

        val nameView = label(name, 28f, Color.WHITE, true)
        nameView.gravity = Gravity.CENTER
        col.addView(nameView, lp(MATCH, WRAP, 24, 0f))
        if (CallHub.via.isNotEmpty()) {
            val viaView = label(CallHub.via, 14f, colorMuted, false)
            viaView.gravity = Gravity.CENTER
            col.addView(viaView, lp(MATCH, WRAP, 4, 0f))
        }

        val statusLine = when (phase) {
            Phase.OUTGOING -> "Calling..."
            Phase.INCOMING -> "Incoming call"
            Phase.RELAY -> "Passing the call along. Keep this phone here."
            else -> "00:00"
        }
        val statusView = label(statusLine, 18f, colorMuted, false)
        statusView.gravity = Gravity.CENTER
        col.addView(statusView, lp(MATCH, WRAP, 8, 0f))
        if (phase == Phase.ACTIVE) timerView = statusView

        // Pushes the buttons to the bottom.
        col.addView(View(this), lp(1, 0, 0, 1f))

        if (phase == Phase.ACTIVE) {
            val controls = LinearLayout(this)
            controls.orientation = LinearLayout.HORIZONTAL
            val muteBtn = pill(
                if (CallHub.muted) "Unmute" else "Mute",
                if (CallHub.muted) colorAmber else colorOff,
                if (CallHub.muted) Color.BLACK else Color.WHITE
            ) {
                CallHub.muted = !CallHub.muted
                CallHub.changed()
            }
            val speakerBtn = pill(
                if (CallHub.speaker) "Speaker on" else "Speaker",
                if (CallHub.speaker) colorAccent else colorOff,
                Color.WHITE
            ) {
                CallHub.speaker = !CallHub.speaker
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.isSpeakerphoneOn = CallHub.speaker
                CallHub.changed()
            }
            val left = lp(0, WRAP, 0, 1f)
            left.rightMargin = dp(8)
            val right = lp(0, WRAP, 0, 1f)
            right.leftMargin = dp(8)
            controls.addView(muteBtn, left)
            controls.addView(speakerBtn, right)
            col.addView(controls, lp(MATCH, WRAP, 0, 0f))
        }

        if (phase == Phase.INCOMING) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val decline = pill("Decline", colorDanger, Color.WHITE) {
                sendToService(CallService.ACTION_REJECT, null)
            }
            val accept = pill("Accept", colorAccent, Color.WHITE) {
                sendToService(CallService.ACTION_ACCEPT, null)
            }
            val left = lp(0, WRAP, 0, 1f)
            left.rightMargin = dp(8)
            val right = lp(0, WRAP, 0, 1f)
            right.leftMargin = dp(8)
            row.addView(decline, left)
            row.addView(accept, right)
            col.addView(row, lp(MATCH, WRAP, 16, 0f))
        } else {
            col.addView(
                pill(if (phase == Phase.RELAY) "Stop relaying" else "Hang up", colorDanger, Color.WHITE) {
                    sendToService(CallService.ACTION_HANGUP, null)
                },
                lp(MATCH, WRAP, 16, 0f)
            )
        }
        return col
    }

    private fun updateTimer() {
        val tv = timerView ?: return
        val start = CallHub.startedAt
        if (CallHub.phase != Phase.ACTIVE || start == 0L) return
        val seconds = ((System.currentTimeMillis() - start) / 1000).toInt()
        tv.text = String.format("%02d:%02d", seconds / 60, seconds % 60)
    }
}
