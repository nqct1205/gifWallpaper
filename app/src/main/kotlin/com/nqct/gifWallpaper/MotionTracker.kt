package com.nqct.gifWallpaper

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 传感器状态快照，供渲染线程读取。全部不可变，每次更新整体替换引用。
 */
data class MotionState(
    val accX: Float = 0f,
    val accY: Float = 0f,
    val accZ: Float = 0f,
    val gForce: Float = 0f,
    val gx: Float = 0f,
    val gy: Float = 0f,
    val gz: Float = 1f,
    val rollDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val postureText: String = "未知",
    /** 最近一次摇一摇触发的 elapsedRealtime，0 表示从未触发 */
    val lastShakeElapsedMs: Long = 0L,
    val lastShakeText: String = "无"
)

/**
 * 方向 + 摇一摇检测。
 *
 * 【低功耗策略】
 *  - 方向判断优先使用 [Sensor.TYPE_GRAVITY]（非唤醒传感器，低功耗，且已滤波）。
 *    外部以 SENSOR_DELAY_NORMAL（约 200ms）注册即可。
 *  - 摇一摇必须用 [Sensor.TYPE_ACCELEROMETER]（需要原始高频数据），
 *    外部仅在“存在摇一摇事件规则”且“壁纸可见”时注册。
 *  - 若设备无 TYPE_GRAVITY，自动退化为用加速度计 + 一阶低通滤波判断方向。
 *
 * 【坐标系】event.values 除以 GRAVITY_EARTH 后：
 *  - 静止平放屏幕朝上时 gz ≈ +1（设备受重力反向，Z 轴读正）。
 *  - 屏幕朝下时 gz ≈ -1。
 */
class MotionTracker(
    private val shakeG: Float = 2.4f,
    private val shakeCooldownMs: Long = 1200L,
    private val lowPassAlpha: Float = 0.8f
) {

    @Volatile
    var state: MotionState = MotionState()
        private set

    private var gx = 0f
    private var gy = 0f
    private var gz = 1f
    private var gravitySensorAvailable = false

    private var lastAccX = 0f
    private var lastAccY = 0f
    private var lastAccZ = 0f
    private var lastGForce = 0f

    private var lastShakeElapsed = 0L
    private var lastShakeText = "无"

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 统一入口：根据 sensor.type 分发。 */
    fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> onGravity(event)
            Sensor.TYPE_ACCELEROMETER -> onAccelerometer(event)
        }
    }

    private fun onGravity(event: SensorEvent) {
        gravitySensorAvailable = true
        gx = event.values[0] / SensorManager.GRAVITY_EARTH
        gy = event.values[1] / SensorManager.GRAVITY_EARTH
        gz = event.values[2] / SensorManager.GRAVITY_EARTH
        rebuildState()
    }

    private fun onAccelerometer(event: SensorEvent) {
        val x = event.values[0] / SensorManager.GRAVITY_EARTH
        val y = event.values[1] / SensorManager.GRAVITY_EARTH
        val z = event.values[2] / SensorManager.GRAVITY_EARTH
        val g = sqrt(x * x + y * y + z * z)

        lastAccX = x
        lastAccY = y
        lastAccZ = z
        lastGForce = g

        // 无 TYPE_GRAVITY 时用加速度计低通兜底
        if (!gravitySensorAvailable) {
            gx = lowPassAlpha * gx + (1 - lowPassAlpha) * x
            gy = lowPassAlpha * gy + (1 - lowPassAlpha) * y
            gz = lowPassAlpha * gz + (1 - lowPassAlpha) * z
        }

        // 摇一摇检测
        val now = SystemClock.elapsedRealtime()
        if (g > shakeG && now - lastShakeElapsed > shakeCooldownMs) {
            lastShakeElapsed = now
            lastShakeText = timeFormat.format(Date())
        }

        rebuildState()
    }

    private fun rebuildState() {
        val roll = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
        val pitch = Math.toDegrees(
            atan2(-gz.toDouble(), sqrt((gx * gx + gy * gy).toDouble()))
        ).toFloat()
        state = MotionState(
            accX = lastAccX, accY = lastAccY, accZ = lastAccZ, gForce = lastGForce,
            gx = gx, gy = gy, gz = gz,
            rollDeg = roll, pitchDeg = pitch,
            postureText = postureName(gz, roll),
            lastShakeElapsedMs = lastShakeElapsed,
            lastShakeText = lastShakeText
        )
    }

    /** 当前是否处于摇一摇“已触发且未过冷却”状态。 */
    fun isShaking(now: Long = SystemClock.elapsedRealtime()): Boolean {
        return lastShakeElapsed > 0L && now - lastShakeElapsed < shakeCooldownMs
    }

    private fun postureName(gz: Float, roll: Float): String = when {
        abs(gz) > 0.7f -> if (gz > 0) "平放(屏朝上)" else "平放(屏朝下)"
        abs(roll) < 45 -> "竖握"
        roll in 45f..135f -> "横握(左转90°)"
        roll in -135f..-45f -> "横握(右转90°)"
        else -> "倒握"
    }
}