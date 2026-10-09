package dev.hardline.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import dev.hardline.usb.PixelKind
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/** How one on-screen window shows the picture. */
data class PreviewParams(
    val aspectMode: Int = 0,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val dual: Boolean = false,
    val showOsd: Boolean = true,
)

/** A bitmap drawn over the video. Geometry is in fractions of the frame, origin top-left. */
class ImageLayer(val osd: Boolean = true) {
    @Volatile var bitmap: Bitmap? = null
    @Volatile var version = 0
    @Volatile var left = 0f
    @Volatile var top = 0f
    @Volatile var width = 0f
    @Volatile var height = 0f
    @Volatile var alpha = 1f
    @Volatile var visible = true
    internal var texture = 0
    internal var uploaded = -1
}

/** A live inset (phone camera, screen) drawn over the video from its own surface. */
class SurfaceLayer internal constructor(internal val surfaceTexture: SurfaceTexture, internal val texture: Int) {
    val surface = Surface(surfaceTexture)
    @Volatile var left = 0f
    @Volatile var top = 0f
    @Volatile var width = 0.3f
    @Volatile var height = 0.3f
    @Volatile var rotation = 0
    @Volatile var mirror = false
    @Volatile var visible = true
    @Volatile internal var hasFrame = false
}

/**
 * The frame pipeline. Sources (raw camera frames, decoded bitmaps, or a decoder surface) are turned
 * into one composed RGB picture on the GPU; that picture is then presented to preview windows and
 * encoder input surfaces, and read back for consumers that need pixels.
 */
class Pipeline {
    /** A continuous read-back. With [beforeOverlays] it sees the camera picture without text, watermark and insets. */
    class Tap(val maxWidth: Int, val minIntervalNs: Long, val beforeOverlays: Boolean = false, val callback: (ByteBuffer, Int, Int, Long) -> Unit) {
        internal var last = 0L
    }

    private class Target(val key: Any, val surface: EGLSurface, var width: Int, var height: Int, val encoder: Boolean, var params: PreviewParams)

    private val thread = HandlerThread("pipeline").apply { start() }
    val handler = Handler(thread.looper)
    private lateinit var egl: EglCore
    private lateinit var rgba: Program
    private lateinit var external: Program
    private lateinit var packed: Program
    private lateinit var planar: Program

    private val quad = floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val texUpright = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val texFlipped = floatBuffer(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val mvp = FloatArray(16)
    private val texM = FloatArray(16)

    private val planes = IntArray(3)
    private var bitmapTexture = 0
    private var packedTexture = 0
    private var sourceTexture = 0
    private var sourceSurfaceTexture: SurfaceTexture? = null
    private var sourceSurface: Surface? = null
    private var srcFbo: Fbo? = null
    private var baseFbo: Fbo? = null
    private var finalFbo: Fbo? = null
    private var tapFbo: Fbo? = null
    private var output: Fbo? = null
    private var readBuffer: ByteBuffer? = null

    private val targets = ArrayList<Target>()
    private val taps = HashMap<String, Tap>()
    private val pending = AtomicInteger(0)
    private var frames = 0
    private var fpsWindowStart = 0L
    private var lastPts = 0L

    @Volatile var rotation = 0
    @Volatile var flipHorizontal = false
    @Volatile var flipVertical = false
    @Volatile var deinterlace = false
    val imageLayers = CopyOnWriteArrayList<ImageLayer>()
    val surfaceLayers = CopyOnWriteArrayList<SurfaceLayer>()

    /** Size of the composed picture, after rotation. (0, 0) while there is no source. */
    val outputSize = MutableStateFlow(0 to 0)
    val fps = MutableStateFlow(0f)

    /** Called on the pipeline thread just before each frame is composed. */
    @Volatile var beforeCompose: ((width: Int, height: Int, timeNs: Long) -> Unit)? = null

    init {
        handler.post {
            egl = EglCore()
            rgba = Program(Shaders.VERTEX, Shaders.RGBA)
            external = Program(Shaders.VERTEX, Shaders.EXTERNAL)
            packed = Program(Shaders.VERTEX, Shaders.PACKED)
            planar = Program(Shaders.VERTEX, Shaders.PLANAR)
            for (i in 0..2) planes[i] = newTexture()
            bitmapTexture = newTexture()
            packedTexture = newTexture(filter = GLES20.GL_NEAREST)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        }
    }

    // ------------------------------------------------------------------ sources

    /** True when the pipeline is keeping up and another frame may be queued. */
    fun canAccept(): Boolean = pending.get() < 2

    /** Queues one uncompressed frame. [release] is always called exactly once. */
    fun submitRaw(kind: PixelKind, data: ByteBuffer, width: Int, height: Int, ptsNs: Long, release: () -> Unit) {
        if (!canAccept()) return release()
        pending.incrementAndGet()
        handler.post {
            try {
                if (uploadRaw(kind, data, width, height)) compose(ptsNs)
            } catch (e: Exception) {
                Log.w(TAG, "frame dropped", e)
            } finally {
                pending.decrementAndGet()
                release()
            }
        }
    }

    /** Queues one decoded picture. [release] is always called exactly once. */
    fun submitBitmap(bitmap: Bitmap, ptsNs: Long, release: () -> Unit) {
        if (!canAccept()) return release()
        pending.incrementAndGet()
        handler.post {
            try {
                val fbo = ensureSource(bitmap.width, bitmap.height)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bitmapTexture)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                fbo.bind()
                drawTexture(rgba, GLES20.GL_TEXTURE_2D, bitmapTexture, identity, identity, texUpright)
                compose(ptsNs)
            } catch (e: Exception) {
                Log.w(TAG, "frame dropped", e)
            } finally {
                pending.decrementAndGet()
                release()
            }
        }
    }

