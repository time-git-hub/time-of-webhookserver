package com.time.webhook

import android.content.Context
import android.content.SharedPreferences

class Config private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    var webhookUrl: String?
        get() = prefs.getString("webhook_url", null)
        set(value) = prefs.edit().putString("webhook_url", value).apply()

    var username: String?
        get() = prefs.getString("username", null)
        set(value) = prefs.edit().putString("username", value).apply()

    var password: String?
        get() = prefs.getString("password", null)
        set(value) = prefs.edit().putString("password", value).apply()

    var serverPort: Int
        get() = prefs.getInt("server_port", 8443)
        set(value) = prefs.edit().putInt("server_port", value).apply()

    fun isConfigured(): Boolean {
        return  !username.isNullOrEmpty() &&
                !password.isNullOrEmpty()
    }
    fun clear() {
        prefs.edit().clear().apply()
    }
    companion object {
        @Volatile
        private var instance: Config? = null

        fun getInstance(context: Context): Config {
            return instance ?: synchronized(this) {
                instance ?: Config(context.applicationContext).also { instance = it }
            }
        }
    }
}