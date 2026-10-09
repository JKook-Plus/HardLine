package dev.hardline.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.hardline.gl.Pipeline
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Decodes Motion-JPEG frames off the USB thread. Only the newest waiting frame is kept, so a slow
 * decoder drops frames instead of falling behind.
 */
class MjpegDecoder(private val pipeline: Pipeline, private val dropBroken: () -> Boolean) {
    private class Job(val data: ByteArray, val length: Int, val ptsNs: Long)

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "mjpeg-decoder") }
    private val waiting = AtomicReference<Job?>(null)
    private val arrays = ConcurrentLinkedQueue<ByteArray>()
    private val bitmaps = ConcurrentLinkedQueue<Bitmap>()
    @Volatile private var closed = false

    /** Returns a scratch array of at least [size] bytes for the caller to fill and pass to [submit]. */
    fun obtain(size: Int): ByteArray {
        while (true) {
            val a = arrays.poll() ?: break
            if (a.size >= size) return a
        }
        return ByteArray(size + size / 4)
    }

    fun submit(data: ByteArray, length: Int, ptsNs: Long) {
        if (closed) return
        val old = waiting.getAndSet(Job(data, length, ptsNs))
        if (old != null) recycle(old.data) else executor.execute(::drain)
    }

    private fun recycle(a: ByteArray) {
        if (arrays.size < 4) arrays.offer(a)
    }

    private fun drain() {
        val job = waiting.getAndSet(null) ?: return
        try {
            if (!pipeline.canAccept()) return
            var data = job.data
            var length = job.length
            if (dropBroken() && !endsWithEoi(data, length)) return
            if (!hasHuffmanTables(data, length)) {
                data = withDefaultTables(data, length) ?: return
                length = data.size
            }
            val opts = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inBitmap = bitmaps.poll()
            }
            val bmp = try {
                BitmapFactory.decodeByteArray(data, 0, length, opts)
            } catch (e: IllegalArgumentException) {      // recycled bitmap had another size
                opts.inBitmap = null
                BitmapFactory.decodeByteArray(data, 0, length, opts)
            } ?: return
            pipeline.submitBitmap(bmp, job.ptsNs) { if (!closed && bitmaps.size < 3) bitmaps.offer(bmp) else bmp.recycle() }
        } finally {
            recycle(job.data)
        }
    }

    fun close() {
        closed = true
        executor.shutdown()
    }

    companion object {
        private fun endsWithEoi(d: ByteArray, n: Int): Boolean {
            var i = n - 1
            while (i > 1 && d[i].toInt() == 0) i--            // cameras often pad frames with zeros
            return i >= 1 && d[i - 1] == 0xFF.toByte() && d[i] == 0xD9.toByte()
        }

        /** Scans the header segments; many cameras leave the Huffman tables out. */
        private fun hasHuffmanTables(d: ByteArray, n: Int): Boolean {
            var i = 2
            while (i + 3 < n && d[i] == 0xFF.toByte()) {
                val marker = d[i + 1].toInt() and 0xFF
                if (marker == 0xC4) return true
                if (marker == 0xDA) return false
                i += 2 + (((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF))
            }
            return true
        }

        private fun withDefaultTables(d: ByteArray, n: Int): ByteArray? {
            var i = 2
            while (i + 3 < n && d[i] == 0xFF.toByte()) {
                if ((d[i + 1].toInt() and 0xFF) == 0xDA) {
                    val out = ByteArray(n + DEFAULT_TABLES.size)
                    System.arraycopy(d, 0, out, 0, i)
                    System.arraycopy(DEFAULT_TABLES, 0, out, i, DEFAULT_TABLES.size)
                    System.arraycopy(d, i, out, i + DEFAULT_TABLES.size, n - i)
                    return out
                }
                i += 2 + (((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF))
            }
            return null
        }

        /** The standard baseline Huffman tables (ITU T.81 Annex K) as one DHT segment. */
        private val DEFAULT_TABLES: ByteArray = run {
            val dcBits = arrayOf(
                intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0),
                intArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0),
            )
            val dcVals = IntArray(12) { it }
            val acBits = arrayOf(
                intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d),
                intArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77),
            )
            val acLuma = intArrayOf(
                0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
                0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
                0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
                0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
                0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
                0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
                0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
                0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
                0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
                0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
                0xf9, 0xfa,
            )
            val acChroma = intArrayOf(
                0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
                0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
                0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
                0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
                0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
                0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
                0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
                0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
                0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
                0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
                0xf9, 0xfa,
            )
            val body = ArrayList<Byte>()
            fun table(id: Int, bits: IntArray, vals: IntArray) {
                body += id.toByte()
                bits.forEach { body += it.toByte() }
                vals.forEach { body += it.toByte() }
            }
            table(0x00, dcBits[0], dcVals)
            table(0x10, acBits[0], acLuma)
            table(0x01, dcBits[1], dcVals)
            table(0x11, acBits[1], acChroma)
            val len = body.size + 2
            byteArrayOf(0xFF.toByte(), 0xC4.toByte(), (len shr 8).toByte(), len.toByte()) + body.toByteArray()
        }
    }
}
