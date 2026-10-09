package dev.hardline.media

import dev.hardline.gl.Pipeline
import dev.hardline.usb.PixelKind
import dev.hardline.usb.VideoListener
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Receives frames from the camera on the USB thread and routes them: uncompressed frames go
 * straight to the pipeline, Motion-JPEG through [MjpegDecoder], H.264/H.265 through [H26xDecoder].
 * Compressed frames are also offered to [onCompressed] so they can be forwarded untouched.
 */
class VideoIngest(
    private val pipeline: Pipeline,
    val kind: PixelKind,
    private val width: Int,
    private val height: Int,
    private val preferHardwareDecoder: Boolean,
    dropBrokenJpeg: () -> Boolean,
    private val onCompressed: (data: ByteArray, length: Int, ptsNs: Long) -> Unit,
    private val onButton: (pressed: Boolean) -> Unit,
) : VideoListener {
    private val pool = BufferPool()
    private val mjpeg = if (kind == PixelKind.MJPEG) MjpegDecoder(pipeline, dropBrokenJpeg) else null
    private var decoder: H26xDecoder? = null
    private var scratch = ByteArray(0)
    val frames = AtomicLong(0)

    /** Name of the video decoder in use, once a compressed stream has started. */
    val decoderName: String? get() = decoder?.let { (if (it.hardware) "HW " else "SW ") + it.codecName }

    override fun onFrame(data: ByteBuffer, size: Int, width: Int, height: Int, ptsNs: Long) {
        frames.incrementAndGet()
        when (kind) {
            PixelKind.MJPEG -> {
                val a = mjpeg!!.obtain(size)
                data.get(a, 0, size)
                onCompressed(a, size, ptsNs)
                mjpeg.submit(a, size, ptsNs)
            }
            PixelKind.H264, PixelKind.H265 -> {
                if (scratch.size < size) scratch = ByteArray(size + size / 2)
                data.get(scratch, 0, size)
                onCompressed(scratch, size, ptsNs)
                val d = decoder ?: runCatching {
                    H26xDecoder(kind == PixelKind.H265, this.width, this.height, pipeline.createSourceSurface(this.width, this.height), preferHardwareDecoder)
                }.getOrNull()?.also { decoder = it } ?: return
                d.submit(scratch, size, ptsNs)
            }
            PixelKind.UNSUPPORTED -> Unit
            else -> {
                if (!pipeline.canAccept()) return
                val b = pool.obtain(size)
                b.put(data)
                b.flip()
                pipeline.submitRaw(kind, b, width, height, ptsNs) { pool.recycle(b) }
            }
        }
    }

    override fun onButton(button: Int, state: Int) = onButton(state != 0)

    fun close() {
        mjpeg?.close()
        decoder?.close()
        decoder = null
    }
}
