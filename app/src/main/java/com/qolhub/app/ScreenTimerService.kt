package com.qolhub.app

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

class ScreenTimerService : Service() {

    companion object {
        const val CHANNEL_ID = "screen_timer_channel"
    }

    private var timerThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val durationMs = intent?.getLongExtra("duration_ms", 30_000L) ?: 30_000L

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QoL Hub")
            .setContentText("Screen timer running...")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)

        timerThread?.interrupt()
        timerThread = thread {
            try {
                Thread.sleep(durationMs)
                lockScreen()
                stopSelf()
            } catch (e: InterruptedException) {
                // cancelled
            }
        }

        return START_NOT_STICKY
    }

    private fun lockScreen() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(adminComponent)) {
            dpm.lockNow()
        }
    }

    override fun onDestroy() {
        timerThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Screen Timer", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
