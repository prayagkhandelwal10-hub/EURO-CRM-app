package com.kushal.eurocall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * Auto start/stop recording for normal CELLULAR calls.
 * OFFHOOK -> start, IDLE -> stop. VoIP calls are handled by VoipCallListener.
 */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.getBool(context, Prefs.AUTO_DETECT, true)) return
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> CallControl.start(context, "Cellular", number)
            TelephonyManager.EXTRA_STATE_IDLE -> CallControl.stop(context)
        }
    }
}
