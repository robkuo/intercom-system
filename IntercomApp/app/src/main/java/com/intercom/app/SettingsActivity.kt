package com.intercom.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {

    private val extensionPasswords = mapOf(
        "101" to "password101",
        "102" to "password102",
        "103" to "password103",
        "104" to "password104",
        "105" to "password105",
        "106" to "password106",
        "107" to "password107",
        "108" to "password108"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.title = "設定"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val etServerIp = findViewById<EditText>(R.id.etServerIp)
        val spinnerExtension = findViewById<Spinner>(R.id.spinnerExtension)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnSave = findViewById<Button>(R.id.btnSave)

        val prefs = getSharedPreferences("intercom_prefs", Context.MODE_PRIVATE)

        // 載入已儲存設定
        etServerIp.setText(prefs.getString("server_ip", "192.168.100.163"))

        // 分機 Spinner
        val extensions = extensionPasswords.keys.sorted()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, extensions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerExtension.adapter = adapter

        val savedExt = prefs.getString("extension", "101") ?: "101"
        spinnerExtension.setSelection(extensions.indexOf(savedExt).coerceAtLeast(0))
        etPassword.setText(prefs.getString("password", "password101"))

        // 選擇分機時自動填入預設密碼
        spinnerExtension.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                val ext = extensions[position]
                etPassword.setText(extensionPasswords[ext] ?: "")
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        btnSave.setOnClickListener {
            val serverIp = etServerIp.text.toString().trim()
            val extension = extensions[spinnerExtension.selectedItemPosition]
            val password = etPassword.text.toString().trim()

            if (serverIp.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "請填寫所有欄位", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit().apply {
                putString("server_ip", serverIp)
                putString("extension", extension)
                putString("password", password)
                apply()
            }

            Toast.makeText(this, "設定已儲存，重新連線中...", Toast.LENGTH_SHORT).show()

            // 重新登錄 SIP
            SipService.instance?.reloadSettings()

            finish()
        }

        // ── 版本顯示 ──
        val tvCurrentVersion = findViewById<TextView>(R.id.tvCurrentVersion)
        val pInfo = packageManager.getPackageInfo(packageName, 0)
        val currentCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pInfo.longVersionCode.toInt() else @Suppress("DEPRECATION") pInfo.versionCode
        tvCurrentVersion.text = "目前版本：${pInfo.versionName} ($currentCode)"

        val btnCheckUpdate = findViewById<Button>(R.id.btnCheckUpdate)
        val pbDownload     = findViewById<android.widget.ProgressBar>(R.id.pbDownload)
        val tvUpdateStatus = findViewById<TextView>(R.id.tvUpdateStatus)

        btnCheckUpdate.setOnClickListener {
            checkForUpdate(
                currentCode,
                prefs.getString("server_ip", "192.168.100.163")!!,
                btnCheckUpdate, pbDownload, tvUpdateStatus
            )
        }
    }

    private fun checkForUpdate(
        currentCode: Int,
        serverIp: String,
        btnUpdate: Button,
        pb: android.widget.ProgressBar,
        tvStatus: TextView
    ) {
        btnUpdate.isEnabled = false
        tvStatus.visibility = android.view.View.VISIBLE
        tvStatus.text = "正在檢查更新..."

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. 取得版本資訊
                val verResp = client.newCall(
                    Request.Builder()
                        .url("http://$serverIp:8888/version.json")
                        .build()
                ).execute()

                if (!verResp.isSuccessful) {
                    runOnUiThread {
                        tvStatus.text = "檢查失敗：無法連線至伺服器 (${verResp.code})"
                        btnUpdate.isEnabled = true
                    }
                    return@launch
                }

                val json = JSONObject(verResp.body!!.string())
                val remoteCode = json.getInt("versionCode")
                val remoteName = json.getString("versionName")

                if (remoteCode <= currentCode) {
                    runOnUiThread {
                        tvStatus.text = "已是最新版本 ($remoteName)"
                        btnUpdate.isEnabled = true
                    }
                    return@launch
                }

                // 2. 有新版本：下載 APK
                runOnUiThread {
                    tvStatus.text = "發現新版本 $remoteName，下載中..."
                    pb.visibility = android.view.View.VISIBLE
                    pb.progress = 0
                }

                val apkUrl = "http://$serverIp:8888/app-debug.apk"
                val apkFile = File(filesDir, "update.apk")
                val dlResp = client.newCall(
                    Request.Builder().url(apkUrl).build()
                ).execute()

                if (!dlResp.isSuccessful) {
                    runOnUiThread {
                        tvStatus.text = "下載失敗：${dlResp.code}"
                        pb.visibility = android.view.View.GONE
                        btnUpdate.isEnabled = true
                    }
                    return@launch
                }

                val total = dlResp.body!!.contentLength()
                var downloaded = 0L
                val buf = ByteArray(8192)
                apkFile.outputStream().use { fos ->
                    dlResp.body!!.byteStream().use { input ->
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            fos.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt()
                                runOnUiThread { pb.progress = pct }
                            }
                        }
                    }
                }

                // 3. 觸發安裝
                runOnUiThread {
                    pb.visibility = android.view.View.GONE
                    tvStatus.text = "下載完成，請按安裝"
                    btnUpdate.isEnabled = true

                    val uri = FileProvider.getUriForFile(
                        this@SettingsActivity,
                        "${packageName}.fileprovider",
                        apkFile
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }

            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "錯誤：${e.message}"
                    pb.visibility = android.view.View.GONE
                    btnUpdate.isEnabled = true
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
