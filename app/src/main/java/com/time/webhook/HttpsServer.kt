package com.time.webhook

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.RandomAccessFile
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.math.sqrt
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.security.cert.CertificateFactory
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.x509.X509V3CertificateGenerator
import java.security.SecureRandom
import java.security.Security
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLSocket
import com.time.webhook.ChartJsCode
class HttpsServer(private val context: Context, port: Int) : NanoHTTPD(port) {
    private val config = Config.getInstance(context)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val connectionPool = mutableMapOf<String, SSLSocket>()
    private val poolLock = ReentrantLock()
    private var currentAcceleration = 0f
    private var currentLight = 0f
    @Volatile
    private var _isServerRunning = false
    override fun start() {
        super.start()
        _isServerRunning = true
        Log.i("HttpsServer", "HTTPS服务已启动")
    }

    // 添加 stop 方法
    override fun stop() {
        super.stop()
        _isServerRunning = false
        Log.i("HttpsServer", "HTTPS服务已停止")
    }

    fun isHttpsServerRunning(): Boolean {
        val status = _isServerRunning && isAlive
        Log.i("HttpsServer", "HTTPS服务状态检查: $status")
        return status
    }
    private fun getBatteryInfo(): Intent? {
        return context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    // 获取电池电量
    private fun getBatteryLevel(): Int {
        return try {
            val intent = getBatteryInfo()
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level != -1 && scale != -1) {
                    (level * 100) / scale
                } else {
                    0
                }
            } ?: 0
        } catch (e: Exception) {
            Log.e("HttpsServer", "获取电池电量失败", e)
            0
        }
    }

    // 获取电池温度
    private fun getBatteryTemperature(): Float {
        return try {
            val intent = getBatteryInfo()
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            temp / 10.0f
        } catch (e: Exception) {
            Log.e("HttpsServer", "获取电池温度失败", e)
            0f
        }
    }

    companion object {
        private const val KEY_ALIAS = "time"
        private const val KEY_PASSWORD = "aaaa1234"
    }

    init {
        try {
            val sslContext = createSSLContext()
            makeSecure(sslContext.serverSocketFactory, null)
        } catch (e: Exception) {
            Log.e("HttpsServer", "HTTPS服务器初始化失败", e)
            throw RuntimeException("HTTPS服务器初始化失败: ${e.message}", e)
        }



        initSensors()
    }
    private fun createSSLContext(): SSLContext {
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())

        return SSLContext.getInstance("TLS").apply {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048)
            }.generateKeyPair()

            val cert = generateCertificate(keyPair)

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setKeyEntry(
                    KEY_ALIAS,
                    keyPair.private,
                    KEY_PASSWORD.toCharArray(),
                    arrayOf(cert)
                )
            }

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, KEY_PASSWORD.toCharArray())
            }

            init(kmf.keyManagers, null, SecureRandom())
        }
    }

    private fun generateCertificate(keyPair: KeyPair): X509Certificate {
        val certGen = org.bouncycastle.x509.X509V3CertificateGenerator()
        val dnName = X500Principal("CN=time")
        val startDate = Date()
        val endDate = Date(startDate.time + 365L * 24 * 60 * 60 * 1000)

        certGen.setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
        certGen.setSubjectDN(dnName)
        certGen.setIssuerDN(dnName)
        certGen.setNotBefore(startDate)
        certGen.setNotAfter(endDate)
        certGen.setPublicKey(keyPair.public)
        certGen.setSignatureAlgorithm("SHA256WithRSAEncryption")

        return certGen.generate(keyPair.private)
    }

    private fun initSensors() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        val x = event.values[0]
                        val y = event.values[1]
                        val z = event.values[2]
                        currentAcceleration = sqrt(x * x + y * y + z * z)
                    }
                    Sensor.TYPE_LIGHT -> {
                        currentLight = event.values[0]
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        accelerometer?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        lightSensor?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        try {
            val auth = session.headers["authorization"]
            if (!checkAuth(auth)) {
                return createAuthResponse()
            }

            val response = when (session.uri) {
                "/" -> serveHtml()
                "/data" -> serveJsonData()
                else -> notFound()
            }

            // 添加响应头
            response.apply {
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Keep-Alive", "timeout=15, max=100")
                addHeader("Connection", "keep-alive")
                addHeader("Cache-Control", "no-cache")
                addHeader("Content-Security-Policy", "upgrade-insecure-requests")
            }

            return response
        } catch (e: Exception) {
            Log.e("HttpsServer", "处理请求失败", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "服务器内部错误"
            )
        }
    }

    private fun checkAuth(auth: String?): Boolean {
        if (auth == null) return false
        if (!auth.startsWith("Basic ")) return false

        val credentials = String(Base64.decode(auth.substring(6), Base64.DEFAULT))
        val parts = credentials.split(":")
        return parts.size == 2 &&
                parts[0] == config.username &&
                parts[1] == config.password
    }

    private fun createAuthResponse(): Response {
        val response = newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_HTML, "")
        response.addHeader("WWW-Authenticate", "Basic realm=\"Device Monitor\"")
        return response
    }

    private fun serveJsonData(): Response {
        return try {
            val data = JSONObject().apply {
                put("battery_level", getBatteryLevel())
                put("battery_temperature", getBatteryTemperature().toString())
                put("acceleration", String.format("%.2f", currentAcceleration))
                put("light_level", String.format("%.1f", currentLight))
                put("memory_usage", getMemoryUsage())
                put("cpu_usage", String.format("%.1f", getCpuUsage()))
                put("timestamp", System.currentTimeMillis())
            }

            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                data.toString()
            )
        } catch (e: Exception) {
            Log.e("HttpsServer", "生成JSON数据失败", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "数据生成失败"
            )
        }
    }




    private fun getMemoryUsage(): JSONObject {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return JSONObject().apply {
            put("total", memInfo.totalMem)
            put("available", memInfo.availMem)
            put("used", memInfo.totalMem - memInfo.availMem)
            put("percentage", ((memInfo.totalMem - memInfo.availMem) * 100.0 / memInfo.totalMem))
        }
    }

    private fun getCpuUsage(): Double {
        try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val load = reader.readLine().split(" +".toRegex()).dropWhile { it.isEmpty() }
            reader.close()

            val totalCpu = load[1].toLong() + load[2].toLong() + load[3].toLong() + load[4].toLong()
            val idleCpu = load[4].toLong()
            return ((totalCpu - idleCpu) * 100.0 / totalCpu)
        } catch (e: Exception) {
            return 0.0
        }
    }

    private fun serveHtml(): Response {
        val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>web server</title>
            <script>
            ${ChartJsCode.getCode(context)}
            </script>
            <style>
                body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    margin: 0;
                    padding: 20px;
                    background-color: #f5f5f5;
                    color: #333;
                    transition: background-color 0.3s;
                }
                .header {
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    position: relative;
                    padding: 20px;
                    margin-bottom: 30px;
                }
                .title {
                    font-size: 24px;
                    text-align: center;
                    transition: all 0.3s;
                }
                .battery-container {
                    position: absolute;
                    right: 20px;
                    top: 50%;
                    transform: translateY(-50%);
                    display: flex;
                    align-items: center;
                }
                .battery-icon {
                    width: 40px;
                    height: 20px;
                    border: 2px solid #333;
                    border-radius: 3px;
                    padding: 2px;
                    position: relative;
                }
                .battery-icon:after {
                    content: '';
                    position: absolute;
                    right: -5px;
                    top: 50%;
                    transform: translateY(-50%);
                    width: 3px;
                    height: 10px;
                    background: #333;
                    border-radius: 0 2px 2px 0;
                }
                .battery-level {
                    height: 100%;
                    background: #4CAF50;
                    border-radius: 1px;
                    transition: all 0.3s;
                }
                .battery-text {
                    margin-left: 10px;
                    font-size: 14px;
                }
                .charts-container {
                    display: grid;
                    grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
                    gap: 20px;
                    margin-top: 20px;
                }
                .chart-item {
                    background: white;
                    padding: 15px;
                    border-radius: 10px;
                    box-shadow: 0 2px 4px rgba(0,0,0,0.1);
                }
                #updateTime {
                    text-align: center;
                    margin-top: 20px;
                    color: #666;
                    font-size: 14px;
                }
            </style>
        </head>
        <body>
            <div class="header">
                <h1 class="title">状态监控</h1>
                <div class="battery-container">
                    <div class="battery-icon">
                        <div class="battery-level"></div>
                    </div>
                    <span class="battery-text">--</span>
                </div>
            </div>
            <div class="charts-container">
                <div class="chart-item">
                    <canvas id="tempChart"></canvas>
                </div>
                <div class="chart-item">
                    <canvas id="accelChart"></canvas>
                </div>
                <div class="chart-item">
                    <canvas id="cpuGauge"></canvas>
                </div>
                <div class="chart-item">
                    <canvas id="memoryGauge"></canvas>
                </div>
            </div>
            <div id="updateTime"></div>

            <script>
            // 初始化图表配置
            const commonLineOptions = {
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    y: {
                        beginAtZero: true
                    }
                },
                plugins: {
                    legend: {
                        position: 'top'
                    }
                }
            };

            const commonGaugeOptions = {
                responsive: true,
                maintainAspectRatio: false,
                cutout: '70%',
                plugins: {
                    legend: {
                        display: false
                    }
                }
            };

            // 初始化图表
            const tempChart = new Chart(document.getElementById('tempChart'), {
                type: 'line',
                data: {
                    labels: [],
                    datasets: [{
                        label: '电池温度 (°C)',
                        data: [],
                        borderColor: '#4CAF50',
                        tension: 0.4,
                        fill: false
                    }]
                },
                options: commonLineOptions
            });

            const accelChart = new Chart(document.getElementById('accelChart'), {
                type: 'line',
                data: {
                    labels: [],
                    datasets: [{
                        label: '加速度',
                        data: [],
                        borderColor: '#2196F3',
                        tension: 0.4,
                        fill: false
                    }]
                },
                options: commonLineOptions
            });

            const cpuGauge = new Chart(document.getElementById('cpuGauge'), {
                type: 'doughnut',
                data: {
                    labels: ['使用', '空闲'],
                    datasets: [{
                        data: [0, 100],
                        backgroundColor: ['#ff6b6b', '#e9ecef']
                    }]
                },
                options: {
                    ...commonGaugeOptions,
                    plugins: {
                        ...commonGaugeOptions.plugins,
                        title: {
                            display: true,
                            text: 'CPU使用率'
                        }
                    }
                }
            });

            const memoryGauge = new Chart(document.getElementById('memoryGauge'), {
                type: 'doughnut',
                data: {
                    labels: ['使用', '空闲'],
                    datasets: [{
                        data: [0, 100],
                        backgroundColor: ['#339af0', '#e9ecef']
                    }]
                },
                options: {
                    ...commonGaugeOptions,
                    plugins: {
                        ...commonGaugeOptions.plugins,
                        title: {
                            display: true,
                            text: '内存使用率'
                        }
                    }
                }
            });

            // 更新数据函数
            async function updateData() {
                try {
                    const response = await fetch('/data', {
                        headers: {
                            'Authorization': 'Basic ' + btoa('${Config.getInstance(context).username}:${Config.getInstance(context).password}')
                        }
                    });
                    
                    if (!response.ok) {
                        throw new Error('Network response was not ok: ' + response.status);
                    }
                    
                    const data = await response.json();
                    console.log('Received data:', data);

                    // 更新电池图标
                    const batteryLevel = data.battery_level;
                    document.querySelector('.battery-level').style.width = batteryLevel + '%';
                    document.querySelector('.battery-text').textContent = batteryLevel + '%';
                    document.querySelector('.battery-level').style.background = 
                        batteryLevel > 20 ? '#4CAF50' : '#ff6b6b';

                    // 更新图表
                    const now = new Date().toLocaleTimeString();
                    
                    // 温度图表
                    if (tempChart.data.labels.length > 10) {
                        tempChart.data.labels.shift();
                        tempChart.data.datasets[0].data.shift();
                    }
                    tempChart.data.labels.push(now);
                    tempChart.data.datasets[0].data.push(parseFloat(data.battery_temperature));
                    tempChart.update();

                    // 加速度图表
                    if (accelChart.data.labels.length > 10) {
                        accelChart.data.labels.shift();
                        accelChart.data.datasets[0].data.shift();
                    }
                    accelChart.data.labels.push(now);
                    accelChart.data.datasets[0].data.push(parseFloat(data.acceleration));
                    accelChart.update();

                    // CPU使用率
                    const cpuUsage = parseFloat(data.cpu_usage);
                    cpuGauge.data.datasets[0].data = [cpuUsage, 100 - cpuUsage];
                    cpuGauge.update();

                    // 内存使用率
                    const memoryUsage = data.memory_usage.percentage;
                    memoryGauge.data.datasets[0].data = [memoryUsage, 100 - memoryUsage];
                    memoryGauge.update();

                    // 根据光照强度调整标题
                    const lightLevel = parseFloat(data.light_level);
                    const brightness = Math.min(100, lightLevel / 10);
                    const hue = Math.min(60, lightLevel / 50);
                    document.querySelector('.title').style.color = 
                        `hsl(${'$'}{hue}, 70%, ${'$'}{Math.max(20, brightness)}%)`;

                    document.getElementById('updateTime').textContent = 
                        '最后更新: ' + new Date().toLocaleString();
                } catch (error) {
                    console.error('更新失败:', error);
                }
            }

            // 设置图表容器高度
            document.querySelectorAll('.chart-item').forEach(item => {
                item.style.height = '300px';
            });

            // 每秒更新一次数据
            setInterval(updateData, 1000);
            updateData(); // 初始加载
            </script>
        </body>
        </html>
    """.trimIndent()

        return newFixedLengthResponse(html)
    }

    private fun notFound(): Response {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_HTML, "404 Not Found")
    }
}