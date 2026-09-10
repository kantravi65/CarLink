package carlink.com.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import carlink.com.MainActivity
import carlink.com.R
import carlink.com.data.CarLinkSettings
import carlink.com.data.SettingsRepository
import carlink.com.util.YouTubeLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException
import java.util.Locale

private const val TAG = "CarLinkVoice"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "carlink_voice_channel"

// Vosk audio config — must match what the model expects
private const val SAMPLE_RATE = 16000
private const val BUFFER_SIZE_SECONDS = 0.2f // 200 ms chunks

/**
 * Persistent foreground service that:
 * 1. Runs Vosk keyword spotting for "GUNNU" continuously in background.
 *    → Vosk is 100% offline, open source, no account or signup required.
 * 2. Intercepts steering wheel media key events via MediaSession.
 * 3. On activation: plays chime → starts Android SpeechRecognizer → passes query to YouTubeLauncher.
 *
 * Wake word flow:
 *   AudioRecord (raw PCM) → Vosk Recognizer (grammar=["GUNNU","[unk]"])
 *   → "GUNNU" detected → chime → SpeechRecognizer → YouTubeLauncher
 */
class VoiceAssistantService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Vosk model and recognizer
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null

    // Background audio recording job
    private var audioJob: Job? = null
    private var audioRecord: AudioRecord? = null

    // MediaSession for steering wheel key capture
    private var mediaSession: MediaSessionCompat? = null

    // Android SpeechRecognizer for converting speech → text after wake
    private var speechRecognizer: SpeechRecognizer? = null

    // Audio feedback
    private var toneGenerator: ToneGenerator? = null

    // Loaded settings
    private var settings: CarLinkSettings? = null

    // Prevent double-activation
    @Volatile private var isListening = false

    // Wake word grammar — Vosk will ONLY recognise these words, nothing else.
    // Hindi model phonetically maps "GUNNU" well (ग-न-ू sound).
    // "[unk]" catches everything else and suppresses road/music noise.
    private val WAKE_GRAMMAR = """["gunnu", "gunu", "gnu", "[unk]"]"""

    companion object {
        const val ACTION_START = "carlink.com.service.START"
        const val ACTION_STOP = "carlink.com.service.STOP"
        const val ACTION_WAKE = "carlink.com.service.WAKE"
        const val ACTION_STATUS_CHANGED = "carlink.com.ACTION_STATUS_CHANGED"
        const val EXTRA_STATUS = "EXTRA_STATUS"

        const val STATUS_IDLE = "IDLE"
        const val STATUS_LOADING = "LOADING"
        const val STATUS_LISTENING = "LISTENING"
        const val STATUS_PROCESSING = "PROCESSING"
        const val STATUS_LAUNCHING = "LAUNCHING"
    }

    // ─── Service lifecycle ──────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VoiceAssistantService created")
        createNotificationChannel()
        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        startForeground(NOTIFICATION_ID, buildNotification(STATUS_LOADING))
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
        stopAudioCapture()
        releaseVosk()
        releaseMediaSession()
        releaseSpeechRecognizer()
        toneGenerator?.release()
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
        if (s.serviceEnabled) {
            loadVoskModelAndStartListening()
        } else {
            Log.i(TAG, "VoiceAssistantService in steering/keep-alive mode (Vosk offline hotword disabled, mic free)")
            stopAudioCapture()
            releaseVosk()
            updateNotification(STATUS_IDLE)
            broadcastStatus(STATUS_IDLE)
        }
    }

    // ─── Vosk Wake Word Engine ─────────────────────────────────────────────

    /**
     * Custom unzipper to completely bypass Vosk's broken StorageService.
     * Extracts the zip directly to filesDir/vosk-model, stripping the parent folder.
     */
    private fun loadVoskModelAndStartListening() {
        updateNotification(STATUS_LOADING)
        broadcastStatus(STATUS_LOADING)

        scope.launch(Dispatchers.IO) {
            try {
                val modelDir = java.io.File(applicationContext.filesDir, "vosk-model")
                // Delete broken old model if it exists
                if (modelDir.exists()) {
                    modelDir.deleteRecursively()
                }

                Log.i(TAG, "Extracting Vosk zip model manually...")
                val zipStream = java.util.zip.ZipInputStream(applicationContext.assets.open("vosk-model-small-hi-0.22.zip"))
                var entry = zipStream.nextEntry
                while (entry != null) {
                    // Windows Zip files use \, Unix uses /. Normalize it first!
                    val normalizedName = entry.name.replace("\\", "/")
                    // The zip contains a top-level directory "vosk-model-small-hi-0.22/"
                    // We strip it so the model files sit directly inside `modelDir`
                    val name = normalizedName.substringAfter("/")
                    
                    if (name.isNotEmpty()) {
                        val file = java.io.File(modelDir, name)
                        if (entry.isDirectory || normalizedName.endsWith("/")) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            file.outputStream().use { zipStream.copyTo(it) }
                        }
                    }
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
                zipStream.close()

                Log.i(TAG, "Vosk Hindi model extracted and loaded successfully")
                voskModel = org.vosk.Model(modelDir.absolutePath)
                startWakeWordRecognizer()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Vosk model: \${e.message}")
                updateNotification(STATUS_IDLE)
                broadcastStatus(STATUS_IDLE)
            }
        }
    }

    private fun startWakeWordRecognizer() {
        stopAudioCapture()
        releaseVosk()

        try {
            val model = voskModel ?: return
            // Remove tight English grammar because the Hindi model outputs Devanagari script!
            // Full decoding will let us see exactly what Hindi word the engine maps "Gunnu" to.
            voskRecognizer = org.vosk.Recognizer(model, SAMPLE_RATE.toFloat())
            Log.i(TAG, "Vosk recognizer created with open Hindi grammar")
        } catch (e: java.io.IOException) {
            Log.e(TAG, "Failed to create Vosk recognizer: ${e.message}")
            return
        }

        audioJob = scope.launch(Dispatchers.IO) {
            startAudioCaptureLoop()
        }

        updateNotification(STATUS_IDLE)
        broadcastStatus(STATUS_IDLE)
        Log.i(TAG, "Wake word detection started — say GUNNU to activate")
    }

    /**
     * Continuous audio recording loop.
     * Reads PCM audio from the microphone → feeds to Vosk → checks for "GUNNU".
     * Runs on [Dispatchers.IO] so it never blocks the main thread.
     */
    private suspend fun startAudioCaptureLoop() {
        val bufferSize = android.media.AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(
            (SAMPLE_RATE * BUFFER_SIZE_SECONDS * 2).toInt() // 2 bytes per sample (16-bit)
        )

        val recorder = android.media.AudioRecord(
            android.media.MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (recorder.state != android.media.AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize — check RECORD_AUDIO permission")
            recorder.release()
            return
        }

        audioRecord = recorder
        recorder.startRecording()
        Log.d(TAG, "AudioRecord started, bufferSize=$bufferSize")

        val buffer = ShortArray(bufferSize / 2)

        try {
            while (audioJob?.isActive == true) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0 && !isListening) {
                    // Feed raw PCM to Vosk recognizer
                    val rec = voskRecognizer ?: break
                    if (rec.acceptWaveForm(buffer, read)) {
                        // Full utterance result
                        val result = rec.result
                        checkForWakeWord(result)
                    } else {
                        // Partial result — also check (faster response)
                        val partial = rec.partialResult
                        checkForWakeWord(partial)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio capture loop error: ${e.message}")
        } finally {
            recorder.stop()
            recorder.release()
            audioRecord = null
            Log.d(TAG, "AudioRecord stopped and released")
        }
    }

    /**
     * Parse Vosk JSON result and trigger wake word activation if "GUNNU" is found.
     * Vosk returns JSON like: {"text": "gunnu"} or {"partial": "gun"}
     */
    private fun checkForWakeWord(json: String?) {
        if (json.isNullOrBlank()) return
        val lower = json.lowercase()
        
        // Log all non-empty results so we can see what the Hindi model thinks "Gunnu" sounds like!
        if (lower.contains("text") && !lower.contains("\"\"")) {
            Log.d(TAG, "Vosk heard: $json")
        } else if (lower.contains("partial") && !lower.contains("\"\"")) {
            Log.d(TAG, "Vosk partial: $json")
        }
        
        // Check for English OR Devanagari matches
        if (lower.contains("gunnu") || lower.contains("gunu") || lower.contains("gnu") ||
            lower.contains("गुन्नू") || lower.contains("गुन्नु") || lower.contains("गुन") || lower.contains("गन") || lower.contains("गन्नु")
        ) {
            Log.i(TAG, "Wake word GUNNU detected! json=$json")
            scope.launch(Dispatchers.Main) {
                onWakeWordDetected()
            }
        }
    }

    private fun stopAudioCapture() {
        audioJob?.cancel()
        audioJob = null
        audioRecord?.apply {
            try { stop() } catch (_: Exception) {}
            release()
        }
        audioRecord = null
    }

    private fun releaseVosk() {
        voskRecognizer?.close()
        voskRecognizer = null
        // Do NOT close voskModel here — reuse it for new recognizer instances
    }

    // ─── MediaSession (Steering Wheel Keys) ───────────────────────────────

    /**
     * Set up a MediaSession to intercept hardware media button events.
     * The Brezza ZXi+ steering sends media key events through the CarLink device.
     *
     * NOTE: MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS is deprecated in API 31+
     * but still required for media button routing on older implementations.
     * On API 31+ the system uses MediaController routing automatically.
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
                    // Use type-safe getParcelableExtra for API 33+
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

                            // 1. Release any background audio capture so the mic is 100% free for YouTube
                            stopAudioCapture()

                            // 2. Bring YouTube to front if not already frontmost
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

                            // 3. Request Accessibility service to click YouTube's native voice search mic
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

    // ─── Voice Activation ─────────────────────────────────────────────────

    private fun onWakeWordDetected() {
        if (isListening) {
            Log.d(TAG, "Already listening — ignoring duplicate wake event")
            return
        }
        Log.i(TAG, "Activation triggered — playing chime and starting STT")
        playActivationChime()
        updateNotification(STATUS_LISTENING)
        broadcastStatus(STATUS_LISTENING)
        startSpeechRecognition()
    }

    // ─── Speech Recognition ───────────────────────────────────────────────

    private fun startSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "SpeechRecognizer not available on this device")
            isListening = false
            return
        }

        isListening = true
        releaseSpeechRecognizer()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onEndOfSpeech() {
                    updateNotification(STATUS_PROCESSING)
                    broadcastStatus(STATUS_PROCESSING)
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "STT error code: $error")
                    isListening = false
                    updateNotification(STATUS_IDLE)
                    broadcastStatus(STATUS_IDLE)
                }

                override fun onResults(results: Bundle?) {
                    val query = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    Log.i(TAG, "STT result: \"$query\"")
                    isListening = false

                    if (!query.isNullOrBlank()) {
                        updateNotification(STATUS_LAUNCHING)
                        broadcastStatus(STATUS_LAUNCHING)
                        YouTubeLauncher.search(
                            applicationContext,
                            query,
                            autoPlay = settings?.autoPlayFirst ?: false
                        )
                        scope.launch {
                            delay(2000)
                            updateNotification(STATUS_IDLE)
                            broadcastStatus(STATUS_IDLE)
                        }
                    } else {
                        updateNotification(STATUS_IDLE)
                        broadcastStatus(STATUS_IDLE)
                    }
                }
            })
        }

        speechRecognizer?.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }
        )
    }

    private fun releaseSpeechRecognizer() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    // ─── Audio Feedback ───────────────────────────────────────────────────

    private fun playActivationChime() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
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
        val text = when (status) {
            STATUS_LOADING   -> "⏳ Loading voice model…"
            STATUS_LISTENING -> getString(R.string.notification_text_listening)
            STATUS_PROCESSING, STATUS_LAUNCHING -> getString(R.string.notification_text_processing)
            else             -> getString(R.string.notification_text_idle)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
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
