package carlink.com.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "CarLinkA11y"

/**
 * Accessibility Service that monitors YouTube and automatically:
 * 1. Clicks the "Skip Ad" button as soon as it becomes available.
 * 2. Dismisses "Visit advertiser" / "Why this ad?" overlays.
 *
 * Configuration is in res/xml/accessibility_service_config.xml.
 * The service targets only com.google.android.youtube to minimize battery impact.
 *
 * NOTE: This service must be enabled manually by the user in:
 *   Android Settings → Accessibility → CarLink Voice Assistant
 */
class CarLinkAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Timestamp of last skip attempt — prevents rapid-fire repeated clicks
    private var lastSkipAttemptMs = 0L
    private val skipCooldownMs = 1000L
    
    private var lastMicAutoClickMs = 0L
    private val micAutoClickCooldownMs = 3000L
    
    private var waitingForSearchResults = false
    private var voiceSearchInitiatedMs = 0L

    // Known text labels for the Skip button across YouTube versions and languages
    // YouTube uses different text/content-desc depending on version and locale
    private val skipButtonTexts = setOf(
        "Skip Ad",
        "Skip Ads",
        "Skip ad",
        "Skip ads",
        "SKIP AD",
        "SKIP ADS",
        "विज्ञापन छोड़ें",   // Hindi
        "ad skip karo"
    )

    // Known resource IDs YouTube uses for the skip button (may vary with app version)
    private val skipButtonIds = setOf(
        "com.google.android.youtube:id/skip_ad_button",
        "com.google.android.youtube:id/skip_button",
        "com.google.android.youtube:id/ad_skip_button"
    )

    // Ad overlay dismiss button texts
    private val overlayDismissTexts = setOf(
        "Got it",
        "Close",
        "Dismiss",
        "No thanks"
    )

    companion object {
        private const val TAG = "CarLinkA11y"
        @Volatile var instance: CarLinkAccessibilityService? = null
        @Volatile var pendingMicActivation = false

        fun requestMicActivation() {
            Log.i(TAG, "Steering button requested mic activation")
            pendingMicActivation = true
            instance?.activateMicNow()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "CarLink Accessibility Service connected")

        // Programmatically reinforce the configuration (belt-and-suspenders)
        serviceInfo = serviceInfo?.also { info ->
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.notificationTimeout = 100
            info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or 
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Only scan for skip buttons if we are inside the YouTube app
        if (event.packageName != "com.google.android.youtube") return

        // Only act on window content changes (most efficient)
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        val now = System.currentTimeMillis()

        // Scan the accessibility tree for skip-related nodes
        scope.launch(Dispatchers.Main) {
            val root = rootInActiveWindow ?: return@launch
            try {
                // If steering button requested mic activation, do it immediately
                if (pendingMicActivation) {
                    if (activateMicNow(root)) {
                        pendingMicActivation = false
                    }
                }

                // 1. Check for Skip Ad buttons
                if (now - lastSkipAttemptMs >= skipCooldownMs) {
                    if (trySkipAd(root)) {
                        lastSkipAttemptMs = System.currentTimeMillis()
                    }
                }
                
                // 2. Automate Voice Search & Auto-Play First Result
                val searchBoxes = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/search_edit_text")
                var isSearchFocused = false
                for (box in searchBoxes) {
                    if (box.isFocused) {
                        isSearchFocused = true
                        break
                    }
                }

                if (isSearchFocused) {
                    // We are explicitly in the search box. Auto-click mic if cooldown allows.
                    if (now - lastMicAutoClickMs >= 5000L) {
                        val micNodes = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/voice_search") + 
                                       root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/menu_search_voice")
                        for (node in micNodes) {
                            if (node.isVisibleToUser && node.isEnabled) {
                                if (clickNodeOrParent(node)) {
                                    Log.i(TAG, "Auto-clicked YouTube native Voice Search microphone!")
                                    val nowMs = System.currentTimeMillis()
                                    lastMicAutoClickMs = nowMs
                                    waitingForSearchResults = true
                                    voiceSearchInitiatedMs = nowMs
                                    break
                                }
                            }
                        }
                    }
                } else {
                    // Not focused on search box. Are we waiting for search results to load?
                    if (waitingForSearchResults) {
                        if (now - voiceSearchInitiatedMs > 3000L) { // Give UI 3s to transition from Voice overlay
                            val videoThumbs = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/thumbnail")
                            val videoTitles = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/title")
                            
                            // Prioritize titles over thumbnails! Thumbnails have dead zones (timestamp badges) 
                            // that swallow clicks if tapped exactly in the center. Titles are pure text and 100% clickable.
                            val results = videoTitles + videoThumbs
                            
                            for (node in results) {
                                if (node.isVisibleToUser) {
                                    // Force a physical gesture tap because YouTube's search result views
                                    // often have fake clickable parents that swallow Accessibility ACTION_CLICK.
                                    if (clickGesture(node)) {
                                        Log.i(TAG, "Auto-clicked FIRST search result video via physical gesture!")
                                        waitingForSearchResults = false
                                        break
                                    }
                                }
                            }
                        }
                        // Stop waiting if 20 seconds have passed
                        if (now - voiceSearchInitiatedMs > 20000L) {
                            waitingForSearchResults = false
                        }
                    }
                }
            } finally {
                // Removed root.recycle() here to prevent crashes, OS will GC it.
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Finds and activates YouTube's voice search mic on screen.
     * If the search bar isn't open yet, clicks the search icon to reveal it.
     */
    fun activateMicNow(providedRoot: AccessibilityNodeInfo? = null): Boolean {
        val root = providedRoot ?: rootInActiveWindow ?: return false

        // 1. Check if the voice search mic button is directly visible
        val micIds = listOf(
            "com.google.android.youtube:id/voice_search",
            "com.google.android.youtube:id/menu_search_voice",
            "com.google.android.youtube:id/voice_search_button"
        )
        for (id in micIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (node.isVisibleToUser && node.isEnabled) {
                    if (clickNodeOrParent(node)) {
                        Log.i(TAG, "Steering voice trigger: Clicked YouTube voice search mic by ID $id")
                        val nowMs = System.currentTimeMillis()
                        lastMicAutoClickMs = nowMs
                        waitingForSearchResults = true
                        voiceSearchInitiatedMs = nowMs
                        pendingMicActivation = false
                        return true
                    }
                }
            }
        }

        // 2. Check by text/content description for Voice Search
        val descKeywords = listOf("voice", "mic", "बोलकर", "आवाज़")
        for (keyword in descKeywords) {
            val textNodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in textNodes) {
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val text = node.text?.toString()?.lowercase() ?: ""
                if ((desc.contains("voice") || desc.contains("आवाज़") || text.contains("voice")) &&
                    node.isVisibleToUser && node.isEnabled
                ) {
                    if (clickNodeOrParent(node)) {
                        Log.i(TAG, "Steering voice trigger: Clicked YouTube voice search mic by desc/text")
                        val nowMs = System.currentTimeMillis()
                        lastMicAutoClickMs = nowMs
                        waitingForSearchResults = true
                        voiceSearchInitiatedMs = nowMs
                        pendingMicActivation = false
                        return true
                    }
                }
            }
        }

        // 3. If mic is not yet visible, click the search icon to open the search bar
        val searchIds = listOf(
            "com.google.android.youtube:id/menu_search",
            "com.google.android.youtube:id/search"
        )
        for (id in searchIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (node.isVisibleToUser && node.isEnabled) {
                    if (clickNodeOrParent(node)) {
                        Log.i(TAG, "Steering voice trigger: Clicked YouTube search icon to open search bar")
                        lastMicAutoClickMs = 0L // Allow immediate mic click once search bar opens
                        return true
                    }
                }
            }
        }

        return false
    }

    // ─── Ad Detection & Clicking ─────────────────────────────────────────

    /**
     * Walk the accessibility tree and attempt to find and click a "Skip Ad" button.
     * @return true if a button was clicked.
     */
    private fun trySkipAd(root: AccessibilityNodeInfo): Boolean {
        // Strategy 1: Find by known resource IDs (fastest, most reliable when stable)
        for (id in skipButtonIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (node.isVisibleToUser && node.isEnabled) {
                    Log.i(TAG, "Skip button found by ID: $id")
                    val clicked = clickNodeOrParent(node)
                    if (clicked) return true
                }
            }
        }

        // Strategy 2: Find by text content (more resilient to ID changes)
        for (text in skipButtonTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isVisibleToUser && node.isEnabled) {
                    Log.i(TAG, "Skip button found by text: \"${node.text}\"")
                    val clicked = clickNodeOrParent(node)
                    if (clicked) return true
                }
            }
        }

        // Strategy 3: Check content descriptions (for icon-only buttons)
        for (text in skipButtonTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                val desc = node.contentDescription?.toString() ?: ""
                if (skipButtonTexts.any { it.equals(desc, ignoreCase = true) } &&
                    node.isVisibleToUser && node.isEnabled
                ) {
                    Log.i(TAG, "Skip button found by content-desc: \"$desc\"")
                    val clicked = clickNodeOrParent(node)
                    if (clicked) return true
                }
            }
        }

        return false
    }

    /**
     * Try to click [node]; if not clickable, walk up the parent chain
     * until we find a clickable ancestor (common pattern in YouTube's view hierarchy).
     * If all accessibility actions fail, physically tap the screen.
     */
    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        // 1. Try directly
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // 2. Try parents
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable && parent.isEnabled && parent.isVisibleToUser) {
                if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
            parent = parent.parent
            depth++
        }

        // 3. Fallback: Physical gesture tap
        return clickGesture(node)
    }

    /**
     * Dispatches a physical gesture tap to the top-left quadrant of the node on the screen.
     * This avoids dead zones like timestamp badges (bottom-right) and 3-dot menus (right edge).
     */
    private fun clickGesture(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false

        // Target the top-left 25% of the element.
        // For a video thumbnail or title, this completely avoids the center, bottom-right duration badge, and right-side 3-dot menus.
        val x = bounds.left.toFloat() + (bounds.width() * 0.25f)
        val y = bounds.top.toFloat() + (bounds.height() * 0.25f)

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 150)) // 150ms firm tap
            .build()

        Log.i(TAG, "Dispatching physical gesture tap at ($x, $y)")
        return dispatchGesture(gesture, null, null)
    }

    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        // The steering wheel voice button on this car actually sends KEYCODE_SEARCH (84)
        if (event.keyCode == android.view.KeyEvent.KEYCODE_SEARCH) {
            if (event.action == android.view.KeyEvent.ACTION_UP) {
                Log.i(TAG, "Steering wheel KEYCODE_SEARCH intercepted! Automating YouTube mic...")
                // Launch coroutine to automatically click the microphone icon right after YouTube natively opens the search box
                scope.launch {
                    var clicked = false
                    for (i in 0..15) {
                        kotlinx.coroutines.delay(100)
                        val root = rootInActiveWindow ?: continue
                        if (root.packageName == "com.google.android.youtube") {
                            val micNodes = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/voice_search") + 
                                           root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/menu_search_voice") +
                                           root.findAccessibilityNodeInfosByText("Voice search") +
                                           root.findAccessibilityNodeInfosByText("Search with your voice")
                            for (node in micNodes) {
                                if (node.isVisibleToUser && node.isEnabled) {
                                    if (clickNodeOrParent(node)) {
                                        Log.i(TAG, "Successfully clicked YouTube native Voice Search button!")
                                        clicked = true
                                        break
                                    }
                                }
                            }
                        }
                        if (clicked) break
                    }
                }
            }
            // Return FALSE to let the OS deliver the key to YouTube normally, which naturally opens the text search box!
            return false 
        } else if (event.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY) {
            if (event.action == android.view.KeyEvent.ACTION_UP) {
                val intent = android.content.Intent(this, VoiceAssistantService::class.java).apply {
                    action = "carlink.com.service.WAKE"
                }
                startService(intent)
            }
            return true
        }
        return super.onKeyEvent(event)
    }
}
