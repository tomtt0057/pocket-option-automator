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
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ Foreground service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🚀 Foreground service started")

        // Activate the bot
        BotAccessibilityService.isBotActive = true

        // Start as foreground with persistent notification
        startForeground(NOTIFICATION_ID, buildNotification())

        // If service is killed by system, restart it automatically
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Deactivate the bot when service stops
        BotAccessibilityService.isBotActive = false
        Log.d(TAG, "🔴 Foreground service stopped")
    }

    override fun onBind(intent: Intent?): IBinder? {
        // We do not need binding for this service
        return null
    }

    // ─── Build the persistent notification ────────────────────────────────

    private fun buildNotification(): Notification {
        // Tapping the notification opens the app
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop bot action button inside notification
        val stopIntent = Intent(this, BotForegroundService::class.java)
        stopIntent.action = "STOP_BOT"
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🤖 Pocket Bot Active")
            .setContentText("Watching for trading signals...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Cannot be dismissed by swipe
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_delete,
                "Stop Bot",
                stopPendingIntent
            )
            .build()
    }

    // ─── Create notification channel (required for Android 8+) ───────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        manager.createNotificationChannel(channel)
        Log.d(TAG, "✅ Notification channel created")
    }
}
