package com.tomtt0057.pocketbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.Executors

class BotAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "PocketBot"
        const val POCKET_OPTION_PACKAGE = "com.pocketoption.broker"
        const val TELEGRAM_PACKAGE = "org.telegram.messenger"
        const val API_URL = "https://apex-signal-bot-production-fa99.up.railway.app"
        const val API_KEY = "apexbot2026"
        const val SIGNAL_INTERVAL_MS = 1 * 60 * 1000L
        var instance: BotAccessibilityService? = null
        var isBotActive: Boolean = false
        var logListener: ((String) -> Unit)? = null
    }

    // Navigation states
    private enum class BotState {
        IDLE,
        OPENING_APP,
        CHECKING_ACCOUNT,
        SWITCHING_TO_DEMO,
        SELECTING_ASSET,
        SETTING_TIMEFRAME,
        PLACING_TRADE,
        TRADE_COMPLETE
    }

    private var currentState = BotState.IDLE
    private var pendingAsset: String = ""
    private var pendingDirection: String = ""
    private var pendingTimeframe: String = "1min"
    private var navigationAttempts: Int = 0
    private val maxAttempts = 10

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var signalRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        log("✅ Service connected")
    }

    fun startSignalLoop() {
        log("🚀 Bot started — fetching first signal...")
        fetchAndTrade()
        scheduleNextSignal()
    }

    fun stopSignalLoop() {
        signalRunnable?.let { mainHandler.removeCallbacks(it) }
        signalRunnable = null
        currentState = BotState.IDLE
        pendingAsset = ""
        pendingDirection = ""
        log("⏹ Signal loop stopped")
    }

    private fun scheduleNextSignal() {
        signalRunnable = Runnable {
            if (isBotActive) {
                fetchAndTrade()
                scheduleNextSignal()
            }
        }
        mainHandler.postDelayed(signalRunnable!!, SIGNAL_INTERVAL_MS)
       log("⏳ Next signal in 1 minute...")
    }

    // ─── FETCH SIGNAL FROM API ─────────────────────────────────

    private fun fetchAndTrade() {
        if (!isBotActive) return
        if (currentState != BotState.IDLE) {
            log("⚠️ Bot busy — skipping this cycle")
            return
        }
        log("📡 Fetching signal from API...")

        executor.execute {
            try {
                val url = "$API_URL/autoscan?key=$API_KEY&category=all"
                val response = URL(url).readText()
                val json = JSONObject(response)
                val success = json.optBoolean("success", false)
                val goodTime = json.optBoolean("good_time", true)
                val signals = json.optJSONArray("signals")

                if (!success || signals == null || signals.length() == 0) {
                    mainHandler.post { log("⚠️ No strong signals. Waiting...") }
                    return@execute
                }

                if (!goodTime) {
                    mainHandler.post { log("⚠️ Slow market session. Skipping.") }
                    return@execute
                }

                val best = signals.getJSONObject(0)
                val pair = best.optString("pair", "")
                val signal = best.optString("signal", "HOLD")
                val confidence = best.optInt("confidence", 0)
                val confText = best.optString("confidence_text", "")
                val entryPrice = best.optDouble("entry_price", 0.0)

                if (signal == "HOLD") {
                    mainHandler.post { log("⏸ Signal is HOLD — skipping") }
                    return@execute
                }

                if (confidence < 4) {
                    mainHandler.post { log("⚠️ Low confidence ($confText) — skipping") }
                    return@execute
                }

                mainHandler.post {
                    log("📊 Signal: $pair → $signal ($confText) @ $entryPrice")
                    pendingAsset = pair
                    pendingDirection = signal
                    pendingTimeframe = "1min"
                    navigationAttempts = 0
                    currentState = BotState.OPENING_APP
                    openPocketOption()
                }

            } catch (e: Exception) {
                Log.e(TAG, "API error: ${e.message}")
                mainHandler.post {
                    log("❌ API error: ${e.message}")
                    log("📱 Switching to Telegram fallback...")
                }
            }
        }
    }

    // ─── SCREEN EVENT HANDLER ──────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isBotActive) return
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        when (packageName) {
            TELEGRAM_PACKAGE -> handleTelegramScreen()
            POCKET_OPTION_PACKAGE -> handlePocketOptionNavigation()
        }
    }

    // ─── POCKET OPTION NAVIGATION STATE MACHINE ───────────────

    private fun handlePocketOptionNavigation() {
        if (currentState == BotState.IDLE) return

        navigationAttempts++
        if (navigationAttempts > maxAttempts) {
            log("❌ Navigation failed after $maxAttempts attempts")
            currentState = BotState.IDLE
            return
        }

        val root = rootInActiveWindow ?: return
        val allText = extractAllText(root).joinToString(" ")

        when (currentState) {
            BotState.OPENING_APP, BotState.CHECKING_ACCOUNT -> {
                checkAndSwitchToDemoAccount(root, allText)
            }
            BotState.SWITCHING_TO_DEMO -> {
                handleDemoAccountSwitch(root, allText)
            }
            BotState.SELECTING_ASSET -> {
                handleAssetSelection(root, allText)
            }
            BotState.SETTING_TIMEFRAME -> {
    selectTimeframeOption(root)
            }
            BotState.PLACING_TRADE -> {
                handleTradePlacement(root, allText)
            }
            else -> {}
        }
    }

    // ─── STEP 1: Check account type ────────────────────────────

    private fun checkAndSwitchToDemoAccount(
        root: AccessibilityNodeInfo,
        allText: String
    ) {
        log("🔍 Checking account type...")

        // Check if already on demo account
        if (allText.contains("Demo", ignoreCase = true) ||
            allText.contains("QT Demo", ignoreCase = true)) {
            log("✅ Already on Demo account")
            currentState = BotState.SELECTING_ASSET
            mainHandler.postDelayed({
                openAssetSelector(root)
            }, 1000)
            return
        }

        // Check if on real account — need to switch
        if (allText.contains("Real", ignoreCase = true) ||
            allText.contains("QT Real", ignoreCase = true)) {
            log("🔄 Switching to Demo account...")
            currentState = BotState.SWITCHING_TO_DEMO
            // Tap the balance/account area at the top
            tapAccountSelector(root)
            return
        }

        // App just opened — wait for it to load
        log("⏳ Waiting for Pocket Option to load...")
        mainHandler.postDelayed({
            currentState = BotState.CHECKING_ACCOUNT
        }, 2000)
    }

    // ─── STEP 2: Switch to demo account ────────────────────────

    private fun handleDemoAccountSwitch(
        root: AccessibilityNodeInfo,
        allText: String
    ) {
        // Look for demo option in the account menu
        val demoNode = findNodeByText(root, "Demo")
            ?: findNodeByText(root, "QT Demo")
            ?: findNodeByText(root, "Practice")

        if (demoNode != null) {
            log("✅ Tapping Demo account option...")
            tapNode(demoNode)
            currentState = BotState.SELECTING_ASSET
            mainHandler.postDelayed({
                val newRoot = rootInActiveWindow ?: return@postDelayed
                openAssetSelector(newRoot)
            }, 1500)
        } else {
            // Try tapping the balance area at top of screen
            tapAccountSelector(root)
        }
    }

    // ─── STEP 3: Open asset selector ───────────────────────────

    private fun openAssetSelector(root: AccessibilityNodeInfo) {
        log("📂 Opening asset selector for $pendingAsset...")
        currentState = BotState.SELECTING_ASSET

        // The asset name is shown at top left — tap it to open selector
        val assetButton = findNodeByText(root, "EUR/USD OTC")
            ?: findNodeByText(root, "EUR/USD")
            ?: findNodeByText(root, "Gold OTC")
            ?: findNodeByText(root, "BTC/USD")

        if (assetButton != null) {
            tapNode(assetButton)
            log("✅ Asset selector opened")
        } else {
            // Tap top-left area where asset name typically appears
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            performTap(screenWidth * 0.25f, screenHeight * 0.12f)
            log("👆 Tapping asset name area...")
        }
    }

    // ─── STEP 4: Select the correct asset ──────────────────────

    private fun handleAssetSelection(
        root: AccessibilityNodeInfo,
        allText: String
    ) {
        // Try to find and tap our target asset
        val targetAsset = findNodeByText(root, pendingAsset)

        if (targetAsset != null) {
            log("✅ Found asset: $pendingAsset — tapping...")
            tapNode(targetAsset)
            currentState = BotState.SETTING_TIMEFRAME
            mainHandler.postDelayed({
                val newRoot = rootInActiveWindow ?: return@postDelayed
                setTimeframe(newRoot)
            }, 1500)
            return
        }

        // Asset not visible — search for it
        val searchBox = findNodeByText(root, "Search")
            ?: findNodeByText(root, "search")
            ?: findNodeByContentDescription(root, "Search")

        if (searchBox != null) {
            tapNode(searchBox)
            mainHandler.postDelayed({
                searchBox.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    android.os.Bundle().apply {
                        putString(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            pendingAsset.replace(" OTC", "")
                        )
                    }
                )
                log("🔍 Searching for $pendingAsset...")
            }, 500)
        } else {
            log("⚠️ Cannot find asset $pendingAsset — using current asset")
            currentState = BotState.SETTING_TIMEFRAME
            val newRoot = rootInActiveWindow ?: return
            setTimeframe(newRoot)
        }
    }

    // ─── STEP 5: Set timeframe ─────────────────────────────────

    private fun setTimeframe(root: AccessibilityNodeInfo) {
        log("⏱ Setting timeframe: $pendingTimeframe...")
        currentState = BotState.SETTING_TIMEFRAME

        // Find the time/expiry selector
        val timeNode = findNodeByText(root, "00:01:00")
            ?: findNodeByText(root, "1 min")
            ?: findNodeByText(root, "Time")
            ?: findNodeByText(root, "00:00:")

        if (timeNode != null) {
            tapNode(timeNode)
            log("✅ Time selector opened")
            mainHandler.postDelayed({
                val newRoot = rootInActiveWindow ?: return@postDelayed
                selectTimeframeOption(newRoot)
            }, 1000)
        } else {
            // Skip timeframe and go straight to trade
            log("⚠️ Time selector not found — using default timeframe")
            currentState = BotState.PLACING_TRADE
            mainHandler.postDelayed({
                val newRoot = rootInActiveWindow ?: return@postDelayed
                handleTradePlacement(newRoot, extractAllText(newRoot).joinToString(" "))
            }, 1000)
        }
    }

    private fun selectTimeframeOption(root: AccessibilityNodeInfo) {
        // Map timeframe to display text
        val timeText = when (pendingTimeframe) {
            "1min" -> "1 min"
            "2min" -> "2 min"
            "3min" -> "3 min"
            "5min" -> "5 min"
            "15min" -> "15 min"
            "30min" -> "30 min"
            "1h" -> "1 hour"
            else -> "1 min"
        }

        val timeOption = findNodeByText(root, timeText)
            ?: findNodeByText(root, "1 min")

        if (timeOption != null) {
            tapNode(timeOption)
            log("✅ Timeframe set to $timeText")
        }

        currentState = BotState.PLACING_TRADE
        mainHandler.postDelayed({
            val newRoot = rootInActiveWindow ?: return@postDelayed
            val text = extractAllText(newRoot).joinToString(" ")
            handleTradePlacement(newRoot, text)
        }, 1000)
    }

    // ─── STEP 6: Place the trade ───────────────────────────────

    private fun handleTradePlacement(
        root: AccessibilityNodeInfo,
        allText: String
    ) {
        if (!allText.contains("BUY", ignoreCase = true) &&
            !allText.contains("SELL", ignoreCase = true)) {
            log("⏳ Waiting for trading screen...")
            return
        }

        log("🎯 Placing $pendingDirection on $pendingAsset...")

        if (pendingDirection == "BUY") {
            val buyBtn = findNodeByText(root, "BUY")
                ?: findNodeByText(root, "Buy")
                ?: findNodeByText(root, "UP")
                ?: findNodeByText(root, "Higher")
            if (buyBtn != null) {
                tapNode(buyBtn)
                log("✅ BUY trade placed on $pendingAsset! 🎉")
                currentState = BotState.TRADE_COMPLETE
            } else {
                // Try coordinate-based tap for BUY button (bottom left)
                val w = resources.displayMetrics.widthPixels
                val h = resources.displayMetrics.heightPixels
                performTap(w * 0.2f, h * 0.88f)
                log("👆 BUY tapped by position on $pendingAsset")
                currentState = BotState.TRADE_COMPLETE
            }
        } else {
            val sellBtn = findNodeByText(root, "SELL")
                ?: findNodeByText(root, "Sell")
                ?: findNodeByText(root, "DOWN")
                ?: findNodeByText(root, "Lower")
            if (sellBtn != null) {
                tapNode(sellBtn)
                log("✅ SELL trade placed on $pendingAsset! 🎉")
                currentState = BotState.TRADE_COMPLETE
            } else {
                // Try coordinate-based tap for SELL button (bottom right)
                val w = resources.displayMetrics.widthPixels
                val h = resources.displayMetrics.heightPixels
                performTap(w * 0.8f, h * 0.88f)
                log("👆 SELL tapped by position on $pendingAsset")
                currentState = BotState.TRADE_COMPLETE
            }
        }

        // Reset after trade
        mainHandler.postDelayed({
            currentState = BotState.IDLE
            pendingAsset = ""
            pendingDirection = ""
            navigationAttempts = 0
        }, 3000)
    }

    // ─── TELEGRAM FALLBACK ─────────────────────────────────────

    private fun handleTelegramScreen() {
        if (currentState != BotState.IDLE) return
        val root = rootInActiveWindow ?: return
        val allText = extractAllText(root).joinToString(" ")
        if (!allText.contains("AUTO-SIGNAL ALERT")) return
        if (allText.contains("HOLD")) return
        if (allText.contains("Low")) return

        val direction = when {
            allText.contains("Signal:") && allText.contains("BUY") -> "BUY"
            allText.contains("Signal:") && allText.contains("SELL") -> "SELL"
            else -> return
        }
        val asset = extractAsset(allText) ?: return
        pendingAsset = asset
        pendingDirection = direction
        pendingTimeframe = "1min"
        navigationAttempts = 0
        currentState = BotState.OPENING_APP
        log("📡 Telegram signal: $asset → $direction")
        openPocketOption()
    }

    private fun extractAsset(text: String): String? {
        val assets = listOf(
            "EUR/USD OTC", "GBP/USD OTC", "USD/JPY OTC",
            "AUD/USD OTC", "NZD/USD OTC", "USD/CAD OTC",
            "EUR/GBP OTC", "EUR/JPY OTC", "GBP/JPY OTC",
            "EUR/USD", "GBP/USD", "USD/JPY", "USD/CHF",
            "AUD/USD", "NZD/USD", "USD/CAD", "EUR/GBP",
            "BTC/USD", "ETH/USD", "XRP/USD", "SOL/USD",
            "BNB/USD", "ADA/USD", "DOGE/USD", "LTC/USD",
            "Gold", "Silver"
        )
        return assets.firstOrNull { text.contains(it, ignoreCase = true) }
    }

    // ─── OPEN POCKET OPTION ────────────────────────────────────

    private fun openPocketOption() {
        try {
            val intent = Intent()
            intent.setClassName(
                POCKET_OPTION_PACKAGE,
                "com.potradeweb.activities.MainActivity"
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            applicationContext.startActivity(intent)
            log("📱 Opening Pocket Option...")
            mainHandler.postDelayed({
                currentState = BotState.CHECKING_ACCOUNT
            }, 3000)
        } catch (e: Exception) {
            try {
                val intent = packageManager
                    .getLaunchIntentForPackage(POCKET_OPTION_PACKAGE)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    applicationContext.startActivity(intent)
                    log("📱 Opening Pocket Option (fallback)...")
                    mainHandler.postDelayed({
                        currentState = BotState.CHECKING_ACCOUNT
                    }, 3000)
                } else {
                    log("❌ Pocket Option not found!")
                    currentState = BotState.IDLE
                }
            } catch (e2: Exception) {
                log("❌ Cannot open Pocket Option: ${e2.message}")
                currentState = BotState.IDLE
            }
        }
    }

    // ─── TAP ACCOUNT SELECTOR ──────────────────────────────────

    private fun tapAccountSelector(root: AccessibilityNodeInfo) {
        // Tap the balance display at the top of the screen
        val balanceNode = findNodeByText(root, "QT Real")
            ?: findNodeByText(root, "QT Demo")
            ?: findNodeByText(root, "Real")
            ?: findNodeByContentDescription(root, "balance")

        if (balanceNode != null) {
            tapNode(balanceNode)
            log("👆 Tapping account selector...")
        } else {
            // Tap top center area where balance is shown
            val w = resources.displayMetrics.widthPixels
            val h = resources.displayMetrics.heightPixels
            performTap(w * 0.5f, h * 0.07f)
            log("👆 Tapping top balance area...")
        }
    }

    // ─── HELPER: LOG ───────────────────────────────────────────

    private fun log(message: String) {
        Log.d(TAG, message)
        mainHandler.post { logListener?.invoke(message) }
    }

    // ─── HELPER: EXTRACT ALL TEXT ──────────────────────────────

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

    // ─── HELPER: FIND NODE BY TEXT ─────────────────────────────

    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {
        val results = root.findAccessibilityNodeInfosByText(text)
        return if (results.isNullOrEmpty()) null else results[0]
    }

    // ─── HELPER: FIND NODE BY CONTENT DESCRIPTION ─────────────

    private fun findNodeByContentDescription(
        root: AccessibilityNodeInfo,
        desc: String
    ): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString()
                ?.contains(desc, ignoreCase = true) == true) {
            return root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByContentDescription(child, desc)
            if (found != null) return found
        }
        return null
    }

    // ─── HELPER: TAP NODE ──────────────────────────────────────

    private fun tapNode(node: AccessibilityNodeInfo) {
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            performTap(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
    }

    // ─── HELPER: PERFORM TAP ───────────────────────────────────

    private fun performTap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "👆 Tap at ($x, $y)")
    }

    override fun onInterrupt() {
        isBotActive = false
        stopSignalLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isBotActive = false
        stopSignalLoop()
        executor.shutdown()
    }
}
