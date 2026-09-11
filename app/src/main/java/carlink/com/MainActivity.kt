package carlink.com

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import carlink.com.ui.CarLinkViewModel
import carlink.com.ui.MainScreen
import carlink.com.ui.SettingsScreen
import carlink.com.ui.theme.CarLinkTheme
import com.github.javiersantos.appupdater.AppUpdater
import com.github.javiersantos.appupdater.enums.UpdateFrom
import com.github.javiersantos.appupdater.enums.Display

private const val ROUTE_MAIN = "main"
private const val ROUTE_SETTINGS = "settings"

class MainActivity : ComponentActivity() {

    private val viewModel: CarLinkViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        // Self-heal accessibility if the car reboot blocked the BootReceiver
        tryAutoEnableAccessibility()
    }

    private fun tryAutoEnableAccessibility() {
        val serviceString = "carlink.com/carlink.com.service.CarLinkAccessibilityService"
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                val am = getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
                val isBound = am?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    ?.any { it.resolveInfo?.serviceInfo?.packageName == packageName } == true

                if (!isBound) {
                    // Force Android to rebind by toggling
                    android.provider.Settings.Secure.putString(
                        contentResolver,
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        ""
                    )
                    android.provider.Settings.Secure.putString(
                        contentResolver,
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        serviceString
                    )
                    android.provider.Settings.Secure.putString(
                        contentResolver,
                        android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
                        "1"
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check for app updates from GitHub Releases
        AppUpdater(this)
            .setUpdateFrom(UpdateFrom.GITHUB)
            .setGitHubUserAndRepo("kantravi65", "CarLink")
            .setDisplay(Display.DIALOG)
            .start()

        enableEdgeToEdge()
        setContent {
            CarLinkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CarLinkApp(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
fun CarLinkApp(viewModel: CarLinkViewModel) {
    val navController = rememberNavController()

    // Note: App no longer requests RECORD_AUDIO as Vosk is removed.
    
    // Auto-Start whitelist instruction
    var showAutoStartNotice by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val ctx = navController.context
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    if (showAutoStartNotice) {
        AlertDialog(
            onDismissRequest = { showAutoStartNotice = false },
            title = { Text("Important: Background Execution") },
            text = {
                Text(
                    "To ensure steering wheel controls work automatically after the car reboots, " +
                    "please open your car's 'Auto Start' or 'Background App' manager settings " +
                    "and explicitly allow CarLink to run in the background."
                )
            },
            confirmButton = {
                TextButton(onClick = { showAutoStartNotice = false }) { Text("Got it") }
            }
        )
    }

    // Navigation graph
    NavHost(
        navController = navController,
        startDestination = ROUTE_MAIN
    ) {
        composable(ROUTE_MAIN) {
            MainScreen(
                viewModel = viewModel,
                onNavigateToSettings = { navController.navigate(ROUTE_SETTINGS) }
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}