package carlink.com.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import carlink.com.service.VoiceAssistantService

/**
 * Main screen of CarLink — shows service status, big toggle button,
 * and accessibility service setup prompt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: CarLinkViewModel,
    onNavigateToSettings: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val status by viewModel.serviceStatus.collectAsState()
    val context = LocalContext.current

    // Derive UI state from service status
    val isServiceRunning = settings.serviceEnabled
    val isListening = status == VoiceAssistantService.STATUS_LISTENING
    val isProcessing = status == VoiceAssistantService.STATUS_PROCESSING ||
                       status == VoiceAssistantService.STATUS_LAUNCHING

    // Animated accent color for the status ring
    val accentColor by animateColorAsState(
        targetValue = when {
            isListening -> Color(0xFF4CAF50)   // Green: listening
            isProcessing -> Color(0xFFFF9800)  // Orange: processing
            isServiceRunning -> Color(0xFF2196F3) // Blue: idle/running
            else -> Color(0xFF607D8B)          // Grey: off
        },
        animationSpec = tween(400),
        label = "accent"
    )

    // Pulse scale for the listening state
    val pulseScale by animateFloatAsState(
        targetValue = if (isListening) 1.12f else 1f,
        animationSpec = tween(600),
        label = "pulse"
    )

    // Check Accessibility Service status dynamically
    var isA11yEnabled by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isA11yEnabled = isAccessibilityEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "🚗 CarLink",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {

            // ── Status label ──────────────────────────────────────────────
            Text(
                text = when (status) {
                    VoiceAssistantService.STATUS_LISTENING -> "🎤 Listening…"
                    VoiceAssistantService.STATUS_PROCESSING -> "⏳ Processing…"
                    VoiceAssistantService.STATUS_LAUNCHING -> "🚀 Launching YouTube…"
                    else -> if (isServiceRunning) "💤 Say \"GUNNU\"" else "Assistant is Off"
                },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                color = accentColor
            )

            // ── Big circular toggle button ────────────────────────────────
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(200.dp)
                    .scale(pulseScale)
            ) {
                // Outer status ring
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.15f))
                )

                // Inner button
                FilledIconButton(
                    onClick = {
                        if (isServiceRunning) viewModel.stopService()
                        else viewModel.startService()
                    },
                    modifier = Modifier.size(150.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = accentColor,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = if (isServiceRunning) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = if (isServiceRunning) "Stop" else "Start",
                        modifier = Modifier.size(56.dp)
                    )
                }
            }

            // ── Sub-label ─────────────────────────────────────────────────
            Text(
                text = if (isServiceRunning) "Tap to stop assistant" else "Tap to start assistant",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Accessibility not enabled — show ADB command (Ambrane has no Accessibility settings UI)
                if (settings.autoSkipAds && !isA11yEnabled) {
                    StatusCard(
                        text = "⚠️ Ad-skip not active yet.\n\nRun this ADB command once from your PC:\n\nadb shell settings put secure enabled_accessibility_services carlink.com/carlink.com.service.CarLinkAccessibilityService && adb shell settings put secure accessibility_enabled 1",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        textColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }

                // Ready — no API key or signup needed with Vosk
                if (!settings.autoSkipAds || isA11yEnabled) {
                    StatusCard(
                        text = "✅ CarLink is ready! No signup needed.\nSay \"${settings.wakeWord}\" to activate",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        textColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    text: String,
    containerColor: Color,
    textColor: Color,
    onClick: (() -> Unit)? = null
) {
    val modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(containerColor)
        .then(
            if (onClick != null) Modifier else Modifier
        )
        .padding(16.dp)

    if (onClick != null) {
        Surface(
            onClick = onClick,
            color = containerColor,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = text,
                color = textColor,
                fontSize = 13.sp,
                modifier = Modifier.padding(16.dp)
            )
        }
    } else {
        Box(modifier = modifier) {
            Text(text = text, color = textColor, fontSize = 13.sp)
        }
    }
}

/**
 * Check if the CarLink Accessibility Service is currently enabled.
 */
fun isAccessibilityEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}
