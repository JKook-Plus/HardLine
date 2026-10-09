package dev.hardline.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

enum class VideoCodec(val mime: String, val label: String) {
    H264(MediaFormat.MIMETYPE_VIDEO_AVC, "H.264"),
    HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC, "HEVC"),
    AV1(MediaFormat.MIMETYPE_VIDEO_AV1, "AV1");

    companion object {
        fun of(index: Int) = entries.getOrElse(index) { H264 }
    }
}

/** One encoded picture. H.264/HEVC data is Annex-B; AV1 is a temporal unit of OBUs. */
class VideoPacket(val data: ByteArray, val ptsUs: Long, val key: Boolean)

/**
 * Stream parameters. [sets] holds the raw parameter sets without start codes: SPS, PPS for H.264;
 * VPS, SPS, PPS for HEVC; the sequence-header OBU for AV1. [format] is what a muxer needs.
 */
class VideoConfig(val codec: VideoCodec, val width: Int, val height: Int, val sets: List<ByteArray>, val format: MediaFormat?)

class AudioPacket(val data: ByteArray, val ptsUs: Long)
class AudioConfig(val sampleRate: Int, val channels: Int, val specific: ByteArray, val format: MediaFormat?)

interface VideoSink {
    fun onVideoConfig(config: VideoConfig)
    fun onVideoPacket(packet: VideoPacket)
}

interface AudioSink {
    fun onAudioConfig(config: AudioConfig)
    fun onAudioPacket(packet: AudioPacket)
}

internal fun ByteBuffer.toArray(): ByteArray = ByteArray(remaining()).also { duplicate().get(it) }

internal fun splitParameterSets(csd: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    Nal.forEach(csd, 0, csd.size) { s, n -> out += csd.copyOfRange(s, s + n) }
    return out
}

/** Finds the sequence-header OBU inside an AV1 temporal unit or configuration record. */
internal fun av1SequenceHeader(data: ByteArray): ByteArray? {
    var i = 0
    while (i < data.size) {
        val header = data[i].toInt() and 0xFF
        val type = (header shr 3) and 0x0F
        val hasSize = header and 0x02 != 0
        var p = i + 1 + (if (header and 0x04 != 0) 1 else 0)
        if (!hasSize) return if (type == 1) data.copyOfRange(i, data.size) else null
        var size = 0
        var shift = 0
        while (p < data.size) {
            val b = data[p++].toInt() and 0xFF
            size = size or ((b and 0x7F) shl shift)
            shift += 7
            if (b and 0x80 == 0) break
        }
        if (p + size > data.size) return null
        if (type == 1) return data.copyOfRange(i, p + size)
        i = p + size
    }
    return null
}

