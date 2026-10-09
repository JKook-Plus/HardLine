package dev.hardline.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Decodes an H.264 or H.265 elementary stream from the camera onto [surface]. Access units are
 * queued from the USB thread and fed from a worker; input starts at the first access unit that
 * carries parameter sets.
 */
class H26xDecoder(private val hevc: Boolean, width: Int, height: Int, surface: Surface, preferHardware: Boolean) {
    private class Unit(val data: ByteArray, val ptsUs: Long)

    val codecName: String
    val hardware: Boolean
    private val codec: MediaCodec
    private val queue = ArrayBlockingQueue<Unit>(30)
    @Volatile private var running = true
    private var started = false
    private val worker: Thread

    init {
        val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, width, height)
        val name = pick(mime, format, preferHardware)
        codec = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime)
        codecName = codec.name
        hardware = !isSoftware(codec.codecInfo)
        codec.configure(format, surface, null, 0)
        codec.start()
        worker = Thread(::loop, "h26x-decoder").apply { start() }
    }

    fun submit(data: ByteArray, length: Int, ptsNs: Long) {
        if (!running) return
        if (!started) {
            if (!Nal.hasParameterSets(data, 0, length, hevc)) return
            started = true
        }
        if (!queue.offer(Unit(data.copyOf(length), ptsNs / 1000))) {
            // Fell behind: drop everything and resynchronise on the next keyframe.
            queue.clear()
            started = false
        }
    }

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val unit = queue.poll(20, TimeUnit.MILLISECONDS)
                if (unit != null) {
                    var index = -1
                    while (running && index < 0) {
                        index = codec.dequeueInputBuffer(10_000)
                        drain(info)
                    }
                    if (index >= 0) {
                        codec.getInputBuffer(index)!!.apply { clear(); put(unit.data) }
                        codec.queueInputBuffer(index, 0, unit.data.size, unit.ptsUs, 0)
                    }
                }
                drain(info)
            }
        } catch (e: Exception) {
            Log.w("H26xDecoder", "decoder stopped", e)
        }
    }

    private fun drain(info: MediaCodec.BufferInfo) {
        while (running) {
            val out = codec.dequeueOutputBuffer(info, 0)
            if (out >= 0) codec.releaseOutputBuffer(out, true) else if (out == MediaCodec.INFO_TRY_AGAIN_LATER) return
        }
    }

    fun close() {
        running = false
        worker.join(1000)
        runCatching { codec.stop() }
        codec.release()
    }

    companion object {
        fun isSoftware(info: MediaCodecInfo): Boolean =
            if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
            else info.name.lowercase().let { it.startsWith("omx.google.") || it.startsWith("c2.android.") }

        private fun pick(mime: String, format: MediaFormat, hardware: Boolean): String? =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } && isSoftware(info) != hardware &&
                    runCatching { info.getCapabilitiesForType(mime).isFormatSupported(format) }.getOrDefault(false)
            }?.name
    }
}
