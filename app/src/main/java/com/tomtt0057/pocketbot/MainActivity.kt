package com.tomtt0057.pocketbot

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvPermissionWarning: TextView
    private lateinit var tvTradeLog: TextView
    private lateinit var seekBarDuration: SeekBar

    private var sessionTimer: CountDownTimer? = null
    private var sessionHours: Int = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvDuration = findViewById(R.id.tvDuration)
        tvPermissionWarning = findViewById(R.id.tvPermissionWarning)
        tvTradeLog = findViewById(R.id.tvTradeLog)
        seekBarDuration = findViewById(R.id.seekBarDuration)

        // Set log listener so bot can write to trade log
        BotAccessibilityService.logListener = { message ->
            runOnUiThread { addToLog(message) }
        }

        seekBarDuration.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    sessionHours = if (progress < 1) 1 else progress
                    tvDuration.text = "$sessionHours hour" +
                        if (sessionHours > 1) "s" else ""
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            }
        )

        btnStart.setOnClickListener {
            if (!isAccessibilityEnabled()) {
                tvPermissionWarning.visibility =
                    android.view.View.VISIBLE
                tvStatus.text =
                    "⚠️ Please enable Accessibility permission first."
                openAccessibilitySettings()
            } else {
                startBot()
            }
        }

        btnStop.setOnClickListener {
            stopBot()
        }

        tvPermissionWarning.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    override fun onResume() {
        super.onResume()
        // Reconnect log listener every time app comes to foreground
        BotAccessibilityService.logListener = { message ->
            runOnUiThread { addToLog(message) }
        }
        if (isAccessibilityEnabled()) {
            tvPermissionWarning.visibility = android.view.View.GONE
            if (!BotAccessibilityService.isBotActive) {
                tvStatus.text = "✅ Ready. Tap Start Bot to begin."
            }
        } else {
            tvPermissionWarning.visibility = android.view.View.VISIBLE
            tvStatus.text = "⚠️ Accessibility permission required."
        }
    }

    private fun startBot() {
        tvStatus.text = "🟢 Bot running — fetching signals..."
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        seekBarDuration.isEnabled = false
        tvPermissionWarning.visibility = android.view.View.GONE

        // Start foreground service
        val serviceIntent = Intent(this, BotForegroundService::class.java)
        startForegroundService(serviceIntent)

        // Activate bot and start signal loop
        BotAccessibilityService.isBotActive = true
        BotAccessibilityService.instance?.startSignalLoop()

        // Start session countdown timer
        val durationMillis = sessionHours * 60 * 60 * 1000L
        sessionTimer = object : CountDownTimer(durationMillis, 60000) {
            override fun onTick(millisUntilFinished: Long) {
                val minutesLeft = millisUntilFinished / 60000
                val hoursLeft = minutesLeft / 60
                val minsLeft = minutesLeft % 60
                tvStatus.text =
                    "🟢 Bot running — " +
                    "${hoursLeft}h ${minsLeft}m remaining"
            }
            override fun onFinish() {
                stopBot()
                tvStatus.text =
                    "✅ Session complete after $sessionHours hour(s)."
            }
        }.start()

        addToLog("🚀 Bot started — session: $sessionHours hour(s)")
        addToLog("🌐 Connecting to ApexSignal API...")
    }

    private fun stopBot() {
        sessionTimer?.cancel()
        sessionTimer = null

        tvStatus.text = "⏹ Bot stopped."
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        seekBarDuration.isEnabled = true

        // Stop bot and signal loop
        BotAccessibilityService.isBotActive = false
        BotAccessibilityService.instance?.stopSignalLoop()

        // Stop foreground service
        val serviceIntent = Intent(this, BotForegroundService::class.java)
        stopService(serviceIntent)

        addToLog("⏹ Bot stopped.")
    }

    fun addToLog(message: String) {
        val timestamp = java.text.SimpleDateFormat(
            "HH:mm:ss", java.util.Locale.getDefault()
        ).format(java.util.Date())
        val current = tvTradeLog.text.toString()
        tvTradeLog.text = "[$timestamp] $message\n$current"
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE)
            as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabled.any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
