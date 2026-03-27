package com.intercom.app

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.lang.ref.WeakReference

class IncomingCallActivity : AppCompatActivity() {

    companion object {
        const val TAG = "IncomingCallActivity"
        private var weakInstance: WeakReference<IncomingCallActivity>? = null

        /** SipService 可直接呼叫此方法關閉來電畫面（無論 broadcast 是否收到）*/
        fun dismissIfActive() {
            weakInstance?.get()?.let { activity ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.runOnUiThread {
                        activity.stopRingtone()
                        activity.finish()
                    }
                }
            }
            weakInstance = null
        }
    }

    private lateinit var mjpegView: MjpegView
    private var ringtone: Ringtone? = null
    private var serverIp: String = "192.168.100.163"

    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "收到通話結束廣播，關閉來電畫面")
            stopRingtone()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        weakInstance = WeakReference(this)
        Log.i(TAG, "IncomingCallActivity onCreate (API ${Build.VERSION.SDK_INT})")

        // ── 讓 Activity 在鎖定畫面上顯示 ──
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // 現代 API（API 27+）：setShowWhenLocked / setTurnScreenOn
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            // 請求解除鍵盤鎖（有密碼時仍需解鎖，但畫面會先顯示）
            val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            // 舊版相容
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // 保持螢幕常亮（所有版本）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_incoming_call)

        val prefs = getSharedPreferences("intercom_prefs", Context.MODE_PRIVATE)
        serverIp = prefs.getString("server_ip", "192.168.100.163") ?: "192.168.100.163"

        mjpegView = findViewById(R.id.mjpegView)
        val tvTitle = findViewById<TextView>(R.id.tvCallerName)
        val btnAnswer = findViewById<Button>(R.id.btnAnswer)
        val btnReject = findViewById<Button>(R.id.btnReject)

        tvTitle.text = "門口來電"

        // 啟動 MJPEG 串流
        mjpegView.startStream("http://$serverIp:5000/camera/stream")

        // 響鈴
        startRingtone()

        btnAnswer.setOnClickListener {
            Log.i(TAG, "使用者接聽")
            stopRingtone()
            mjpegView.stopStream()   // 先停止串流，避免與 CallActivity 同時連線造成攝影機衝突
            val answered = SipService.instance?.answerCall() ?: false
            Log.i(TAG, "answerCall() 回傳 = $answered")
            if (answered) {
                startActivity(Intent(this, CallActivity::class.java))
            }
            finish()
        }

        btnReject.setOnClickListener {
            Log.i(TAG, "使用者拒絕")
            stopRingtone()
            SipService.instance?.rejectCall()
            finish()
        }

        // 監聽通話結束 / 服務要求關閉事件
        val endedFilter = IntentFilter().apply {
            addAction("com.intercom.app.CALL_ENDED")
            addAction("com.intercom.app.FINISH_CALL")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(callEndedReceiver, endedFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(callEndedReceiver, endedFilter)
        }
    }

    private fun startRingtone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)
            ringtone?.play()
            Log.i(TAG, "鈴聲已啟動")
        } catch (e: Exception) {
            Log.e(TAG, "鈴聲失敗: ${e.message}")
        }
    }

    private fun stopRingtone() {
        try {
            ringtone?.stop()
            ringtone = null
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "IncomingCallActivity onDestroy")
        weakInstance = null
        stopRingtone()
        mjpegView.stopStream()
        try { unregisterReceiver(callEndedReceiver) } catch (e: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 不允許按返回鍵離開來電畫面
    }
}
