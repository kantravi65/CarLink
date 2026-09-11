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
            instance?.launchMicActivationLoop()
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
                
                // 2. Automate Voice Search & Auto-Clear Previous Query
                val searchBoxes = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/search_edit_text") +
                                root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/search_query")
                val isSearchFocused = searchBoxes.any { it.isFocused && it.isVisibleToUser }

                if (isSearchFocused) {
                    // Step A: If previous search query is still in the box, wipe it so YouTube shows the mic!
                    val clearButtons = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/search_clear") +
                                    root.findAccessibilityNodeInfosByText("Clear")
                    for (clearBtn in clearButtons) {
                        if (clearBtn.isVisibleToUser && clearBtn.isEnabled) {
                            val desc = clearBtn.contentDescription?.toString() ?: ""
                            val id = clearBtn.viewIdResourceName ?: ""
                            if (id.contains("search_clear") || desc.equals("Clear", ignoreCase = true)) {
                                if (clickNodeOrParent(clearBtn)) {
                                    Log.i(TAG, "Search box focused with old query — auto-clicked Clear button!")
                                    lastMicAutoClickMs = 0L // Allow immediate mic click on next tick
                                    break
                                }
                            }
                        }
                    }

                    // Directly empty the search box text as well
                    for (box in searchBoxes) {
                        val boxText = box.text?.toString() ?: ""
                        if (box.isFocused && boxText.isNotEmpty()) {
                            val args = android.os.Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                            }
                            box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        }
                    }

                    // Step B: Auto-click mic icon if visible
                    if (now - lastMicAutoClickMs >= 2000L) {
                        val micNodes = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/voice_search") + 
                                       root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/menu_search_voice") +
                                       root.findAccessibilityNodeInfosByText("Voice search")
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
                }

                // 3. Auto-play first search result if waiting
                if (waitingForSearchResults) {
                    val elapsed = now - voiceSearchInitiatedMs
                    if (elapsed in 2000L..45000L) {
                        // Ensure the listening overlay has finished before clicking
                        if (!isVoiceOverlayActive(root)) {
                            if (tryAutoPlayFirstVideo(root)) {
                                waitingForSearchResults = false
                            }
                        }
                    } else if (elapsed > 45000L) {
                        waitingForSearchResults = false
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
     * Actively polls for YouTube window and clicks the mic immediately on steering key press.
     */
    fun launchMicActivationLoop() {
        scope.launch {
            val startTime = System.currentTimeMillis()
            var activated = false
            while (System.currentTimeMillis() - startTime < 4000L && !activated) {
                val root = rootInActiveWindow
                if (root != null && root.packageName == "com.google.android.youtube") {
                    if (activateMicNow(root)) {
                        activated = true
                        pendingMicActivation = false
                        Log.i(TAG, "Mic activated successfully on FIRST steering press!")
                        break
                    }
                }
                kotlinx.coroutines.delay(120)
            }
        }
    }

    /**
     * Finds and activates YouTube's voice search mic on screen.
     * If the search bar isn't open yet, clicks the search icon to reveal it.
     * Returns true ONLY if the mic itself was clicked.
     */
    fun activateMicNow(providedRoot: AccessibilityNodeInfo? = null): Boolean {
        val root = providedRoot ?: rootInActiveWindow ?: return false

        // 0. If previous search text exists in the search bar, CLEAR IT so YouTube reveals the mic!
        val clearButtons = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/search_clear") +
                root.findAccessibilityNodeInfosByText("Clear")
        for (clearBtn in clearButtons) {
            val desc = clearBtn.contentDescription?.toString() ?: ""
            val text = clearBtn.text?.toString() ?: ""
            if (clearBtn.isVisibleToUser && clearBtn.isEnabled && 
                (clearBtn.viewIdResourceName?.contains("search_clear") == true || desc.equals("Clear", ignoreCase = true) || text.equals("Clear", ignoreCase = true))) {
                if (clickNodeOrParent(clearBtn)) {
                    Log.i(TAG, "Steering voice trigger: Clicked 'Clear' button to wipe previous search term")
                    return false // Keep polling; mic icon will appear on next tick!
                }
            }
        }

        // Also check if search box has text and wipe it directly
        val searchBoxes = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/search_query") +
                root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/search_edit_text")
        for (box in searchBoxes) {
            val boxText = box.text?.toString() ?: ""
            if (box.isVisibleToUser && boxText.isNotEmpty()) {
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                }
                box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }

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
        // We return FALSE here so the activation loop immediately continues and clicks the mic on next tick!
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
                        return false // Mic not yet clicked! Keep polling.
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
        // Strategy 1: Find by known resource IDs
        for (id in skipButtonIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (node.isVisibleToUser && node.isEnabled) {
                    Log.i(TAG, "Skip button found by ID: $id")
                    if (clickNodeOrParent(node)) return true
                }
            }
        }

        // Strategy 2: Recursive scan for any element with "skip" or "छोड़ें" in text, content-desc, or id
        val skipNodes = mutableListOf<AccessibilityNodeInfo>()
        collectSkipNodes(root, skipNodes)
        for (node in skipNodes) {
            if (node.isVisibleToUser && node.isEnabled) {
                Log.i(TAG, "Skip button found: text='${node.text}', desc='${node.contentDescription}', id='${node.viewIdResourceName}'")
                if (clickNodeOrParent(node)) return true
            }
        }

        return false
    }

    private fun collectSkipNodes(node: AccessibilityNodeInfo, outList: MutableList<AccessibilityNodeInfo>) {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""

        val isSkip = text.contains("skip", ignoreCase = true) ||
                     desc.contains("skip", ignoreCase = true) ||
                     id.contains("skip", ignoreCase = true) ||
                     text.contains("छोड़ें") || desc.contains("छोड़ें")

        if (isSkip && text.length < 30 && desc.length < 30) {
            outList.add(node)
            return
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectSkipNodes(child, outList)
        }
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
    /**
     * Finds and automatically plays the first video in the YouTube search results.
     */
    private fun tryAutoPlayFirstVideo(root: AccessibilityNodeInfo): Boolean {
        // Strategy 1: Find by "play video" in content-description (verified on this head unit)
        val playVideoNodes = mutableListOf<AccessibilityNodeInfo>()
        collectPlayVideoNodes(root, playVideoNodes)
        for (node in playVideoNodes) {
            if (node.isVisibleToUser) {
                Log.i(TAG, "Found video result via 'play video' content-desc: ${node.contentDescription}")
                if (clickNodeOrParent(node)) {
                    Log.i(TAG, "Auto-clicked FIRST search result video!")
                    return true
                }
            }
        }

        // Strategy 2: Look inside the results RecyclerView
        val resultsViews = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/results")
        for (resultsView in resultsViews) {
            if (resultsView.childCount > 0) {
                for (i in 0 until resultsView.childCount) {
                    val child = resultsView.getChild(i) ?: continue
                    if (child.isVisibleToUser) {
                        val target = findFirstClickable(child) ?: child
                        Log.i(TAG, "Found video result in results RecyclerView")
                        if (clickNodeOrParent(target)) {
                            Log.i(TAG, "Auto-clicked FIRST video item in RecyclerView!")
                            return true
                        }
                    }
                }
            }
        }

        // Strategy 3: Check classic YouTube IDs
        val classicNodes = root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/title") +
                root.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/thumbnail")
        for (node in classicNodes) {
            if (node.isVisibleToUser) {
                if (clickNodeOrParent(node)) {
                    Log.i(TAG, "Auto-clicked video result via classic ID!")
                    return true
                }
            }
        }

        return false
    }

    private fun collectPlayVideoNodes(node: AccessibilityNodeInfo, outList: MutableList<AccessibilityNodeInfo>) {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (desc.contains("play video") || desc.contains("video चलाएं") || desc.contains("वीडियो चलाएं")) {
            outList.add(node)
            return
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectPlayVideoNodes(child, outList)
        }
    }

    private fun findFirstClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstClickable(child)
            if (found != null) return found
        }
        return null
    }

    private fun isVoiceOverlayActive(root: AccessibilityNodeInfo): Boolean {
        val listeningTexts = listOf("listening", "try saying", "say something", "सुन रहे", "बोलें")
        for (keyword in listeningTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            if (nodes.any { it.isVisibleToUser }) return true
        }
        return false
    }

    /**
     * Dispatches a physical gesture tap to the video thumbnail/title area.
     */
    private fun clickGesture(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false

        val x: Float
        val y: Float
        if (bounds.width() > 600) {
            // Full-width video card in search results -> tap thumbnail area (left side)
            x = (bounds.left + minOf(220, bounds.width() / 2)).toFloat()
            y = (bounds.top + (bounds.height() * 0.35f)).toFloat()
        } else {
            // Normal buttons (Skip Ad, Mic, Search) -> tap the exact center!
            x = bounds.centerX().toFloat()
            y = bounds.centerY().toFloat()
        }

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 150)) // 150ms firm tap
            .build()

        Log.i(TAG, "Dispatching physical gesture tap at ($x, $y) for node bounds=$bounds")
        return dispatchGesture(gesture, null, null)
    }

    override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
        // The steering wheel voice button on this car actually sends KEYCODE_SEARCH (84)
        if (event.keyCode == android.view.KeyEvent.KEYCODE_SEARCH) {
            if (event.action == android.view.KeyEvent.ACTION_UP) {
                Log.i(TAG, "Steering wheel KEYCODE_SEARCH intercepted! Automating YouTube mic...")
                requestMicActivation()
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
