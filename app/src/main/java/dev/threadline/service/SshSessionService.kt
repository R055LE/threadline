package dev.threadline.service

import android.annotation.SuppressLint
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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.threadline.MainActivity
import dev.threadline.R
import dev.threadline.SessionRuntime
import dev.threadline.core.model.ConnectionStage
import dev.threadline.core.model.SessionError
import dev.threadline.core.model.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SshSessionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var handlesSessionCommand = false

    override fun onCreate() {
        super.onCreate()
        SessionRuntime.initialize(applicationContext)
        createNotificationChannel()
        serviceScope.launch {
            SessionRuntime.manager.state.collectLatest(::onStateChanged)
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_CONNECT -> startPreparedConnection()
            ACTION_DISCONNECT -> SessionRuntime.manager.disconnect()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (handlesSessionCommand) {
            SessionRuntime.manager.onServiceDestroyed()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPreparedConnection() {
        handlesSessionCommand = true
        if (!SessionRuntime.manager.connectPrepared()) {
            handlesSessionCommand = false
            stopSelf()
            return
        }

        try {
            promoteToForeground(notificationFor(SessionRuntime.manager.state.value))
        } catch (_: RuntimeException) {
            SessionRuntime.manager.cancelPrepared(SessionError.ServiceStartFailed)
            stopSelf()
        }
    }

    private fun onStateChanged(state: SessionState) {
        if (!handlesSessionCommand) return

        when (state) {
            SessionState.Disconnected,
            is SessionState.Failed,
            -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> updateNotification(notificationFor(state))
        }
    }

    private fun promoteToForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(notification: Notification) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    private fun notificationFor(state: SessionState): Notification {
        val (title, text) = when (state) {
            SessionState.Disconnected -> "Threadline" to "SSH session ended"
            is SessionState.Connecting -> {
                val verb = when (state.stage) {
                    ConnectionStage.CONNECTING -> "Connecting"
                    ConnectionStage.AUTHENTICATING -> "Authenticating"
                    ConnectionStage.STARTING_SHELL -> "Starting shell"
                }
                "Threadline · ${state.displayName}" to verb
            }

            is SessionState.AwaitingHostKey ->
                "Threadline · ${state.displayName}" to "Confirm the server host key"
            is SessionState.Connected ->
                "Threadline · ${state.displayName}" to "Raw shell connected"
            is SessionState.Disconnecting ->
                "Threadline" to "Disconnecting"
            is SessionState.Failed ->
                "Threadline" to state.error.userMessage
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SshSessionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(state !is SessionState.Failed && state !is SessionState.Disconnected)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Disconnect", disconnectIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.session_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "live_ssh_session"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_CONNECT = "dev.threadline.action.CONNECT"
        private const val ACTION_DISCONNECT = "dev.threadline.action.DISCONNECT"

        fun connect(context: Context) {
            val intent = Intent(context, SshSessionService::class.java)
                .setAction(ACTION_CONNECT)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
