package com.tomtt0057.pocketbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class BotAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "PocketBot"
        const val POCKET_OPTION_PACKAGE = "com.po.app"
        const val TELEGRAM_PACKAGE = "org.telegram.messenger"

        // Shared instance so MainActivity can communicate with service
        var instance: BotAccessibilityService? = null
        var isBotActive: Boolean = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "✅ Accessibility Service connected and ready")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only act when bot is active
        if (!isBotActive) return
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return

        when (packageName) {
            TELEGRAM_PACKAGE -> {
                // Telegram is open — check for new signals
                handleTelegramEvent(event)
            }
            POCKET_OPTION_PACKAGE -> {
                // Pocket Option is open — handle trading screen
                handlePocketOptionEvent(event)
            }
        }
    }

    // ─── TELEGRAM SIGNAL READING ───────────────────────────────────────────

    private fun handleTelegramEvent(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return

        // Search all text on screen for trading signals
        val allText = extractAllText(rootNode)

        // Check if any text looks like a trading signal
        allText.forEach { text ->
            if (isTradeSignal(text)) {
                Log.d(TAG, "📡 Signal detected: $text")
                parseAndExecuteSignal(text)
            }
        }
    }

    // ─── SIGNAL DETECTION ──────────────────────────────────────────────────

    private fun isTradeSignal(text: String): Boolean {
        val upperText = text.uppercase()
        // Signal must contain direction AND an asset name
        val hasDirection = upperText.contains("CALL") ||
                upperText.contains("PUT") ||
                upperText.contains("BUY") ||
                upperText.contains("SELL") ||
                upperText.contains("UP") ||
                upperText.contains("DOWN")
        val hasAsset = upperText.contains("EUR") ||
                upperText.contains("USD") ||
                upperText.contains("GBP") ||
                upperText.contains("BTC") ||
                upperText.contains("ETH") ||
                upperText.contains("OTC")
        return hasDirection && hasAsset
    }

    // ─── SIGNAL PARSING ────────────────────────────────────────────────────

    private fun parseAndExecuteSignal(signalText: String) {
        val upperText = signalText.uppercase()

        // Determine trade direction
        val direction = when {
            upperText.contains("CALL") || upperText.contains("BUY") ||
            upperText.contains("UP") -> "CALL"
            upperText.contains("PUT") || upperText.contains("SELL") ||
            upperText.contains("DOWN") -> "PUT"
            else -> return // Unknown direction — skip
        }

        // Extract asset (basic detection)
        val asset = when {
            upperText.contains("EUR/USD") -> "EUR/USD"
            upperText.contains("GBP/USD") -> "GBP/USD"
            upperText.contains("EUR/GBP") -> "EUR/GBP"
            upperText.contains("BTC") -> "BTC/USD"
            upperText.contains("ETH") -> "ETH/USD"
            else -> "EUR/USD" // Default asset
        }

        Log.d(TAG, "📊 Parsed signal — Asset: $asset | Direction: $direction")

        // Open Pocket Option and execute trade
        openPocketOption()

        // Wait 3 seconds for app to open then place trade
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            executeTrade(direction, asset)
        }, 3000)
    }

    // ─── POCKET OPTION HANDLER ─────────────────────────────────────────────

    private fun handlePocketOptionEvent(event: AccessibilityEvent) {
        // This will be expanded in the next phase
        // For now just log that we can see the app
        Log.d(TAG, "👁 Pocket Option screen detected")
    }

    // ─── TRADE EXECUTION ───────────────────────────────────────────────────

    private fun executeTrade(direction: String, asset: String) {
        val rootNode = rootInActiveWindow ?: run {
            Log.e(TAG, "❌ Cannot access screen — root node is null")
            return
        }

        Log.d(TAG, "🎯 Executing $direction trade on $asset")

        if (direction == "CALL") {
            // Find and tap the CALL/UP/BUY button
            val callButton = findNodeByText(rootNode, "Call") ?:
                             findNodeByText(rootNode, "UP") ?:
                             findNodeByText(rootNode, "Higher")
            if (callButton != null) {
                tapNode(callButton)
                Log.d(TAG, "✅ CALL trade placed on $asset")
            } else {
                Log.e(TAG, "❌ Could not find CALL button on screen")
            }
        } else {
            // Find and tap the PUT/DOWN/SELL button
            val putButton = findNodeByText(rootNode, "Put") ?:
                            findNodeByText(rootNode, "DOWN") ?:
                            findNodeByText(rootNode, "Lower")
            if (putButton != null) {
                tapNode(putButton)
                Log.d(TAG, "✅ PUT trade placed on $asset")
            } else {
                Log.e(TAG, "❌ Could not find PUT button on screen")
            }
        }
    }

    // ─── HELPER: Open Pocket Option ────────────────────────────────────────

    private fun openPocketOption() {
        val intent = packageManager.getLaunchIntentForPackage(POCKET_OPTION_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            applicationContext.startActivity(intent)
            Log.d(TAG, "📱 Opening Pocket Option...")
        } else {
            Log.e(TAG, "❌ Pocket Option app not found on device")
        }
    }

    // ─── HELPER: Extract all text from screen ──────────────────────────────

    private fun extractAllText(node: AccessibilityNodeInfo): List<String> {
        val texts = mutableListOf<String>()
        val text = node.text?.toString()
        if (!text.isNullOrEmpty()) texts.add(text)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            texts.addAll(extractAllText(child))
        }
        return texts
    }

    // ─── HELPER: Find node by visible text ────────────────────────────────

    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {
        val results = root.findAccessibilityNodeInfosByText(text)
        return if (results.isNullOrEmpty()) null else results[0]
    }

    // ─── HELPER: Tap a UI element ──────────────────────────────────────────

    private fun tapNode(node: AccessibilityNodeInfo) {
        // Try direct click first
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        // If not clickable, use gesture tap on its position
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val x = rect.centerX().toFloat()
        val y = rect.centerY().toFloat()
        performTap(x, y)
    }

    // ─── HELPER: Perform screen tap by coordinates ─────────────────────────

    private fun performTap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "👆 Tapped screen at ($x, $y)")
    }

    override fun onInterrupt() {
        Log.d(TAG, "⚠️ Accessibility Service interrupted")
        isBotActive = false
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isBotActive = false
        Log.d(TAG, "🔴 Accessibility Service destroyed")
    }
}
