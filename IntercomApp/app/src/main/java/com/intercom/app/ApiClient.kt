package com.intercom.app

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val TAG = "ApiClient"
    private const val DOOR_API_TOKEN = "intercom-door-2024"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 開門（Token 認證）
     */
    fun unlockDoor(serverIp: String, callback: (Boolean, String) -> Unit) {
        val url = "http://$serverIp:5000/api/door/unlock/token"
        val json = JSONObject().apply {
            put("token", DOOR_API_TOKEN)
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "開門 API 失敗: ${e.message}")
                callback(false, "網路錯誤: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body?.string() ?: ""
                try {
                    val jsonResp = JSONObject(bodyStr)
                    val success = jsonResp.optBoolean("success", false)
                    val msg = jsonResp.optString("message", jsonResp.optString("error", ""))
                    Log.i(TAG, "開門回應: success=$success, msg=$msg")
                    callback(success, msg)
                } catch (e: Exception) {
                    callback(false, "回應解析錯誤")
                }
            }
        })
    }

    /**
     * 從 Server 取得分機對應的公司名稱（無需登入）。
     * 成功時 callback 傳回公司名稱字串；失敗或找不到時傳回 null（呼叫端改用分機號顯示）。
     */
    fun fetchCompanyName(serverIp: String, extension: String, callback: (String?) -> Unit) {
        val url = "http://$serverIp:5000/api/extension/company-name?ext=$extension"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "fetchCompanyName 失敗: ${e.message}")
                callback(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val name = try {
                    val n = JSONObject(response.body?.string() ?: "").optString("name")
                    // Server fallback 會回傳分機號本身，視為「未找到公司名」
                    if (n.isNotEmpty() && n != extension) n else null
                } catch (e: Exception) { null }
                Log.i(TAG, "fetchCompanyName: ext=$extension → name=$name")
                callback(name)
            }
        })
    }
}
