package com.intercom.app

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat

class SipService : Service() {

    companion object {
        const val TAG = "SipService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "sip_service_channel"
        const val INCOMING_CALL_CHANNEL_ID = "incoming_call_channel"

        // Doze 喚醒：每 8 分鐘重新登錄，確保 NAT mapping 長期有效
        const val ACTION_DOZE_WAKEUP = "com.intercom.app.DOZE_WAKEUP"
        private const val WAKEUP_INTERVAL_MS = 8L * 60 * 1000   // 8 分鐘

        var instance: SipService? = null
    }

    private var miniSip: MiniSipStack? = null
    internal var rtpSession: RtpAudioSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null   // 保持 WiFi radio 活躍，確保 Doze 中仍能收到 INVITE
    private val mainHandler = Handler(Looper.getMainLooper())
    private var localRtpPort = 16384
    private var currentExtension = "101"   // 目前登錄中的分機
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null  // API 26+ 音訊焦點請求物件

    // ───────── 狀態持久化 ─────────

    private fun saveStatus(status: String, detail: String = "") {
        getSharedPreferences("intercom_prefs", Context.MODE_PRIVATE).edit()
            .putString("sip_status", status)
            .putString("sip_detail", detail)
            .apply()
        sendBroadcast(Intent("com.intercom.app.STATUS_CHANGED"))
    }

    // ───────── 生命週期 ─────────

    override fun onCreate() {
        super.onCreate()
        instance = this

        // ★ 最優先：立即寫入狀態（在任何可能崩潰的操作之前）
        rawSaveStatus("starting", "服務已創建（API ${Build.VERSION.SDK_INT}）")

        // 建立通知頻道
        try {
            createNotificationChannels()
        } catch (e: Exception) {
            Log.e(TAG, "createNotificationChannels 失敗: ${e.message}", e)
            rawSaveStatus("failed", "通知頻道失敗: ${e.javaClass.simpleName}: ${e.message?.take(60)}")
            stopSelf()
            return
        }

        // 建立通知
        val notification = try {
            buildNotification("啟動中...")
        } catch (e: Exception) {
            Log.e(TAG, "buildNotification 失敗: ${e.message}", e)
            rawSaveStatus("failed", "通知建立失敗: ${e.javaClass.simpleName}")
            stopSelf()
            return
        }

        // 啟動前台服務
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11 (API 30)+ 需指定前台服務類型
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            rawSaveStatus("starting", "前台服務已啟動，準備連線")
        } catch (e: SecurityException) {
            Log.e(TAG, "startForeground SecurityException: ${e.message}", e)
            rawSaveStatus("failed", "SecurityException: ${e.message?.take(80)}")
            stopSelf()
            return
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失敗: ${e.javaClass.simpleName}: ${e.message}", e)
            rawSaveStatus("failed", "${e.javaClass.simpleName}: ${e.message?.take(80)}")
            stopSelf()
            return
        }

        // WiFi Lock：保持 WiFi radio 活躍，確保 Doze 深度模式下仍能接收 INVITE 封包
        // WIFI_MODE_FULL_HIGH_PERF 是 VoIP 標準做法（Linphone/Zoiper 均使用）
        acquireWifiLock()

        startMiniSip()

