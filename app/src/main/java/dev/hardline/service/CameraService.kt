package dev.hardline.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.hardline.App
import dev.hardline.R
import dev.hardline.core.Keys
import dev.hardline.ui.MainActivity

/** Keeps the process in the foreground while a camera is open, recording or being served. */
class CameraService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    private var mediaSession: MediaSession? = null
    private var settingsListener: Any? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Camera status", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        val controller = (application as App).controller
        settingsListener = controller.settings.onChange { key ->
            if (key == Keys.mediaPlayAction.key || key == Keys.mediaPreviousAction.key || key == Keys.mediaNextAction.key) refreshMediaSession()
        }
        refreshMediaSession()
    }

    /**
     * Headset and remote-control buttons reach the app through a media session. It is only active
     * while one of those buttons has an action, so that music players keep their buttons otherwise.
     */
    private fun refreshMediaSession() {
        val controller = (application as App).controller
        val wanted = listOf(Keys.mediaPlayAction, Keys.mediaPreviousAction, Keys.mediaNextAction).any { controller.settings[it] != 0 }
        if (!wanted) {
            mediaSession?.release()
            mediaSession = null
            return
        }
        if (mediaSession != null) return
        mediaSession = MediaSession(this, "HardLine").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    @Suppress("DEPRECATION") val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                    if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
                    return controller.onMediaKey(event.keyCode)
                }
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f).build(),
            )
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as App).controller.shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        val projection = intent?.getBooleanExtra(EXTRA_PROJECTION, false) == true
        promote(projection)
        (application as App).controller.onServicePromoted(projection)
        return START_STICKY
    }

    /** Enters the foreground with every service type the app is currently entitled to. */
    fun promote(withProjection: Boolean = false) {
        val n = buildNotification((application as App).controller.statusLine())
        if (Build.VERSION.SDK_INT < 29) return startForeground(NOTIFICATION_ID, n)
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        var extra = 0
        if (Build.VERSION.SDK_INT >= 30) {
            if (granted(Manifest.permission.CAMERA)) extra = extra or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (granted(Manifest.permission.RECORD_AUDIO)) extra = extra or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (withProjection) extra = extra or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        try {
            startForeground(NOTIFICATION_ID, n, types or extra)
        } catch (e: Exception) {
            // Camera and microphone types are refused when the app is not visible; the USB type is enough to keep running.
            Log.w("CameraService", "limited foreground types: ${e.message}")
            runCatching { startForeground(NOTIFICATION_ID, n, types) }
        }
    }

    fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CameraService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        instance = null
        mediaSession?.release()
        mediaSession = null
        (settingsListener as? android.content.SharedPreferences.OnSharedPreferenceChangeListener)?.let {
            (application as App).controller.settings.prefs.unregisterOnSharedPreferenceChangeListener(it)
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "status"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "dev.hardline.STOP"
        const val EXTRA_PROJECTION = "projection"
        @Volatile var instance: CameraService? = null
            private set

        fun start(context: Context, withProjection: Boolean = false) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, CameraService::class.java).putExtra(EXTRA_PROJECTION, withProjection))
            }.onFailure { Log.w("CameraService", "could not start", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraService::class.java))
        }
    }
}
