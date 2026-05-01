package com.tomtt0057.pocketbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class BotForegroundService : Service() {

    companion object {
        const val TAG = "PocketBotService"
        const val CHANNEL_ID = "pocket_bot_channel"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_NAME = "Bot Notifications"
        const val CHANNEL_DESC = "Notifications from Pocket Option Bot"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Foreground service created")
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        Log.d(TAG, "Foreground service started")
        BotAccessibilityService.isBotActive = true
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        BotAccessibilityService.isBotActive = false
        Log.d(TAG, "Foreground service stopped")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pocket Bot Active")
            .setContentText("Watching for trading signals...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = CHANNEL_DESC
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        manager.createNotificationChannel(channel)
        Log.d(TAG, "Notification channel created")
    }
}
