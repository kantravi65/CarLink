package carlink.com.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

private const val TAG = "YouTubeLauncher"

/** YouTube's package name on standard Android */
private const val YOUTUBE_PACKAGE = "com.google.android.youtube"

/**
 * Utility for launching YouTube with a voice search query.
 *
 * Strategy (in priority order):
 * 1. Deep-link intent with ACTION_SEARCH directly into YouTube (fastest, no browser).
 * 2. Web URL intent fallback: opens youtube.com/results?search_query=... in YouTube app.
 * 3. Last resort: open search URL in any available browser.
 */
object YouTubeLauncher {

    /**
     * Launch YouTube and search for [query].
     *
     * @param context Application context.
     * @param query   The user's voice search string (e.g. "lofi hip hop music").
     * @param autoPlay Whether to attempt auto-playing the top result (not yet implemented via intent alone).
     */
    fun search(context: Context, query: String, autoPlay: Boolean = false) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            Log.w(TAG, "Empty query — skipping YouTube launch")
            return
        }
        Log.i(TAG, "Launching YouTube search for: \"$trimmedQuery\"")

        // Strategy 1: Use YouTube's own search action intent
        val youtubeSearchIntent = buildYouTubeSearchIntent(trimmedQuery)
        if (youtubeSearchIntent != null && context.packageManager.resolveActivity(youtubeSearchIntent, 0) != null) {
            context.startActivity(youtubeSearchIntent)
            Log.d(TAG, "Launched via YouTube search intent")
            return
        }

        // Strategy 2: Deep link URL (YouTube app intercepts this)
        val encodedQuery = Uri.encode(trimmedQuery)
        val urlIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encodedQuery")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(YOUTUBE_PACKAGE) // Force YouTube app
        }
        if (context.packageManager.resolveActivity(urlIntent, 0) != null) {
            context.startActivity(urlIntent)
            Log.d(TAG, "Launched via YouTube URL deep-link")
            return
        }

        // Strategy 3: Fallback — remove package restriction and try any app
        urlIntent.setPackage(null)
        try {
            context.startActivity(urlIntent)
            Log.d(TAG, "Launched via generic URL intent (fallback)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch YouTube: ${e.message}")
        }
    }

    /**
     * Launch YouTube app homepage (no search).
     */
    fun launchYouTube(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(YOUTUBE_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch YouTube homepage: ${e.message}")
        }
    }

    /**
     * Build a YouTube-specific search intent using the standard ACTION_SEARCH.
     * YouTube registers itself as a SearchProvider and accepts this.
     */
    private fun buildYouTubeSearchIntent(query: String): Intent? = try {
        Intent(Intent.ACTION_SEARCH).apply {
            setPackage(YOUTUBE_PACKAGE)
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Check if the YouTube app is installed on this device.
     */
    fun isYouTubeInstalled(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(YOUTUBE_PACKAGE) != null
}
