package com.nqct.gifWallpaper

import java.io.File
import java.io.InputStream

/**
 * GIF 文件的元信息。
 * 由 [GifMetadataParser] 解析得到，供壁纸引擎用于：
 *  - 显示调试信息（帧数 / 时长 / 尺寸）
 *  - 计算合理的重绘间隔
 *  - 判断透明通道、循环模式
 */
data class GifInfo(
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val totalDelayMs: Int,
    val loopCount: Int,
    val fileSize: Long,
    /** 各帧的累计结束时间（毫秒），用于按时间轴推算当前帧 */
    val frameEndTimes: IntArray,
    val hasTransparency: Boolean
) {
    /** 平均每帧时长（毫秒）。帧数或总时长异常时返回 0f。 */
    val averageFrameDurationMs: Float
        get() = if (frameCount > 0 && totalDelayMs > 0) totalDelayMs.toFloat() / frameCount else 0f

    /** 平均帧率 FPS。 */
    val averageFps: Float
        get() {
            val d = averageFrameDurationMs
            return if (d > 0f) 1000f / d else 0f
        }

    /**
     * 计算壁纸重绘间隔（毫秒）。
     * @param movieDurationMs 期望总时长；<=0 时使用 GIF 本身时长
     */
    fun frameIntervalMs(movieDurationMs: Int): Long {
        val duration = if (movieDurationMs > 0) movieDurationMs else totalDelayMs
        return if (frameCount > 0 && duration > 0) {
            (duration.toLong() / frameCount).coerceIn(MIN_FRAME_INTERVAL_MS, MAX_FRAME_INTERVAL_MS)
        } else {
            DEFAULT_FRAME_INTERVAL_MS
        }
    }

    /**
     * 推算下一次重绘应当延迟多久（毫秒）。
     * 当前实现按时间轴查找下一帧；若已到本轮末尾则进入下一循环。
     */
    fun nextFrameDelayMs(currentTimeMs: Int, periodMs: Int): Long {
        val period = if (periodMs > 0) periodMs else totalDelayMs
        if (frameEndTimes.isEmpty() || period <= 0) {
            return frameIntervalMs(period)
        }
        for (i in frameEndTimes.indices) {
            if (frameEndTimes[i] > currentTimeMs) {
                return (frameEndTimes[i] - currentTimeMs).toLong()
                    .coerceIn(MIN_FRAME_INTERVAL_MS, MAX_FRAME_INTERVAL_MS)
            }
        }
        val nextCycle = frameEndTimes[0] + period
        return (nextCycle - currentTimeMs).toLong()
            .coerceIn(MIN_FRAME_INTERVAL_MS, MAX_FRAME_INTERVAL_MS)
    }

    companion object {
        /** 无法获取时长时使用的默认间隔 */
        const val DEFAULT_FRAME_INTERVAL_MS = 33L
        /** 重绘频率下限（防止高频绘制浪费电量） */
        const val MIN_FRAME_INTERVAL_MS = 8L
        /** 重绘频率上限（防止长帧时几乎不刷新） */
        const val MAX_FRAME_INTERVAL_MS = 200L
    }
}

/**
 * 轻量级 GIF 元数据解析器。
 *
 * 仅解析文件头 + 各数据块控制字段，不解码像素，因此非常快，
 * 可以安全地在主线程对少量文件批量解析。
 *
 * 【注意】解析失败一律返回 null，绝不抛异常给调用方，
 * 保证单个坏文件不会拖垮整个壁纸加载流程。
 */
object GifMetadataParser {

