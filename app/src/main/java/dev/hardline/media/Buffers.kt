package dev.hardline.media

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

/** Recycles direct buffers of one size so camera frames can be copied without allocating. */
class BufferPool(private val limit: Int = 4) {
    private val free = ConcurrentLinkedQueue<ByteBuffer>()

    fun obtain(size: Int): ByteBuffer {
        while (true) {
            val b = free.poll() ?: break
            if (b.capacity() >= size) return b.apply { clear() }
        }
        return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
    }

    fun recycle(buffer: ByteBuffer) {
        if (free.size < limit) free.offer(buffer)
    }
}

/** Start-code helpers for H.264 / H.265 elementary streams. */
object Nal {
    /** Calls [block] with (offset, length) of each NAL unit payload in an Annex-B buffer. */
    inline fun forEach(data: ByteArray, offset: Int, length: Int, block: (start: Int, size: Int) -> Unit) {
        val end = offset + length
        var i = offset
        var start = -1
        while (i + 2 < end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                if (start >= 0) {
                    var stop = i
                    while (stop > start && data[stop - 1].toInt() == 0) stop--
                    block(start, stop - start)
                }
                start = i + 3
                i += 3
            } else i++
        }
        if (start in 0 until end) block(start, end - start)
    }

    fun h264Type(data: ByteArray, start: Int) = data[start].toInt() and 0x1F
    fun h265Type(data: ByteArray, start: Int) = (data[start].toInt() shr 1) and 0x3F

    /** True when the access unit contains a random-access picture. */
    fun isKeyFrame(data: ByteArray, offset: Int, length: Int, hevc: Boolean): Boolean {
        var key = false
        forEach(data, offset, length) { s, _ ->
            if (hevc) { if (h265Type(data, s) in 16..21) key = true } else if (h264Type(data, s) == 5) key = true
        }
        return key
    }

    /** True when the access unit carries the stream's parameter sets. */
    fun hasParameterSets(data: ByteArray, offset: Int, length: Int, hevc: Boolean): Boolean {
        var found = false
        forEach(data, offset, length) { s, _ ->
            if (hevc) { if (h265Type(data, s) == 33) found = true } else if (h264Type(data, s) == 7) found = true
        }
        return found
    }
}
