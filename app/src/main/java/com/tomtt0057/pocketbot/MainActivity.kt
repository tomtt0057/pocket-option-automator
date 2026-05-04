package com.tomtt0057.pocketbot

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvPermissionWarning: TextView
    private lateinit var tvTradeLog: TextView
    private lateinit var seekBarDuration: SeekBar
    private lateinit var updateManager: UpdateManager

    private var sessionTimer: CountDownTimer? = null
    private var sessionHours: Int = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize update manager
        updateManager = UpdateManager(this)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvDuration = findViewById(R.id.tvDuration)
        tvPermissionWarning = findViewById(R.id.tvPermissionWarning)
        tvTradeLog = findViewById(R.id.tvTradeLog)
        seekBarDuration = findViewById(R.id.seekBarDuration)

        // Set log listener
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

        // Check for updates when app opens
        checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
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

    private fun checkForUpdates() {
        updateManager.checkForUpdate(
            onUpdateAvailable = { versionName, notes, apkUrl ->
                AlertDialog.Builder(this)
                    .setTitle("🆕 Update Available — v$versionName")
                    .setMessage("What's new:\n$notes\n\nUpdate now?")
                    .setPositiveButton("Update Now") { _, _ ->
                        addToLog("⬇️ Starting update download...")
                        updateManager.downloadAndInstall(apkUrl) { progress ->
                            addToLog(progress)
                        }
                    }
                    .setNegativeButton("Later") { dialog, _ ->
                        dialog.dismiss()
                        addToLog("ℹ️ Update postponed.")
                    }
                    .setCancelable(false)
                    .show()
            },
            onNoUpdate = {
                Log.d("MainActivity", "App is up to date")
            },
            onError = { error ->
                Log.d("MainActivity", "Update check failed: $error")
            }
        )
    }

    private fun startBot() {
        tvStatus.text = "🟢 Bot running — fetching signals..."
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        seekBarDuration.isEnabled = false
        tvPermissionWarning.visibility = android.view.View.GONE

        val serviceIntent = Intent(this, BotForegroundService::class.java)
        startForegroundService(serviceIntent)

        BotAccessibilityService.isBotActive = true
        BotAccessibilityService.instance?.startSignalLoop()

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

        BotAccessibilityService.isBotActive = false
        BotAccessibilityService.instance?.stopSignalLoop()

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