    fun parse(file: File?): GifInfo? {
        if (file == null || !file.exists() || file.length() == 0L) return null
        return try {
            file.inputStream().buffered().use { input ->
                parseStream(input, file.length())
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseStream(input: InputStream, fileSize: Long): GifInfo? {
        val r = Reader(input)

        // ---- 1. 文件头 GIF87a / GIF89a ----
        val header = ByteArray(6)
        if (!r.readFully(header, 6)) return null
        if (!String(header, Charsets.US_ASCII).startsWith("GIF8")) return null

        // ---- 2. 逻辑屏幕描述符 ----
        val width = r.readShortLE()
        val height = r.readShortLE()
        val packed = r.readByte()
        r.readByte() // 背景色索引
        r.readByte() // 像素宽高比
        if (width <= 0 || height <= 0) return null

        // 全局调色板
        if (packed and 0x80 != 0) {
            val gctSize = 3 * (1 shl ((packed and 0x07) + 1))
            r.skip(gctSize)
        }

        // ---- 3. 遍历数据块 ----
        var totalDelayMs = 0
        var pendingDelayMs = -1
        var pendingTransparent = false
        var anyTransparency = false
        var loopCount = 1
        val frameDurations = ArrayList<Int>()

        parseLoop@ while (true) {
            val block = r.readByte()
            if (block < 0) break

            when (block) {
                0x3B -> break@parseLoop // Trailer，文件结束

                // ---- 扩展块 ----
                0x21 -> {
                    val label = r.readByte()
                    when (label) {
                        // Graphic Control Extension（图形控制扩展）
                        0xF9 -> {
                            val blockSize = r.readByte()
                            if (blockSize == 4) {
                                val gcePacked = r.readByte()
                                val delay = r.readShortLE()
                                r.readByte()
                                r.readByte()
                                pendingDelayMs = if (delay > 0) delay * 10 else -1
                                pendingTransparent = (gcePacked and 0x01) != 0
                            } else if (blockSize > 0) {
                                r.skip(blockSize)
                                r.skipSubBlocks()
                            }
                        }

                        // Application Extension（应用扩展），可包含 NETSCAPE 循环信息
                        0xFF -> {
                            val blockSize = r.readByte()
                            var isNetscape = false
                            if (blockSize > 0) {
                                val app = ByteArray(blockSize)
                                if (r.readFully(app, blockSize)) {
                                    if (String(app, Charsets.US_ASCII).startsWith("NETSCAPE")) {
                                        isNetscape = true
                                    }
                                }
                            }
                            while (true) {
                                val len = r.readByte()
                                if (len <= 0) break
                                if (isNetscape && len == 3) {
                                    val subId = r.readByte()
                                    val lo = r.readByte()
                                    val hi = r.readByte()
                                    if (subId == 0x01 && lo >= 0 && hi >= 0) {
                                        loopCount = lo or (hi shl 8)
                                    }
                                } else {
                                    r.skip(len)
                                }
                            }
                        }

                        // 其他扩展：跳过
                        else -> {
                            val blockSize = r.readByte()
                            if (blockSize > 0) r.skip(blockSize)
                            r.skipSubBlocks()
                        }
                    }
                }

                // ---- 图像描述符（每张图像 = 一帧） ----
                0x2C -> {
                    r.skip(8)                    // 位置 / 尺寸
                    val ip = r.readByte()        // 局部 packed 字段
                    if (ip and 0x80 != 0) {      // 局部调色板
                        r.skip(3 * (1 shl ((ip and 0x07) + 1)))
                    }
                    r.readByte()                 // LZW 最小码长
                    r.skipSubBlocks()            // 图像数据子块

                    // 帧延迟：优先使用 GCE 声明；否则兜底 10ms
                    val delay = if (pendingDelayMs > 0) pendingDelayMs else 10
                    frameDurations.add(delay)
                    totalDelayMs += delay
                    if (pendingTransparent) anyTransparency = true
                    pendingDelayMs = -1
                    pendingTransparent = false
                }

                else -> break@parseLoop
            }
        }

        if (frameDurations.isEmpty()) return null

        // ---- 4. 计算累计结束时间 ----
        val frameEndTimes = IntArray(frameDurations.size)
        var acc = 0
        for (i in frameDurations.indices) {
            acc += frameDurations[i]
            frameEndTimes[i] = acc
        }

        return GifInfo(
            width = width,
            height = height,
            frameCount = frameDurations.size,
            totalDelayMs = totalDelayMs,
            loopCount = loopCount,
            fileSize = fileSize,
            frameEndTimes = frameEndTimes,
            hasTransparency = anyTransparency
        )
    }

    /** 带容错的输入流包装器，所有方法均不会抛异常。 */
    private class Reader(private val input: InputStream) {
        fun readByte(): Int = try { input.read() } catch (e: Exception) { -1 }

        fun readShortLE(): Int {
            val lo = readByte(); val hi = readByte()
            return if (lo < 0 || hi < 0) -1 else lo or (hi shl 8)
        }

        fun readFully(buffer: ByteArray, length: Int): Boolean {
            var offset = 0
            while (offset < length) {
                val n = try { input.read(buffer, offset, length - offset) } catch (e: Exception) { -1 }
                if (n <= 0) return false
                offset += n
            }
            return true
        }

        fun skip(count: Int) {
            var remaining = count.toLong()
            val temp = ByteArray(256)
            while (remaining > 0) {
                val toRead = minOf(remaining, temp.size.toLong()).toInt()
                val read = try { input.read(temp, 0, toRead) } catch (e: Exception) { -1 }
                if (read <= 0) break
                remaining -= read
            }
        }

        fun skipSubBlocks() {
            while (true) {
                val len = readByte()
                if (len <= 0) break
                skip(len)
            }
        }
    }
}