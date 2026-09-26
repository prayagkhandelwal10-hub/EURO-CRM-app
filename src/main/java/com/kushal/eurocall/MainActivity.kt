package com.kushal.eurocall

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/* ============================ Settings store ============================ */
object Prefs {
    private const val FILE = "eurocall_prefs"
    const val BACKEND_URL = "backend_url"
    const val MY_LANG = "my_lang"
    const val THEIR_LANG = "their_lang"
    const val AUTO_DETECT = "auto_detect"
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun get(c: Context, k: String, d: String) = sp(c).getString(k, d) ?: d
    fun set(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
    fun getBool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    fun setBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
}

/* ============================ Start/stop helper ============================ */
object CallControl {
    fun start(ctx: Context, label: String, number: String = "") {
        if (RecorderService.isRecording) return
        val i = Intent(ctx, RecorderService::class.java).apply {
            action = RecorderService.ACTION_START
            putExtra(RecorderService.EXTRA_LABEL, label)
            putExtra(RecorderService.EXTRA_NUMBER, number)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
    }
    fun stop(ctx: Context) {
        if (!RecorderService.isRecording) return
        ctx.startService(Intent(ctx, RecorderService::class.java).apply { action = RecorderService.ACTION_STOP })
    }
}

/* ============================ Foreground recorder ============================ */
class RecorderService : Service() {
    companion object {
        const val ACTION_START = "com.kushal.eurocall.START"
        const val ACTION_STOP = "com.kushal.eurocall.STOP"
        const val EXTRA_LABEL = "label"
        const val EXTRA_NUMBER = "number"
        private const val CHANNEL_ID = "eurocall_rec"
        private const val NOTIF_ID = 4711
        @Volatile var isRecording = false; private set
        // MIC records the room (both voices when the call is on speaker). On a rooted phone
        // switch to MediaRecorder.AudioSource.VOICE_CALL for a clean direct capture.
        private const val SOURCE = MediaRecorder.AudioSource.MIC
    }
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var label = "Call"; private var number = ""

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                label = intent.getStringExtra(EXTRA_LABEL) ?: "Call"
                number = intent.getStringExtra(EXTRA_NUMBER) ?: ""
                goForeground(); startRec()
            }
            ACTION_STOP -> { stopRec(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Call recording", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("EURO Call — recording")
            .setContentText("Listening ($label). Keep the call on speaker.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    private fun startRec() {
        if (isRecording) return
        try {
            val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            currentFile = File(dir, "call_${label.replace(Regex("[^A-Za-z0-9]"), "")}_$stamp.m4a")
            recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
                else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(SOURCE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000); setAudioChannels(1); setAudioEncodingBitRate(64000)
                setOutputFile(currentFile!!.absolutePath)
                prepare(); start()
            }
            isRecording = true
        } catch (e: Exception) { e.printStackTrace(); isRecording = false }
    }

    private fun stopRec() {
        if (!isRecording) return
        try { recorder?.apply { stop(); release() } } catch (e: Exception) { e.printStackTrace() }
        recorder = null; isRecording = false
        currentFile?.let { Uploader.enqueue(applicationContext, it, label, number) }
    }

    override fun onDestroy() { stopRec(); super.onDestroy() }
}

/* ============================ Cellular call auto start/stop ============================ */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Prefs.getBool(ctx, Prefs.AUTO_DETECT, true)) return
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""
        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> CallControl.start(ctx, "Cellular", number)
            TelephonyManager.EXTRA_STATE_IDLE -> CallControl.stop(ctx)
        }
    }
}

