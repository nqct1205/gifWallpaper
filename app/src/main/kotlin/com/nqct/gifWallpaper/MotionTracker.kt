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

// 传感器状态快照，供渲染线程读取。全部不可变，每次更新整体替换引用。
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
    // 已确认的方向（防抖后的稳定结果）
    val orientation: DeviceOrientation = DeviceOrientation.UNKNOWN,
    // 调试用文本：与 orientation.label 一致
    val postureText: String = DeviceOrientation.UNKNOWN.label,
    // 最近一次摇一摇触发时间（elapsedRealtime）；0 表示从未触发
    val lastShakeElapsedMs: Long = 0L,
    // 最近一次摇一摇触发的时刻文本（HH:mm:ss）
    val lastShakeText: String = "无"
)

// 方向 + 摇一摇检测。
//
// 低功耗策略（由外部控制注册时机，不在本类内部管理）：
//   - 方向：优先 Sensor.TYPE_GRAVITY（非唤醒、已滤波、低功耗）
//   - 摇一摇：Sensor.TYPE_ACCELEROMETER（需要原始高频数据）
//   - 无 TYPE_GRAVITY 时用加速度计 + 一阶低通兜底算方向
//
// 方向防抖：连续 stableCount 次读数一致才确认方向变化，
// 避免临界抖动导致方向频繁切换。
//
// 摇一摇阈值与冷却时间均可动态调整：
//   setShakeThreshold(g)   - 灵敏度
//   setShakeCooldown(ms)   - 冷却时长
class MotionTracker(
    initialShakeG: Float = 2.4f,
    private val lowPassAlpha: Float = 0.8f,
    private val stableCount: Int = 2
) {

    @Volatile
    var state: MotionState = MotionState()
        private set

    // 摇一摇阈值（g），可通过 setShakeThreshold 动态调整
    @Volatile
    var shakeThresholdG: Float = initialShakeG
        private set

    // 摇一摇冷却时间（ms），可通过 setShakeCooldown 动态调整
    @Volatile
    private var shakeCooldownMs: Long = 1200L

    // 方向变化回调（已防抖）。调用方负责切到主线程。
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

    // 加速度计原始值
    private var lastAccX = 0f
    private var lastAccY = 0f
    private var lastAccZ = 0f
    private var lastGForce = 0f

    // 摇一摇
    private var lastShakeElapsed = 0L
    private var lastShakeText = "无"

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // 动态调整摇一摇灵敏度。g 越小越灵敏；范围由 UI 层约束。
    fun setShakeThreshold(g: Float) {
        shakeThresholdG = g.coerceIn(1.5f, 4.0f)
    }

    // 动态调整摇一摇冷却时间。范围由 UI 层约束。
    fun setShakeCooldown(ms: Long) {
        shakeCooldownMs = ms.coerceIn(300L, 5000L)
    }

    // 统一入口：根据 sensor.type 分发。
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

        lastAccX = x
        lastAccY = y
        lastAccZ = z
        lastGForce = g

        // 无 TYPE_GRAVITY 时用加速度计低通兜底
        if (!gravitySensorAvailable) {
            gx = lowPassAlpha * gx + (1 - lowPassAlpha) * x
            gy = lowPassAlpha * gy + (1 - lowPassAlpha) * y
            gz = lowPassAlpha * gz + (1 - lowPassAlpha) * z
            updateOrientation()
        }

        // 摇一摇检测：g 超过阈值且冷却已过
        val now = SystemClock.elapsedRealtime()
        if (g > shakeThresholdG && now - lastShakeElapsed > shakeCooldownMs) {
            lastShakeElapsed = now
            lastShakeText = timeFormat.format(Date())
        }

        rebuildState()
    }

    // 计算 6 方向候选值，并做防抖确认。
    private fun updateOrientation() {
        val candidate = computeOrientation(gx, gy, gz)

        if (candidate == candidateOrientation) {
            candidateCount++
        } else {
            candidateOrientation = candidate
            candidateCount = 1
        }

        // 稳定 stableCount 次才确认
        if (candidateCount >= stableCount && candidate != confirmedOrientation) {
            val previous = confirmedOrientation
            confirmedOrientation = candidate
            // UNKNOWN 不视为有效方向变化
            if (candidate != DeviceOrientation.UNKNOWN && previous != candidate) {
                onOrientationChanged?.invoke(candidate)
            }
        }
    }

    // 基于重力分量判定 6 方向。
    private fun computeOrientation(gx: Float, gy: Float, gz: Float): DeviceOrientation {
        // 平放优先：|gz| > 0.7
        if (abs(gz) > 0.7f) {
            return if (gz > 0) DeviceOrientation.SCREEN_UP else DeviceOrientation.SCREEN_DOWN
        }
        // 竖持：|gy| > 0.7
        if (abs(gy) > 0.7f) {
            return if (gy > 0) DeviceOrientation.PORTRAIT else DeviceOrientation.PORTRAIT_REVERSED
        }
        // 横持：|gx| > 0.7
        if (abs(gx) > 0.7f) {
            return if (gx > 0) DeviceOrientation.LANDSCAPE_LEFT else DeviceOrientation.LANDSCAPE_RIGHT
        }
        return DeviceOrientation.UNKNOWN
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
            orientation = confirmedOrientation,
            postureText = confirmedOrientation.label,
            lastShakeElapsedMs = lastShakeElapsed,
            lastShakeText = lastShakeText
        )
    }

    // 当前是否处于摇一摇"已触发且未过冷却"状态。
    fun isShaking(now: Long = SystemClock.elapsedRealtime()): Boolean {
        return lastShakeElapsed > 0L && now - lastShakeElapsed < shakeCooldownMs
    }
}