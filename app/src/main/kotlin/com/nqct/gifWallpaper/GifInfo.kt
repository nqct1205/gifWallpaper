package com.nqct.gifWallpaper

import java.io.File
import java.io.InputStream

data class GifInfo(
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val totalDelayMs: Int,
    val loopCount: Int,
    val fileSize: Long,
    val frameEndTimes: IntArray,
    val hasTransparency: Boolean
) {
    val averageFrameDurationMs: Float
        get() = if (frameCount > 0 && totalDelayMs > 0) totalDelayMs.toFloat() / frameCount else 0f

    val averageFps: Float
        get() {
            val d = averageFrameDurationMs
            return if (d > 0f) 1000f / d else 0f
        }

    fun frameIntervalMs(movieDurationMs: Int): Long {
        val duration = if (movieDurationMs > 0) movieDurationMs else totalDelayMs
        return if (frameCount > 0 && duration > 0) {
            (duration.toLong() / frameCount).coerceIn(MIN_FRAME_INTERVAL_MS, MAX_FRAME_INTERVAL_MS)
        } else {
            DEFAULT_FRAME_INTERVAL_MS
        }
    }

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
        const val DEFAULT_FRAME_INTERVAL_MS = 33L
        const val MIN_FRAME_INTERVAL_MS = 8L
        const val MAX_FRAME_INTERVAL_MS = 200L
    }
}

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
        if (!String(header, Charsets.US_ASCII).startsWith("GIF8")) return null

        val width = r.readShortLE()
        val height = r.readShortLE()
        val packed = r.readByte()
        r.readByte()
        r.readByte()
        if (width <= 0 || height <= 0) return null

        if (packed and 0x80 != 0) {
            val gctSize = 3 * (1 shl ((packed and 0x07) + 1))
            r.skip(gctSize)
        }

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
                0x3B -> break@parseLoop

                0x21 -> {
                    val label = r.readByte()
                    when (label) {
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

                        else -> {
                            val blockSize = r.readByte()
                            if (blockSize > 0) r.skip(blockSize)
                            r.skipSubBlocks()
                        }
                    }
                }

                0x2C -> {
                    r.skip(8)
                    val ip = r.readByte()
                    if (ip and 0x80 != 0) {
                        r.skip(3 * (1 shl ((ip and 0x07) + 1)))
                    }
                    r.readByte()
                    r.skipSubBlocks()

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