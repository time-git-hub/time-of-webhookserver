package com.time.webhook

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import java.net.NetworkInterface

class SetupActivity : AppCompatActivity() {
    private lateinit var config: Config
    private var isConfigured = false

    private lateinit var webhookInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var portInput: EditText
    private lateinit var saveButton: Button
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        initializeViews()

        config = Config.getInstance(this)
        isConfigured = config.isConfigured()

        if (isConfigured) {
            if (!isServiceRunning(MonitorService::class.java)) {
                startMonitorService()
            }
            invalidateOptionsMenu()
            showRunningStatus()
        } else {
            setupUI()
        }
    }

    private fun initializeViews() {
        container = findViewById(R.id.container)
        webhookInput = findViewById(R.id.feishuWebhookInput)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        portInput = findViewById(R.id.portInput)
        saveButton = findViewById(R.id.saveButton)
    }

    private fun requestBatteryOptimizationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent()
            val packageName = packageName
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }

    private fun showRunningStatus() {
        container.removeAllViews()
        val statusView = TextView(this).apply {

                val serviceRunning = isServiceRunning(MonitorService::class.java)
                val httpsRunning = MonitorService.isHttpsServerRunning()

                text = buildString {
                    append("服务状态: ${if (serviceRunning) "运行中" else "未运行"}\n")

                }

                textSize = 14f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 16.dpToPx(this@SetupActivity)
                }
            }
        container.addView(statusView)

        val ipView = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dpToPx(this@SetupActivity)
            }
        }
        container.addView(ipView)

        // 添加端口显示
        val portView = TextView(this).apply {
            text = "端口：${config.serverPort}"
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dpToPx(this@SetupActivity)
            }
        }
        container.addView(portView)

        // 隐藏输入框和保存按钮
        webhookInput.isVisible = false
        usernameInput.isVisible = false
        passwordInput.isVisible = false
        portInput.isVisible = false
        saveButton.isVisible = false

        updateIpAddress(ipView)
    }

    private fun updateIpAddress(ipView: TextView) {
        Thread {
            try {
                val ipv4 = getIpAddress(false)
                val ipv6 = getIpAddress(true)

                runOnUiThread {
                    val ipText = StringBuilder().apply {
                        append("IPv4：").append(ipv4)
                        if (ipv6 != "::") {
                            append("\nIPv6：").append(ipv6)
                        }
                    }.toString()
                    ipView.text = ipText
                }
            } catch (e: Exception) {
                Log.e("SetupActivity", "获取IP地址失败", e)
                runOnUiThread {
                    ipView.text = "无法获取IP地址"
                }
            }
        }.start()
    }

    private fun getIpAddress(ipv6: Boolean): String {
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
                if (!networkInterface.isLoopback && networkInterface.isUp) {
                    networkInterface.inetAddresses.toList()
                        .filter { address ->
                            !address.isLoopbackAddress &&
                                    (ipv6 == address.hostAddress?.contains(':')) &&
                                    (!ipv6 || // IPv4 不需要额外过滤
                                            (!address.isLinkLocalAddress && // 过滤本地链路地址
                                                    !address.isSiteLocalAddress && // 过滤站点本地地址
                                                    !address.hostAddress.startsWith("fc") && // 过滤ULA地址
                                                    !address.hostAddress.startsWith("fd")))
                        }
                        .firstOrNull()?.hostAddress?.let { addr ->
                            // 对于IPv6地址，移除域ID
                            return if (ipv6 && addr.contains('%')) {
                                addr.substring(0, addr.indexOf('%'))
                            } else {
                                addr
                            }
                        }
                }
            }
        } catch (e: Exception) {
            Log.e("SetupActivity", "获取IP地址失败", e)
        }
        return if (ipv6) "::" else "0.0.0.0"
    }

    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }


    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun setupUI() {
        // 预填充已有配置
        config.webhookUrl?.let {
            webhookInput.setText(it)
        }
        config.username?.let {
            usernameInput.setText(it)
        }
        portInput.setText(config.serverPort.toString())

        setupSaveButton()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (isConfigured) {
            menuInflater.inflate(R.menu.setup_menu, menu)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reset -> {
                resetConfig()
                true
            }

            R.id.action_restart_service -> {
                restartService()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }
    private fun setupSaveButton() {
        saveButton.setOnClickListener {
            val webhook = webhookInput.text.toString()
            val username = usernameInput.text.toString()
            val password = passwordInput.text.toString()
            val port = portInput.text.toString().toIntOrNull() ?: 8443

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "请填写所有必填项", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 添加端口验证


            config.webhookUrl = webhook
            config.username = username
            config.password = password
            config.serverPort = port

            startMonitorService()
            isConfigured = true
            showRunningStatus()
            invalidateOptionsMenu()
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        }
    }
    private fun resetConfig() {
        // 先停止服务
        if (isServiceRunning(MonitorService::class.java)) {
            stopService(Intent(this, MonitorService::class.java))
        }

        // 清除配置
        config.clear()
        isConfigured = false

        // 清除所有视图
        container.removeAllViews()

        // 重新添加所有输入框和按钮到container
        container.addView(webhookInput)
        container.addView(usernameInput)
        container.addView(passwordInput)
        container.addView(portInput)
        container.addView(saveButton)

        // 显示所有输入框和按钮
        webhookInput.isVisible = true
        usernameInput.isVisible = true
        passwordInput.isVisible = true
        portInput.isVisible = true
        saveButton.isVisible = true

        // 清空输入框
        webhookInput.text.clear()
        usernameInput.text.clear()
        passwordInput.text.clear()
        portInput.setText("8443")
        setupSaveButton()

        invalidateOptionsMenu()


    }

    private fun restartService() {
        if (isServiceRunning(MonitorService::class.java)) {
            stopService(Intent(this, MonitorService::class.java))
        }
        startMonitorService()
        showRunningStatus()
        invalidateOptionsMenu()
        Toast.makeText(this, "服务已重启", Toast.LENGTH_SHORT).show()
    }

    private fun startMonitorService() {
        val serviceIntent = Intent(this, MonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}