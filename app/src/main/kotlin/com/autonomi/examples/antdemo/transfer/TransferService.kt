package com.autonomi.examples.antdemo.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.autonomi.examples.antdemo.MainActivity

/// Foreground service that keeps up/downloads alive while the app is
/// backgrounded. Android suspends (and can kill) backgrounded processes, which
/// would drop the FFI P2P node mid-transfer; a `dataSync` foreground service —
/// with an ongoing notification + a partial wake lock — tells the OS to keep us
/// running. Started when the first transfer becomes active, stopped when none
/// remain. This is V2-563, the Android counterpart of iOS's "keep app open"
/// warning (on iOS no entitlement unlocks background QUIC P2P — see V2-562).
class TransferService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Reconcile against the live count rather than trusting the intent: a
        // transfer that ends before this async start command runs would leave a
        // stale ACTION_STOP skipped (the sender's isRunning was still false), so
        // re-check here and self-stop if nothing is active.
        val active = com.autonomi.examples.antdemo.files.FilesStore.activeTransferCount()
        if (intent?.action == ACTION_STOP || active <= 0) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(active),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        acquireWakeLock()
        isRunning = true
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        isRunning = false
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        releaseWakeLock()
        isRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(count: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (count == 1) "1 transfer in progress" else "$count transfers in progress"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Autonomi")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Transfers", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Keeps uploads and downloads running in the background"
                        setShowBadge(false)
                    },
                )
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "antdemo:transfer").apply {
            setReferenceCounted(false)
            acquire(TIMEOUT_MS) // safety cap — released on stop, this just bounds a leak
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "transfers"
        private const val NOTIF_ID = 1001
        private const val ACTION_STOP = "com.autonomi.examples.antdemo.STOP_TRANSFERS"
        private const val TIMEOUT_MS = 30 * 60 * 1000L

        @Volatile private var isRunning = false

        /// Reflect the current active-transfer count: start (or refresh) the
        /// service when > 0, stop it when 0. The service re-reads the live count
        /// on each start, so it self-corrects if a transfer ends before an async
        /// start command runs. Must be called while the app is foreground for the
        /// initial start (Android bars background FGS starts) — transfers are
        /// always user-initiated from the foreground, so that holds.
        fun sync(context: Context, activeCount: Int) {
            val ctx = context.applicationContext
            val intent = Intent(ctx, TransferService::class.java)
            if (activeCount <= 0) {
                if (!isRunning) return
                intent.action = ACTION_STOP
                runCatching { ctx.startService(intent) }
            } else {
                ContextCompat.startForegroundService(ctx, intent)
            }
        }
    }
}
