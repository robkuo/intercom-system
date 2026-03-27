package com.intercom.app

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.net.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 輕量 SIP 客戶端（純 UDP，不依賴 android.net.sip）
 * 支援：REGISTER（帶 MD5 Digest 認證）、INVITE 接收、200 OK、BYE、OPTIONS
 *
 * 修復：所有 socket.send() 均在 SipSend HandlerThread 執行，
 *       避免 Android 主執行緒的 NetworkOnMainThreadException。
 */
class MiniSipStack(
    private val serverIp: String,
    private val serverPort: Int = 5060,
    private val extension: String,
    private val password: String,
    private val listener: SipListener
) {
    interface SipListener {
        fun onRegistered()
        fun onRegistrationFailed(reason: String)
        fun onReregistering() {}   // 每 55 分鐘重新登錄前通知（預設空實作）
        fun onIncomingCall(invite: InviteInfo)
        fun onCallEnded()
    }

    data class InviteInfo(
        val callId: String,
        val fromUri: String,
        val remoteRtpIp: String,
        val remoteRtpPort: Int,
        val via: String,
        val from: String,
        val to: String,
        val cseq: String,
        val rawMsg: String
    )

    private var socket: DatagramSocket? = null
    var localIp: String = "127.0.0.1"; private set
    var localPort: Int = 0; private set
    private val running = AtomicBoolean(false)
    private var cseq = 1
    private val regCallId = randomHex(12) + "@intercom"
    private val mainHandler = Handler(Looper.getMainLooper())

    // 背景執行緒：所有 socket.send() 在此執行，避免 NetworkOnMainThreadException
    private val sipSendThread = HandlerThread("SipSend").also { it.start() }
    private val sipSendHandler = Handler(sipSendThread.looper)

    private var lastRealm: String? = null
    private var lastNonce: String? = null
    private var reregisterRunnable: Runnable? = null
    private var keepaliveRunnable: Runnable? = null
    private var registerTimeoutRunnable: Runnable? = null
    private var pendingInvite: InviteInfo? = null
    private var localDialogTag: String? = null   // 200 OK 裡的 To-tag，BYE 的 From-tag 必須一致
    private var inCall = false

    // 200 OK 重傳狀態（RFC 3261：UAS 必須持續重傳 200 OK 直到收到 ACK）
    @Volatile private var answeredCallId: String? = null

    companion object {
        const val TAG = "MiniSip"
        // RE_REGISTER_INTERVAL 已改為從伺服器 200 OK 的 Expires 動態計算（×0.9）
        const val REGISTER_TIMEOUT_MS = 15_000L             // REGISTER 無回應逾時 15 秒
        const val UDP_KEEPALIVE_INTERVAL = 25_000L          // 25秒 UDP 心跳，維持 NAT 映射
    }

    fun start() {
        localIp = detectLocalIp()
        socket = DatagramSocket().also { localPort = it.localPort }
        Log.i(TAG, "SIP socket on $localIp:$localPort")
        running.set(true)
        Thread(::receiveLoop, "SipReceive").start()
        // REGISTER 從 sipSendThread 發送，避免 start() 可能在主執行緒呼叫的情況
        sipSendHandler.post { sendRegister() }
    }

    fun stop() {
        running.set(false)
        reregisterRunnable?.let { mainHandler.removeCallbacks(it) }
        reregisterRunnable = null
        keepaliveRunnable?.let { sipSendHandler.removeCallbacks(it) }
        keepaliveRunnable = null
        cancelRegisterTimeout()
        stopOkRetransmission()
        sipSendHandler.removeCallbacksAndMessages(null)
        sipSendThread.quitSafely()
        socket?.close()
        Log.i(TAG, "MiniSipStack stopped")
    }

    fun answerCall(localRtpPort: Int): Boolean {
        val invite = pendingInvite ?: return false
        val sdp = buildSdp(localIp, localRtpPort)
        val sdpBytes = sdp.toByteArray(Charsets.UTF_8)
        val tag = randomHex(8)
        localDialogTag = tag   // 儲存 dialog tag，BYE 的 From-tag 必須與此相同
        val response = buildString {
            append("SIP/2.0 200 OK\r\n")
            appendVia(invite.via)
            append("From: ${invite.from}\r\n")
            append("To: ${invite.to};tag=$tag\r\n")
            append("Call-ID: ${invite.callId}\r\n")
            append("CSeq: ${invite.cseq}\r\n")
            append("Contact: <sip:$extension@$localIp:$localPort>\r\n")
            append("Content-Type: application/sdp\r\n")
            append("Content-Length: ${sdpBytes.size}\r\n\r\n")
            append(sdp)
        }
        inCall = true
        Log.i(TAG, "answerCall: queuing 200 OK → callId=${invite.callId}, " +
                "SDP=$localIp:$localRtpPort, msgLen=${response.length}B")
        // 傳送與重傳均在 sipSendThread 執行
        sipSendHandler.post {
            val sent = sendSipInternal(response)
            Log.i(TAG, "answerCall: initial 200 OK send result = $sent")
        }
        // RFC 3261 §13.3.1.4：UAS 必須持續重傳 200 OK 直到收到 ACK
        startOkRetransmission(response, invite.callId)
        return true
    }

    fun isInCall(): Boolean = inCall

    fun rejectCall() {
        val invite = pendingInvite ?: return
        sendSimpleResponse(invite, 603, "Decline")
        pendingInvite = null
    }

    /** 取得目前來電的遠端 RTP 位址（供 SipService 啟動 RTP session 用） */
    fun getRemoteRtp(): Pair<String, Int> {
        val inv = pendingInvite ?: return Pair(serverIp, 0)
        return Pair(inv.remoteRtpIp, inv.remoteRtpPort)
    }

    fun hangup() {
        if (!inCall) return
        stopOkRetransmission()
        val invite = pendingInvite ?: return
        // From-tag 必須與 200 OK 的 To-tag 相同，否則 Asterisk 回 481 並忽略 BYE
        val fromTag = localDialogTag ?: randomHex(8)
        val bye = buildString {
            append("BYE sip:${invite.fromUri} SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:$localPort;rport;branch=z9hG4bK${randomHex(8)}\r\n")
            append("From: ${invite.to};tag=$fromTag\r\n")
            append("To: ${invite.from}\r\n")
            append("Call-ID: ${invite.callId}\r\n")
            append("CSeq: ${cseq++} BYE\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
        Log.i(TAG, "Sending BYE: callId=${invite.callId}, fromTag=$fromTag")
        sendSip(bye)
        inCall = false
        pendingInvite = null
        localDialogTag = null
    }

    // ───────── 內部方法 ─────────

    private fun sendRegister(realm: String? = null, nonce: String? = null) {
        val authHeader = if (realm != null && nonce != null) {
            val uri = "sip:$serverIp"
            val ha1 = md5("$extension:$realm:$password")
            val ha2 = md5("REGISTER:$uri")
            val resp = md5("$ha1:$nonce:$ha2")
            "Authorization: Digest username=\"$extension\",realm=\"$realm\"," +
                "nonce=\"$nonce\",uri=\"$uri\",response=\"$resp\",algorithm=MD5\r\n"
        } else ""

        val branch = "z9hG4bK${randomHex(10)}"
        val tag = randomHex(8)
        val msg = buildString {
            append("REGISTER sip:$serverIp SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:$localPort;rport;branch=$branch\r\n")
            append("Max-Forwards: 70\r\n")
            append("From: <sip:$extension@$serverIp>;tag=$tag\r\n")
            append("To: <sip:$extension@$serverIp>\r\n")
            append("Call-ID: $regCallId\r\n")
            append("CSeq: ${cseq++} REGISTER\r\n")
            append("Contact: <sip:$extension@$localIp:$localPort;transport=udp>\r\n")
            append("Expires: 3600\r\n")
            append("User-Agent: IntercomApp/1.0\r\n")
            append(authHeader)
            append("Content-Length: 0\r\n\r\n")
        }
        // sendRegister() 本身可能從 sipSendThread 或 receiveLoop 呼叫，直接呼叫 sendSip()
        sendSip(msg)
        Log.i(TAG, "Sent REGISTER (ext=$extension, auth=${realm != null})")

        // 啟動逾時偵測：若 15 秒內沒收到 200 OK，通知 listener 登錄失敗
        startRegisterTimeout()
    }

    private fun startRegisterTimeout() {
        // 取消舊的逾時（例如 401 → 重傳時重新計時）
        registerTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            registerTimeoutRunnable = null
            Log.w(TAG, "REGISTER 逾時（${REGISTER_TIMEOUT_MS}ms），SIP 伺服器無回應")
            mainHandler.post { listener.onRegistrationFailed("SIP 伺服器無回應（逾時 15 秒）") }
        }
        registerTimeoutRunnable = r
        mainHandler.postDelayed(r, REGISTER_TIMEOUT_MS)
    }

    private fun cancelRegisterTimeout() {
        registerTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        registerTimeoutRunnable = null
    }

    private fun receiveLoop() {
        val buf = ByteArray(65536)
        while (running.get()) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                socket?.receive(pkt) ?: break
                val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                handleMessage(msg)
            } catch (e: SocketException) {
                if (running.get()) Log.e(TAG, "Socket closed: ${e.message}")
                break
            } catch (e: Exception) {
                Log.e(TAG, "Receive error: ${e.message}")
            }
        }
        Log.i(TAG, "Receive loop ended")
    }

    private fun handleMessage(msg: String) {
        val firstLine = msg.substringBefore("\r\n")
        Log.d(TAG, "Recv: $firstLine")

        when {
            // 401/407 認證挑戰
            firstLine.startsWith("SIP/2.0 401") || firstLine.startsWith("SIP/2.0 407") -> {
                val authLine = msg.lines().find {
                    it.startsWith("WWW-Authenticate:") || it.startsWith("Proxy-Authenticate:")
                } ?: return
                val realm = extractQuotedParam(authLine, "realm") ?: return
                val nonce = extractQuotedParam(authLine, "nonce") ?: return
                lastRealm = realm; lastNonce = nonce
                Log.i(TAG, "Got 401, retrying with auth")
                // 在 receiveLoop (SipReceive thread) 呼叫，透過 sendSip() 自動路由到 sipSendThread
                sendSip { sendRegister(realm, nonce) }
            }

            // 200 OK for REGISTER
            firstLine.startsWith("SIP/2.0 200") && hasCseq(msg, "REGISTER") -> {
                Log.i(TAG, "✅ SIP REGISTER 成功！")
                cancelRegisterTimeout()
                // 解析伺服器協商後的實際 Expires（Asterisk 可能將 3600 縮短為 600）
                val negotiatedExpires = parseNegotiatedExpires(msg)
                Log.i(TAG, "協商 Expires=$negotiatedExpires 秒，將在 ${(negotiatedExpires * 0.9).toInt()} 秒後重新登錄")
                scheduleReRegistration(negotiatedExpires)
                scheduleKeepalive()   // 啟動 25s UDP 心跳，維持 NAT 映射
                mainHandler.post { listener.onRegistered() }
            }

            // 200 OK for BYE (ignore)
            firstLine.startsWith("SIP/2.0 2") && hasCseq(msg, "BYE") -> {
                Log.i(TAG, "BYE 200 OK")
            }

            // OPTIONS keepalive (Asterisk 定期發送)
            firstLine.startsWith("OPTIONS") -> {
                val lines = msg.lines()
                val via = lines.find { it.startsWith("Via:") }?.substringAfter("Via:").orEmpty().trim()
                val from = lines.find { it.startsWith("From:") }?.substringAfter("From:").orEmpty().trim()
                val to = lines.find { it.startsWith("To:") }?.substringAfter("To:").orEmpty().trim()
                val callId = lines.find { it.startsWith("Call-ID:") }?.substringAfter("Call-ID:").orEmpty().trim()
                val cseqVal = lines.find { it.startsWith("CSeq:") }?.substringAfter("CSeq:").orEmpty().trim()
                val response = buildString {
                    append("SIP/2.0 200 OK\r\n")
                    appendVia(via)
                    append("From: $from\r\n")
                    append("To: $to\r\n")
                    append("Call-ID: $callId\r\n")
                    append("CSeq: $cseqVal\r\n")
                    append("Content-Length: 0\r\n\r\n")
                }
                sendSip(response)
            }

            // INVITE 來電
            firstLine.startsWith("INVITE") -> handleInvite(msg)

            // BYE 對方掛斷
            firstLine.startsWith("BYE") -> {
                Log.i(TAG, "收到 BYE")
                stopOkRetransmission()
                val lines = msg.lines()
                val via = lines.find { it.startsWith("Via:") }?.substringAfter("Via:").orEmpty().trim()
                val from = lines.find { it.startsWith("From:") }?.substringAfter("From:").orEmpty().trim()
                val to = lines.find { it.startsWith("To:") }?.substringAfter("To:").orEmpty().trim()
                val callId = lines.find { it.startsWith("Call-ID:") }?.substringAfter("Call-ID:").orEmpty().trim()
                val cseqVal = lines.find { it.startsWith("CSeq:") }?.substringAfter("CSeq:").orEmpty().trim()
                val response = buildString {
                    append("SIP/2.0 200 OK\r\n")
                    appendVia(via)
                    append("From: $from\r\n")
                    append("To: $to\r\n")
                    append("Call-ID: $callId\r\n")
                    append("CSeq: $cseqVal\r\n")
                    append("Content-Length: 0\r\n\r\n")
                }
                sendSip(response)
                inCall = false
                pendingInvite = null
                localDialogTag = null
                mainHandler.post { listener.onCallEnded() }
            }

            // CANCEL (Asterisk 30秒超時取消來電)
            firstLine.startsWith("CANCEL") -> {
                Log.i(TAG, "收到 CANCEL（Asterisk 取消來電）")
                stopOkRetransmission()
                val lines = msg.lines()
                val via = lines.find { it.startsWith("Via:") }?.substringAfter("Via:").orEmpty().trim()
                val from = lines.find { it.startsWith("From:") }?.substringAfter("From:").orEmpty().trim()
                val to = lines.find { it.startsWith("To:") }?.substringAfter("To:").orEmpty().trim()
                val callId = lines.find { it.startsWith("Call-ID:") }?.substringAfter("Call-ID:").orEmpty().trim()
                val cseqVal = lines.find { it.startsWith("CSeq:") }?.substringAfter("CSeq:").orEmpty().trim()
                // 回應 CANCEL → 200 OK
                val cancelOk = buildString {
                    append("SIP/2.0 200 OK\r\n")
                    appendVia(via)
                    append("From: $from\r\n")
                    append("To: $to\r\n")
                    append("Call-ID: $callId\r\n")
                    append("CSeq: $cseqVal\r\n")
                    append("Content-Length: 0\r\n\r\n")
                }
                sendSip(cancelOk)
                // 原始 INVITE → 487 Request Terminated
                pendingInvite?.let { invite ->
                    val terminated = buildString {
                        append("SIP/2.0 487 Request Terminated\r\n")
                        appendVia(invite.via)
                        append("From: ${invite.from}\r\n")
                        append("To: ${invite.to}\r\n")
                        append("Call-ID: ${invite.callId}\r\n")
                        append("CSeq: ${invite.cseq}\r\n")
                        append("Content-Length: 0\r\n\r\n")
                    }
                    sendSip(terminated)
                }
                inCall = false
                pendingInvite = null
                localDialogTag = null
                mainHandler.post { listener.onCallEnded() }
            }

            // ACK（Asterisk 確認收到 200 OK → 停止重傳）
            firstLine.startsWith("ACK") -> {
                Log.i(TAG, "✅ ACK received — call fully established, stopping 200 OK retransmission")
                stopOkRetransmission()
            }

            // 其他回應（忽略）
            firstLine.startsWith("SIP/2.0") -> Log.d(TAG, "SIP response: $firstLine")
        }
    }

    private fun handleInvite(msg: String) {
        val lines = msg.lines()
        val via = lines.find { it.startsWith("Via:") }?.substringAfter("Via:").orEmpty().trim()
        val from = lines.find { it.startsWith("From:") }?.substringAfter("From:").orEmpty().trim()
        val to = lines.find { it.startsWith("To:") }?.substringAfter("To:").orEmpty().trim()
        val callId = lines.find { it.startsWith("Call-ID:") }?.substringAfter("Call-ID:").orEmpty().trim()
        val cseqVal = lines.find { it.startsWith("CSeq:") }?.substringAfter("CSeq:").orEmpty().trim()

        // 解析 SDP 取得遠端 RTP 位址
        val sdpBody = msg.substringAfter("\r\n\r\n")
        val remoteIp = Regex("""c=IN IP4 ([\d.]+)""").find(sdpBody)?.groupValues?.get(1) ?: serverIp
        val remotePort = Regex("""m=audio (\d+)""").find(sdpBody)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        // 從 From header 提取 URI
        val fromUri = Regex("""<([^>]+)>""").find(from)?.groupValues?.get(1) ?: ""

        val inviteInfo = InviteInfo(callId, fromUri, remoteIp, remotePort, via, from, to, cseqVal, msg)
        pendingInvite = inviteInfo
        inCall = false

        // 發送 100 Trying
        sendSimpleResponse(inviteInfo, 100, "Trying")
        // 發送 180 Ringing
        sendSimpleResponse(inviteInfo, 180, "Ringing")

        Log.i(TAG, "📞 收到來電！from=$fromUri, rtp=$remoteIp:$remotePort")
        mainHandler.post { listener.onIncomingCall(inviteInfo) }
    }

    private fun sendSimpleResponse(invite: InviteInfo, code: Int, reason: String) {
        val response = buildString {
            append("SIP/2.0 $code $reason\r\n")
            appendVia(invite.via)
            append("From: ${invite.from}\r\n")
            append("To: ${invite.to}\r\n")
            append("Call-ID: ${invite.callId}\r\n")
            append("CSeq: ${invite.cseq}\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
        sendSip(response)
    }

    /**
     * 主要 sendSip()：自動路由 — 若在主執行緒呼叫，dispatch 到 sipSendThread；
     * 否則直接在當前執行緒（SipReceive 或 SipSend）執行。
     * 避免 Android NetworkOnMainThreadException。
     */
    private fun sendSip(msg: String): Boolean {
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            // 主執行緒 → 非同步 dispatch 到 sipSendThread
            Log.d(TAG, "sendSip: dispatching from main thread → SipSend: ${msg.substringBefore("\r\n")}")
            sipSendHandler.post { sendSipInternal(msg) }
            true  // fire-and-forget，樂觀回傳 true
        } else {
            // 已在背景執行緒（SipReceive / SipSend）→ 直接執行
            sendSipInternal(msg)
        }
    }

    /**
     * sendSip() 的 lambda 版本，用於需要在 sipSendThread 呼叫多個方法的情況
     * （例如 sendRegister() 在 401 處理後需完整執行）
     */
    private fun sendSip(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            sipSendHandler.post(block)
        } else {
            block()
        }
    }

    /**
     * 實際執行 UDP 傳送，必須在非主執行緒呼叫。
     */
    private fun sendSipInternal(msg: String): Boolean {
        val firstLine = msg.substringBefore("\r\n")
        val sock = socket
        if (sock == null || sock.isClosed) {
            Log.e(TAG, "sendSip FAILED [socket ${if (sock == null) "null" else "closed"}]: $firstLine")
            return false
        }
        return try {
            val bytes = msg.toByteArray(Charsets.UTF_8)
            sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(serverIp), serverPort))
            Log.d(TAG, "Sent ${bytes.size}B → $serverIp:$serverPort: $firstLine")
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendSip FAILED [${e.javaClass.simpleName}: ${e.message}]: $firstLine")
            false
        }
    }

    // ───────── 200 OK 重傳（RFC 3261 §13.3.1.4）─────────

    /** 開始 200 OK 重傳計時器，直到收到 ACK 為止 */
    private fun startOkRetransmission(ok200: String, callId: String) {
        answeredCallId = callId
        retransmitOk(ok200, callId, 500L, attempt = 1)
    }

    /**
     * 重傳排程在 sipSendThread，確保 sendSipInternal() 在背景執行緒呼叫。
     * 指數退避：500ms → 1s → 2s → 4s → 4s → … 最多 12 次（~39 秒）
     */
    private fun retransmitOk(ok200: String, callId: String, delayMs: Long, attempt: Int) {
        if (attempt > 12) {
            Log.w(TAG, "200 OK retransmit: max attempts reached, giving up")
            return
        }
        sipSendHandler.postDelayed({
            if (answeredCallId == callId) {
                Log.i(TAG, "Retransmitting 200 OK (attempt $attempt, after ${delayMs}ms)")
                sendSipInternal(ok200)
                retransmitOk(ok200, callId, minOf(delayMs * 2, 4000L), attempt + 1)
            }
        }, delayMs)
    }

    /** 停止 200 OK 重傳（收到 ACK / CANCEL / BYE 時呼叫）*/
    private fun stopOkRetransmission() {
        if (answeredCallId != null) {
            Log.i(TAG, "Stopping 200 OK retransmission")
            answeredCallId = null
            // 取消 sipSendHandler 中所有待執行的重傳任務
            // 注意：這同時也會取消正在排隊的 sendSip() 任務，
            // 所以只在 stop() / 通話結束時呼叫
            // 此處不 removeCallbacksAndMessages，依賴 answeredCallId == null 判斷停止
        }
    }

    /**
     * 解析 REGISTER 200 OK 中伺服器協商後的 Expires 值。
     * 優先讀 Contact 標頭的 expires= 參數，其次讀 Expires: 標頭。
     * 若解析失敗，回傳預設值 3600。
     */
    private fun parseNegotiatedExpires(msg: String): Int {
        // 1. Contact 標頭的 expires= 參數（Asterisk 常用此方式）
        val contactLine = msg.lines().find { it.startsWith("Contact:", ignoreCase = true) }
        if (contactLine != null) {
            val m = Regex("""expires=(\d+)""", RegexOption.IGNORE_CASE).find(contactLine)
            if (m != null) return m.groupValues[1].toIntOrNull() ?: 3600
        }
        // 2. 獨立的 Expires: 標頭
        val expiresLine = msg.lines().find { it.startsWith("Expires:", ignoreCase = true) }
        if (expiresLine != null) {
            val v = expiresLine.substringAfter(":").trim().toIntOrNull()
            if (v != null && v > 0) return v
        }
        return 3600
    }

    private fun scheduleReRegistration(expiresSeconds: Int = 3600) {
        reregisterRunnable?.let { mainHandler.removeCallbacks(it) }
        // 在到期前 10% 提前重新登錄（例如 600 秒 → 540 秒後重新登錄）
        val intervalMs = (expiresSeconds * 0.9 * 1000).toLong().coerceAtLeast(30_000L)
        val r = Runnable {
            Log.i(TAG, "重新登錄（Expires=${expiresSeconds}s）...")
            // 通知 listener：正在重新登錄（讓 UI 顯示「連線中」）
            mainHandler.post { listener.onReregistering() }
            // 在 sipSendThread 執行 sendRegister()，避免 NetworkOnMainThreadException
            sipSendHandler.post {
                if (lastRealm != null && lastNonce != null)
                    sendRegister(lastRealm, lastNonce)
                else
                    sendRegister()
            }
        }
        reregisterRunnable = r
        mainHandler.postDelayed(r, intervalMs)
    }

    /**
     * 每 25 秒向 SIP 伺服器發一個 CRLF 封包（RFC 5626 §4.4.1 UDP keepalive）。
     * 作用：維持路由器 NAT 映射，讓 Asterisk 的 INVITE/OPTIONS 封包能持續抵達。
     * 不發送於通話中也沒影響，因為 RTP 封包已足以維持 NAT 映射。
     */
    private fun scheduleKeepalive() {
        keepaliveRunnable?.let { sipSendHandler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                if (!running.get()) return
                val sock = socket
                if (sock != null && !sock.isClosed) {
                    try {
                        val crlf = "\r\n".toByteArray(Charsets.UTF_8)
                        sock.send(DatagramPacket(crlf, crlf.size,
                            InetAddress.getByName(serverIp), serverPort))
                        Log.v(TAG, "Keepalive → $serverIp:$serverPort")
                    } catch (e: Exception) {
                        Log.w(TAG, "Keepalive failed: ${e.message}")
                    }
                }
                sipSendHandler.postDelayed(this, UDP_KEEPALIVE_INTERVAL)
            }
        }
        keepaliveRunnable = r
        sipSendHandler.postDelayed(r, UDP_KEEPALIVE_INTERVAL)
    }

    private fun buildSdp(ip: String, rtpPort: Int) = buildString {
        append("v=0\r\n")
        append("o=- 0 0 IN IP4 $ip\r\n")
        append("s=intercom\r\n")
        append("c=IN IP4 $ip\r\n")
        append("t=0 0\r\n")
        append("m=audio $rtpPort RTP/AVP 0 8\r\n")
        append("a=rtpmap:0 PCMU/8000\r\n")
        append("a=rtpmap:8 PCMA/8000\r\n")
        append("a=sendrecv\r\n")
    }

    private fun StringBuilder.appendVia(via: String) {
        if (via.contains("rport")) {
            // 已有 rport，直接使用
            append("Via: $via\r\n")
        } else {
            append("Via: $via\r\n")
        }
    }

    private fun hasCseq(msg: String, method: String) =
        msg.lines().any { it.startsWith("CSeq:") && it.contains(method) }

    private fun extractQuotedParam(header: String, name: String): String? =
        Regex("""$name="([^"]+)"""").find(header)?.groupValues?.get(1)

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun detectLocalIp(): String {
        // 方法 1：UDP connect 技巧（讓 OS 選正確路由介面，不實際送封包）
        try {
            val socket = DatagramSocket()
            socket.connect(InetAddress.getByName(serverIp), serverPort)
            val raw = socket.localAddress.hostAddress
            socket.close()
            val ip = raw?.removePrefix("/")
            if (!ip.isNullOrEmpty() && ip != "0.0.0.0") {
                Log.i(TAG, "detectLocalIp() via UDP trick = $ip")
                return ip
            }
        } catch (_: Exception) {}

        // 方法 2：尋找與 serverIp 同 /24 子網的介面（解決雙網卡：WiFi + 行動數據）
        val serverPrefix = serverIp.substringBeforeLast(".")   // e.g. "192.168.100"
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress?.removePrefix("/") ?: continue
                        if (ip.startsWith(serverPrefix)) {
                            Log.i(TAG, "detectLocalIp() via subnet match = $ip")
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 方法 3：任意非 loopback IPv4（最後手段）
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress?.removePrefix("/") ?: continue
                        Log.i(TAG, "detectLocalIp() via fallback = $ip")
                        return ip
                    }
                }
            }
        } catch (_: Exception) {}

        return "127.0.0.1"
    }

    private fun randomHex(len: Int = 8) =
        (0 until len).map { "0123456789abcdef"[(Math.random() * 16).toInt()] }.joinToString("")
}
