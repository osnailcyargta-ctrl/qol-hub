package com.qolhub.app

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

class FocusService : Service() {

    companion object {
        const val CHANNEL_ID = "focus_channel"
        const val NOTIF_ID = 3
        const val CHECK_INTERVAL = 1000L
    }

    private var focusThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val blockedPkg = intent?.getStringExtra("blocked_package") ?: return START_NOT_STICKY
        val durationMs = intent.getLongExtra("duration_ms", 25 * 60_000L)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Focus Mode Active")
            .setContentText("Blocking: $blockedPkg")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notification)

        focusThread?.interrupt()
        focusThread = thread {
            val endTime = System.currentTimeMillis() + durationMs
            try {
                while (System.currentTimeMillis() < endTime) {
                    if (getForegroundApp() == blockedPkg) {
                        val home = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(home)
                    }
                    Thread.sleep(CHECK_INTERVAL)
                }
            } catch (e: InterruptedException) {
                // cancelled
            }
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun getForegroundApp(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 5000, now)
        return stats?.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    override fun onDestroy() {
        focusThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Focus Mode", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
