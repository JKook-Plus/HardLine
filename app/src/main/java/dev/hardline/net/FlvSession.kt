package dev.hardline.net

import dev.hardline.media.AudioConfig
import dev.hardline.media.AudioPacket
import dev.hardline.media.AudioSink
import dev.hardline.media.VideoConfig
import dev.hardline.media.VideoPacket
import dev.hardline.media.VideoSink

/**
 * Turns encoded packets into FLV message bodies with stream-relative timestamps: metadata and
 * sequence headers first, then frames starting at the first keyframe. Used by both the HTTP-FLV
 * endpoint and the RTMP publisher.
 */
class FlvSession(
    private val fps: () -> Float,
    private val expectAudio: Boolean,
    private val emit: (type: Int, timestampMs: Long, body: ByteArray, droppable: Boolean) -> Unit,
) : VideoSink, AudioSink {
    private var video: VideoConfig? = null
    private var audioSent = false
    private var baseUs = -1L
    private var started = false

    @Synchronized
    override fun onVideoConfig(config: VideoConfig) {
        video = config
        val header = Flv.videoHeader(config) ?: return
        emit(TYPE_SCRIPT, 0, Flv.metadata(config, fps(), expectAudio), false)
        emit(TYPE_VIDEO, 0, header, false)
        started = false
    }

    @Synchronized
    override fun onVideoPacket(packet: VideoPacket) {
        val v = video ?: return
        if (!started) {
            if (!packet.key) return
            started = true
            if (baseUs < 0) baseUs = packet.ptsUs
        }
        emit(TYPE_VIDEO, maxOf(0, (packet.ptsUs - baseUs) / 1000), Flv.videoFrame(v.codec, packet), !packet.key)
    }

    @Synchronized
    override fun onAudioConfig(config: AudioConfig) {
        emit(TYPE_AUDIO, 0, Flv.audioHeader(config.specific), false)
        audioSent = true
    }

    @Synchronized
    override fun onAudioPacket(packet: AudioPacket) {
        if (!started || !audioSent || packet.ptsUs < baseUs) return
        emit(TYPE_AUDIO, (packet.ptsUs - baseUs) / 1000, Flv.audioFrame(packet.data), false)
    }

    companion object {
        const val TYPE_AUDIO = 8
        const val TYPE_VIDEO = 9
        const val TYPE_SCRIPT = 18
    }
}
