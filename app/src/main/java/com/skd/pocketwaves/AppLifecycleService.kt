package com.skd.pocketwaves

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

/**
 * Hosts the playback notification as a foreground-service notification.
 *
 * Why: a plain notification outlives the app process. Many OEM "clear all /
 * clean" actions kill the process without running any callbacks, so the
 * notification used to stay behind with dead buttons. Notifications that belong
 * to a foreground service are removed by Android itself whenever that service's
 * process dies, so they can no longer be orphaned.
 *
 * android:stopWithTask="false" (manifest) additionally delivers onTaskRemoved()
 * on devices that do clean up gracefully, so we remove it immediately there too.
 */
class AppLifecycleService : Service() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_NOTIFICATION, Notification::class.java)
        else
            @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_NOTIFICATION)

        if (notification != null) {
            show(notification)
        } else {
            // Started without a notification — nothing to host.
            stopSelf()
        }
        return START_NOT_STICKY // do not restart if the system kills the service
    }

    /** Posts or updates the playback notification while staying in the foreground. */
    fun show(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, MainActivity.NOTIFICATION_ID, notification, type)
    }

    /** Removes the notification and shuts the service down. */
    fun dismiss() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(MainActivity.NOTIFICATION_ID)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped the app from recents — remove the notification immediately
        dismiss()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NOTIFICATION = "extra_notification"
        var instance: AppLifecycleService? = null
            private set
    }
}
