package com.kushal.eurocall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kushal.eurocall.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled by user re-tapping if needed */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Load settings
        b.backendUrl.setText(Prefs.get(this, Prefs.BACKEND_URL, ""))
        b.myLang.setText(Prefs.get(this, Prefs.MY_LANG, "en"))
        b.theirLang.setText(Prefs.get(this, Prefs.THEIR_LANG, "auto"))
        b.autoDetect.isChecked = Prefs.getBool(this, Prefs.AUTO_DETECT, true)

        b.saveSettings.setOnClickListener {
            Prefs.set(this, Prefs.BACKEND_URL, b.backendUrl.text.toString())
            Prefs.set(this, Prefs.MY_LANG, b.myLang.text.toString())
            Prefs.set(this, Prefs.THEIR_LANG, b.theirLang.text.toString())
            Prefs.setBool(this, Prefs.AUTO_DETECT, b.autoDetect.isChecked)
            toast("Settings saved")
        }

        b.grantPerms.setOnClickListener { requestPerms() }
        b.enableVoip.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        b.btnStart.setOnClickListener {
            CallControl.start(this, "Manual")
            refresh()
        }
        b.btnStop.setOnClickListener {
            CallControl.stop(this)
            refresh()
        }

        requestPerms()
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun requestPerms() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    private fun refresh() {
        b.status.text = if (RecorderService.isRecording) "● Recording…" else "Idle"
        val dir = File(getExternalFilesDir(null), "recordings")
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() }?.map { it.name } ?: emptyList()
        b.recordings.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, files)
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
}
