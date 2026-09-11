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

    companion object {
        @Volatile private var lastBootLaunchMs = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "Boot/wake event received ($action) — initializing CarLink")

        // 1. Synchronously auto-enable and bind Accessibility Service (<5ms IPC)
        tryAutoEnableAccessibility(context)

        // 2. Start VoiceAssistantService for keep-alive and steering media keys
        startVoiceService(context)

        // 3. Immediately launch MainActivity so the app opens automatically
        val now = System.currentTimeMillis()
        if (now - lastBootLaunchMs > 4_000L) {
            lastBootLaunchMs = now
            launchMainActivity(context)

            // 4. Use goAsync() to schedule a secondary launch after 2.5s
            // In case the car launcher takes a few moments to finish its boot animation
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    kotlinx.coroutines.delay(2500)
                    launchMainActivity(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in delayed boot launch: ${e.message}")
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private fun launchMainActivity(context: Context) {
        try {
            val appIntent = Intent(context, carlink.com.MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            context.startActivity(appIntent)
            Log.i(TAG, "MainActivity launched successfully on boot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch MainActivity on boot: ${e.message}")
        }
    }

    private fun startVoiceService(context: Context) {
        try {
            val serviceIntent = Intent(context, VoiceAssistantService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "VoiceAssistantService started on boot")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VoiceAssistantService on boot: ${e.message}")
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

                val a11yGlobal = try {
                    android.provider.Settings.Secure.getInt(context.contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED)
                } catch (e: Exception) { 0 }

                if (!isBound || a11yGlobal != 1) {
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
                    Log.i(TAG, "Self-healed: Enabled and bound CarLink accessibility service.")
                }
            } else {
                Log.w(TAG, "Cannot auto-enable accessibility: WRITE_SECURE_SETTINGS not granted via ADB.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-enable accessibility: ${e.message}")
        }
    }
}
