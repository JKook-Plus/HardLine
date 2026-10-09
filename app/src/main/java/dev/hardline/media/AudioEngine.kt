package dev.hardline.media

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import dev.hardline.usb.AudioListener
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Collects audio from up to three sources (the USB device, the phone's microphone and the
 * system's own playback), mixes them into one 48 kHz stereo stream in 20 ms blocks and hands
 * that to encoders, the level meter and the monitor output.
 */
class AudioEngine {
    private class Ring(private val capacity: Int) {
        private val data = ShortArray(capacity)
        private var head = 0
        private var size = 0

        @Synchronized fun available() = size

        @Synchronized fun write(src: ShortArray, count: Int) {
            for (i in 0 until count) {
                data[(head + size) % capacity] = src[i]
                if (size < capacity) size++ else head = (head + 1) % capacity
            }
        }

        @Synchronized fun read(dst: ShortArray, count: Int): Int {
            val n = minOf(count, size)
            for (i in 0 until n) dst[i] = data[(head + i) % capacity]
            head = (head + n) % capacity
            size -= n
            return n
        }

        @Synchronized fun skip(count: Int) {
            val n = minOf(count, size)
            head = (head + n) % capacity
            size -= n
        }

        @Synchronized fun clear() {
            size = 0
        }
    }

    private class Source {
        val ring = Ring(RATE * 2)
        @Volatile var gain = 1f
        @Volatile var active = false
        private var primed = false

        /** Fills [dst] with [count] samples, absorbing delivery jitter; returns false when silent. */
        fun pull(dst: ShortArray, count: Int): Boolean {
            if (!active) return false
            val avail = ring.available()
            if (!primed) {
                if (avail < count * 2) return false
                primed = true
            }
            if (avail > RATE / 2) ring.skip(avail - count * 3)      // keep latency under a quarter second
            val n = ring.read(dst, count)
            if (n < count) {
                dst.fill(0, n, count)
                primed = false
            }
            return true
        }

        fun reset(on: Boolean) {
            ring.clear()
            primed = false
            active = on
        }
    }

    private val usb = Source()
    private val mic = Source()
    private val system = Source()
    private val sinks = CopyOnWriteArrayList<(pcm: ShortArray, samples: Int, ptsUs: Long) -> Unit>()

    /** Peak level of the mix, 0..1. */
    val level = MutableStateFlow(0f)

    /** Whether any sound source is switched on; observable by the user interface. */
    val active = MutableStateFlow(false)
    val hasInput: Boolean get() = usb.active || mic.active || system.active
    val usbActive: Boolean get() = usb.active
    val micActive: Boolean get() = mic.active
    val systemActive: Boolean get() = system.active

    @Volatile var monitor = false
    @Volatile var limitMonitorVolume = true
    var usbGain: Float
        get() = usb.gain
        set(v) { usb.gain = v }
    var micGain: Float
        get() = mic.gain
        set(v) { mic.gain = v }
    var systemGain: Float
        get() = system.gain
        set(v) { system.gain = v }

    @Volatile private var running = false
    private var mixer: Thread? = null
    private var micRecord: AudioRecord? = null
    private var systemRecord: AudioRecord? = null
    private var micThread: Thread? = null
    private var systemThread: Thread? = null

    // --- USB input: converts whatever rate the device runs at to 48 kHz
    private var usbScratch = ShortArray(0)
    private var usbOut = ShortArray(0)
    private var resamplePos = 0.0
    private var prevL: Short = 0
    private var prevR: Short = 0

    val usbListener = object : AudioListener {
        override fun onAudio(data: ByteBuffer, frames: Int, sampleRate: Int) {
            if (!usb.active) return
            if (usbScratch.size < frames * 2) usbScratch = ShortArray(frames * 2)
            data.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(usbScratch, 0, frames * 2)
            if (sampleRate == RATE) return usb.ring.write(usbScratch, frames * 2)
            val step = sampleRate.toDouble() / RATE
            val maxOut = ((frames + 1) / step).toInt() + 2
            if (usbOut.size < maxOut * 2) usbOut = ShortArray(maxOut * 2)
            var n = 0
            // Position -1..0 lies between the last frame of the previous block and the first of this one.
            while (resamplePos < frames - 1) {
                val i = kotlin.math.floor(resamplePos).toInt()
                val f = (resamplePos - i).toFloat()
                val l0 = if (i < 0) prevL else usbScratch[i * 2]
                val r0 = if (i < 0) prevR else usbScratch[i * 2 + 1]
                usbOut[n++] = (l0 + (usbScratch[(i + 1) * 2] - l0) * f).toInt().toShort()
                usbOut[n++] = (r0 + (usbScratch[(i + 1) * 2 + 1] - r0) * f).toInt().toShort()
                resamplePos += step
            }
            resamplePos -= frames
            prevL = usbScratch[(frames - 1) * 2]
            prevR = usbScratch[(frames - 1) * 2 + 1]
            usb.ring.write(usbOut, n)
        }
    }

    fun setUsbActive(on: Boolean) {
        resamplePos = 0.0
        usb.reset(on)
        ensureMixer()
    }

    // --- phone microphone and system playback

    @SuppressLint("MissingPermission")
    fun startMicrophone(): Boolean {
        if (mic.active) return true
        val record = runCatching { buildRecord(MediaRecorder.AudioSource.CAMCORDER, null) }.getOrNull() ?: return false
        micRecord = record
        mic.reset(true)
        micThread = reader(record, mic, "mic-reader")
        ensureMixer()
        return true
    }

    fun stopMicrophone() {
        mic.reset(false)
        micThread?.join(500)
        micRecord?.run { runCatching { stop() }; release() }
        micRecord = null
        ensureMixer()
    }

    @SuppressLint("MissingPermission")
    fun startSystemCapture(projection: MediaProjection): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        if (system.active) return true
        val record = runCatching { buildRecord(0, projection) }.getOrNull() ?: return false
        systemRecord = record
        system.reset(true)
        systemThread = reader(record, system, "system-audio-reader")
        ensureMixer()
        return true
    }

    fun stopSystemCapture() {
        system.reset(false)
        systemThread?.join(500)
        systemRecord?.run { runCatching { stop() }; release() }
        systemRecord = null
        ensureMixer()
    }

    @SuppressLint("MissingPermission")
    private fun buildRecord(source: Int, projection: MediaProjection?): AudioRecord {
        val format = AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val size = maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT), FRAME * 8)
        val builder = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(size)
        if (projection != null && Build.VERSION.SDK_INT >= 29) {
            builder.setAudioPlaybackCaptureConfig(
                AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).excludeUid(Process.myUid()).build(),
            )
        } else builder.setAudioSource(source)
        return builder.build().also {
            check(it.state == AudioRecord.STATE_INITIALIZED) { "audio input unavailable" }
            it.startRecording()
        }
    }

    private fun reader(record: AudioRecord, into: Source, name: String) = Thread({
        val buf = ShortArray(FRAME * 2)
        while (into.active) {
            val n = record.read(buf, 0, buf.size)
            if (n > 0) into.ring.write(buf, n) else if (n < 0) break
        }
    }, name).apply { start() }

    // --- mixer

    fun addSink(sink: (pcm: ShortArray, samples: Int, ptsUs: Long) -> Unit) { sinks += sink }
    fun removeSink(sink: (pcm: ShortArray, samples: Int, ptsUs: Long) -> Unit) { sinks -= sink }

    private fun ensureMixer() {
        active.value = hasInput
        if (hasInput && !running) {
            running = true
            mixer = Thread(::mixLoop, "audio-mixer").apply { priority = Thread.MAX_PRIORITY; start() }
        } else if (!hasInput && running) {
            running = false
            mixer?.join(500)
            mixer = null
            level.value = 0f
        }
    }

    private fun mixLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val samples = FRAME * 2
        val mix = IntArray(samples)
        val out = ShortArray(samples)
        val tmp = ShortArray(samples)
        val monitorOut = ShortArray(samples)
        var track: AudioTrack? = null
        var next = System.nanoTime()
        var produced = 0L
        var startUs = System.nanoTime() / 1000
        try {
            while (running) {
                mix.fill(0)
                var monitorHas = false
                for (src in arrayOf(usb, mic, system)) {
                    if (!src.pull(tmp, samples)) continue
                    val g = src.gain
                    for (i in 0 until samples) mix[i] += (tmp[i] * g).toInt()
                    if (src === usb) {
                        System.arraycopy(tmp, 0, monitorOut, 0, samples)
                        monitorHas = true
                    }
                }
                var peak = 0
                for (i in 0 until samples) {
                    val v = mix[i].coerceIn(-32768, 32767)
                    out[i] = v.toShort()
                    if (abs(v) > peak) peak = abs(v)
                }
                level.value = peak / 32768f

                var ptsUs = startUs + produced * 1_000_000L / RATE
                val nowUs = System.nanoTime() / 1000
                if (abs(nowUs - ptsUs) > 200_000) {       // clock slipped (pause, suspend): restart the timeline
                    startUs = nowUs
                    produced = 0
                    ptsUs = nowUs
                }
                produced += FRAME
                for (s in sinks) s(out, samples, ptsUs)

                // The monitor plays the USB source only, so the phone's microphone cannot feed back.
                if (monitor && monitorHas) {
                    val t = track ?: newTrack().also { track = it }
                    val volume = if (limitMonitorVolume && mic.active) 0.3f else 1f
                    t.setVolume(volume)
                    t.write(monitorOut, 0, samples, AudioTrack.WRITE_NON_BLOCKING)
                } else if (track != null) {
                    track?.release()
                    track = null
                }

                next += 20_000_000L
                val wait = next - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt()) else if (wait < -200_000_000L) next = System.nanoTime()
            }
        } catch (e: Exception) {
            Log.w("AudioEngine", "mixer stopped", e)
        } finally {
            track?.release()
        }
    }

    private fun newTrack(): AudioTrack {
        val format = AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val size = maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT), FRAME * 16)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(format).setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build().apply { play() }
    }

    fun shutdown() {
        setUsbActive(false)
        stopMicrophone()
        stopSystemCapture()
    }

    companion object {
        const val RATE = 48000
        const val FRAME = 960
    }
}
