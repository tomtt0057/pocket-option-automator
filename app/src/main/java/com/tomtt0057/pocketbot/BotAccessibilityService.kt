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
        const val POCKET_OPTION_PACKAGE = "com.po.app"
        const val TELEGRAM_PACKAGE = "org.telegram.messenger"
        const val API_URL = "https://apex-signal-bot-production-fa99.up.railway.app"
        const val API_KEY = "apexbot2026"
        const val SIGNAL_INTERVAL_MS = 5 * 60 * 1000L
        var instance: BotAccessibilityService? = null
        var isBotActive: Boolean = false
        var logListener: ((String) -> Unit)? = null
    }

    private var pendingAsset: String = ""
    private var pendingDirection: String = ""
    private var waitingToTrade: Boolean = false
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var signalRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "✅ Service connected")
    }

    fun startSignalLoop() {
        log("🚀 Bot started — fetching first signal...")
        fetchAndTrade()
        scheduleNextSignal()
    }

    fun stopSignalLoop() {
        signalRunnable?.let { mainHandler.removeCallbacks(it) }
        signalRunnable = null
        waitingToTrade = false
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
        log("⏳ Next signal in 5 minutes...")
    }

    private fun fetchAndTrade() {
        if (!isBotActive) return
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
                    waitingToTrade = true
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isBotActive) return
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        when (packageName) {
            TELEGRAM_PACKAGE -> handleTelegramScreen()
            POCKET_OPTION_PACKAGE -> {
                if (waitingToTrade) handlePocketOptionScreen()
            }
        }
    }

    private fun handleTelegramScreen() {
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
        waitingToTrade = true
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

    private fun handlePocketOptionScreen() {
        val root = rootInActiveWindow ?: return
        val allText = extractAllText(root).joinToString(" ")
        if (!allText.contains("BUY") || !allText.contains("SELL")) return
        log("📱 Trading screen ready!")
        mainHandler.postDelayed({ placeTrade() }, 2000)
    }

    private fun placeTrade() {
        val root = rootInActiveWindow ?: run {
            log("❌ Cannot access screen")
            waitingToTrade = false
            return
        }

        if (pendingDirection == "BUY") {
            val btn = findNodeByText(root, "BUY")
                ?: findNodeByText(root, "Buy")
                ?: findNodeByText(root, "HIGHER")
            if (btn != null) {
                tapNode(btn)
                log("✅ BUY placed on $pendingAsset")
            } else {
                log("❌ BUY button not found")
            }
        } else {
            val btn = findNodeByText(root, "SELL")
                ?: findNodeByText(root, "Sell")
                ?: findNodeByText(root, "LOWER")
            if (btn != null) {
                tapNode(btn)
                log("✅ SELL placed on $pendingAsset")
            } else {
                log("❌ SELL button not found")
            }
        }

        waitingToTrade = false
        pendingAsset = ""
        pendingDirection = ""
    }

    private fun openPocketOption() {
        val intent = packageManager
            .getLaunchIntentForPackage(POCKET_OPTION_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            applicationContext.startActivity(intent)
            log("📱 Opening Pocket Option...")
        } else {
            log("❌ Pocket Option not found!")
            waitingToTrade = false
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        mainHandler.post {
            logListener?.invoke(message)
        }
    }

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

    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {
        val results = root.findAccessibilityNodeInfosByText(text)
        return if (results.isNullOrEmpty()) null else results[0]
    }

    private fun tapNode(node: AccessibilityNodeInfo) {
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        performTap(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
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