        // 監聽網路切換（WiFi 重連後 IP 可能變更，需重新 SIP 登錄）
        registerNetworkCallback()
    }

    /**
     * 最基礎的狀態寫入（不依賴 saveStatus，避免 sendBroadcast 異常影響診斷）
     */
    private fun rawSaveStatus(status: String, detail: String) {
        try {
            getSharedPreferences("intercom_prefs", Context.MODE_PRIVATE).edit()
                .putString("sip_status", status)
                .putString("sip_detail", detail)
                .apply()
            try { sendBroadcast(Intent("com.intercom.app.STATUS_CHANGED")) } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "rawSaveStatus 失敗: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DOZE_WAKEUP) {
            Log.i(TAG, "Doze wakeup alarm 觸發，重新登錄以刷新 NAT mapping")
            if (miniSip?.isInCall() != true) startMiniSip()
            scheduleDozeWakeup()   // 安排下一次
            return START_STICKY
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        cancelDozeWakeup()
        // 服務停止前先清除「已連線」狀態，避免下次啟動前 MainActivity 誤顯示「已連線」
        rawSaveStatus("failed", "服務已停止，重新啟動中…")
        instance = null
        stopCall()
        miniSip?.stop()
        miniSip = null
        releaseWakeLock()
        releaseWifiLock()
        connectivityCallback?.let {
            (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(it)
        }
        connectivityCallback = null
    }

    // ───────── 啟動 MiniSipStack ─────────

    private fun startMiniSip() {
        val prefs = getSharedPreferences("intercom_prefs", Context.MODE_PRIVATE)
        val serverIp = prefs.getString("server_ip", "192.168.100.163") ?: "192.168.100.163"
        val extension = prefs.getString("extension", "101") ?: "101"
        val password = prefs.getString("password", "password101") ?: "password101"
        currentExtension = extension

        // 清除上次快取的公司名稱，避免切換分機後仍顯示舊名稱
        prefs.edit().remove("company_name").apply()
        saveStatus("registering", "正在登錄 $extension@$serverIp")
        updateNotification("連線中...")

        miniSip?.stop()
        miniSip = MiniSipStack(
            serverIp = serverIp,
            serverPort = 5060,
            extension = extension,
            password = password,
            listener = object : MiniSipStack.SipListener {

                override fun onRegistered() {
                    Log.i(TAG, "✅ SIP 登錄成功 $extension@$serverIp")
                    // 先用分機號暫時顯示，非同步取得公司名後更新
                    updateNotification("已登錄（分機 $extension）")
                    saveStatus("registered", extension)
                    sendBroadcast(Intent("com.intercom.app.SIP_REGISTERED"))
                    // 啟動 Doze 喚醒鬧鐘（每 8 分鐘重新登錄，確保待機數天仍可收到來電）
                    scheduleDozeWakeup()
                    // 非同步從 Server 取得公司名稱並存入 SharedPreferences
                    ApiClient.fetchCompanyName(serverIp, extension) { name ->
                        val displayName = name ?: return@fetchCompanyName  // null = 找不到，保持分機號顯示
                        getSharedPreferences("intercom_prefs", Context.MODE_PRIVATE).edit()
                            .putString("company_name", displayName)
                            .apply()
                        mainHandler.post {
                            updateNotification("已登錄（$displayName）")
                            saveStatus("registered", displayName)
                        }
                    }
                }

                override fun onReregistering() {
                    Log.i(TAG, "重新登錄中（55 分鐘刷新）")
                    updateNotification("重新連線中...")
                    saveStatus("registering", "重新登錄 $extension@$serverIp")
                }

                override fun onRegistrationFailed(reason: String) {
                    Log.e(TAG, "SIP 登錄失敗: $reason")
                    updateNotification("登錄失敗，重試中...")
                    saveStatus("failed", reason)
                    sendBroadcast(Intent("com.intercom.app.SIP_FAILED"))
                    // 10 秒後重試
                    mainHandler.postDelayed({ startMiniSip() }, 10_000L)
                }

                override fun onIncomingCall(invite: MiniSipStack.InviteInfo) {
                    Log.i(TAG, "📞 onIncomingCall 觸發: from=${invite.fromUri}, rtp=${invite.remoteRtpIp}:${invite.remoteRtpPort}")

                    // 寫入來電狀態（供 MainActivity 偵測）
                    rawSaveStatus("incoming_call", invite.fromUri)

                    // 1. WakeLock 喚醒螢幕
                    try {
                        acquireWakeLock()
                        Log.i(TAG, "WakeLock 取得成功")
                    } catch (e: Exception) {
                        Log.e(TAG, "WakeLock 失敗: ${e.message}")
                    }

                    // 2. 發送高優先度通知（帶全螢幕 Intent）
                    try {
                        showIncomingCallNotification()
                        Log.i(TAG, "來電通知已發送")
                    } catch (e: Exception) {
                        Log.e(TAG, "showIncomingCallNotification 失敗: ${e.message}", e)
                    }

                    // 3. 振動
                    try {
                        vibrate()
                        Log.i(TAG, "振動已啟動")
                    } catch (e: Exception) {
                        Log.e(TAG, "vibrate 失敗: ${e.message}")
                    }

                    // 4. 直接啟動 Activity（Android 12- 有效；Android 12+ 靠通知全螢幕 Intent）
                    try {
                        launchIncomingCallActivity()
                        Log.i(TAG, "launchIncomingCallActivity 已呼叫")
                    } catch (e: Exception) {
                        Log.e(TAG, "launchIncomingCallActivity 失敗: ${e.message}")
                    }

                    // 5. 廣播 INCOMING_CALL（供 MainActivity 在前台時直接開啟來電畫面）
                    try {
                        sendBroadcast(Intent("com.intercom.app.INCOMING_CALL").also { it.setPackage(packageName) })
                        Log.i(TAG, "INCOMING_CALL broadcast 已發送")
                    } catch (e: Exception) {
                        Log.e(TAG, "INCOMING_CALL broadcast 失敗: ${e.message}")
                    }
                }

                override fun onCallEnded() {
                    Log.i(TAG, "通話結束")
                    stopCall()
                    cancelIncomingCallNotification()
                    // 恢復已登錄狀態
                    saveStatus("registered", currentExtension)
                    // 直接關閉來電畫面（最可靠）
                    IncomingCallActivity.dismissIfActive()
                    // 同時發廣播，確保 CallActivity 也能收到
                    sendBroadcast(Intent("com.intercom.app.CALL_ENDED").also { it.setPackage(packageName) })
                    sendBroadcast(Intent("com.intercom.app.FINISH_CALL").also { it.setPackage(packageName) })
                }
            }
        )

        // 在背景執行緒啟動（start() 會開 socket）
        Thread({
            try {
                miniSip?.start()
            } catch (e: Exception) {
                Log.e(TAG, "MiniSip start 失敗: ${e.message}")
                mainHandler.post {
                    saveStatus("failed", "SIP 啟動失敗: ${e.message}")
                }
            }
        }, "SipStart").start()
    }

    // ───────── 通話控制（供 IncomingCallActivity / CallActivity 呼叫）─────────

    fun answerCall(): Boolean {
        val sip = miniSip ?: return false
        // 選擇本地 RTP port（偶數）
        localRtpPort = findFreeUdpPort(16384)
        val ok = sip.answerCall(localRtpPort)
        if (ok) {
            // 立即在背景執行緒開 RTP socket（不等 500ms）
            // 確保 Asterisk 送 RTP 時 socket 已就緒，避免 ICMP Port Unreachable
            Thread({ startRtpSession() }, "RtpInit").start()
            cancelIncomingCallNotification()
            sendBroadcast(Intent("com.intercom.app.CALL_CONNECTED"))
        }
        return ok
    }

    fun rejectCall() {
        miniSip?.rejectCall()
        cancelIncomingCallNotification()
        releaseWakeLock()
        // 恢復已登錄狀態
        saveStatus("registered", currentExtension)
    }

    fun hangupCall() {
        miniSip?.hangup()
        stopCall()
        saveStatus("registered", currentExtension)
    }

    fun getRtpDebugStats(): String {
        val sdpIp = miniSip?.localIp ?: "?"
        val rtpStats = rtpSession?.getDebugStats() ?: "RTP 未啟動 remotePort=${miniSip?.getRemoteRtp()?.second}"
        return "SDP_IP=$sdpIp\n$rtpStats"
    }

    fun reloadSettings() {
        miniSip?.stop()
        miniSip = null
        startMiniSip()
    }

    // ───────── RTP 音訊 ─────────

    private fun startRtpSession() {
        val (remoteIp, remotePort) = miniSip?.getRemoteRtp() ?: return
        if (remotePort == 0) {
            Log.w(TAG, "遠端 RTP port 為 0，跳過音訊")
            return
        }
        Log.i(TAG, "RTP 準備啟動: 遠端=$remoteIp:$remotePort, 本地port=$localRtpPort")

        // AudioManager 操作必須在主執行緒才能可靠生效（部分 Android 版本限制）
        // 設定完成後，再從背景執行緒啟動 RtpAudioSession
        mainHandler.post {
            setupAudioForCall()
            Thread({
                rtpSession?.stop()
                rtpSession = null
                rtpSession = RtpAudioSession(remoteIp, remotePort, localRtpPort).also { it.start() }
                Log.i(TAG, "RtpAudioSession 已啟動")
            }, "RtpStart").start()
        }
    }

    /**
     * 在主執行緒設定音訊：申請音訊焦點，保持 MODE_NORMAL。
     *
     * ★ 關鍵：不設定 MODE_IN_COMMUNICATION。
     *   MODE_IN_COMMUNICATION 會在 Qualcomm/MediaTek DSP 層啟動 Hardware AEC，
     *   把手機麥克風錄到的聲音誤判為喇叭迴音並消除 → Phone→Pi 方向靜音。
     *   對講機場景手機與門口機在不同房間，根本沒有迴音，AEC 是誤判干擾。
     *   AudioTrack 改用 USAGE_MEDIA（走 STREAM_MUSIC），預設走喇叭輸出，
     *   不需要 isSpeakerphoneOn / setCommunicationDevice。
     */
    private fun setupAudioForCall() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager

            // 申請音訊焦點（USAGE_MEDIA，與 AudioTrack 一致）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val focusReq = android.media.AudioFocusRequest.Builder(
                    android.media.AudioManager.AUDIOFOCUS_GAIN
                ).setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                ).setAcceptsDelayedFocusGain(false)
                 .setWillPauseWhenDucked(false)
                 .build()
                audioFocusRequest = focusReq
                val result = am.requestAudioFocus(focusReq)
                Log.i(TAG, "requestAudioFocus(MEDIA) → $result（1=GRANTED）")
            } else {
                @Suppress("DEPRECATION")
                val result = am.requestAudioFocus(
                    null,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN
                )
                Log.i(TAG, "requestAudioFocus(STREAM_MUSIC 舊版）→ $result")
            }

            // 保持 MODE_NORMAL，避免 Hardware AEC 啟動
            Log.i(TAG, "保持 AudioManager MODE=${am.mode}（不切換 MODE_IN_COMMUNICATION）")

            // 確保 STREAM_MUSIC 音量不為 0
            val mediaMax = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val mediaVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            Log.i(TAG, "STREAM_MUSIC 音量: $mediaVol / $mediaMax")
            if (mediaVol == 0) {
                val target = (mediaMax * 0.7).toInt()
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
                Log.i(TAG, "STREAM_MUSIC 已從 0 調至 $target")
            }
        } catch (e: Exception) {
            Log.e(TAG, "setupAudioForCall 失敗: ${e.message}")
        }
    }

    /**
     * 監聽網路狀態變化。當 WiFi 重新連線（IP 可能改變），立即重新 SIP 登錄，
     * 確保 Asterisk 持有的 Contact URI 是最新的 IP:Port。
     */
    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            var networkWasLost = false   // 追蹤是否真的發生過斷線
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    networkWasLost = true
                    Log.i(TAG, "網路已斷線")
                }
                override fun onAvailable(network: Network) {
                    if (!networkWasLost) {
                        // 初始觸發（App 啟動時網路原本已在線）：跳過，避免重複重啟 SIP
                        Log.i(TAG, "初始 onAvailable（網路原本即可用），跳過重新登錄")
                        return
                    }
                    networkWasLost = false
                    // 通話中不重啟（避免掉話）
                    if (miniSip?.isInCall() == true) {
                        Log.i(TAG, "網路恢復但通話中，跳過重新登錄")
                        return
                    }
                    Log.i(TAG, "網路恢復（onAvailable），1 秒後重新 SIP 登錄")
                    mainHandler.postDelayed({ startMiniSip() }, 1000L)
                }
            }
            cm.registerNetworkCallback(req, cb)
            connectivityCallback = cb
            Log.i(TAG, "NetworkCallback 已註冊")
        } catch (e: Exception) {
            Log.e(TAG, "registerNetworkCallback 失敗: ${e.message}")
        }
    }

    private fun stopCall() {
        rtpSession?.stop()
        rtpSession = null
        releaseWakeLock()
        // 釋放音訊焦點（不需要恢復 mode，原本就是 MODE_NORMAL）
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
            Log.i(TAG, "音訊焦點已釋放")
        } catch (_: Exception) {}
    }

    private fun findFreeUdpPort(startPort: Int): Int {
        for (port in startPort..65000 step 2) {
            try {
                java.net.DatagramSocket(port).close()
                return port
            } catch (_: Exception) {}
        }
        return startPort
    }

    // ───────── 系統 UI ─────────

    private fun launchIncomingCallActivity() {
        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        Log.i(TAG, "startActivity IncomingCallActivity (API ${Build.VERSION.SDK_INT})")
        startActivity(intent)
    }

    private fun vibrate() {
        val vib = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500), 0))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(longArrayOf(0, 500, 300, 500), 0)
        }
    }

    private fun showIncomingCallNotification() {
        // Android 14+ 檢查 USE_FULL_SCREEN_INTENT 是否已授予
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (!nm.canUseFullScreenIntent()) {
                Log.w(TAG, "⚠️ USE_FULL_SCREEN_INTENT 未授予！來電畫面將無法自動全螢幕顯示。請在設定中允許。")
            } else {
                Log.i(TAG, "✅ USE_FULL_SCREEN_INTENT 已授予")
            }
        }

        val fullScreenIntent = PendingIntent.getActivity(
            this, 2, Intent(this, IncomingCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 通知點擊也開啟來電畫面
        val contentIntent = PendingIntent.getActivity(
            this, 3, Intent(this, IncomingCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(this, INCOMING_CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("門口來電")
            .setContentText("有人按門鈴 — 點此接聽")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenIntent, true)
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID + 1, notification)
    }

    private fun cancelIncomingCallNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID + 1)
        (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.cancel()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "IntercomApp:CallWakeLock"
        )
        wakeLock?.acquire(60_000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * WiFi Lock：WIFI_MODE_FULL_HIGH_PERF 讓 WiFi radio 保持全功率活躍。
     *
     * 問題：Android Doze 深度模式下 WiFi radio 進入低功耗休眠，
     * 路由器送來的 INVITE UDP 封包無法喚醒 radio → 電話打不進來。
     *
     * 對講機場景手機通常在室內接充電，WiFi Lock 的耗電量可接受。
     * 未持有 WiFi Lock 時（通話中已足夠，RTP 封包本身維持 radio 活躍），
     * 不需要特別釋放 → 因此整個 Service 生命週期持有一把 Lock 即可。
     */
    private fun acquireWifiLock() {
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IntercomApp:SipWifiLock")
            wifiLock?.acquire()
            Log.i(TAG, "WiFi Lock 已取得（FULL_HIGH_PERF）")
        } catch (e: Exception) {
            Log.w(TAG, "acquireWifiLock 失敗: ${e.message}")
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
            Log.i(TAG, "WiFi Lock 已釋放")
        } catch (e: Exception) {
            Log.w(TAG, "releaseWifiLock 失敗: ${e.message}")
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        NotificationChannel(CHANNEL_ID, "SIP 服務", NotificationManager.IMPORTANCE_LOW)
            .also { nm.createNotificationChannel(it) }
        NotificationChannel(INCOMING_CALL_CHANNEL_ID, "來電通知", NotificationManager.IMPORTANCE_HIGH)
            .apply {
                // 讓全螢幕 Intent 正常作動
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            .also { nm.createNotificationChannel(it) }
    }

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("對講機").setContentText(status)
            .setContentIntent(pi).setOngoing(true).build()
    }

    private fun updateNotification(status: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    // ───────── Doze 喚醒鬧鐘（NAT mapping / SIP 登錄長期保活）─────────

    /**
     * 每 8 分鐘透過 setExactAndAllowWhileIdle() 喚醒服務並重新 SIP 登錄。
     *
     * 背景：Android Doze 模式會凍結 HandlerThread.postDelayed() 與
     * mainHandler.postDelayed()，導致 25 秒 UDP keepalive 和 55 分鐘
     * 重新登錄均無法執行，NAT mapping 過期後 Asterisk 就打不進來。
     *
     * setExactAndAllowWhileIdle() 保證在 Doze 維護視窗內觸發
     * （系統保證最多延遲約 9 分鐘），無需額外權限。
     */
    private fun scheduleDozeWakeup() {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + WAKEUP_INTERVAL_MS,
            dozeWakeupPendingIntent()
        )
        Log.d(TAG, "Doze wakeup 已排程，${WAKEUP_INTERVAL_MS / 60000} 分鐘後觸發")
    }

    private fun cancelDozeWakeup() {
        try {
            (getSystemService(ALARM_SERVICE) as AlarmManager)
                .cancel(dozeWakeupPendingIntent())
            Log.d(TAG, "Doze wakeup 已取消")
        } catch (e: Exception) {
            Log.w(TAG, "cancelDozeWakeup 失敗: ${e.message}")
        }
    }

    private fun dozeWakeupPendingIntent(): PendingIntent =
        PendingIntent.getService(
            this, 42,
            Intent(this, SipService::class.java).apply { action = ACTION_DOZE_WAKEUP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
