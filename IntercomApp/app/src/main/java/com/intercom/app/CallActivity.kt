package com.intercom.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CallActivity : AppCompatActivity() {

    private lateinit var mjpegView: MjpegView
    private lateinit var tvTimer: TextView
    private lateinit var tvDebugStats: TextView
    private var startTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var serverIp: String = "192.168.100.163"
    private var isHungUp = false

    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - startTime) / 1000
            val min = elapsed / 60
            val sec = elapsed % 60
            tvTimer.text = String.format("%02d:%02d", min, sec)
            // 更新 RTP debug 統計（sent/recv 封包數、AudioRecord/Track 狀態）
            tvDebugStats.text = SipService.instance?.rtpSession?.getDebugStats() ?: ""
            handler.postDelayed(this, 1000)
        }
    }

    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        setContentView(R.layout.activity_call)

        val prefs = getSharedPreferences("intercom_prefs", Context.MODE_PRIVATE)
        serverIp = prefs.getString("server_ip", "192.168.100.163") ?: "192.168.100.163"

        mjpegView = findViewById(R.id.mjpegView)
        tvTimer = findViewById(R.id.tvTimer)
        tvDebugStats = findViewById(R.id.tvDebugStats)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val btnUnlock = findViewById<Button>(R.id.btnUnlock)
        val btnHangup = findViewById<Button>(R.id.btnHangup)

        tvStatus.text = "通話中"
        startTime = System.currentTimeMillis()
        handler.post(timerRunnable)

        // 啟動 MJPEG 串流
        mjpegView.startStream("http://$serverIp:5000/camera/stream")

        btnUnlock.setOnClickListener {
            btnUnlock.isEnabled = false
            btnUnlock.text = "開門中..."
            ApiClient.unlockDoor(serverIp) { success, message ->
                runOnUiThread {
                    btnUnlock.isEnabled = true
                    btnUnlock.text = "開門"
                    if (success) {
                        Toast.makeText(this, "✓ 門已開啟", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "開門失敗: $message", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnHangup.setOnClickListener {
            isHungUp = true
            SipService.instance?.hangupCall()
            finish()
        }

        // 監聽通話結束
        val filter = IntentFilter().apply {
            addAction("com.intercom.app.CALL_ENDED")
            addAction("com.intercom.app.FINISH_CALL")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(callEndedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(callEndedReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(timerRunnable)
        mjpegView.stopStream()
        try { unregisterReceiver(callEndedReceiver) } catch (e: Exception) {}
        // 確保無論以何種方式離開通話畫面（按鈕、OS 關閉、滑走 App）都送出 SIP BYE
        if (!isHungUp) {
            isHungUp = true
            SipService.instance?.hangupCall()
        }
    }

    override fun onBackPressed() {
        // 不允許按返回鍵（必須明確掛斷）
    }
}
