package com.kushal.eurocall

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Detects ongoing VoIP calls by watching the "ongoing call" notifications that
 * WhatsApp / Viber / Dialpad post while a call is active, and auto start/stops recording.
 *
 * Enable once: Settings > Notifications > Special app access > Notification access > EURO Call.
 *
 * Detection is heuristic (package + notification category/text). Tune the keywords below
 * for the exact call notifications your apps post on your phone.
 */
class VoipCallListener : NotificationListenerService() {

    private val voipPackages = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.viber.voip" to "Viber",
        "com.dialpad.dialpad" to "Dialpad"
    )

    // Words that appear on an ACTIVE call notification (add localised variants as needed)
    private val activeHints = listOf("ongoing call", "ongoing voice call", "voice call", "call in progress", "tap to return", "calling", "on call")

    private var activePackage: String? = null

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Prefs.getBool(applicationContext, Prefs.AUTO_DETECT, true)) return
        val app = voipPackages[sbn.packageName] ?: return
        val extras = sbn.notification.extras
        val text = buildString {
            append(extras.getCharSequence("android.title") ?: "")
            append(" ")
            append(extras.getCharSequence("android.text") ?: "")
            append(" ")
            append(sbn.notification.category ?: "")
        }.lowercase()

        val looksLikeActiveCall = sbn.notification.category == "call" ||
            activeHints.any { text.contains(it) }

        if (looksLikeActiveCall && activePackage == null) {
            activePackage = sbn.packageName
            CallControl.start(applicationContext, app)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // When the call notification disappears, the call ended -> stop.
        if (sbn.packageName == activePackage) {
            activePackage = null
            CallControl.stop(applicationContext)
        }
    }
}
