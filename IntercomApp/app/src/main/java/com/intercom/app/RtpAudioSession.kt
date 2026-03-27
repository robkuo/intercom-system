package com.intercom.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RTP 音訊會話（G.711 μ-law / A-law）
 * 使用兩個 DatagramSocket 分別發送（localPort）和接收（localPort+1 or same）
 * 實際上 RTP 用同一個 port 雙向傳輸
 */
class RtpAudioSession(
    private val remoteIp: String,
    private val remoteRtpPort: Int,
    val localRtpPort: Int = 16384
) {
    companion object {
        const val TAG = "RtpAudio"
        const val SAMPLE_RATE = 8000
        const val FRAME_MS = 20          // 每幀 20ms
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000  // 160 samples
        const val PAYLOAD_PCMU = 0       // G.711 μ-law
    }

    private val running = AtomicBoolean(false)
    private var rtpSocket: DatagramSocket? = null
    private var sendThread: Thread? = null
    private var recvThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var seqNum = 0
    private var timestamp = 0L
    private val ssrc = (Math.random() * 0xFFFFFFFFL).toLong()

    // AEC effect 參考（保持持有，避免 GC 釋放；AudioTrack 啟動後用於 reDisableAec()）
    private var aecEffect: android.media.audiofx.AcousticEchoCanceler? = null
    // AGC effect 參考（MIC source 不保證開啟 AGC，明確建立並啟用以確保麥克風增益）
    private var agcEffect: android.media.audiofx.AutomaticGainControl? = null

    // Debug stats（供 CallActivity 顯示）
    val recvPackets = java.util.concurrent.atomic.AtomicInteger(0)
    val sentPackets = java.util.concurrent.atomic.AtomicInteger(0)
    val lastRms    = java.util.concurrent.atomic.AtomicLong(0)

    fun getDebugStats(): String {
        val arState = audioRecord?.recordingState ?: -1  // 3=RECORDING
        val atState = audioTrack?.playState ?: -1        // 3=PLAYING
        val localIp = try {
            val s = java.net.DatagramSocket()
            s.connect(java.net.InetAddress.getByName(remoteIp), 5060)
            val ip = s.localAddress.hostAddress ?: "?"
            s.close(); ip
        } catch (e: Exception) { "err:${e.message?.take(20)}" }
        return "RTP recv=${recvPackets.get()} sent=${sentPackets.get()}  RMS=${lastRms.get()}\n" +
               "remote=$remoteIp:$remoteRtpPort\n" +
               "local=$localIp:$localRtpPort\n" +
               "AR=$arState(3=ok)  AT=$atState(3=ok)"
    }

    fun start() {
        if (running.get()) return
        running.set(true)

        try {
            rtpSocket = DatagramSocket(localRtpPort)
            rtpSocket?.soTimeout = 100
            Log.i(TAG, "RTP socket 綁定成功 port=$localRtpPort")
        } catch (e: Exception) {
            Log.e(TAG, "RTP socket 綁定失敗 port=$localRtpPort: ${e.message}")
            running.set(false)
            return
        }

        initAudioRecord()
        initAudioTrack()

        // 檢查初始化狀態
        val recState = audioRecord?.state
        val trackState = audioTrack?.state
        Log.i(TAG, "AudioRecord state=$recState (1=OK), AudioTrack state=$trackState (1=OK)")

        if (recState != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "⚠️ AudioRecord 初始化失敗！state=$recState，麥克風可能無聲")
        }
        if (trackState != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "⚠️ AudioTrack 初始化失敗！state=$trackState，播放可能無聲")
        }

        audioRecord?.startRecording()
        audioTrack?.play()

        Log.i(TAG, "AudioRecord recordingState=${audioRecord?.recordingState} (3=RECORDING)")
        Log.i(TAG, "AudioTrack playState=${audioTrack?.playState} (3=PLAYING)")

        // HAL Watchdog：AudioTrack 啟動後約 200-300ms，HAL 偵測到同時 playback+record，
        // 可能自動重新啟用 Hardware AEC。在 100ms 和 500ms 時各強制關閉一次。
        Thread({
            try {
                Thread.sleep(100)
                reDisableAec()     // T=100ms：第一次再確認
                Thread.sleep(400)
                reDisableAec()     // T=500ms：第二次再確認（HAL 稍後可能才反應）
                Thread.sleep(1000)
                reDisableAec()     // T=1500ms：第三次確認（部分 OEM HAL 延遲較長）
            } catch (_: InterruptedException) {}
        }, "AecWatchdog").also { it.isDaemon = true; it.start() }

        recvThread = Thread(::receiveLoop, "RtpRecv").also { it.start() }
        sendThread = Thread(::sendLoop, "RtpSend").also { it.start() }

        Log.i(TAG, "RTP session 啟動完成: local=$localRtpPort remote=$remoteIp:$remoteRtpPort")
    }

    fun stop() {
        if (!running.get()) return
        running.set(false)

        try { rtpSocket?.close() } catch (_: Exception) {}
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
        try { aecEffect?.release() } catch (_: Exception) {}
        try { agcEffect?.release() } catch (_: Exception) {}
        audioRecord = null
        audioTrack = null
        aecEffect = null
        agcEffect = null
        sendThread?.join(500)
        recvThread?.join(500)
        Log.i(TAG, "RTP session stopped")
    }

    // ───────── 初始化 ─────────

    private fun initAudioRecord() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, FRAME_SAMPLES * 2 * 4)

        // MIC（=1）：中性 source，無預設效果鏈，讓我們完全控制 AEC/AGC/NS。
        // 避免 VOICE_RECOGNITION（=6）：Android 官方規格明確說明「with gain control disabled」
        //   → VOICE_RECOGNITION 永遠無 AGC → 麥克風增益在硬體最低值 → RMS≈130 → v1.3~v1.6 靜音根源。
        // 避免 VOICE_COMMUNICATION（=7）：Qualcomm/MediaTek DSP 層強制 Hardware AEC，
        //   本裝置 AcousticEchoCanceler.isAvailable()=false，API 層完全無法停用。
        val audioSource = MediaRecorder.AudioSource.MIC
        Log.i(TAG, "AudioRecord source = MIC(1)")

        audioRecord = AudioRecord(
            audioSource,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        Log.i(TAG, "AudioRecord state=${audioRecord?.state}")

        val sessionId = audioRecord?.audioSessionId ?: return

        // ★ AEC 停用（嘗試）：對講機不需要迴音消除。
        // 本裝置 isAvailable()=false（硬體 DSP AEC），此區塊為 no-op，但保留正確語意。
        if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
            aecEffect = android.media.audiofx.AcousticEchoCanceler.create(sessionId)
            aecEffect?.enabled = false
            Log.i(TAG, "AEC 停用成功 (sessionId=$sessionId)")
        } else {
            Log.i(TAG, "AEC isAvailable=false（硬體 DSP AEC，API 無法停用）")
        }

        // ★ AGC 明確啟用：MIC source 不保證開啟 AGC，必須明確建立並 enabled=true。
        // 無 AGC → 麥克風硬體原始增益極低 → RMS≈130（噪音底板）→ Pi 端收到靜音。
        // 這是 v1.3~v1.6 整通通話無聲的根本原因（VOICE_RECOGNITION 規格已禁用 AGC，
        // 移除我們的 disable 也沒有效果）。
        if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
            agcEffect = android.media.audiofx.AutomaticGainControl.create(sessionId)
            agcEffect?.enabled = true
            Log.i(TAG, "AGC 明確啟用 isEnabled=${agcEffect?.enabled} (sessionId=$sessionId)")
        } else {
            Log.i(TAG, "AGC isAvailable=false（硬體 AGC，系統自行維持增益）")
        }

        // NS 保持系統預設：消除背景雜音，對語音振幅無害，不干預。
        Log.i(TAG, "NS 保持系統預設（不干預）")
    }

    /**
     * 強制再次停用 AEC。
     * Android HAL 在偵測到「同時 playback + recording」時會重新啟用 Hardware AEC，
     * 此方法在 AudioTrack.play() 後週期性呼叫，確保 AEC 保持關閉。
     */
    private fun reDisableAec() {
        val effect = aecEffect ?: return
        try {
            if (effect.enabled) {
                effect.enabled = false
                Log.i(TAG, "⚠️ AEC 被 HAL 重新啟用，已強制關閉")
            } else {
                Log.d(TAG, "AEC 確認仍為關閉狀態")
            }
        } catch (e: Exception) {
            Log.w(TAG, "reDisableAec 例外: ${e.message}")
        }
    }

    private fun initAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, FRAME_SAMPLES * 2 * 4)
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                // USAGE_MEDIA：走 STREAM_MUSIC，不依賴 MODE_IN_COMMUNICATION。
                // MODE_IN_COMMUNICATION 在 Qualcomm/MediaTek HAL 層觸發 Hardware AEC，
                // 會把手機麥克風訊號誤判為迴音消除 → Phone→Pi 方向靜音。
                // 改用 USAGE_MEDIA 後，系統不為此 session 啟動 VoIP AEC 管線。
                // 音量由媒體音量鍵控制，預設走外置喇叭輸出。
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        Log.i(TAG, "AudioTrack state=${audioTrack?.state}")
    }

    // ───────── 發送迴圈 ─────────

    private fun sendLoop() {
        val pcmBuf = ShortArray(FRAME_SAMPLES)
        val remote = InetAddress.getByName(remoteIp)
        var sendCount = 0

        while (running.get()) {
            val read = audioRecord?.read(pcmBuf, 0, FRAME_SAMPLES) ?: break
            if (read <= 0) continue

            // 計算 RMS（每幀更新一次，用於 debug 顯示；>500 表示有聲音內容）
            var sumSq = 0L
            for (i in 0 until read) sumSq += pcmBuf[i].toLong() * pcmBuf[i].toLong()
            lastRms.set(kotlin.math.sqrt((sumSq.toDouble() / read)).toLong())

            val pcmu = ByteArray(read) { i -> pcmToUlaw(pcmBuf[i]) }
            val pkt = buildRtpPacket(PAYLOAD_PCMU, pcmu)

            try {
                rtpSocket?.send(DatagramPacket(pkt, pkt.size, remote, remoteRtpPort))
                sendCount++
                sentPackets.set(sendCount)
                if (sendCount == 1 || sendCount % 500 == 0)
                    Log.i(TAG, "RTP 已發送 $sendCount 封包 → $remoteIp:$remoteRtpPort")
                // 週期性確認 AEC 仍然關閉（每 5 秒 = 250 幀）
                if (sendCount in listOf(5, 15, 25) || (sendCount > 25 && sendCount % 250 == 0)) {
                    reDisableAec()
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "RTP send error: ${e.message}")
            }
            seqNum++
            timestamp += read
        }
        Log.i(TAG, "sendLoop 結束，共發送 $sendCount 封包")
    }

    // ───────── 接收迴圈 ─────────

    private fun receiveLoop() {
        val buf = ByteArray(1024)
        var recvCount = 0

        while (running.get()) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                rtpSocket?.receive(pkt) ?: break

                // 解析 RTP header（最少 12 bytes）
                if (pkt.length < 12) continue
                val payload = buf.copyOfRange(12, pkt.length)
                val pt = buf[1].toInt() and 0x7F

                // 解碼 G.711 μ-law 或 A-law
                val pcm = when (pt) {
                    0 -> ShortArray(payload.size) { i -> ulawToPcm(payload[i]) }
                    8 -> ShortArray(payload.size) { i -> alawToPcm(payload[i]) }
                    else -> {
                        Log.d(TAG, "跳過未知 payload type=$pt")
                        continue
                    }
                }

                // 轉成 ByteArray 小端序寫入 AudioTrack
                val bytes = ByteArray(pcm.size * 2)
                for (i in pcm.indices) {
                    bytes[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
                    bytes[i * 2 + 1] = (pcm[i].toInt() shr 8 and 0xFF).toByte()
                }
                audioTrack?.write(bytes, 0, bytes.size)
                recvCount++
                recvPackets.set(recvCount)
                if (recvCount == 1 || recvCount % 500 == 0)
                    Log.i(TAG, "RTP 已接收 $recvCount 封包，PT=$pt，payload=${payload.size}B")

            } catch (e: java.net.SocketTimeoutException) {
                // 正常超時，繼續等
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "RTP recv error: ${e.message}")
            }
        }
        Log.i(TAG, "receiveLoop 結束，共接收 $recvCount 封包")
    }

    // ───────── RTP 封包 ─────────

    private fun buildRtpPacket(payloadType: Int, payload: ByteArray): ByteArray {
        val pkt = ByteArray(12 + payload.size)
        pkt[0] = 0x80.toByte()                                // V=2, P=0, X=0, CC=0
        pkt[1] = payloadType.toByte()                         // M=0, PT
        pkt[2] = (seqNum shr 8 and 0xFF).toByte()
        pkt[3] = (seqNum and 0xFF).toByte()
        val ts = timestamp.toInt()
        pkt[4] = (ts shr 24 and 0xFF).toByte()
        pkt[5] = (ts shr 16 and 0xFF).toByte()
        pkt[6] = (ts shr 8 and 0xFF).toByte()
        pkt[7] = (ts and 0xFF).toByte()
        val s = ssrc.toInt()
        pkt[8] = (s shr 24 and 0xFF).toByte()
        pkt[9] = (s shr 16 and 0xFF).toByte()
        pkt[10] = (s shr 8 and 0xFF).toByte()
        pkt[11] = (s and 0xFF).toByte()
        System.arraycopy(payload, 0, pkt, 12, payload.size)
        return pkt
    }

    // ───────── G.711 μ-law 編解碼 ─────────

    private fun pcmToUlaw(pcm: Short): Byte {
        var sample = pcm.toInt()
        val sign = if (sample < 0) { sample = -sample; 0x80 } else 0
        if (sample > 32767) sample = 32767
        sample += 0x84
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && sample and mask == 0) { exponent--; mask = mask shr 1 }
        val mantissa = sample shr (exponent + 3) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    private fun ulawToPcm(ulaw: Byte): Short {
        val u = ulaw.toInt().inv() and 0xFF
        val sign = u and 0x80
        val exponent = u shr 4 and 0x07
        val mantissa = u and 0x0F
        var sample = ((mantissa shl 3) + 0x84) shl exponent
        sample -= 0x84
        return (if (sign != 0) -sample else sample).toShort()
    }

    // ───────── G.711 A-law 解碼 ─────────

    private fun alawToPcm(alaw: Byte): Short {
        var a = (alaw.toInt() xor 0x55) and 0xFF
        val sign = a and 0x80
        a = a and 0x7F
        val sample = if (a >= 16) {
            val exponent = a shr 4
            val mantissa = a and 0x0F
            ((mantissa shl 1) + 33) shl (exponent - 1)
        } else {
            a * 2 + 1
        }
        return (if (sign == 0) sample else -sample).toShort()
    }
}
