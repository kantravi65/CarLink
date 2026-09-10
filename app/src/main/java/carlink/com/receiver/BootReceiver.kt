package carlink.com.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import carlink.com.data.SettingsRepository
import carlink.com.service.VoiceAssistantService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "BootReceiver"

/**
 * Automatically starts the [VoiceAssistantService] when:
 *   - The device finishes booting (BOOT_COMPLETED)
 *   - The app package is replaced/updated (MY_PACKAGE_REPLACED)
 *
 * Service is only started if the user had it enabled before the reboot
 * (persisted in DataStore settings).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        Log.i(TAG, "Boot/update event received ($action) — checking if service should start")

        // Read the persisted "service enabled" setting before starting
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(context).settings.first()
                
                // If auto-skip is desired, try to forcefully re-enable accessibility service
                if (settings.autoSkipAds) {
                    tryAutoEnableAccessibility(context)
                }

                // ALWAYS start VoiceAssistantService! Even if the offline voice engine is disabled,
                // the service must run its Foreground Notification to act as an immortal Keep-Alive.
                // This prevents the car's memory manager from killing the Accessibility Service
                // when the user swipes the app away from the recent apps menu.
                Log.i(TAG, "Starting VoiceAssistantService to ensure background keep-alive!")
                startVoiceService(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error reading settings on boot: ${e.message}")
                // If settings can't be read, don't start (safe default)
            }
        }
    }

    private fun startVoiceService(context: Context) {
        val serviceIntent = Intent(context, VoiceAssistantService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun tryAutoEnableAccessibility(context: Context) {
        val serviceString = "carlink.com/carlink.com.service.CarLinkAccessibilityService"
        try {
            // Check if we have the WRITE_SECURE_SETTINGS permission
            if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
                val isBound = am?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    ?.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName } == true

                if (!isBound) {
                    // Force Android to re-bind by clearing and re-setting
                    android.provider.Settings.Secure.putString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        ""
                    )
                    android.provider.Settings.Secure.putString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        serviceString
                    )
                    android.provider.Settings.Secure.putString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
                        "1"
                    )
                    Log.i(TAG, "Self-healed: Toggled CarLink in enabled accessibility services.")
                }
            } else {
                Log.w(TAG, "Cannot auto-enable accessibility: WRITE_SECURE_SETTINGS not granted via ADB.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-enable accessibility: \${e.message}")
        }
    }
}
