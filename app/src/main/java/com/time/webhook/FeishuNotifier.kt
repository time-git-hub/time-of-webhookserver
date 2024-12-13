package com.time.webhook

import android.os.Build
import android.util.Log
import okhttp3.*
import java.io.IOException

class FeishuNotifier(
    private val webhookUrl: String,
    private val serverPort: Int
){
    private val client = OkHttpClient()
    private val JSON = MediaType.parse("application/json; charset=utf-8")
    private val deviceName = getDeviceName() // 获取设备名称

    private fun getDeviceName(): String {
        return try {
            val manufacturer = Build.MANUFACTURER
            val model = Build.MODEL
            if (model.startsWith(manufacturer, ignoreCase = true)) {
                model
            } else {
                "$manufacturer $model"
            }
        } catch (e: Exception) {
            "未知设备"
        }
    }
    fun sendIpUpdate(ipv4: String, ipv6: String) {
        val content = """
        {
            "msg_type": "post",
            "content": {
                "post": {
                    "zh_cn": {
                        "title": "$deviceName",
                        "content": [
                            [{
                                "tag": "text",
                                "text": "设备IP已更新\n"
                            }],
                            [{
                                "tag": "text",
                                "text": "IPv4: https://$ipv4:$serverPort\n"
                            }],
                            [{
                                "tag": "text",
                                "text": "IPv6: https://[$ipv6]:$serverPort\n"
                            }]
                        ]
                    }
                }
            }
        }
        """.trimIndent()

        sendRequest(content)
    }

    private fun sendRequest(content: String) {
        val requestBody = RequestBody.create(JSON, content)
        val request = Request.Builder()
            .url(webhookUrl)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("FeishuNotifier", "发送失败", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }
}