/** A hardware (or software) video encoder fed through an input surface. */
class VideoEncoder(
    val codec: VideoCodec, val width: Int, val height: Int, fps: Int, bitrate: Int, keyframeSeconds: Int,
    preferHardware: Boolean, h264Profile: Int, private val sink: VideoSink,
) {
    private val thread = HandlerThread("video-encoder").apply { start() }
    private val mediaCodec: MediaCodec
    val inputSurface: Surface
    val name: String
    @Volatile private var released = false
    private var configSent = false

    init {
        var created: MediaCodec? = null
        var lastError: Exception? = null
        // Try the preferred kind of encoder first, then the other, then plain settings.
        for (attempt in 0..3) {
            val hardware = if (attempt < 2) preferHardware else !preferHardware
            val detailed = attempt % 2 == 0
            val format = MediaFormat.createVideoFormat(codec.mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(1, 120))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeSeconds.coerceAtLeast(1))
                if (detailed) {
                    if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                    if (codec == VideoCodec.H264 && h264Profile > 0) {
                        setInteger(MediaFormat.KEY_PROFILE, if (h264Profile == 1) MediaCodecInfo.CodecProfileLevel.AVCProfileMain else MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                    }
                }
            }
            val pick = pickEncoder(codec.mime, width, height, hardware) ?: continue
            val c = runCatching { MediaCodec.createByCodecName(pick) }.getOrNull() ?: continue
            try {
                c.setCallback(callback, Handler(thread.looper))
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                created = c
                break
            } catch (e: Exception) {
                lastError = e
                c.release()
            }
        }
        mediaCodec = created ?: run {
            thread.quitSafely()
            throw IllegalStateException("No ${codec.label} encoder accepts ${width}x$height", lastError)
        }
        name = mediaCodec.name
        inputSurface = mediaCodec.createInputSurface()
        mediaCodec.start()
    }

    private val callback: MediaCodec.Callback
        get() = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) = Unit
            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                Log.w("VideoEncoder", "$name: ${e.diagnosticInfo}")
            }

            override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
                val sets = ArrayList<ByteArray>()
                for (key in listOf("csd-0", "csd-1", "csd-2")) {
                    val b = format.getByteBuffer(key)?.toArray() ?: continue
                    if (codec == VideoCodec.AV1) av1SequenceHeader(b)?.let { sets += it } else sets += splitParameterSets(b)
                }
                if (sets.isNotEmpty() || codec == VideoCodec.AV1) {
                    configSent = sets.isNotEmpty()
                    sink.onVideoConfig(VideoConfig(codec, width, height, sets, format))
                }
            }

            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (released) return
                try {
                    val buf = c.getOutputBuffer(index)
                    if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        buf.position(info.offset).limit(info.offset + info.size)
                        val data = buf.toArray()
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        if (!configSent && key && codec == VideoCodec.AV1) {
                            av1SequenceHeader(data)?.let {
                                configSent = true
                                sink.onVideoConfig(VideoConfig(codec, width, height, listOf(it), c.outputFormat))
                            }
                        }
                        sink.onVideoPacket(VideoPacket(data, info.presentationTimeUs, key))
                    }
                    c.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    Log.w("VideoEncoder", "output dropped: ${e.message}")
                }
            }
        }

    fun requestKeyFrame() {
        if (released) return
        runCatching { mediaCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { mediaCodec.stop() }
        mediaCodec.release()
        inputSurface.release()
        thread.quitSafely()
    }

    companion object {
        fun pickEncoder(mime: String, width: Int, height: Int, hardware: Boolean): String? =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } && H26xDecoder.isSoftware(info) != hardware &&
                    runCatching { info.getCapabilitiesForType(mime).videoCapabilities.isSizeSupported(width, height) }.getOrDefault(false)
            }?.name

        fun isSupported(codec: VideoCodec, width: Int, height: Int): Boolean =
            pickEncoder(codec.mime, width, height, true) != null || pickEncoder(codec.mime, width, height, false) != null
    }
}

/** Encodes 16-bit PCM to AAC-LC or Opus on its own thread. */
class AudioEncoder(mime: String, private val sampleRate: Int, private val channels: Int, bitrate: Int, private val sink: AudioSink) {
    private class Chunk(val pcm: ShortArray, val samples: Int, val ptsUs: Long)

    private val codec: MediaCodec = MediaCodec.createEncoderByType(mime)
    private val queue = ArrayBlockingQueue<Chunk>(50)
    @Volatile private var running = true
    private val worker: Thread

    init {
        val format = MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        worker = Thread(::loop, "audio-encoder").apply { start() }
    }

    /** [pcm] is interleaved; it is copied. */
    fun feed(pcm: ShortArray, samples: Int, ptsUs: Long) {
        if (running) queue.offer(Chunk(pcm.copyOf(samples), samples, ptsUs))
    }

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val chunk = queue.poll(20, TimeUnit.MILLISECONDS)
                if (chunk != null) {
                    var index = -1
                    while (running && index < 0) {
                        index = codec.dequeueInputBuffer(10_000)
                        drain(info)
                    }
                    if (index >= 0) {
                        val buf = codec.getInputBuffer(index)!!
                        buf.clear()
                        buf.asShortBuffer().put(chunk.pcm, 0, chunk.samples)
                        codec.queueInputBuffer(index, 0, chunk.samples * 2, chunk.ptsUs, 0)
                    }
                }
                drain(info)
            }
        } catch (e: Exception) {
            Log.w("AudioEncoder", "stopped", e)
        }
    }

    private fun drain(info: MediaCodec.BufferInfo) {
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sink.onAudioConfig(AudioConfig(sampleRate, channels, f.getByteBuffer("csd-0")?.toArray() ?: ByteArray(0), f))
                }
                index >= 0 -> {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        buf.position(info.offset).limit(info.offset + info.size)
                        sink.onAudioPacket(AudioPacket(buf.toArray(), info.presentationTimeUs))
                    }
                    codec.releaseOutputBuffer(index, false)
                }
                else -> return
            }
        }
    }

    fun release() {
        running = false
        worker.join(1000)
        runCatching { codec.stop() }
        codec.release()
    }
}
