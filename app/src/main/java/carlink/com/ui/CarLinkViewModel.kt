package carlink.com.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import carlink.com.data.CarLinkSettings
import carlink.com.data.SettingsRepository
import carlink.com.service.VoiceAssistantService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel shared between MainScreen and SettingsScreen.
 * Exposes settings as StateFlow and provides service control methods.
 */
class CarLinkViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SettingsRepository(application)

    /** Live settings from DataStore */
    val settings: StateFlow<CarLinkSettings> = repo.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CarLinkSettings(
                wakeWord = "GUNNU",
                serviceEnabled = false,
                autoPlayFirst = false,
                autoSkipAds = true,
                steeringKeyCode = 85
            )
        )

    /** Voice assistant status: idle / listening / processing / launching */
    private val _serviceStatus = MutableStateFlow(VoiceAssistantService.STATUS_IDLE)
    val serviceStatus: StateFlow<String> = _serviceStatus.asStateFlow()

    // Listen to broadcast status updates from VoiceAssistantService
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(VoiceAssistantService.EXTRA_STATUS)
                ?: VoiceAssistantService.STATUS_IDLE
            _serviceStatus.value = status
        }
    }

    init {
        // Register for service status broadcasts
        val filter = IntentFilter(VoiceAssistantService.ACTION_STATUS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            application.registerReceiver(statusReceiver, filter)
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(statusReceiver)
        } catch (_: Exception) {}
    }

    // ─── Service Control ─────────────────────────────────────────────────

    fun startService() {
        viewModelScope.launch {
            repo.setServiceEnabled(true)
        }
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, VoiceAssistantService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }

    fun stopService() {
        viewModelScope.launch {
            repo.setServiceEnabled(false)
        }
        val ctx = getApplication<Application>()
        ctx.stopService(Intent(ctx, VoiceAssistantService::class.java))
        _serviceStatus.value = VoiceAssistantService.STATUS_IDLE
    }

    // ─── Settings Updates ────────────────────────────────────────────────

    fun setAutoPlayFirst(enabled: Boolean) = viewModelScope.launch { repo.setAutoPlayFirst(enabled) }
    fun setAutoSkipAds(enabled: Boolean) = viewModelScope.launch { repo.setAutoSkipAds(enabled) }
    fun setSteeringKeyCode(code: Int) = viewModelScope.launch { repo.setSteeringKeyCode(code) }
    fun setWakeWord(word: String) = viewModelScope.launch { repo.setWakeWord(word) }
}