/* ============================ VoIP call auto start/stop ============================ */
class VoipCallListener : NotificationListenerService() {
    private val voip = mapOf(
        "com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business",
        "com.viber.voip" to "Viber", "com.dialpad.dialpad" to "Dialpad"
    )
    private val hints = listOf("ongoing call", "voice call", "call in progress", "tap to return", "calling", "on call")
    private var activePkg: String? = null

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Prefs.getBool(applicationContext, Prefs.AUTO_DETECT, true)) return
        val app = voip[sbn.packageName] ?: return
        val ex = sbn.notification.extras
        val text = ("${ex.getCharSequence("android.title") ?: ""} " +
                "${ex.getCharSequence("android.text") ?: ""} ${sbn.notification.category ?: ""}").lowercase()
        val active = sbn.notification.category == "call" || hints.any { text.contains(it) }
        if (active && activePkg == null) { activePkg = sbn.packageName; CallControl.start(applicationContext, app) }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == activePkg) { activePkg = null; CallControl.stop(applicationContext) }
    }
}

/* ============================ Upload to EURO backend ============================ */
object Uploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES).build()

    fun enqueue(ctx: Context, file: File, label: String, number: String) {
        val backend = Prefs.get(ctx, Prefs.BACKEND_URL, "").trimEnd('/')
        if (backend.isEmpty()) return  // no backend yet — file stays saved on the phone
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", file.name, file.asRequestBody("audio/mp4".toMediaType()))
            .addFormDataPart("label", label)
            .addFormDataPart("number", number)
            .addFormDataPart("my_lang", Prefs.get(ctx, Prefs.MY_LANG, "en"))
            .addFormDataPart("their_lang", Prefs.get(ctx, Prefs.THEIR_LANG, "auto"))
            .build()
        val req = Request.Builder().url("$backend/api/calls/upload").post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }
}

/* ============================ UI (built in code, no XML) ============================ */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var recordings: TextView
    private lateinit var backendUrl: EditText
    private lateinit var myLang: EditText
    private lateinit var theirLang: EditText
    private lateinit var autoDetect: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        fun tv(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
            this.text = text; textSize = size; if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 8)
        }
        fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text; setOnClickListener { onClick() }
        }

        root.addView(tv("EURO Call Assistant", 22f, true))
        root.addView(tv("Records call audio and sends it to EURO for transcript, translation and summary. Keep the call on SPEAKER so both voices are captured.", 13f))

        status = tv("Idle", 16f, true); root.addView(status)
        root.addView(btn("Start recording") { CallControl.start(this, "Manual"); refresh() })
        root.addView(btn("Stop") { CallControl.stop(this); refresh() })

        root.addView(tv("Settings", 16f, true))
        backendUrl = EditText(this).apply { hint = "EURO backend URL (https://…)" }; root.addView(backendUrl)
        myLang = EditText(this).apply { hint = "My language (en)" }; root.addView(myLang)
        theirLang = EditText(this).apply { hint = "Their language (auto)" }; root.addView(theirLang)
        autoDetect = CheckBox(this).apply { text = "Auto start/stop on calls (cellular + VoIP)" }; root.addView(autoDetect)
        root.addView(btn("Save settings") {
            Prefs.set(this, Prefs.BACKEND_URL, backendUrl.text.toString())
            Prefs.set(this, Prefs.MY_LANG, myLang.text.toString())
            Prefs.set(this, Prefs.THEIR_LANG, theirLang.text.toString())
            Prefs.setBool(this, Prefs.AUTO_DETECT, autoDetect.isChecked)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        })

        root.addView(btn("Grant permissions") { askPerms() })
        root.addView(btn("Enable VoIP call detection") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        root.addView(tv("Recordings", 16f, true))
        recordings = tv("", 13f); root.addView(recordings)

        val scroll = ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)

        backendUrl.setText(Prefs.get(this, Prefs.BACKEND_URL, ""))
        myLang.setText(Prefs.get(this, Prefs.MY_LANG, "en"))
        theirLang.setText(Prefs.get(this, Prefs.THEIR_LANG, "auto"))
        autoDetect.isChecked = Prefs.getBool(this, Prefs.AUTO_DETECT, true)

        askPerms(); refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun askPerms() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun refresh() {
        status.text = if (RecorderService.isRecording) "● Recording…" else "Idle"
        val dir = File(getExternalFilesDir(null), "recordings")
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() }?.joinToString("\n") { it.name }
        recordings.text = if (files.isNullOrEmpty()) "No recordings yet." else files
    }
}
