package com.example.myassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Android does not allow an app to start the microphone by itself right after a restart.
 * So after boot we show one notification; a single tap on it wakes Jarvis.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("jarvis_boot", "Jarvis startup", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = Intent(context, MainActivity::class.java)
            .putExtra("autostart", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, "jarvis_boot")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Wake Jarvis")
            .setContentText("Tap here to start Jarvis after the restart")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(2, n)
        } catch (e: Exception) {
        }
    }
}
