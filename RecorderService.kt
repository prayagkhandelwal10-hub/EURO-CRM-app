package com.kushal.eurocall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that records the call audio through the microphone.
 *
 * Capture note: on stock Android the reliable way to get BOTH voices is to keep the
 * call on SPEAKER — the mic then picks up your voice directly and the other party
 * through the speaker. AudioSource.MIC is used (no echo cancellation) so the far-end
 * is not stripped out. On a rooted / privileged build you can switch SOURCE to
 * MediaRecorder.AudioSource.VOICE_CALL for a clean two-channel capture.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.kushal.eurocall.START"
        const val ACTION_STOP = "com.kushal.eurocall.STOP"
        const val EXTRA_LABEL = "label"          // e.g. "WhatsApp", "Cellular", "Viber"
        const val EXTRA_NUMBER = "number"        // optional phone number for CRM matching
        private const val CHANNEL_ID = "eurocall_rec"
        private const val NOTIF_ID = 4711
        @Volatile var isRecording = false
            private set
        // AudioSource.MIC records the room (both voices via speaker). Change to VOICE_CALL on rooted builds.
        private const val SOURCE = MediaRecorder.AudioSource.MIC
    }

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var label: String = "Call"
    private var number: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                label = intent.getStringExtra(EXTRA_LABEL) ?: "Call"
                number = intent.getStringExtra(EXTRA_NUMBER) ?: ""
                startForegroundSafe()
                startRecording()
            }
            ACTION_STOP -> {
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundSafe() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Call recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EURO Call — recording")
            .setContentText("Listening & recording ($label). Keep the call on speaker.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startRecording() {
        if (isRecording) return
        try {
            val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeLabel = label.replace(Regex("[^A-Za-z0-9]"), "")
            currentFile = File(dir, "call_${safeLabel}_$stamp.m4a")

            recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(SOURCE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)   // 16 kHz mono — ideal for Whisper STT
                setAudioChannels(1)
                setAudioEncodingBitRate(64000)
                setOutputFile(currentFile!!.absolutePath)
                prepare()
                start()
            }
            isRecording = true
        } catch (e: Exception) {
            e.printStackTrace()
            isRecording = false
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        try {
            recorder?.apply { stop(); release() }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            recorder = null
            isRecording = false
        }
        // Hand the finished file to the uploader → backend transcribes/translates/summarizes → CRM
        currentFile?.let { f ->
            Uploader.enqueue(applicationContext, f, label, number)
        }
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
