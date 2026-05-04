package com.tomtt0057.pocketbot

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.Executors

class UpdateManager(private val context: Context) {

    companion object {
        const val TAG = "UpdateManager"
        const val VERSION_URL =
            "https://raw.githubusercontent.com/tomtt0057/" +
            "pocket-option-automator/main/version.json"
        const val CURRENT_VERSION_CODE = 2
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var downloadId: Long = -1

    // ─── Check for updates ─────────────────────────────────────

    fun checkForUpdate(
        onUpdateAvailable: (versionName: String, notes: String, apkUrl: String) -> Unit,
        onNoUpdate: () -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                Log.d(TAG, "🔍 Checking for updates...")
                val response = URL(VERSION_URL).readText()
                val json = JSONObject(response)

                val latestVersionCode = json.optInt("version_code", 1)
                val latestVersionName = json.optString("version_name", "1.0.0")
                val apkUrl = json.optString("apk_url", "")
                val releaseNotes = json.optString("release_notes", "")
                val forceUpdate = json.optBoolean("force_update", false)

                Log.d(TAG, "Latest version: $latestVersionCode, Current: $CURRENT_VERSION_CODE")

                mainHandler.post {
                    if (latestVersionCode > CURRENT_VERSION_CODE || forceUpdate) {
                        Log.d(TAG, "✅ New update available: $latestVersionName")
                        onUpdateAvailable(latestVersionName, releaseNotes, apkUrl)
                    } else {
                        Log.d(TAG, "✅ App is up to date")
                        onNoUpdate()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Update check error: ${e.message}")
                mainHandler.post { onError(e.message ?: "Unknown error") }
            }
        }
    }

    // ─── Download and install update ───────────────────────────

    fun downloadAndInstall(apkUrl: String, onProgress: (String) -> Unit) {
        try {
            onProgress("⬇️ Downloading update...")

            // Clean up old APK if exists
            val apkFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "pocket-bot-update.apk"
            )
            if (apkFile.exists()) apkFile.delete()

            // Use DownloadManager for reliable download
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Pocket Bot Update")
                .setDescription("Downloading new version...")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationUri(Uri.fromFile(apkFile))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE)
                as DownloadManager
            downloadId = dm.enqueue(request)

            onProgress("⬇️ Downloading... please wait")

            // Listen for download complete
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(
                        DownloadManager.EXTRA_DOWNLOAD_ID, -1
                    )
                    if (id == downloadId) {
                        context.unregisterReceiver(this)
                        onProgress("✅ Download complete — installing...")
                        installApk(apkFile, onProgress)
                    }
                }
            }

            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )

        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}")
            onProgress("❌ Download failed: ${e.message}")
        }
    }

    // ─── Install downloaded APK ────────────────────────────────

    private fun installApk(apkFile: File, onProgress: (String) -> Unit) {
        try {
            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(installIntent)
            onProgress("📲 Installing update — tap Install when prompted")

        } catch (e: Exception) {
            Log.e(TAG, "Install error: ${e.message}")
            onProgress("❌ Install failed: ${e.message}")
        }
    }
}