    /** Creates a surface that a video decoder can render into; each frame it produces is composed. */
    fun createSourceSurface(width: Int, height: Int): Surface {
        return runBlocking {
            releaseSourceSurface()
            sourceTexture = newTexture(OES)
            val st = SurfaceTexture(sourceTexture)
            st.setDefaultBufferSize(width, height)
            st.setOnFrameAvailableListener({
                try {
                    it.updateTexImage()
                    it.getTransformMatrix(texM)
                    ensureSource(width, height).bind()
                    drawTexture(external, OES, sourceTexture, identity, texM, texFlipped)
                    compose(System.nanoTime())
                } catch (e: Exception) {
                    Log.w(TAG, "decoder frame dropped", e)
                }
            }, handler)
            sourceSurfaceTexture = st
            Surface(st).also { sourceSurface = it }
        }
    }

    private fun releaseSourceSurface() {
        sourceSurface?.release()
        sourceSurfaceTexture?.release()
        if (sourceTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(sourceTexture), 0)
        sourceSurface = null
        sourceSurfaceTexture = null
        sourceTexture = 0
    }

    /** Drops the current picture and blanks every preview. */
    fun clearSource() = handler.post {
        releaseSourceSurface()
        srcFbo?.release(); baseFbo?.release(); finalFbo?.release()
        srcFbo = null; baseFbo = null; finalFbo = null; output = null
        outputSize.value = 0 to 0
        fps.value = 0f
        present(0)
    }

    private fun ensureSource(width: Int, height: Int): Fbo {
        srcFbo?.let { if (it.width == width && it.height == height) return it else it.release() }
        return Fbo(width, height).also { srcFbo = it }
    }

