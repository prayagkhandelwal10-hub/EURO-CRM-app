package com.kushal.eurocall

import android.content.Context
import android.content.Intent
import android.os.Build

/** Central helper to start/stop the RecorderService from anywhere. */
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
        val i = Intent(ctx, RecorderService::class.java).apply { action = RecorderService.ACTION_STOP }
        ctx.startService(i)
    }
}
