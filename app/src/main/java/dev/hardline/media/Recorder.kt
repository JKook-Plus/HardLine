package dev.hardline.media

import android.media.MediaCodec
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import dev.hardline.core.Keys
import dev.hardline.core.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer

data class RecordingState(val startedAt: Long, val bytes: Long, val paused: Boolean, val motion: Boolean, val fileName: String, val pausedMs: Long = 0)

/**
 * Writes the record stream and the shared AAC stream into MP4 files. A new file is started at a
 * keyframe when the segment length or the 3.5 GB FAT32 guard is reached.
 */
class Recorder(
    private val storage: Storage,
    private val settings: Settings,
    private val video: VideoBroadcast,
    private val audio: AudioBroadcast,
    private val hasAudioInput: () -> Boolean,
    private val deviceName: () -> String?,
    private val onFileFinished: (name: String, uri: Uri, motion: Boolean) -> Unit,
    private val onError: (String) -> Unit,
) : VideoSink, AudioSink {
    val state = MutableStateFlow<RecordingState?>(null)

    private val lock = Any()
    private var active = false
    private var motion = false
    private var paused = false
    private var expectAudio = false
    private var startedMs = 0L
    private var pausedTotalMs = 0L
    private var pauseStartMs = 0L

    private var videoConfig: VideoConfig? = null
    private var audioConfig: AudioConfig? = null
    private var muxer: MediaMuxer? = null
    private var output: Storage.Output? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var needKey = true
    private var split = false
    private var baseUs = 0L
    private var gapUs = 0L
    private var gapStartUs = 0L
    private var lastVideoUs = -1L
    private var lastAudioUs = -1L
    private var bytes = 0L
    private var segmentBytes = 0L
    private var segmentStartMs = 0L
    private var lastStateMs = 0L
    private var openFailed = false
    private val info = MediaCodec.BufferInfo()

    val isRecording: Boolean get() = active
    val isMotionRecording: Boolean get() = active && motion

    fun start(motion: Boolean = false): Boolean {
        synchronized(lock) {
            if (active) return true
            active = true
            this.motion = motion
            paused = false
            expectAudio = hasAudioInput()
            startedMs = SystemClock.elapsedRealtime()
            pausedTotalMs = 0
            bytes = 0
            openFailed = false
            videoConfig = null
            audioConfig = null
            state.value = RecordingState(startedMs, 0, false, motion, "")
        }
        video.subscribe(this)
        if (expectAudio) audio.subscribe(this)
        if (video.lastError != null) {
            onError("Recording could not start: ${video.lastError}")
            stop()
            return false
        }
        return true
    }

    fun stop() {
        synchronized(lock) {
            if (!active) return
            active = false
        }
        video.unsubscribe(this)
        audio.unsubscribe(this)
        synchronized(lock) {
            closeFile()
            state.value = null
        }
    }

    fun setPaused(value: Boolean): Unit = synchronized(lock) {
        if (!active || paused == value) return
        paused = value
        val now = SystemClock.elapsedRealtime()
        if (value) pauseStartMs = now else {
            pausedTotalMs += now - pauseStartMs
            needKey = true
            video.requestKeyFrame()
        }
        publish(force = true)
    }

    override fun onVideoConfig(config: VideoConfig) = synchronized(lock) { videoConfig = config }
    override fun onAudioConfig(config: AudioConfig) = synchronized(lock) { audioConfig = config }

    private fun openFile(): Boolean {
        val vc = videoConfig?.format ?: return false
        // Give the audio encoder a moment to announce its format, then go on without sound.
        if (expectAudio && audioConfig?.format == null && SystemClock.elapsedRealtime() - startedMs < 1500 && muxer == null && bytes == 0L) return false
        val name = storage.fileName(if (motion) "MOTION" else "VID", "mp4", deviceName())
        val out = storage.create(name, "video/mp4", motion)
        if (out == null) {
            openFailed = true
            onError("Could not create the recording file. Check the storage location in Settings.")
            return false
        }
        return try {
            val m = MediaMuxer(out.descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            videoTrack = m.addTrack(vc)
            audioTrack = audioConfig?.format?.let { m.addTrack(it) } ?: -1
            m.start()
            muxer = m
            output = out
            needKey = true
            lastVideoUs = -1
            lastAudioUs = -1
            segmentBytes = 0
            gapUs = 0
            segmentStartMs = SystemClock.elapsedRealtime()
            publish(force = true)
            true
        } catch (e: Exception) {
            Log.w(TAG, "muxer", e)
            out.finish(keep = false)
            openFailed = true
            onError("This device cannot write ${videoConfig?.codec?.label} into MP4: ${e.message}")
            false
        }
    }

    private fun closeFile() {
        val m = muxer ?: return
        val out = output
        muxer = null
        output = null
        val ok = segmentBytes > 0 && runCatching { m.stop() }.isSuccess
        runCatching { m.release() }
        out?.finish(keep = ok)
        if (ok && out != null) onFileFinished(out.name, out.uri, motion)
    }

    override fun onVideoPacket(packet: VideoPacket) {
        var failed = false
        synchronized(lock) {
            if (!active) return
            if (paused) {
                if (gapStartUs == 0L) gapStartUs = packet.ptsUs
                return
            }
            if (muxer == null && !openFile()) {
                if (openFailed) failed = true else return
            }
            if (!failed && split && packet.key) {
                split = false
                closeFile()
                trim()
                if (!openFile()) failed = true
            }
            if (!failed) {
                if (needKey) {
                    if (!packet.key) return
                    needKey = false
                    if (lastVideoUs < 0) baseUs = packet.ptsUs
                    if (gapStartUs != 0L) {
                        gapUs += packet.ptsUs - gapStartUs
                        gapStartUs = 0
                    }
                }
                val pts = maxOf(packet.ptsUs - baseUs - gapUs, lastVideoUs + 1)
                lastVideoUs = pts
                write(videoTrack, packet.data, pts, packet.key)
                val now = SystemClock.elapsedRealtime()
                val minutes = settings[if (motion) Keys.motionSegmentMinutes else Keys.segmentMinutes]
                if (!split && ((minutes > 0 && now - segmentStartMs >= minutes * 60_000L) || (settings[Keys.fourGbLimit] && segmentBytes > FAT32_GUARD))) {
                    split = true
                    video.requestKeyFrame()
                }
                publish(force = false)
            }
        }
        if (failed) stop()
    }

    override fun onAudioPacket(packet: AudioPacket): Unit = synchronized(lock) {
        if (!active || paused || muxer == null || needKey || audioTrack < 0) return
        val pts = maxOf(packet.ptsUs - baseUs - gapUs, lastAudioUs + 1)
        if (pts < 0) return
        lastAudioUs = pts
        write(audioTrack, packet.data, pts, true)
    }

    private fun write(track: Int, data: ByteArray, ptsUs: Long, key: Boolean) {
        info.set(0, data.size, ptsUs, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        try {
            muxer?.writeSampleData(track, ByteBuffer.wrap(data), info)
            bytes += data.size
            segmentBytes += data.size
        } catch (e: Exception) {
            Log.w(TAG, "write failed: ${e.message}")
        }
    }

    /** Loop recording: make room before the next segment. */
    private fun trim() {
        val loop = settings[if (motion) Keys.motionLoopRecording else Keys.loopRecording]
        if (!loop) return
        val limitGb = settings[if (motion) Keys.motionSpaceLimitGb else Keys.spaceLimitGb]
        storage.trim(motion, limitGb * 1_000_000_000L, 500_000_000L, output?.name)
    }

    private fun publish(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastStateMs < 500) return
        lastStateMs = now
        val pausedMs = pausedTotalMs + if (paused) now - pauseStartMs else 0
        state.value = RecordingState(startedMs, bytes, paused, motion, output?.name ?: "", pausedMs)
    }

    companion object {
        private const val TAG = "Recorder"
        private const val FAT32_GUARD = 3_500_000_000L
    }
}
