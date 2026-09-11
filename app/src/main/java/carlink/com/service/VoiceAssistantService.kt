package carlink.com.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import carlink.com.MainActivity
import carlink.com.R
import carlink.com.data.CarLinkSettings
import carlink.com.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "CarLinkVoice"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "carlink_voice_channel"

/**
 * Persistent foreground service that:
 * 1. Intercepts steering wheel media key events via MediaSession.
 * 2. On activation: routes steering wheel inputs directly to YouTube.
 */
class VoiceAssistantService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // MediaSession for steering wheel key capture
    private var mediaSession: MediaSessionCompat? = null

    // Loaded settings
    private var settings: CarLinkSettings? = null

    companion object {
        const val ACTION_START = "carlink.com.service.START"
        const val ACTION_STOP = "carlink.com.service.STOP"
        const val ACTION_STATUS_CHANGED = "carlink.com.ACTION_STATUS_CHANGED"
        const val EXTRA_STATUS = "EXTRA_STATUS"

        const val STATUS_IDLE = "IDLE"
    }

    // ─── Service lifecycle ──────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VoiceAssistantService created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(STATUS_IDLE))
        loadSettingsAndInitialize()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            else -> loadSettingsAndInitialize()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "VoiceAssistantService destroying")
        releaseMediaSession()
        scope.cancel()
        super.onDestroy()
    }

    // ─── Settings & Init ───────────────────────────────────────────────────

    private fun loadSettingsAndInitialize() {
        scope.launch {
            try {
                settings = SettingsRepository(applicationContext).settings.first()
                initializeComponents()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load settings: ${e.message}")
            }
        }
    }

    private fun initializeComponents() {
        val s = settings ?: return
        initializeMediaSession(s.steeringKeyCode)
        Log.i(TAG, "VoiceAssistantService in steering/keep-alive mode")
        updateNotification(STATUS_IDLE)
        broadcastStatus(STATUS_IDLE)
    }

    // ─── MediaSession (Steering Wheel Keys) ───────────────────────────────

    /**
     * Set up a MediaSession to intercept hardware media button events.
     * The Brezza ZXi+ steering sends media key events through the CarLink device.
     */
    @Suppress("DEPRECATION")
    private fun initializeMediaSession(steeringKeyCode: Int) {
        releaseMediaSession()

        mediaSession = MediaSessionCompat(this, "CarLinkMediaSession").apply {
            @Suppress("DEPRECATION")
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PAUSED, 0L, 0f)
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    )
                    .build()
            )

            setCallback(object : MediaSessionCompat.Callback() {
                @Suppress("DEPRECATION")
                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    val event: KeyEvent? = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }

                    if (event != null && event.action == KeyEvent.ACTION_UP) {
                        Log.d(TAG, "Media key received: keyCode=${event.keyCode}")
                        if (event.keyCode == steeringKeyCode) {
                            Log.i(TAG, "Steering voice button pressed (keyCode=$steeringKeyCode)")

                            // 1. Bring YouTube to front if not already frontmost
                            val ytIntent = packageManager.getLaunchIntentForPackage("com.google.android.youtube")?.apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            }
                            if (ytIntent != null) {
                                try {
                                    startActivity(ytIntent)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to launch YouTube: ${e.message}")
                                }
                            }

                            // 2. Request Accessibility service to click YouTube's native voice search mic
                            CarLinkAccessibilityService.requestMicActivation()
                            return true
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })

            isActive = true
        }
        Log.i(TAG, "MediaSession active — listening for steering key $steeringKeyCode")
    }

    private fun releaseMediaSession() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
    }

    // ─── Notifications ────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "CarLink Voice Assistant background service"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text_idle))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(
            Intent(ACTION_STATUS_CHANGED).apply {
                putExtra(EXTRA_STATUS, status)
                setPackage(packageName)
            }
        )
    }
}
