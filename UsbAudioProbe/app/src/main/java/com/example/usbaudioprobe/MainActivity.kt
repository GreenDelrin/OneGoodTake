package com.example.usbaudioprobe

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.sqrt

class MainActivity : Activity() {
    private val ACTION_PERM = "com.example.usbaudioprobe.USB_PERMISSION"
    private val RATE = 48000

    private lateinit var usb: UsbManager
    private lateinit var logView: TextView
    private lateinit var statsView: TextView
    private lateinit var meterBox: LinearLayout
    private lateinit var spinner: Spinner
    private lateinit var btnConnect: Button
    private lateinit var btnRecord: Button

    private var conn: UsbDeviceConnection? = null
    private var device: UsbDevice? = null
    @Volatile private var capturing = false
    private var readThread: Thread? = null
    @Volatile private var wav: WavWriter? = null
    @Volatile private var recordChannel = -1
    private var meters = listOf<ProgressBar>()
    private var nch = 0
    private val ui = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_PERM -> {
                    val d = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (d != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) startCapture(d)
                    else log("USB permission denied")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> { log("USB device detached"); stopCapture() }
            }
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        usb = getSystemService(USB_SERVICE) as UsbManager
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        btnConnect = Button(this).apply { text = "Connect & start capture"; setOnClickListener { onConnect() } }
        spinner = Spinner(this)
        btnRecord = Button(this).apply { text = "Record"; isEnabled = false; setOnClickListener { toggleRecord() } }
        statsView = TextView(this)
        meterBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logView = TextView(this).apply { textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(btnConnect); root.addView(spinner); root.addView(btnRecord)
        root.addView(statsView); root.addView(meterBox)
        root.addView(ScrollView(this).apply { addView(logView) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val f = IntentFilter(ACTION_PERM).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        log("Plug the interface in, then tap Connect.")
    }

    private fun log(m: String) = runOnUiThread { logView.append(m + "\n") }

    private fun onConnect() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        if (capturing) { stopCapture(); return }
        val d = usb.deviceList.values.firstOrNull { dev ->
            (0 until dev.interfaceCount).any { dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
        }
        if (d == null) { log("No USB audio device found"); return }
        device = d
        log("Found ${d.productName ?: "device"} vid=%04x pid=%04x".format(d.vendorId, d.productId))
        if (usb.hasPermission(d)) startCapture(d) else {
            val intent = Intent(ACTION_PERM).setPackage(packageName)
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, intent, flags))
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) onConnect() else log("RECORD_AUDIO denied")
    }

    private fun startCapture(d: UsbDevice) {
        val c = usb.openDevice(d) ?: run { log("openDevice failed"); return }
        for (i in 0 until d.interfaceCount) {
            val intf = d.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                val ok = c.claimInterface(intf, true)   // force: detach kernel/system audio driver
                log("claim interface ${intf.id}: $ok")
            }
        }
        val msg = NativeUsb.start(c.fileDescriptor, c.rawDescriptors, RATE)
        log(msg)
        if (!msg.startsWith("OK")) { c.close(); return }
        conn = c
        nch = NativeUsb.channels()
        capturing = true
        btnConnect.text = "Stop"
        btnRecord.isEnabled = true
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Record ALL $nch channels") + (1..nch).map { "Record input $it only (mono)" })
        meterBox.removeAllViews()
        meters = (1..nch).map { n ->
            meterBox.addView(TextView(this).apply { text = "Input $n"; textSize = 11f })
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
                .also { meterBox.addView(it) }
        }
        readThread = Thread {
            val buf = FloatArray(2048 * nch)
            while (capturing) {
                val n = NativeUsb.read(buf, 2048)
                if (n <= 0) { Thread.sleep(4); continue }
                val w = wav ?: continue
                val ch = recordChannel
                if (ch < 0) w.write(buf, n * nch) else {
                    val mono = FloatArray(n) { buf[it * nch + ch] }
                    w.write(mono, n)
                }
            }
        }.also { it.start() }
        ui.post(updater)
    }

    private val updater = object : Runnable {
        override fun run() {
            if (!capturing) return
            val p = FloatArray(nch)
            NativeUsb.peaks(p)
            p.forEachIndexed { i, v -> meters[i].progress = (sqrt(v.coerceIn(0f, 1f)) * 1000).toInt() }
            val s = NativeUsb.stats()
            statsView.text = "frames=${s[0]}  overruns=${s[1]}  usbErrors=${s[2]}"
            ui.postDelayed(this, 60)
        }
    }

    private fun toggleRecord() {
        val w = wav
        if (w != null) {
            wav = null; w.close(); btnRecord.text = "Record"; log("Recording saved"); return
        }
        recordChannel = spinner.selectedItemPosition - 1
        val dir = getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir
        val f = File(dir, "usb_capture_${System.currentTimeMillis()}.wav")
        wav = WavWriter(f, if (recordChannel < 0) nch else 1, RATE)
        btnRecord.text = "Stop recording"
        log("Recording to ${f.absolutePath}")
    }

    private fun stopCapture() {
        if (!capturing && conn == null) return
        capturing = false
        wav?.let { wav = null; it.close() }
        readThread?.join(500)
        NativeUsb.stop()
        conn?.let { c ->
            device?.let { d -> for (i in 0 until d.interfaceCount) c.releaseInterface(d.getInterface(i)) }
            c.close()
        }
        conn = null
        runOnUiThread { btnConnect.text = "Connect & start capture"; btnRecord.text = "Record"; btnRecord.isEnabled = false }
        log("Stopped")
    }

    override fun onDestroy() {
        stopCapture()
        unregisterReceiver(receiver)
        super.onDestroy()
    }
}
