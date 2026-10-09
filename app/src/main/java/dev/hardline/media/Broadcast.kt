package dev.hardline.media

import android.media.MediaFormat
import android.util.Log
import dev.hardline.gl.Pipeline
import java.util.concurrent.CopyOnWriteArrayList

/** What an encoder should produce. With [passthrough] the camera's own stream is forwarded instead. */
data class EncoderSpec(
    val codec: VideoCodec, val width: Int, val height: Int, val fps: Int, val bitrate: Int,
    val keyframeSeconds: Int, val hardware: Boolean, val h264Profile: Int = 0, val passthrough: Boolean = false,
)

/**
 * One encoded video stream shared by any number of consumers. The encoder exists only while
 * someone is subscribed and a picture is available; [refresh] rebuilds it when the picture changes.
 */
class VideoBroadcast(private val pipeline: Pipeline, private val describe: () -> EncoderSpec?) : VideoSink {
    private val sinks = CopyOnWriteArrayList<VideoSink>()
    private var encoder: VideoEncoder? = null
    private var spec: EncoderSpec? = null
    @Volatile var config: VideoConfig? = null
        private set
    @Volatile var lastError: String? = null
        private set
    val encoderName: String? get() = encoder?.name ?: if (spec?.passthrough == true) "camera stream" else null
    val active: Boolean get() = sinks.isNotEmpty()

    @Synchronized
    fun subscribe(sink: VideoSink) {
        sinks += sink
        config?.let(sink::onVideoConfig)
        if (encoder == null && spec?.passthrough != true) start() else encoder?.requestKeyFrame()
    }

    @Synchronized
    fun unsubscribe(sink: VideoSink) {
        sinks -= sink
        if (sinks.isEmpty()) stop()
    }

    /** Re-evaluates the spec; restarts the encoder when it differs (new size, codec or settings). */
    @Synchronized
    fun refresh() {
        if (sinks.isEmpty()) return
        if (describe() != spec) {
            stop()
            start()
        }
    }

    fun requestKeyFrame() = encoder?.requestKeyFrame()

    private fun start() {
        val s = describe() ?: return
        spec = s
        config = null
        lastError = null
        if (s.passthrough) return
        try {
            val e = VideoEncoder(s.codec, s.width, s.height, s.fps, s.bitrate, s.keyframeSeconds, s.hardware, s.h264Profile, this)
            encoder = e
            pipeline.addWindow(this, e.inputSurface, s.width, s.height, encoder = true)
        } catch (e: Exception) {
            Log.w("VideoBroadcast", "encoder failed", e)
            lastError = e.message
            spec = null
        }
    }

    private fun stop() {
        encoder?.let {
            pipeline.removeWindow(this)
            it.release()
        }
        encoder = null
        spec = null
        config = null
    }

    override fun onVideoConfig(config: VideoConfig) {
        this.config = config
        for (s in sinks) s.onVideoConfig(config)
    }

    override fun onVideoPacket(packet: VideoPacket) {
        for (s in sinks) s.onVideoPacket(packet)
    }

    /** Feeds one access unit of the camera's own H.264/H.265 stream when passing it through. */
    fun feedCameraStream(data: ByteArray, length: Int, ptsNs: Long) {
        val s = spec ?: return
        if (!s.passthrough || sinks.isEmpty()) return
        val hevc = s.codec == VideoCodec.HEVC
        if (config == null) {
            if (!Nal.hasParameterSets(data, 0, length, hevc)) return
            val sets = ArrayList<ByteArray>()
            Nal.forEach(data, 0, length) { start, size ->
                val keep = if (hevc) Nal.h265Type(data, start) in 32..34 else Nal.h264Type(data, start) in 7..8
                if (keep) sets += data.copyOfRange(start, start + size)
            }
            val format = MediaFormat.createVideoFormat(s.codec.mime, s.width, s.height).apply {
                val joined = sets.fold(ByteArray(0)) { acc, n -> acc + byteArrayOf(0, 0, 0, 1) + n }
                if (hevc) setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(joined))
                else {
                    setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sets[0]))
                    if (sets.size > 1) setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sets[1]))
                }
            }
            onVideoConfig(VideoConfig(s.codec, s.width, s.height, sets, format))
        }
        onVideoPacket(VideoPacket(data.copyOf(length), ptsNs / 1000, Nal.isKeyFrame(data, 0, length, hevc)))
    }
}

/** One encoded audio stream (AAC or Opus) shared by any number of consumers. */
class AudioBroadcast(private val engine: AudioEngine, private val mime: String, private val bitrate: Int) : AudioSink {
    private val sinks = CopyOnWriteArrayList<AudioSink>()
    private var encoder: AudioEncoder? = null
    private val feed: (ShortArray, Int, Long) -> Unit = { pcm, samples, pts -> encoder?.feed(pcm, samples, pts) }
    @Volatile var config: AudioConfig? = null
        private set

    @Synchronized
    fun subscribe(sink: AudioSink) {
        sinks += sink
        config?.let(sink::onAudioConfig)
        refresh()
    }

    @Synchronized
    fun unsubscribe(sink: AudioSink) {
        sinks -= sink
        refresh()
    }

    /** Starts or stops the encoder to match whether there are listeners and any audio input. */
    @Synchronized
    fun refresh() {
        val wanted = sinks.isNotEmpty() && engine.hasInput
        if (wanted && encoder == null) {
            try {
                encoder = AudioEncoder(mime, AudioEngine.RATE, 2, bitrate, this)
                engine.addSink(feed)
            } catch (e: Exception) {
                Log.w("AudioBroadcast", "encoder failed", e)
            }
        } else if (!wanted && encoder != null) {
            engine.removeSink(feed)
            encoder?.release()
            encoder = null
            config = null
        }
    }

    override fun onAudioConfig(config: AudioConfig) {
        this.config = config
        for (s in sinks) s.onAudioConfig(config)
    }

    override fun onAudioPacket(packet: AudioPacket) {
        for (s in sinks) s.onAudioPacket(packet)
    }
}
