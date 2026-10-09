package dev.hardline.media

import dev.hardline.gl.Pipeline
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Detects movement by background subtraction on a small greyscale copy of the picture:
 * blur, compare with a running average, threshold, dilate, and look for a changed region
 * large enough to matter.
 */
class MotionDetector(private val pipeline: Pipeline, private val onMotion: () -> Unit) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "motion") }
    @Volatile private var busy = false
    @Volatile var enabled = false
        private set

    private var width = 0
    private var height = 0
    private var luma = IntArray(0)
    private var blurred = IntArray(0)
    private var background = FloatArray(0)
    private var mask = BooleanArray(0)
    private var dilated = BooleanArray(0)
    private var labels = IntArray(0)
    private var warmup = 0

    fun start() {
        if (enabled) return
        enabled = true
        width = 0
        // Movement is judged on the camera picture alone, so a ticking clock or an animated logo never triggers it.
        pipeline.setTap("motion", Pipeline.Tap(WIDTH, 100_000_000L, beforeOverlays = true) { buffer, w, h, _ ->
            if (busy) return@Tap
            busy = true
            copyLuma(buffer, w, h)
            executor.execute {
                try {
                    if (analyse()) onMotion()
                } finally {
                    busy = false
                }
            }
        })
    }

    fun stop() {
        enabled = false
        pipeline.setTap("motion", null)
    }

    private fun copyLuma(rgba: ByteBuffer, w: Int, h: Int) {
        if (w != width || h != height) {
            width = w; height = h
            luma = IntArray(w * h); blurred = IntArray(w * h); background = FloatArray(w * h)
            mask = BooleanArray(w * h); dilated = BooleanArray(w * h); labels = IntArray(w * h)
            warmup = 0
        }
        var p = rgba.position()
        for (i in 0 until w * h) {
            val r = rgba.get(p).toInt() and 0xFF
            val g = rgba.get(p + 1).toInt() and 0xFF
            val b = rgba.get(p + 2).toInt() and 0xFF
            luma[i] = (r * 77 + g * 150 + b * 29) shr 8
            p += 4
        }
    }

    private fun analyse(): Boolean {
        val w = width
        val h = height
        boxBlur(luma, blurred, w, h, RADIUS)
        boxBlur(blurred, luma, w, h, RADIUS)          // two passes approximate a Gaussian
        val cur = luma
        if (warmup == 0) for (i in cur.indices) background[i] = cur[i].toFloat()
        var any = false
        for (i in cur.indices) {
            background[i] += (cur[i] - background[i]) * LEARNING_RATE
            val changed = abs(cur[i] - background[i]) > THRESHOLD
            mask[i] = changed
            any = any || changed
        }
        if (warmup < WARMUP_FRAMES) {
            warmup++
            return false
        }
        if (!any) return false
        for (y in 0 until h) for (x in 0 until w) {
            var on = false
            var dy = -1
            while (dy <= 1 && !on) {
                val yy = y + dy
                if (yy in 0 until h) for (dx in -1..1) {
                    val xx = x + dx
                    if (xx in 0 until w && mask[yy * w + xx]) { on = true; break }
                }
                dy++
            }
            dilated[y * w + x] = on
        }
        return largestRegion(w, h) >= MIN_AREA
    }

    /** Area of the biggest 4-connected region in [dilated], found by flood fill. */
    private fun largestRegion(w: Int, h: Int): Int {
        labels.fill(0)
        val stack = IntArray(w * h)
        var best = 0
        for (start in dilated.indices) {
            if (!dilated[start] || labels[start] != 0) continue
            var top = 0
            var area = 0
            stack[top++] = start
            labels[start] = 1
            while (top > 0) {
                val p = stack[--top]
                area++
                val x = p % w
                if (x > 0 && dilated[p - 1] && labels[p - 1] == 0) { labels[p - 1] = 1; stack[top++] = p - 1 }
                if (x < w - 1 && dilated[p + 1] && labels[p + 1] == 0) { labels[p + 1] = 1; stack[top++] = p + 1 }
                if (p >= w && dilated[p - w] && labels[p - w] == 0) { labels[p - w] = 1; stack[top++] = p - w }
                if (p + w < w * h && dilated[p + w] && labels[p + w] == 0) { labels[p + w] = 1; stack[top++] = p + w }
            }
            if (area > best) best = area
            if (best >= MIN_AREA) return best
        }
        return best
    }

    private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            var sum = 0
            val row = y * w
            for (x in -r..r) sum += src[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / (2 * r + 1)
                sum += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = sum / (2 * r + 1)
                sum += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
    }

    companion object {
        private const val WIDTH = 320
        private const val RADIUS = 3
        private const val LEARNING_RATE = 0.6f
        private const val THRESHOLD = 12f
        private const val MIN_AREA = 25
        private const val WARMUP_FRAMES = 10
    }
}
