package ceo.villa.matty.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import ceo.villa.matty.container

/**
 * Optional foreground service that keeps the process (and so the WebSocket) alive while Matty
 * is in the background. It holds no state of its own: the connection lives in the repository.
 */
class ConnectionService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val c = applicationContext.container
        // Android may start this service again in a new process: that starts the app.
        c.sessions.ensureActive()
        val repo = c.sessions.current.value
        if (repo == null || !c.settings.value.backgroundConnection) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, c.notifier.connectionNotification(repo.siteName), type)
        }.onFailure { stopSelf() }
        return START_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 7001

        fun sync(context: Context, enabled: Boolean) {
            val intent = Intent(context, ConnectionService::class.java)
            if (enabled && context.container.sessions.current.value != null) {
                runCatching { context.startForegroundService(intent) }
            } else {
                context.stopService(intent)
            }
        }
    }
}
