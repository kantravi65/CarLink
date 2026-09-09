package carlink.com.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Extension property to get a singleton DataStore instance
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "carlink_settings")

/**
 * Persistent settings store for CarLink.
 * Backed by DataStore Preferences (survives app restarts).
 * No API keys required — Vosk is fully open source and offline.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        // Wake word phrase label (display only — actual detection is via Vosk grammar)
        val KEY_WAKE_WORD = stringPreferencesKey("wake_word")

        // Whether the voice assistant foreground service should be running
        val KEY_SERVICE_ENABLED = booleanPreferencesKey("service_enabled")

        // Auto-play the first YouTube search result
        val KEY_AUTO_PLAY_FIRST = booleanPreferencesKey("auto_play_first")

        // Auto-skip YouTube ads via Accessibility Service
        val KEY_AUTO_SKIP_ADS = booleanPreferencesKey("auto_skip_ads")

        // KeyCode of the steering wheel button that triggers voice input
        // Default: KEYCODE_MEDIA_PLAY (85) — common for steering mic/voice buttons
        val KEY_STEERING_KEYCODE = intPreferencesKey("steering_keycode")
    }

    /** Observe all settings as a [CarLinkSettings] flow. */
    val settings: Flow<CarLinkSettings> = context.dataStore.data.map { prefs ->
        CarLinkSettings(
            wakeWord = prefs[KEY_WAKE_WORD] ?: "GUNNU",
            serviceEnabled = prefs[KEY_SERVICE_ENABLED] ?: false,
            autoPlayFirst = prefs[KEY_AUTO_PLAY_FIRST] ?: false,
            autoSkipAds = prefs[KEY_AUTO_SKIP_ADS] ?: true,
            steeringKeyCode = prefs[KEY_STEERING_KEYCODE] ?: 85 // KEYCODE_MEDIA_PLAY
        )
    }

    suspend fun setWakeWord(word: String) {
        context.dataStore.edit { it[KEY_WAKE_WORD] = word }
    }

    suspend fun setServiceEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SERVICE_ENABLED] = enabled }
    }

    suspend fun setAutoPlayFirst(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_PLAY_FIRST] = enabled }
    }

    suspend fun setAutoSkipAds(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_SKIP_ADS] = enabled }
    }

    suspend fun setSteeringKeyCode(keyCode: Int) {
        context.dataStore.edit { it[KEY_STEERING_KEYCODE] = keyCode }
    }
}

/**
 * Immutable snapshot of all CarLink settings.
 */
data class CarLinkSettings(
    val wakeWord: String,
    val serviceEnabled: Boolean,
    val autoPlayFirst: Boolean,
    val autoSkipAds: Boolean,
    val steeringKeyCode: Int
)
