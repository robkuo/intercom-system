package com.intercom.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * MJPEG 串流顯示 View
 * 解析 multipart/x-mixed-replace 格式，逐幀顯示 JPEG
 */
class MjpegView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "MjpegView"
        private const val BOUNDARY = "--frame"
        private const val CONTENT_TYPE_HEADER = "Content-Type: image/jpeg"
    }

    private var currentBitmap: Bitmap? = null
    private var streamThread: Thread? = null
    private var isStreaming = false
    private var streamUrl: String? = null

    private val errorPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun startStream(url: String) {
        if (isStreaming && streamUrl == url) return
        stopStream()
        streamUrl = url
        isStreaming = true
        streamThread = Thread { readMjpegStream(url) }.also { it.start() }
        Log.d(TAG, "Stream started: $url")
    }

    fun stopStream() {
        isStreaming = false
        streamThread?.interrupt()
        streamThread = null
        streamUrl = null
    }

    private fun readMjpegStream(url: String) {
        var retryCount = 0
        while (isStreaming && retryCount < 10) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP error: ${response.code}")
                    Thread.sleep(2000)
                    retryCount++
                    continue
                }

                retryCount = 0
                val inputStream = response.body?.byteStream() ?: continue
                parseMjpeg(inputStream)
                inputStream.close()
                response.close()

            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!isStreaming) break
                Log.w(TAG, "Stream error (retry $retryCount): ${e.message}")
                Thread.sleep(2000)
                retryCount++
            }
        }
        Log.d(TAG, "Stream thread ended")
    }

    private fun parseMjpeg(inputStream: InputStream) {
        val buffer = ByteArray(65536)
        val frameBuffer = mutableListOf<Byte>()
        var inFrame = false

        // 簡單的 MJPEG 解析：找 JPEG SOI (0xFF 0xD8) 到 EOI (0xFF 0xD9)
        var prevByte: Int = -1

        while (isStreaming) {
            val b = inputStream.read()
            if (b == -1) break

            if (!inFrame) {
                // 等待 JPEG SOI
                if (prevByte == 0xFF && b == 0xD8) {
                    inFrame = true
                    frameBuffer.clear()
                    frameBuffer.add(0xFF.toByte())
                    frameBuffer.add(0xD8.toByte())
                }
            } else {
                frameBuffer.add(b.toByte())
                // 等待 JPEG EOI
                if (prevByte == 0xFF && b == 0xD9) {
                    inFrame = false
                    val jpegBytes = frameBuffer.toByteArray()
                    frameBuffer.clear()
                    decodeAndDisplay(jpegBytes)
                }
            }
            prevByte = b
        }
    }

    private fun decodeAndDisplay(jpegBytes: ByteArray) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return
            val old = currentBitmap
            currentBitmap = bitmap
            old?.recycle()
            postInvalidate()
        } catch (e: Exception) {
            Log.w(TAG, "Decode error: ${e.message}")
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = currentBitmap
        if (bitmap != null && !bitmap.isRecycled) {
            // 維持比例縮放填滿視圖
            val viewRatio = width.toFloat() / height.toFloat()
            val bmpRatio = bitmap.width.toFloat() / bitmap.height.toFloat()

            val drawWidth: Float
            val drawHeight: Float
            val offsetX: Float
            val offsetY: Float

            if (bmpRatio > viewRatio) {
                drawWidth = width.toFloat()
                drawHeight = width / bmpRatio
                offsetX = 0f
                offsetY = (height - drawHeight) / 2f
            } else {
                drawHeight = height.toFloat()
                drawWidth = height * bmpRatio
                offsetX = (width - drawWidth) / 2f
                offsetY = 0f
            }

            val dst = android.graphics.RectF(offsetX, offsetY, offsetX + drawWidth, offsetY + drawHeight)
            canvas.drawBitmap(bitmap, null, dst, null)
        } else {
            // 顯示等待畫面
            canvas.drawColor(Color.BLACK)
            canvas.drawText("連接攝像頭中...", width / 2f, height / 2f, errorPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopStream()
        currentBitmap?.recycle()
        currentBitmap = null
    }
}
