package com.kushal.eurocall

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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
    const val LAST_SOURCE = "last_source"
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
        // Try the direct call-audio taps first (only granted to system/OEM apps on most
        // phones, but costs nothing to try — some devices/regions allow it). Falls back to
        // VOICE_COMMUNICATION (the echo-cancelled path apps like WhatsApp themselves use)
        // and finally to plain MIC, which is the only one guaranteed to work everywhere.
        // NOTE: none of these can hear a live SIM/cellular call unless it is on speaker —
        // Android mutes the regular mic hardware path during an active cellular call for
        // every non-system app; that block was confirmed on this phone (silent recording).
        // A VoIP call (WhatsApp/Viber/Dialpad) is different: the mic stays live even off
        // speaker, so these fallbacks can meaningfully improve pickup for VoIP specifically.
        private val SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
    }
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var label = "Call"; private var number = ""
    private var sourceUsed = "MIC"

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

    private fun newRecorder() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()

    private fun startRec() {
        if (isRecording) return
        val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        // .aac (ADTS) instead of .m4a: every frame carries its own header, so the file is
        // playable from start to wherever it stopped even if the recording gets cut off
        // mid-call (which is what made the earlier .m4a files unplayable).
        currentFile = File(dir, "call_${label.replace(Regex("[^A-Za-z0-9]"), "")}_$stamp.aac")

        for (src in SOURCES) {
            try {
                val r = newRecorder().apply {
                    setAudioSource(src)
                    setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(16000); setAudioChannels(1); setAudioEncodingBitRate(64000)
                    setOutputFile(currentFile!!.absolutePath)
                    prepare(); start()
                }
                recorder = r
                sourceUsed = when (src) {
                    MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
                    else -> "MIC"
                }
                isRecording = true
                Prefs.set(applicationContext, Prefs.LAST_SOURCE, sourceUsed)
                return
            } catch (e: Exception) {
                e.printStackTrace()
                try { recorder?.release() } catch (_: Exception) {}
                recorder = null
                // this source isn't allowed on this phone — try the next one
            }
        }
        isRecording = false
    }

    private fun stopRec() {
        if (!isRecording) return
        try { recorder?.apply { stop(); release() } } catch (e: Exception) { e.printStackTrace() }
        recorder = null; isRecording = false
        currentFile?.let { Uploader.enqueue(applicationContext, it, label, number) }
    }

    override fun onDestroy() { stopRec(); super.onDestroy() }
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
            .addFormDataPart("audio", file.name, file.asRequestBody("audio/aac".toMediaType()))
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
    private lateinit var recordingsList: LinearLayout
    private var player: MediaPlayer? = null
    private var playingFile: String? = null
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
        root.addView(tv("Records WhatsApp, WhatsApp Business, Viber Out, and Dialpad calls only — your normal Samsung dialer (SIM) calls are never recorded. Works with or without speaker, but speaker gives clearer two-way audio since the recording is taken from the phone's microphone.", 13f))

        status = tv("Idle", 16f, true); root.addView(status)
        root.addView(btn("Start recording") { CallControl.start(this, "Manual"); refresh() })
        root.addView(btn("Stop") { CallControl.stop(this); refresh() })

        root.addView(tv("Settings", 16f, true))
        backendUrl = EditText(this).apply { hint = "EURO backend URL (https://…)" }; root.addView(backendUrl)
        myLang = EditText(this).apply { hint = "My language (en)" }; root.addView(myLang)
        theirLang = EditText(this).apply { hint = "Their language (auto)" }; root.addView(theirLang)
        autoDetect = CheckBox(this).apply { text = "Auto start/stop on WhatsApp/Viber/Dialpad calls" }; root.addView(autoDetect)
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

        root.addView(tv("Recordings — tap Play to listen right here", 16f, true))
        recordingsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(recordingsList)

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
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun stopPlayback() {
        try { player?.stop(); player?.release() } catch (e: Exception) { e.printStackTrace() }
        player = null; playingFile = null
    }

    private fun playOrStop(file: File, playBtn: Button) {
        if (playingFile == file.absolutePath) { stopPlayback(); refresh(); return }
        stopPlayback()
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stopPlayback(); refresh() }
                prepare(); start()
            }
            playingFile = file.absolutePath
            playBtn.text = "⏸ Stop"
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Could not play this file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refresh() {
        val lastSrc = Prefs.get(this, Prefs.LAST_SOURCE, "")
        status.text = if (RecorderService.isRecording) "● Recording… (mic mode: $lastSrc)" else "Idle"

        recordingsList.removeAllViews()
        val dir = File(getExternalFilesDir(null), "recordings")
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            recordingsList.addView(TextView(this).apply { text = "No recordings yet."; textSize = 13f })
            return
        }
        for (f in files) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val name = TextView(this).apply {
                text = f.name; textSize = 12f; setPadding(0, 12, 8, 12)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val playBtn = Button(this).apply {
                text = if (playingFile == f.absolutePath) "⏸ Stop" else "▶ Play"
            }
            playBtn.setOnClickListener { playOrStop(f, playBtn) }
            row.addView(name); row.addView(playBtn)
            recordingsList.addView(row)
        }
    }

    override fun onPause() { super.onPause(); stopPlayback() }
}