    private fun uploadRaw(kind: PixelKind, data: ByteBuffer, w: Int, h: Int): Boolean {
        fun plane(index: Int, format: Int, pw: Int, ph: Int, offset: Int) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, planes[index])
            data.position(offset)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, pw, ph, 0, format, GLES20.GL_UNSIGNED_BYTE, data)
        }
        val size = data.limit()
        val fbo = ensureSource(w, h)
        when (kind) {
            PixelKind.YUYV, PixelKind.UYVY -> {
                if (size < w * h * 2) return false
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, packedTexture)
                data.position(0)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w / 2, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, data)
                fbo.bind()
                GLES20.glUseProgram(packed.id)
                GLES20.glUniform1f(packed.uniform("uWidth"), w.toFloat())
                GLES20.glUniform1f(packed.uniform("uUyvy"), if (kind == PixelKind.UYVY) 1f else 0f)
                drawTexture(packed, GLES20.GL_TEXTURE_2D, packedTexture, identity, identity, texUpright)
            }
            PixelKind.NV12, PixelKind.NV21, PixelKind.I420, PixelKind.GRAY8 -> {
                val luma = w * h
                val mode = when (kind) { PixelKind.I420 -> 0; PixelKind.NV12 -> 1; PixelKind.NV21 -> 2; else -> 3 }
                if (size < if (mode == 3) luma else luma * 3 / 2) return false
                plane(0, GLES20.GL_LUMINANCE, w, h, 0)
                if (mode == 0) {
                    plane(1, GLES20.GL_LUMINANCE, w / 2, h / 2, luma)
                    plane(2, GLES20.GL_LUMINANCE, w / 2, h / 2, luma + luma / 4)
                } else if (mode != 3) {
                    plane(1, GLES20.GL_LUMINANCE_ALPHA, w / 2, h / 2, luma)
                }
                fbo.bind()
                GLES20.glUseProgram(planar.id)
                GLES20.glUniform1i(planar.uniform("uY"), 0)
                GLES20.glUniform1i(planar.uniform("uU"), 1)
                GLES20.glUniform1i(planar.uniform("uV"), 2)
                GLES20.glUniform1i(planar.uniform("uMode"), mode)
                drawQuad(planar, identity, identity, texUpright)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            }
            else -> return false
        }
        return true
    }

    // ------------------------------------------------------------------ composition

    private fun drawQuad(p: Program, mvpMatrix: FloatArray, texMatrix: FloatArray, tex: java.nio.FloatBuffer) {
        GLES20.glUseProgram(p.id)
        GLES20.glUniformMatrix4fv(p.uniform("uMvp"), 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(p.uniform("uTexM"), 1, false, texMatrix, 0)
        val aPos = p.attrib("aPos")
        val aTex = p.attrib("aTex")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, tex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawTexture(
        p: Program, target: Int, texture: Int, mvpMatrix: FloatArray, texMatrix: FloatArray,
        tex: java.nio.FloatBuffer, alpha: Float = 1f, blendStep: Float = 0f,
    ) {
        GLES20.glUseProgram(p.id)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texture)
        GLES20.glUniform1i(p.uniform("uTex"), 0)
        if (p.uniform("uAlpha") >= 0) GLES20.glUniform1f(p.uniform("uAlpha"), alpha)
        if (p.uniform("uBlendStep") >= 0) GLES20.glUniform1f(p.uniform("uBlendStep"), blendStep)
        drawQuad(p, mvpMatrix, texMatrix, tex)
    }

    /** Model matrix for a rectangle given in frame fractions; frame buffers hold the picture top-down. */
    private fun rectMatrix(left: Float, top: Float, width: Float, height: Float): FloatArray {
        Matrix.setIdentityM(mvp, 0)
        Matrix.translateM(mvp, 0, -1f + 2f * (left + width / 2f), -1f + 2f * (top + height / 2f), 0f)
        Matrix.scaleM(mvp, 0, width, height, 1f)
        return mvp
    }

    private fun compose(ptsNs: Long) {
        val src = srcFbo ?: return
        val turn = rotation
        val swap = turn == 90 || turn == 270
        val outW = if (swap) src.height else src.width
        val outH = if (swap) src.width else src.height
        if (baseFbo?.width != outW || baseFbo?.height != outH) {
            baseFbo?.release(); finalFbo?.release()
            baseFbo = Fbo(outW, outH)
            finalFbo = Fbo(outW, outH)
            outputSize.value = outW to outH
        }
        val base = baseFbo!!
        beforeCompose?.invoke(outW, outH, ptsNs)

        base.bind()
        GLES20.glDisable(GLES20.GL_BLEND)
        Matrix.setIdentityM(mvp, 0)
        Matrix.rotateM(mvp, 0, turn.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(mvp, 0, if (flipHorizontal) -1f else 1f, if (flipVertical) -1f else 1f, 1f)
        drawTexture(rgba, GLES20.GL_TEXTURE_2D, src.texture, mvp, identity, texUpright, 1f, if (deinterlace) 1f / src.height else 0f)
        if (taps.values.any { it.beforeOverlays }) {
            runTaps(base, ptsNs, beforeOverlays = true)
            base.bind()
        }

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        for (layer in surfaceLayers) {
            if (!layer.visible || !layer.hasFrame) continue
            layer.surfaceTexture.getTransformMatrix(texM)
            if (layer.rotation != 0 || layer.mirror) {
                Matrix.translateM(texM, 0, 0.5f, 0.5f, 0f)
                Matrix.rotateM(texM, 0, layer.rotation.toFloat(), 0f, 0f, 1f)
                if (layer.mirror) Matrix.scaleM(texM, 0, -1f, 1f, 1f)
                Matrix.translateM(texM, 0, -0.5f, -0.5f, 0f)
            }
            drawTexture(external, OES, layer.texture, rectMatrix(layer.left, layer.top, layer.width, layer.height), texM, texFlipped)
        }
        drawImageLayers(osd = false)

        var out = base
        if (imageLayers.any { it.osd && it.visible && it.bitmap != null }) {
            out = finalFbo!!
            out.bind()
            GLES20.glDisable(GLES20.GL_BLEND)
            drawTexture(rgba, GLES20.GL_TEXTURE_2D, base.texture, identity, identity, texUpright)
            GLES20.glEnable(GLES20.GL_BLEND)
            drawImageLayers(osd = true)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        output = out
        lastPts = ptsNs
        present(ptsNs)
        runTaps(out, ptsNs, beforeOverlays = false)

        frames++
        if (fpsWindowStart == 0L) fpsWindowStart = ptsNs
        if (ptsNs - fpsWindowStart >= 1_000_000_000L) {
            fps.value = frames * 1e9f / (ptsNs - fpsWindowStart)
            frames = 0
            fpsWindowStart = ptsNs
        }
    }

    private fun drawImageLayers(osd: Boolean) {
        for (layer in imageLayers) {
            val bmp = layer.bitmap
            if (layer.osd != osd || !layer.visible || bmp == null || bmp.isRecycled) continue
            if (layer.texture == 0) layer.texture = newTexture()
            if (layer.uploaded != layer.version) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layer.texture)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                layer.uploaded = layer.version
            }
            drawTexture(rgba, GLES20.GL_TEXTURE_2D, layer.texture, rectMatrix(layer.left, layer.top, layer.width, layer.height), identity, texUpright, layer.alpha)
        }
    }

    /** Draws the composed picture into every window; with ptsNs == 0 windows are only refreshed. */
    private fun present(ptsNs: Long) {
        if (targets.isEmpty()) return
        val out = output
        val base = baseFbo
        val dead = ArrayList<Target>()
        for (t in targets) {
            if (t.encoder && (out == null || ptsNs == 0L)) continue
            if (!egl.makeCurrent(t.surface)) {
                dead += t
                continue
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, t.width, t.height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (out != null && base != null) {
                if (t.encoder) {
                    drawTexture(rgba, GLES20.GL_TEXTURE_2D, out.texture, identity, identity, texFlipped)
                    egl.setPresentationTime(t.surface, ptsNs)
                } else {
                    val picture = if (t.params.showOsd) out else base
                    if (t.params.dual) {
                        GLES20.glViewport(0, 0, t.width / 2, t.height)
                        drawPreview(picture, t.width / 2, t.height, t.params)
                        GLES20.glViewport(t.width / 2, 0, t.width / 2, t.height)
                        drawPreview(picture, t.width / 2, t.height, t.params)
                    } else {
                        drawPreview(picture, t.width, t.height, t.params)
                    }
                }
            }
            if (!egl.swap(t.surface)) dead += t
        }
        egl.makeCurrent()
        for (t in dead) {
            targets.remove(t)
            egl.destroySurface(t.surface)
        }
    }

    private fun drawPreview(picture: Fbo, viewW: Int, viewH: Int, p: PreviewParams) {
        val natural = picture.width.toFloat() / picture.height
        val aspect = when (p.aspectMode) {
            1 -> 16f / 9f; 2 -> 4f / 3f; 3 -> 1f; 6 -> 21f / 9f; 7 -> 9f / 16f; 8 -> 3f / 4f; 9 -> 9f / 21f
            else -> natural
        }
        val view = viewW.toFloat() / viewH
        var sx = 1f
        var sy = 1f
        when (p.aspectMode) {
            5 -> Unit                                              // stretch
            4 -> if (aspect > view) sx = aspect / view else sy = view / aspect      // fill and crop
            else -> if (aspect > view) sy = view / aspect else sx = aspect / view   // fit
        }
        Matrix.setIdentityM(mvp, 0)
        Matrix.translateM(mvp, 0, p.panX, p.panY, 0f)
        Matrix.scaleM(mvp, 0, sx * p.zoom, sy * p.zoom, 1f)
        drawTexture(rgba, GLES20.GL_TEXTURE_2D, picture.texture, mvp, identity, texFlipped)
    }

    private fun readPixels(source: Fbo, maxWidth: Int): Triple<ByteBuffer, Int, Int> {
        var w = source.width
        var h = source.height
        var from = source
        if (maxWidth in 1 until w) {
            w = maxWidth and 1.inv()
            h = (source.height.toLong() * w / source.width).toInt() and 1.inv()
            if (tapFbo?.width != w || tapFbo?.height != h) {
                tapFbo?.release()
                tapFbo = Fbo(w, h)
            }
            from = tapFbo!!
            from.bind()
            drawTexture(rgba, GLES20.GL_TEXTURE_2D, source.texture, identity, identity, texUpright)
        } else {
            from.bind()
        }
        val need = w * h * 4
        val buf = readBuffer?.takeIf { it.capacity() >= need }
            ?: ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder()).also { readBuffer = it }
        buf.clear()
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.limit(need)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return Triple(buf, w, h)
    }

    private fun runTaps(picture: Fbo, ptsNs: Long, beforeOverlays: Boolean) {
        for (tap in taps.values) {
            if (tap.beforeOverlays != beforeOverlays || ptsNs - tap.last < tap.minIntervalNs) continue
            tap.last = ptsNs
            val (buf, w, h) = readPixels(picture, tap.maxWidth)
            try {
                tap.callback(buf, w, h, ptsNs)
            } catch (e: Exception) {
                Log.w(TAG, "tap failed", e)
            }
        }
    }

    // ------------------------------------------------------------------ consumers

    /** Registers (or with null removes) a continuous RGBA read-back. The buffer is valid only during the callback. */
    fun setTap(name: String, tap: Tap?) = handler.post { if (tap == null) taps.remove(name) else taps[name] = tap }

    /** Reads the current composed picture once, top row first. Calls back with null when there is none. */
    fun snapshot(callback: (buffer: ByteBuffer?, width: Int, height: Int) -> Unit) = handler.post {
        val out = output
        if (out == null) callback(null, 0, 0) else {
            val (buf, w, h) = readPixels(out, 0)
            callback(buf, w, h)
        }
    }

    fun addWindow(key: Any, surface: Any, width: Int, height: Int, encoder: Boolean, params: PreviewParams = PreviewParams()) = handler.post {
        removeTarget(key)
        val s = egl.createWindowSurface(surface) ?: return@post
        targets += Target(key, s, width, height, encoder, params)
        if (!encoder) {
            egl.makeCurrent(s)
            egl.setSwapInterval(0)
            egl.makeCurrent()
            present(0)
        }
    }

    fun resizeWindow(key: Any, width: Int, height: Int) = handler.post {
        targets.firstOrNull { it.key == key }?.let { it.width = width; it.height = height }
        present(0)
    }

    fun setWindowParams(key: Any, params: PreviewParams) = handler.post {
        targets.firstOrNull { it.key == key }?.params = params
        present(0)
    }

    /** Detaches a window and waits until the pipeline no longer touches its surface. */
    fun removeWindow(key: Any) = runBlocking { removeTarget(key) }

    private fun removeTarget(key: Any) {
        val t = targets.firstOrNull { it.key == key } ?: return
        targets.remove(t)
        egl.destroySurface(t.surface)
    }

    fun createSurfaceLayer(width: Int, height: Int): SurfaceLayer = runBlocking {
        val tex = newTexture(OES)
        val st = SurfaceTexture(tex)
        st.setDefaultBufferSize(width, height)
        val layer = SurfaceLayer(st, tex)
        st.setOnFrameAvailableListener({
            runCatching { it.updateTexImage() }
            layer.hasFrame = true
        }, handler)
        surfaceLayers += layer
        layer
    }

    fun removeSurfaceLayer(layer: SurfaceLayer) = handler.post {
        surfaceLayers.remove(layer)
        layer.surface.release()
        layer.surfaceTexture.release()
        GLES20.glDeleteTextures(1, intArrayOf(layer.texture), 0)
    }

    fun removeImageLayer(layer: ImageLayer) {
        imageLayers.remove(layer)
        handler.post {
            if (layer.texture != 0) GLES20.glDeleteTextures(1, intArrayOf(layer.texture), 0)
            layer.texture = 0
        }
    }

    /** Redraws the preview windows with the picture already composed (after a setting change). */
    fun refresh() = handler.post { present(0) }

    private fun <T> runBlocking(block: () -> T): T {
        if (Thread.currentThread() == thread) return block()
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        handler.post {
            result = runCatching(block)
            latch.countDown()
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "pipeline thread is not responding" }
        return result!!.getOrThrow()
    }

    companion object {
        private const val TAG = "Pipeline"

        fun fitScale(pictureAspect: Float, viewAspect: Float): Pair<Float, Float> =
            if (pictureAspect > viewAspect) 1f to viewAspect / pictureAspect else pictureAspect / viewAspect to 1f

        @Suppress("unused") private fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
    }
}
