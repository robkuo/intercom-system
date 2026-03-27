package com.intercom.app

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvExtension: TextView
    private lateinit var tvDiag: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var hasShownFullScreenDialog = false   // 每次 session 只提示一次
    private var hasShownBatteryDialog = false      // 電池優化豁免提示（每次 session 只提示一次）

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.intercom.app.INCOMING_CALL" -> {
                    // MainActivity 在前台 → 直接啟動來電畫面（不受 Android 12+ 背景限制）
                    android.util.Log.i("MainActivity", "收到 INCOMING_CALL broadcast，直接啟動 IncomingCallActivity")
                    startActivity(Intent(this@MainActivity, IncomingCallActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                }
                else -> updateUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvExtension = findViewById(R.id.tvExtension)
        tvDiag = findViewById(R.id.tvDiag)

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnRetry).setOnClickListener {
            val audioGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!audioGranted) {
                requestPermissionsAndStart()
            } else if (SipService.instance != null) {
                SipService.instance?.reloadSettings()
            } else {
                startSipService()
            }
        }

        requestPermissionsAndStart()
        updateUI()
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun updateUI() {
        val prefs = getSharedPreferences("intercom_prefs", Context.MODE_PRIVATE)
        val serverIp = prefs.getString("server_ip", "192.168.100.163") ?: "192.168.100.163"
        val ext = prefs.getString("extension", "101") ?: "101"
        val companyName = prefs.getString("company_name", null)
        tvExtension.text = if (!companyName.isNullOrEmpty()) {
            "$companyName（分機 $ext）"   // 已取得公司名：顯示「公司 A（分機 101）」
        } else {
            "伺服器：$serverIp  分機：$ext"  // 尚未取得：顯示伺服器 + 分機號
        }

        val sipStatus = prefs.getString("sip_status", null)
        val sipDetail = prefs.getString("sip_detail", "") ?: ""

        when (sipStatus) {
            "registered" -> {
                tvStatus.text = "● 已連線（分機 $ext）"
                tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                tvDiag.text = ""
            }
            "registering" -> {
                tvStatus.text = "● 連線中..."
                tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                tvDiag.text = sipDetail
            }
            "incoming_call" -> {
                // 狀態為來電中 → MainActivity 直接開啟來電畫面
                tvStatus.text = "📞 來電中..."
                tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                tvDiag.text = sipDetail
                startActivity(Intent(this, IncomingCallActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
            "failed" -> {
                tvStatus.text = "✗ 連線失敗"
                tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                tvDiag.text = sipDetail
            }
            "not_supported" -> {
                tvStatus.text = "✗ 裝置不支援 SIP"
                tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                tvDiag.text = sipDetail
            }
            null -> {
                if (!hasAudioPermission()) {
                    tvStatus.text = "✗ 需要麥克風權限"
                    tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    tvDiag.text = "請點「重新連線」並授予麥克風（RECORD_AUDIO）權限"
                } else {
                    tvStatus.text = "● 啟動中..."
                    tvStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                    tvDiag.text = "SipService 初始化中"
                }
            }
        }
    }

    private fun startSipService() {
        val serviceIntent = Intent(this, SipService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun requestPermissionsAndStart() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isEmpty()) {
            startSipService()
        } else {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (hasAudioPermission()) {
                startSipService()
            }
            updateUI()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction("com.intercom.app.STATUS_CHANGED")
            addAction("com.intercom.app.SIP_REGISTERED")
            addAction("com.intercom.app.SIP_FAILED")
            addAction("com.intercom.app.INCOMING_CALL")    // ← 新增：直接攔截來電
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
        updateUI()

        // 5 秒後若狀態仍為 null，視為服務啟動超時
        timeoutRunnable?.let { uiHandler.removeCallbacks(it) }
        val r = Runnable {
            val prefs = getSharedPreferences("intercom_prefs", Context.MODE_PRIVATE)
            if (prefs.getString("sip_status", null) == null) {
                prefs.edit()
                    .putString("sip_status", "failed")
                    .putString("sip_detail", "服務未能啟動（逾時 5 秒）\nAPI ${Build.VERSION.SDK_INT}")
                    .apply()
                updateUI()
            }
        }
        timeoutRunnable = r
        uiHandler.postDelayed(r, 5000L)

        // Android 14+ 需要使用者手動授予 USE_FULL_SCREEN_INTENT
        checkFullScreenIntentPermission()

        // VoIP 必要：確認已豁免電池優化（避免 Doze 阻斷 SIP 封包）
        checkBatteryOptimization()
    }

    override fun onPause() {
        super.onPause()
        timeoutRunnable?.let { uiHandler.removeCallbacks(it) }
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) {}
    }

    // ───────── Android 14 全螢幕通知權限 ─────────

    /**
     * Android 14 (API 34) 起，USE_FULL_SCREEN_INTENT 需要使用者在設定中明確授予。
     * 若未授予，來電時無法自動全螢幕顯示（降級為 HUD 通知）。
     */
    private fun checkFullScreenIntentPermission() {
        if (hasShownFullScreenDialog) return  // 每次 session 只提示一次
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {  // API 34
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.canUseFullScreenIntent()) {
                hasShownFullScreenDialog = true
                showFullScreenIntentDialog()
            }
        }
    }

    // ───────── 電池優化豁免 ─────────

    /**
     * VoIP App 需豁免電池優化，否則 Android Doze 模式會阻斷 UDP 封包接收，
     * 導致手機待機後 SIP 服務無法收到 Asterisk 的 INVITE（不響鈴）。
     */
    private fun checkBatteryOptimization() {
        if (hasShownBatteryDialog) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            hasShownBatteryDialog = true
            AlertDialog.Builder(this)
                .setTitle("需要「不限制電池用量」")
                .setMessage(
                    "為確保門鈴可靠響鈴，請取消本 App 的電池最佳化限制。\n\n" +
                    "點「前往設定」→ 選擇「不限制」，可防止系統在待機時中斷 SIP 連線。"
                )
                .setPositiveButton("前往設定") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                }
                .setNegativeButton("稍後再說", null)
                .show()
        }
    }

    private fun showFullScreenIntentDialog() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        AlertDialog.Builder(this)
            .setTitle("需要「全螢幕通知」權限")
            .setMessage(
                "Android 14 起，需要手動開啟此權限，才能在手機鎖定時自動顯示門口來電畫面。\n\n" +
                "請點「前往設定」→ 找到本應用程式 → 開啟「允許全螢幕通知」。"
            )
            .setPositiveButton("前往設定") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
            }
            .setNegativeButton("稍後再說", null)
            .show()
    }
}
