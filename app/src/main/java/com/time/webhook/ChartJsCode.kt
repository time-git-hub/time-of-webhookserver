package com.time.webhook
import android.content.Context

object ChartJsCode {
    fun getCode(context: Context): String {
        return context.assets.open("chart.js").bufferedReader().use { it.readText() }
    }
}