package com.nqct.gifWallpaper

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

// 后台加载 / 重载 / 失败退避。
//
// 【职责边界】
//   - 负责：加载线程、重载线程、generation 失效、interrupt、失败退避记录。
//   - 不负责：状态机推进、渲染、传感器、UI 提示。结果通过 Callbacks 回到 Engine。
//
// 【线程模型】
//   - load()/reload() 在主线程调用，内部起工作线程解码 GIF。
//   - 工作线程完成后 post 到主线程，回调前检查 generation 是否仍有效。
//   - cancelAll() 使所有未完成回调失效（onDestroy / onSurfaceDestroyed 调用）。
//
// 【时序保证（与拆分前一致）】
//   1. 每次 load 递增 loadGen，旧线程每步检查 gen 与 isInterrupted。
//   2. 工作线程完成后 post 到主线程，post 内再检查 gen。
//   3. cancelAll 递增 gen 并 interrupt，旧回调不再触发。
//   4. reload 同一 animId 进行中时直接回调 onReloadSkipped，不重复起线程。
class RuntimeLoader(
    private val configProvider: () -> ProjectConfigStore.LoadResult,
    private val animDirProvider: () -> File
) {

    data class LoadFailure(val count: Int, val lastAttemptMs: Long)

    interface Callbacks {
        fun onLoadStart(gen: Int)
        fun onLoadSuccess(gen: Int, config: ProjectConfig, cache: Map<String, AnimRuntime>, errorMsg: String)
        fun onLoadFailure(gen: Int, message: String)
        fun onReloadStart(animId: String, stateId: String)
        fun onReloadSkipped(animId: String)
        fun onReloadSuccess(animId: String, stateId: String, runtime: AnimRuntime, costMs: Long)
        fun onReloadFailure(animId: String, stateId: String, costMs: Long, failCount: Int)
    }

    companion object {
        const val LOAD_FAILURE_MAX = 3
        const val LOAD_FAILURE_COOLDOWN_MS = 60_000L
    }

    private val handler = Handler(Looper.getMainLooper())

    private var loadGen = 0
    private var loaderThread: Thread? = null

    private var reloadGen = 0
    private var pendingReloadAnimId: String? = null
    private var pendingReloadStateId: String? = null
    private var pendingReloadThread: Thread? = null

    private val failures = mutableMapOf<String, LoadFailure>()

    // ==================== 加载 ====================

    fun load(callbacks: Callbacks) {
        val gen = ++loadGen
        loaderThread?.interrupt()
        callbacks.onLoadStart(gen)

        val thread = Thread {
            try {
                var cfg = ProjectConfig()
                var cfgMsg: String? = null
                when (val r = configProvider()) {
                    is ProjectConfigStore.LoadResult.Ok -> cfg = r.config
                    is ProjectConfigStore.LoadResult.Corrupted -> {
                        cfgMsg = "配置损坏: ${r.message}\n(未应用，等待用户处理)"
                        LogBuffer.e("配置损坏: ${r.message}")
                    }
                }

                val dir = animDirProvider()
                val newCache = mutableMapOf<String, AnimRuntime>()
                val sb = StringBuilder()
                if (cfgMsg != null) sb.append(cfgMsg).append("\n")
                if (cfg.states.isEmpty()) sb.append("状态为空\n")

                for ((animId, anim) in cfg.animations) {
                    if (gen != loadGen) return@Thread
                    if (Thread.currentThread().isInterrupted) return@Thread
                    val instances = mutableListOf<PlayInstance>()
                    for (ref in anim.resources) {
                        val resource = cfg.resources[ref.resourceId] ?: continue
                        val file = File(dir, resource.fileName)
                        val inst = PlayInstance.fromFile(resource.id, ref, file, resource)
                        if (inst.gifDrawable == null) {
                            sb.append("解码失败: ${resource.id}\n")
                            LogBuffer.e("解码失败: ${resource.id}")
                        }
                        instances.add(inst)
                    }
                    if (instances.isNotEmpty()) {
                        val runtime = AnimRuntime(animId, instances, anim.endTimeMs.toLong())
                        newCache[animId] = runtime
                        val delayInfo = instances.joinToString(", ") {
                            "${it.ref.resourceId}(delay=${it.getStartDelayMs()}ms, dur=${it.getTotalDurationMs()}ms)"
                        }
                        LogBuffer.i(
                            "动画 $animId 预加载 ${instances.size} 个实例, " +
                            "整圈=${runtime.totalDurationMs}ms(用户配置=${runtime.isUsingUserEndTime}), " +
                            "内存约 ${runtime.allocationBytes / 1024 / 1024}MB, [$delayInfo]"
                        )
                    }
                }

                if (gen != loadGen) return@Thread
                val errorMsg = sb.toString()
                handler.post {
                    if (gen != loadGen) return@post
                    callbacks.onLoadSuccess(gen, cfg, newCache, errorMsg)
                }
            } catch (e: Exception) {
                if (gen != loadGen) return@Thread
                LogBuffer.e("加载异常 gen=$gen", e)
                handler.post {
                    if (gen != loadGen) return@post
                    callbacks.onLoadFailure(gen, "加载异常:\n${e.message}")
                }
            }
        }
        loaderThread = thread
        thread.start()
    }

    // ==================== 重载 ====================

    fun reload(config: ProjectConfig, animId: String, stateId: String, callbacks: Callbacks) {
        if (pendingReloadAnimId == animId) {
            callbacks.onReloadSkipped(animId)
            return
        }
        pendingReloadAnimId = animId
        pendingReloadStateId = stateId
        val gen = ++reloadGen
        val startMs = SystemClock.elapsedRealtime()
        callbacks.onReloadStart(animId, stateId)

        val thread = Thread {
            val anim = config.animations[animId]
            val instances = mutableListOf<PlayInstance>()
            if (anim != null) {
                val dir = animDirProvider()
                for (ref in anim.resources) {
                    if (Thread.currentThread().isInterrupted) return@Thread
                    val resource = config.resources[ref.resourceId] ?: continue
                    val file = File(dir, resource.fileName)
                    val inst = PlayInstance.fromFile(resource.id, ref, file, resource)
                    instances.add(inst)
                }
            }
            handler.post {
                if (gen != reloadGen) return@post
                pendingReloadAnimId = null
                pendingReloadStateId = null
                pendingReloadThread = null
                val cost = SystemClock.elapsedRealtime() - startMs
                val ok = instances.isNotEmpty() && instances.any { it.gifDrawable != null }
                if (!ok) {
                    val prev = failures[animId]
                    failures[animId] = LoadFailure(
                        count = (prev?.count ?: 0) + 1,
                        lastAttemptMs = SystemClock.elapsedRealtime()
                    )
                    callbacks.onReloadFailure(animId, stateId, cost, failures[animId]!!.count)
                    return@post
                }
                val runtime = AnimRuntime(animId, instances, anim?.endTimeMs?.toLong() ?: 0L)
                failures.remove(animId)
                callbacks.onReloadSuccess(animId, stateId, runtime, cost)
            }
        }
        pendingReloadThread = thread
        thread.start()
    }

    // ==================== 取消 ====================

    // 使所有未完成回调失效，并中断工作线程。
    fun cancelAll() {
        loadGen++
        loaderThread?.interrupt()
        loaderThread = null
        cancelPendingReload()
    }

    // 仅取消失败重载回调（releaseAllRuntimes 调用，避免旧重载结果写入已清空的缓存）。
    fun cancelPendingReload() {
        reloadGen++
        pendingReloadThread?.interrupt()
        pendingReloadThread = null
        pendingReloadAnimId = null
        pendingReloadStateId = null
    }

    // 当前正在重载的 animId（无则 null）。供 Engine 在释放非当前实例时保留待写入的动画。
    fun pendingReloadAnimId(): String? = pendingReloadAnimId

    // ==================== 失败退避 ====================

    fun clearFailures() {
        failures.clear()
    }

    // 返回剩余退避毫秒；不在退避期返回 0。
    fun backoffRemainingMs(animId: String): Long {
        val rec = failures[animId] ?: return 0L
        if (rec.count < LOAD_FAILURE_MAX) return 0L
        val remain = LOAD_FAILURE_COOLDOWN_MS - (SystemClock.elapsedRealtime() - rec.lastAttemptMs)
        return if (remain > 0) remain else 0L
    }
}
