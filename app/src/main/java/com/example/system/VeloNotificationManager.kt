package com.example.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

class VeloNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_SYNC = "velo_sync_alerts"
        const val CHANNEL_SECURITY = "velo_security_alerts"
        const val CHANNEL_OFFLINE = "velo_offline_ready"
    }

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val syncChannel = NotificationChannel(
                CHANNEL_SYNC,
                "Cross-Device E2EE Sync",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when encrypted shortcuts, passwords, or reading pages sync across devices."
            }

            val securityChannel = NotificationChannel(
                CHANNEL_SECURITY,
                "Privacy & Security Shield",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for blocked tracker surges, HTTPS upgrades, and vault activity."
            }

            val offlineChannel = NotificationChannel(
                CHANNEL_OFFLINE,
                "Offline Reading Cache",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications when web pages are archived for offline access."
            }

            manager.createNotificationChannels(listOf(syncChannel, securityChannel, offlineChannel))
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun notifyOfflinePageCached(title: String, sizeKb: Long) {
        sendNotification(
            channelId = CHANNEL_OFFLINE,
            notificationId = 1001,
            title = "Saved for Offline Reading",
            body = "$title (${sizeKb.coerceAtLeast(1)} KB encrypted & cached locally)"
        )
    }

    fun notifySyncUpdate(summary: String) {
        sendNotification(
            channelId = CHANNEL_SYNC,
            notificationId = 1002,
            title = "Velo E2EE Sync Updated",
            body = summary
        )
    }

    fun notifySecurityAlert(title: String, body: String) {
        sendNotification(
            channelId = CHANNEL_SECURITY,
            notificationId = 1003,
            title = title,
            body = body
        )
    }

    private fun sendNotification(
        channelId: String,
        notificationId: Int,
        title: String,
        body: String
    ) {
        if (!hasNotificationPermission()) return

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Permission revoked at runtime
        }
    }
}
