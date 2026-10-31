package com.nqct.gifWallpaper

import java.io.File
import java.io.InputStream

/**
 * GIF 元数据。通过解析 GIF 二进制结构获得，不依赖 android.graphics.Movie。
 */
data class GifInfo(
    val width: Int,
    val height: Int,
    val frameCount: Int,
    /** 所有帧 delay 累加（毫秒）。GIF delay 单位为 1/100 秒。 */
    val totalDelayMs: Int,
    /** 0 表示无限循环；1 表示只播一次；N 表示循环 N 次。 */
    val loopCount: Int,
    val fileSize: Long
) {
    /** 平均每帧时长（毫秒） */
    val averageFrameDurationMs: Float
        get() = if (frameCount > 0 && totalDelayMs > 0) totalDelayMs.toFloat() / frameCount else 0f

    /** 平均帧率（fps） */
    val averageFps: Float
        get() {
            val d = averageFrameDurationMs
            return if (d > 0f) 1000f / d else 0f
        }

    /**
     * 计算用于节流重绘的帧间隔。
     * 优先使用 Movie 的权威 duration（若 >0），否则回退到解析出的 totalDelayMs。
     * 结果会被夹在 [MIN, MAX] 之间，避免过快过热或过慢。
     */
    fun frameIntervalMs(movieDurationMs: Int): Long {
        val duration = if (movieDurationMs > 0) movieDurationMs else totalDelayMs
        return if (frameCount > 0 && duration > 0) {
            (duration.toLong() / frameCount).coerceIn(MIN_FRAME_INTERVAL_MS, MAX_FRAME_INTERVAL_MS)
        } else {
            DEFAULT_FRAME_INTERVAL_MS
        }
    }

    companion object {
        const val DEFAULT_FRAME_INTERVAL_MS = 33L   // 约 30fps 兜底
        const val MIN_FRAME_INTERVAL_MS = 8L        // 最低 125fps，防 0 延迟死循环
        const val MAX_FRAME_INTERVAL_MS = 200L      // 最高 5fps，防极慢 GIF 过久不刷新
    }
}

/**
 * 轻量 GIF 结构解析器。
 * 只解析块结构、读取 Graphic Control Extension 的 delay、统计帧数和 Netscape 循环次数，
 * 会跳过 LZW 图像数据，速度很快，内存占用低。
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

        val header = ByteArray(6)
        if (!r.readFully(header, 6)) return null
        val signature = String(header, Charsets.US_ASCII)
        if (!signature.startsWith("GIF8")) return null

        val width = r.readShortLE()
        val height = r.readShortLE()
        val packed = r.readByte()
        r.readByte() // background color index
        r.readByte() // pixel aspect ratio
        if (width <= 0 || height <= 0) return null

        // 跳过全局颜色表
        if (packed and 0x80 != 0) {
            val gctSize = 3 * (1 shl ((packed and 0x07) + 1))
            r.skip(gctSize)
        }

        var frameCount = 0
        var totalDelayMs = 0
        var pendingDelayMs = -1
        var loopCount = 1

        parseLoop@ while (true) {
            val block = r.readByte()
            if (block < 0) break

            when (block) {
                0x3B -> break@parseLoop // Trailer

                0x21 -> { // Extension
                    val label = r.readByte()
                    when (label) {
                        0xF9 -> { // Graphic Control Extension
                            val blockSize = r.readByte()
                            if (blockSize == 4) {
                                r.readByte()                 // packed
                                val delay = r.readShortLE()  // 1/100 秒
                                r.readByte()                 // transparent color index
                                r.readByte()                 // block terminator
                                pendingDelayMs = if (delay > 0) delay * 10 else -1
                            } else if (blockSize > 0) {
                                r.skip(blockSize)
                                r.skipSubBlocks()
                            }
                        }

                        0xFF -> { // Application Extension
                            val blockSize = r.readByte()
                            var isNetscape = false
                            if (blockSize > 0) {
                                val app = ByteArray(blockSize)
                                if (r.readFully(app, blockSize)) {
                                    val appStr = String(app, Charsets.US_ASCII)
                                    if (appStr.startsWith("NETSCAPE")) isNetscape = true
                                }
                            }
                            // 读子块
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

                        else -> { // 其他扩展
                            val blockSize = r.readByte()
                            if (blockSize > 0) r.skip(blockSize)
                            r.skipSubBlocks()
                        }
                    }
                }

                0x2C -> { // Image Descriptor
                    r.skip(8) // left, top, width, height
                    val ip = r.readByte()
                    if (ip and 0x80 != 0) {
                        val lctSize = 3 * (1 shl ((ip and 0x07) + 1))
                        r.skip(lctSize)
                    }
                    r.readByte()      // LZW minimum code size
                    r.skipSubBlocks() // 图像数据
                    frameCount++
                    totalDelayMs += if (pendingDelayMs > 0) pendingDelayMs else 10
                    pendingDelayMs = -1
                }

                else -> break@parseLoop
            }
        }

        if (frameCount == 0) return null
        return GifInfo(width, height, frameCount, totalDelayMs, loopCount, fileSize)
    }

    private class Reader(private val input: InputStream) {

        fun readByte(): Int = try {
            input.read()
        } catch (e: Exception) {
            -1
        }

        fun readShortLE(): Int {
            val lo = readByte()
            val hi = readByte()
            return if (lo < 0 || hi < 0) -1 else lo or (hi shl 8)
        }

        fun readFully(buffer: ByteArray, length: Int): Boolean {
            var offset = 0
            while (offset < length) {
                val n = try {
                    input.read(buffer, offset, length - offset)
                } catch (e: Exception) {
                    -1
                }
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
                val read = try {
                    input.read(temp, 0, toRead)
                } catch (e: Exception) {
                    -1
                }
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