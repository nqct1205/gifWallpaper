package com.nqct.gifWallpaper

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 环形日志缓冲（三类独立）。
//
// 【分类】
//   - 普通日志 (General)：状态切换、传感器注册/注销、配置加载、生命周期、用户操作
//   - 性能日志 (Perf)：帧耗时超限、内存超限、卡顿（帧间隔 >100ms）、状态切换耗时、重载耗时
//   - 错误日志 (Error)：异常、加载/导出/导入失败、重载失败
//
// 【用途】排查问题时分类查看，避免关键信息被普通日志淹没。
// 后续如需新增类别（如"传感器"、"网络"），可直接加新缓冲 + 新 dumpXxx + 导出按钮。
//
// 【线程】所有方法内部 synchronized 保护缓冲，跨线程安全。
// 【logcat】所有类别同步打到 logcat，TAG 统一 "GifWP"，性能日志带 [PERF] 前缀。
//
// 【调用约定】
//   - 失败事件（异常、解码/加载/保存/导入导出失败）用 e(msg, throwable)。
//   - 可恢复的降级/退避/重试（加载失败退避、兜底周期流转）用 w(msg)。
//   - 关键流程节点（生命周期、状态切换、用户操作）用 i(msg)。
//   - 性能指标（帧耗时、内存、卡顿、耗时超限）用 perf(msg)，
//     消息应含：指标名 + 当前值 + 阈值 + 相关上下文。
//   - 消息中尽量带可过滤的上下文键值对：state=xxx anim=xxx resource=xxx gen=xxx。
//   - 禁止 printStackTrace；统一走 LogBuffer.e。
object LogBuffer {

    private const val MAX_GENERAL = 2000
    private const val MAX_PERF = 500
    private const val MAX_ERROR = 500
    private const val TAG = "GifWP"

    private val generalBuffer = ArrayDeque<String>(MAX_GENERAL)
    private val perfBuffer = ArrayDeque<String>(MAX_PERF)
    private val errorBuffer = ArrayDeque<String>(MAX_ERROR)
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // ==================== 写入 ====================

    // 调试级：仅 logcat，不入缓冲（避免刷屏）
    fun d(msg: String) {
        Log.d(TAG, msg)
    }

    // 信息级 → 普通日志
    fun i(msg: String) {
        appendTo(generalBuffer, MAX_GENERAL, "I", msg)
        Log.i(TAG, msg)
    }

    // 警告级 → 普通日志
    fun w(msg: String) {
        appendTo(generalBuffer, MAX_GENERAL, "W", msg)
        Log.w(TAG, msg)
    }

    // 错误级 → 错误日志
    fun e(msg: String, tr: Throwable? = null) {
        val extra = tr?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        appendTo(errorBuffer, MAX_ERROR, "E", msg + extra)
        if (tr != null) Log.e(TAG, msg, tr) else Log.e(TAG, msg)
    }

    // 性能日志。调用点应包含：指标名、当前值、阈值、当前状态。
    // 示例："帧耗时超限: 25.3ms > 20ms, state=state_1"
    fun perf(msg: String) {
        appendTo(perfBuffer, MAX_PERF, "P", msg)
        Log.i(TAG, "[PERF] $msg")
    }

    private fun appendTo(buf: ArrayDeque<String>, max: Int, level: String, msg: String) {
        val time = timeFormat.format(Date())
        val line = "[$time] [$level] $msg"
        synchronized(lock) {
            if (buf.size >= max) buf.removeFirst()
            buf.addLast(line)
        }
    }

    // ==================== 导出 ====================

    fun dumpGeneral(): String = synchronized(lock) {
        if (generalBuffer.isEmpty()) "(无普通日志)" else generalBuffer.joinToString("\n")
    }

    fun dumpPerf(): String = synchronized(lock) {
        if (perfBuffer.isEmpty()) "(无性能日志)" else perfBuffer.joinToString("\n")
    }

    fun dumpError(): String = synchronized(lock) {
        if (errorBuffer.isEmpty()) "(无错误日志)" else errorBuffer.joinToString("\n")
    }

    fun dumpAll(): String = synchronized(lock) {
        buildString {
            appendLine("========== 普通日志 (${generalBuffer.size}) ==========")
            appendLine(if (generalBuffer.isEmpty()) "(无)" else generalBuffer.joinToString("\n"))
            appendLine()
            appendLine("========== 性能日志 (${perfBuffer.size}) ==========")
            appendLine(if (perfBuffer.isEmpty()) "(无)" else perfBuffer.joinToString("\n"))
            appendLine()
            appendLine("========== 错误日志 (${errorBuffer.size}) ==========")
            appendLine(if (errorBuffer.isEmpty()) "(无)" else errorBuffer.joinToString("\n"))
        }
    }

    fun sizeGeneral(): Int = synchronized(lock) { generalBuffer.size }
    fun sizePerf(): Int = synchronized(lock) { perfBuffer.size }
    fun sizeError(): Int = synchronized(lock) { errorBuffer.size }

    fun clearAll() = synchronized(lock) {
        generalBuffer.clear()
        perfBuffer.clear()
        errorBuffer.clear()
    }
}