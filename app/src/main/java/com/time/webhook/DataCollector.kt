package com.time.webhook

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import org.json.JSONObject
import java.io.RandomAccessFile
import kotlin.math.sqrt

class DataCollector(private val context: Context) {
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var currentAcceleration = 0f
    private var currentLight = 0f

    init {
        initSensors()
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

    fun collectData(): JSONObject {
        return JSONObject().apply {
            put("battery_level", getBatteryLevel())
            put("battery_temperature", getBatteryTemperature())
            put("acceleration", String.format("%.2f", currentAcceleration))
            put("light_level", String.format("%.1f", currentLight))
            put("memory_usage", getMemoryUsage())
            put("cpu_usage", String.format("%.1f", getCpuUsage()))
            put("timestamp", System.currentTimeMillis())
        }
    }

    private fun getBatteryLevel(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level != -1 && scale != -1) {
                (level * 100) / scale
            } else {
                0
            }
        } ?: 0
    }

    private fun getBatteryTemperature(): Float {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return temp / 10.0f
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
}