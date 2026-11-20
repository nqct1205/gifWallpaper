package com.nqct.gifWallpaper

import java.io.File
import java.io.InputStream

// GIF 元信息。只保留当前有消费者的字段。
//
// 【清理说明】原 frameEndTimes、nextFrameDelayMs、averageFps、hasTransparency
// 在当前架构下均无消费方（帧索引改由 GifDrawable.currentFrameIndex 精确读取，
// 时间轴由 PlayInstance 基于 SystemClock 驱动），已移除。
data class GifInfo(
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val totalDelayMs: Int,
    val loopCount: Int,
    val fileSize: Long
) {
    val averageFrameDurationMs: Float
        get() = if (frameCount > 0 && totalDelayMs > 0) totalDelayMs.toFloat() / frameCount else 0f
}

// 轻量级 GIF 元数据解析器。只解析文件头 + 各数据块控制字段，不解码像素。
// 解析失败返回 null，不抛异常给调用方。
object GifMetadataParser {

    fun parse(file: File?): GifInfo? {
        if (file == null || !file.exists() || file.length() == 0L) return null
        return try {
            file.inputStream().buffered().use { input ->
                parseStream(input, file.length())
            }
        } catch (e: Exception) {
            LogBuffer.e("GIF 元数据解析失败: ${file.name}", e)
            null
        }
    }

    private fun parseStream(input: InputStream, fileSize: Long): GifInfo? {
        val r = Reader(input)

        // 1. 文件头 GIF87a / GIF89a
        val header = ByteArray(6)
        if (!r.readFully(header, 6)) return null
        if (!String(header, Charsets.US_ASCII).startsWith("GIF8")) return null

        // 2. 逻辑屏幕描述符
        val width = r.readShortLE()
        val height = r.readShortLE()
        val packed = r.readByte()
        r.readByte()  // 背景色索引
        r.readByte()  // 像素宽高比
        if (width <= 0 || height <= 0) return null

        // 全局调色板
        if (packed and 0x80 != 0) {
            val gctSize = 3 * (1 shl ((packed and 0x07) + 1))
            r.skip(gctSize)
        }

        // 3. 遍历数据块
        var totalDelayMs = 0
        var pendingDelayMs = -1
        var frameCount = 0
        var loopCount = 1

        parseLoop@ while (true) {
            val block = r.readByte()
            if (block < 0) break

            when (block) {
                0x3B -> break@parseLoop  // Trailer

                0x21 -> {  // 扩展块
                    val label = r.readByte()
                    when (label) {
                        0xF9 -> {  // Graphic Control Extension
                            val blockSize = r.readByte()
                            if (blockSize == 4) {
                                r.readByte()             // gcePacked（不再需要 transparent 标记）
                                val delay = r.readShortLE()
                                r.readByte()             // 透明色索引
                                r.readByte()             // 终止符
                                pendingDelayMs = if (delay > 0) delay * 10 else -1
                            } else if (blockSize > 0) {
                                r.skip(blockSize)
                                r.skipSubBlocks()
                            }
                        }
                        0xFF -> {  // Application Extension（NETSCAPE 循环）
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
                        else -> {
                            val blockSize = r.readByte()
                            if (blockSize > 0) r.skip(blockSize)
                            r.skipSubBlocks()
                        }
                    }
                }

                0x2C -> {  // 图像描述符
                    r.skip(8)                              // 位置 / 尺寸
                    val ip = r.readByte()                  // 局部 packed
                    if (ip and 0x80 != 0) {
                        r.skip(3 * (1 shl ((ip and 0x07) + 1)))
                    }
                    r.readByte()                           // LZW 最小码长
                    r.skipSubBlocks()                      // 图像数据

                    val delay = if (pendingDelayMs > 0) pendingDelayMs else 10
                    totalDelayMs += delay
                    frameCount++
                    pendingDelayMs = -1
                }

                else -> break@parseLoop
            }
        }

        if (frameCount == 0) return null

        return GifInfo(
            width = width,
            height = height,
            frameCount = frameCount,
            totalDelayMs = totalDelayMs,
            loopCount = loopCount,
            fileSize = fileSize
        )
    }

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