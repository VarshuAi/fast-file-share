package com.fastfileshare.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class MediaServerService : Service() {
    private val tag = "MediaServerService"
    private val notificationId = 101
    private val channelId = "FastDropServerChannel"

    private var server: LocalHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "Service start command received")

        // 1. Create Notification Channel for API 26+ (Oreo)
        createNotificationChannel()

        // 2. Build persistent notification
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("⚡ Fast File Share Active")
            .setContentText("High-speed offline file sharing is running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        // 3. Promote service to Foreground Service so OS doesn't kill it
        startForeground(notificationId, notification)

        // 4. Acquire WakeLock to keep CPU running when screen is turned off
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FastFileShare::WakeLock").apply {
            acquire(10 * 60 * 60 * 1000L /* 10 hours max */)
        }

        // 5. Acquire WifiLock to prevent the Wi-Fi chip from going to sleep or low-power state
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            } else {
                WifiManager.WIFI_MODE_FULL
            },
            "FastFileShare::WifiLock"
        ).apply {
            acquire()
        }

        // 6. Start Server Thread (Passing our Context Assets to serve TV app pages)
        if (server == null) {
            server = LocalHttpServer(8080, assets)
            server?.start()
            Log.d(tag, "Local HTTP Server started on port 8080")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(tag, "Service shutting down")
        
        // 1. Stop Server
        server?.stopServer()
        server = null

        // 2. Release locks
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error releasing WakeLock: ${e.message}")
        }
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error releasing WifiLock: ${e.message}")
        }
        wifiLock = null

        // 3. Demote foreground state
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId,
                "Fast File Share Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
