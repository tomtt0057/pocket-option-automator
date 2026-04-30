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

    // UI elements
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvPermissionWarning: TextView
    private lateinit var tvTradeLog: TextView
    private lateinit var seekBarDuration: SeekBar

    // Session timer
    private var sessionTimer: CountDownTimer? = null
    private var sessionHours: Int = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Connect UI elements
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvDuration = findViewById(R.id.tvDuration)
        tvPermissionWarning = findViewById(R.id.tvPermissionWarning)
        tvTradeLog = findViewById(R.id.tvTradeLog)
        seekBarDuration = findViewById(R.id.seekBarDuration)

        // Session duration slider
        seekBarDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                sessionHours = if (progress < 1) 1 else progress
                tvDuration.text = "$sessionHours hour${if (sessionHours > 1) "s" else ""}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // Start button
        btnStart.setOnClickListener {
            if (!isAccessibilityEnabled()) {
                // Permission not granted — send user to settings
                tvPermissionWarning.visibility = android.view.View.VISIBLE
                tvStatus.text = "⚠️ Please enable Accessibility permission first."
                openAccessibilitySettings()
            } else {
                startBot()
            }
        }

        // Stop button
        btnStop.setOnClickListener {
            stopBot()
        }

        // Permission warning tap
        tvPermissionWarning.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    override fun onResume() {
        super.onResume()
        // Check permission every time app comes to foreground
        if (isAccessibilityEnabled()) {
            tvPermissionWarning.visibility = android.view.View.GONE
            tvStatus.text = "✅ Ready. Tap Start Bot to begin."
        } else {
            tvPermissionWarning.visibility = android.view.View.VISIBLE
            tvStatus.text = "⚠️ Accessibility permission required."
        }
    }

    private fun startBot() {
        // Update UI
        tvStatus.text = "🟢 Bot is running — watching for signals..."
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        seekBarDuration.isEnabled = false
        tvPermissionWarning.visibility = android.view.View.GONE

        // Start foreground service to keep bot alive
        val serviceIntent = Intent(this, BotForegroundService::class.java)
        startForegroundService(serviceIntent)

        // Start session countdown timer
        val durationMillis = sessionHours * 60 * 60 * 1000L
        sessionTimer = object : CountDownTimer(durationMillis, 60000) {
            override fun onTick(millisUntilFinished: Long) {
                val minutesLeft = millisUntilFinished / 60000
                val hoursLeft = minutesLeft / 60
                val minsLeft = minutesLeft % 60
                tvStatus.text = "🟢 Bot running — ${hoursLeft}h ${minsLeft}m remaining"
            }
            override fun onFinish() {
                stopBot()
                tvStatus.text = "✅ Session complete. Bot stopped after $sessionHours hour(s)."
            }
        }.start()

        addToLog("🚀 Bot started. Session: $sessionHours hour(s)")
    }

    private fun stopBot() {
        // Cancel timer
        sessionTimer?.cancel()
        sessionTimer = null

        // Update UI
        tvStatus.text = "⏹ Bot stopped."
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        seekBarDuration.isEnabled = true

        // Stop foreground service
        val serviceIntent = Intent(this, BotForegroundService::class.java)
        stopService(serviceIntent)

        addToLog("⏹ Bot stopped by user.")
    }

    fun addToLog(message: String) {
        val timestamp = java.text.SimpleDateFormat(
            "HH:mm:ss", java.util.Locale.getDefault()
        ).format(java.util.Date())
        val currentLog = tvTradeLog.text.toString()
        val newEntry = "[$timestamp] $message\n$currentLog"
        tvTradeLog.text = newEntry
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
}
