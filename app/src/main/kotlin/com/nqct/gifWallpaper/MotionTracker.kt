package com.nqct.gifWallpaper

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

// 传感器状态快照。全部不可变，每次更新整体替换引用。
//
// 【清理说明】原 accX/accY/accZ/gx/gy/gz/rollDeg/pitchDeg 均无外部消费者
// （Service 只读 orientation 和 gForce），已收敛为 MotionTracker 私有字段。
data class MotionState(
    val gForce: Float = 0f,
    val orientation: DeviceOrientation = DeviceOrientation.UNKNOWN,
    val postureText: String = DeviceOrientation.UNKNOWN.label,
    val lastShakeElapsedMs: Long = 0L,
    val lastShakeText: String = "无"
)

// 方向 + 摇一摇检测。注册时机由外部控制。
class MotionTracker(
    initialShakeG: Float = 2.4f,
    private val lowPassAlpha: Float = 0.8f,
    private val stableCount: Int = 2
) {

    @Volatile
    var state: MotionState = MotionState()
        private set

    @Volatile
    var shakeThresholdG: Float = initialShakeG
        private set

    @Volatile
    private var shakeCooldownMs: Long = 1200L

    var onOrientationChanged: ((DeviceOrientation) -> Unit)? = null

    // 方向防抖
    private var candidateOrientation: DeviceOrientation = DeviceOrientation.UNKNOWN
    private var candidateCount: Int = 0
    private var confirmedOrientation: DeviceOrientation = DeviceOrientation.UNKNOWN

    // 低通滤波状态
    private var gx = 0f
    private var gy = 0f
    private var gz = 1f
    private var gravitySensorAvailable = false

    // 摇一摇
    private var lastShakeElapsed = 0L
    private var lastShakeText = "无"
    private var lastGForce = 0f

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun setShakeThreshold(g: Float) {
        shakeThresholdG = g.coerceIn(1.5f, 4.0f)
    }

    fun setShakeCooldown(ms: Long) {
        shakeCooldownMs = ms.coerceIn(300L, 5000L)
    }

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
        updateOrientation()
        rebuildState()
    }

    private fun onAccelerometer(event: SensorEvent) {
        val x = event.values[0] / SensorManager.GRAVITY_EARTH
        val y = event.values[1] / SensorManager.GRAVITY_EARTH
        val z = event.values[2] / SensorManager.GRAVITY_EARTH
        val g = sqrt(x * x + y * y + z * z)
        lastGForce = g

        // 无 TYPE_GRAVITY 时用加速度计低通兜底
        if (!gravitySensorAvailable) {
            gx = lowPassAlpha * gx + (1 - lowPassAlpha) * x
            gy = lowPassAlpha * gy + (1 - lowPassAlpha) * y
            gz = lowPassAlpha * gz + (1 - lowPassAlpha) * z
            updateOrientation()
        }

        val now = SystemClock.elapsedRealtime()
        if (g > shakeThresholdG && now - lastShakeElapsed > shakeCooldownMs) {
            lastShakeElapsed = now
            lastShakeText = timeFormat.format(Date())
        }

        rebuildState()
    }

    private fun updateOrientation() {
        val candidate = computeOrientation(gx, gy, gz)
        if (candidate == candidateOrientation) {
            candidateCount++
        } else {
            candidateOrientation = candidate
            candidateCount = 1
        }
        if (candidateCount >= stableCount && candidate != confirmedOrientation) {
            val previous = confirmedOrientation
            confirmedOrientation = candidate
            if (candidate != DeviceOrientation.UNKNOWN && previous != candidate) {
                onOrientationChanged?.invoke(candidate)
            }
        }
    }

    private fun computeOrientation(gx: Float, gy: Float, gz: Float): DeviceOrientation {
        if (abs(gz) > 0.7f) {
            return if (gz > 0) DeviceOrientation.SCREEN_UP else DeviceOrientation.SCREEN_DOWN
        }
        if (abs(gy) > 0.7f) {
            return if (gy > 0) DeviceOrientation.PORTRAIT else DeviceOrientation.PORTRAIT_REVERSED
        }
        if (abs(gx) > 0.7f) {
            return if (gx > 0) DeviceOrientation.LANDSCAPE_LEFT else DeviceOrientation.LANDSCAPE_RIGHT
        }
        return DeviceOrientation.UNKNOWN
    }

    private fun rebuildState() {
        state = MotionState(
            gForce = lastGForce,
            orientation = confirmedOrientation,
            postureText = confirmedOrientation.label,
            lastShakeElapsedMs = lastShakeElapsed,
            lastShakeText = lastShakeText
        )
    }

    fun isShaking(now: Long = SystemClock.elapsedRealtime()): Boolean {
        return lastShakeElapsed > 0L && now - lastShakeElapsed < shakeCooldownMs
    }
}