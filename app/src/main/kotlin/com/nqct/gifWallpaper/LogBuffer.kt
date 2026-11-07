package com.nqct.gifWallpaper

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 环形日志缓冲。所有关键事件记录在此，可导出。
//
// 容量 2000 条，超出丢弃最旧的。线程安全（synchronized）。
// 只记录 I / W / E 级别；D 级别仅输出到 logcat 不入缓冲（避免刷屏）。
// TAG 统一为 "GifWP"，便于 logcat 筛选。
object LogBuffer {

    private const val MAX = 2000
    private const val TAG = "GifWP"

    private val buffer = ArrayDeque<String>(MAX)
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // 调试级：只输出到 logcat，不入缓冲
    fun d(msg: String) {
        Log.d(TAG, msg)
    }

    // 信息级：入缓冲 + logcat
    fun i(msg: String) {
        append("I", msg)
        Log.i(TAG, msg)
    }

    // 警告级
    fun w(msg: String) {
        append("W", msg)
        Log.w(TAG, msg)
    }

    // 错误级：入缓冲 + logcat + 堆栈
    fun e(msg: String, tr: Throwable? = null) {
        val extra = tr?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        append("E", msg + extra)
        if (tr != null) Log.e(TAG, msg, tr) else Log.e(TAG, msg)
    }

    private fun append(level: String, msg: String) {
        val time = timeFormat.format(Date())
        val line = "[$time] [$level] $msg"
        synchronized(lock) {
            if (buffer.size >= MAX) buffer.removeFirst()
            buffer.addLast(line)
        }
    }

    // 导出全部缓冲内容（按时间顺序）
    fun dump(): String = synchronized(lock) {
        if (buffer.isEmpty()) "(无日志)" else buffer.joinToString("\n")
    }

    fun size(): Int = synchronized(lock) { buffer.size }

    fun clear() = synchronized(lock) { buffer.clear() }
}