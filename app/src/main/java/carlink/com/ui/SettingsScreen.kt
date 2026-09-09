package carlink.com.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings screen — configure wake word label, steering button keycode,
 * and behavior toggles. No API keys needed (Vosk is fully open source).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: CarLinkViewModel,
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()

    var wakeWordDraft by remember(settings.wakeWord) {
        mutableStateOf(settings.wakeWord)
    }
    var steeringKeyDraft by remember(settings.steeringKeyCode) {
        mutableStateOf(settings.steeringKeyCode.toString())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // ── Info banner ──────────────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(
                    text = "✅ No signup or API key needed!\n" +
                           "CarLink uses Vosk — a 100% free, offline,\n" +
                           "open-source voice engine.",
                    modifier = Modifier.padding(16.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // ── Section: Wake Word ───────────────────────────────────────
            SectionHeader("🎤 Wake Word")

            OutlinedTextField(
                value = wakeWordDraft,
                onValueChange = { wakeWordDraft = it },
                label = { Text("Wake word (display label)") },
                supportingText = {
                    Text("Currently set to: \"GUNNU\"\nVosk listens for this word continuously offline.")
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { viewModel.setWakeWord(wakeWordDraft) }) {
                        Text("Save")
                    }
                }
            )

            // ── Section: Steering Wheel ──────────────────────────────────
            SectionHeader("🎛️ Steering Wheel Button")

            OutlinedTextField(
                value = steeringKeyDraft,
                onValueChange = { steeringKeyDraft = it.filter { c -> c.isDigit() } },
                label = { Text("Trigger KeyCode (integer)") },
                supportingText = {
                    val code = steeringKeyDraft.toIntOrNull() ?: 85
                    val keyName = try { KeyEvent.keyCodeToString(code) } catch (_: Exception) { "unknown" }
                    Text(
                        "Current: $keyName ($code)\n" +
                        "Common: 85=MEDIA_PLAY, 79=MEDIA_PLAY_PAUSE\n" +
                        "Run: adb logcat | findstr CarLinkVoice\nthen press your steering button to discover keycode."
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = {
                    TextButton(onClick = {
                        viewModel.setSteeringKeyCode(steeringKeyDraft.toIntOrNull() ?: 85)
                    }) { Text("Save") }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // ── Section: Behavior ────────────────────────────────────────
            SectionHeader("⚙️ Behavior")

            SettingsToggle(
                title = "Auto-play first result",
                subtitle = "Automatically start playing the top YouTube search result",
                checked = settings.autoPlayFirst,
                onCheckedChange = { viewModel.setAutoPlayFirst(it) }
            )

            SettingsToggle(
                title = "Auto-skip YouTube ads",
                subtitle = "Enable via ADB command (see home screen for instructions)",
                checked = settings.autoSkipAds,
                onCheckedChange = { viewModel.setAutoSkipAds(it) }
            )

            // ── Section: Setup Guide ─────────────────────────────────────
            SectionHeader("📋 First-time Setup")

            SetupGuideCard()

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.Medium)
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun SetupGuideCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Steps to get fully working:", fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "1. Install APK on Ambrane device\n" +
                       "   adb install app-debug.apk\n\n" +
                       "2. Enable USB Debugging on Ambrane:\n" +
                       "   Settings → About → tap Build number 7×\n" +
                       "   Developer Options → USB Debugging ON\n\n" +
                       "3. Enable ad-skipping (run once from PC):\n" +
                       "   adb shell settings put secure \\\n" +
                       "     enabled_accessibility_services \\\n" +
                       "     carlink.com/carlink.com.service.CarLinkAccessibilityService\n" +
                       "   adb shell settings put secure accessibility_enabled 1\n\n" +
                       "4. Open CarLink → tap 🎤 Start\n\n" +
                       "5. Say \"GUNNU\" → then speak your YouTube query\n\n" +
                       "6. To find your steering button keycode:\n" +
                       "   adb logcat | findstr CarLinkVoice\n" +
                       "   then press the voice button on steering wheel",
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}
