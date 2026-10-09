package dev.hardline.net

import android.util.Base64
import android.util.Log
import dev.hardline.core.Keys
import dev.hardline.media.AudioConfig
import dev.hardline.media.AudioPacket
import dev.hardline.media.AudioSink
import dev.hardline.media.Nal
import dev.hardline.media.VideoCodec
import dev.hardline.media.VideoConfig
import dev.hardline.media.VideoPacket
import dev.hardline.media.VideoSink
import dev.hardline.service.CameraController
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small RTSP 1.0 server for one live stream at /live: H.264 or HEVC video plus AAC audio,
 * over interleaved TCP or UDP, with Digest authentication.
 */
class RtspServer(private val c: CameraController) {
    val running = MutableStateFlow(false)
    val clientCount = MutableStateFlow(0)
    @Volatile var secure = false
        private set
    @Volatile var port = 0
        private set

    private var server: ServerSocket? = null
    private val count = AtomicInteger(0)
    private val random = SecureRandom()

    fun start(): String? {
        if (running.value) return null
        return try {
            val p = c.settings[Keys.rtspPort]
            val s = Tls.serverSocket(c.app, c.settings, p)
            server = s
            port = p
            secure = c.settings[Keys.https]
            running.value = true
            Thread({
                while (running.value) {
                    val socket = try { s.accept() } catch (_: Exception) { break }
                    Thread({ Connection(socket).run() }, "rtsp-client").start()
                }
            }, "rtsp-accept").start()
            null
        } catch (e: Exception) {
            "The RTSP server could not start on port ${c.settings[Keys.rtspPort]}: ${e.message}"
        }
    }

    fun stop() {
        running.value = false
        runCatching { server?.close() }
        server = null
    }

    private class Track(val payloadType: Int, val clockRate: Int) {
        var channel = -1                      // interleaved channel, or -1 for UDP
        var udp: DatagramSocket? = null
        var address: InetAddress? = null
        var clientPort = 0
        var sequence = 0
        val ssrc = SecureRandom().nextInt()
        var packets = 0L
        var octets = 0L
        var lastReport = 0L
        val ready: Boolean get() = channel >= 0 || udp != null
    }

    private inner class Connection(private val socket: Socket) : VideoSink, AudioSink {
        private val input: InputStream = socket.getInputStream().buffered()
        private val output: OutputStream = socket.getOutputStream()
        private val queue = LinkedBlockingQueue<ByteArray>(1500)
        private val nonce = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        private val session = "%08X".format(random.nextInt())
        private val codec = if (c.settings[Keys.rtspCodec] == 1) VideoCodec.HEVC else VideoCodec.H264
        private val video = Track(96, 90000)
        private val audio = Track(97, 48000)
        @Volatile private var videoConfig: VideoConfig? = null
        @Volatile private var audioConfig: AudioConfig? = null
        @Volatile private var playing = false
        @Volatile private var open = true
        private var subscribed = false
        private var needKey = true
        private var withAudio = false

        fun run() {
            count.incrementAndGet()
            val writer = Thread({
                try {
                    while (open) {
                        val data = queue.poll(1, TimeUnit.SECONDS) ?: continue
                        synchronized(output) { output.write(data); if (queue.isEmpty()) output.flush() }
                    }
                } catch (_: Exception) {
                    open = false
                }
            }, "rtsp-writer").apply { start() }
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = 70_000
                while (open && running.value) if (!readMessage()) break
            } catch (e: Exception) {
                Log.d(TAG, "client closed: ${e.message}")
            } finally {
                open = false
                playing = false
                if (subscribed) {
                    c.streams.getValue(codec).unsubscribe(this)
                    c.aac.unsubscribe(this)
                }
                video.udp?.close()
                audio.udp?.close()
                runCatching { socket.close() }
                writer.join(1500)
                if (video.ready || audio.ready) clientCount.value = (clientCount.value - 1).coerceAtLeast(0)
                count.decrementAndGet()
            }
        }

        private fun readMessage(): Boolean {
            val first = input.read()
            if (first < 0) return false
            if (first == '$'.code) {                 // interleaved data from the client (receiver reports): skip
                input.read()
                val len = (input.read() shl 8) or input.read()
                var left = len
                while (left > 0) { val n = input.skip(left.toLong()).toInt(); if (n <= 0) return false; left -= n }
                return true
            }
            val sb = StringBuilder().append(first.toChar())
            while (!sb.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0 || sb.length > 8192) return false
                sb.append(b.toChar())
            }
            val lines = sb.toString().trim().split("\r\n")
            val parts = lines[0].split(" ")
            if (parts.size < 2) return false
            val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            headers["content-length"]?.toIntOrNull()?.let { n -> repeat(n) { input.read() } }
            handle(parts[0].uppercase(), parts[1], headers)
            return true
        }

        private fun reply(cseq: String?, status: String = "200 OK", extra: String = "", body: String = "") {
            val sb = StringBuilder("RTSP/1.0 $status\r\nCSeq: ${cseq ?: "0"}\r\nServer: HardLine\r\n").append(extra)
            if (body.isNotEmpty()) sb.append("Content-Length: ${body.toByteArray().size}\r\n")
            sb.append("\r\n").append(body)
            synchronized(output) { output.write(sb.toString().toByteArray()); output.flush() }
        }

        private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun authorised(method: String, headers: Map<String, String>): Boolean {
            val user = c.settings[Keys.serverUser]
            val password = c.settings[Keys.serverPassword]
            if (user.isEmpty() && password.isEmpty()) return true
            val auth = headers["authorization"] ?: return false
            if (!auth.startsWith("Digest", true)) return false
            val fields = Regex("(\\w+)=\"?([^\",]*)\"?").findAll(auth.substring(6)).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            if (fields["username"] != user || fields["nonce"] != nonce) return false
            val expected = md5(md5("$user:$REALM:$password") + ":" + nonce + ":" + md5("$method:${fields["uri"]}"))
            return MessageDigest.isEqual(expected.toByteArray(), (fields["response"] ?: "").toByteArray())
        }

        private fun handle(method: String, uri: String, h: Map<String, String>) {
            val cseq = h["cseq"]
            if (method != "OPTIONS" && !authorised(method, h)) {
                return reply(cseq, "401 Unauthorized", "WWW-Authenticate: Digest realm=\"$REALM\", nonce=\"$nonce\"\r\n")
            }
            when (method) {
                "OPTIONS" -> reply(cseq, extra = "Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER\r\n")
                "DESCRIBE" -> describe(cseq, uri)
                "SETUP" -> setup(cseq, uri, h["transport"] ?: "")
                "PLAY" -> {
                    needKey = true
                    playing = true
                    c.streams.getValue(codec).requestKeyFrame()
                    reply(cseq, extra = "Session: $session\r\nRange: npt=0.000-\r\n")
                }
                "PAUSE" -> { playing = false; reply(cseq, extra = "Session: $session\r\n") }
                "TEARDOWN" -> { reply(cseq, extra = "Session: $session\r\n"); open = false }
                "GET_PARAMETER", "SET_PARAMETER" -> reply(cseq, extra = "Session: $session\r\n")
                else -> reply(cseq, "405 Method Not Allowed")
            }
        }

        private fun describe(cseq: String?, uri: String) {
            if (!subscribed) {
                subscribed = true
                withAudio = c.audio.hasInput
                c.streams.getValue(codec).subscribe(this)
                if (withAudio) c.aac.subscribe(this)
            }
            val deadline = System.currentTimeMillis() + 8000
            while (videoConfig == null && System.currentTimeMillis() < deadline && open) Thread.sleep(40)
            val vc = videoConfig ?: return reply(cseq, "503 Service Unavailable")
            val audioDeadline = System.currentTimeMillis() + 1500
            while (withAudio && audioConfig == null && System.currentTimeMillis() < audioDeadline) Thread.sleep(40)
            fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
            val sdp = StringBuilder("v=0\r\no=- ${System.currentTimeMillis()} 1 IN IP4 0.0.0.0\r\ns=${c.pageTitle()}\r\nt=0 0\r\na=control:*\r\na=range:npt=now-\r\n")
            sdp.append("m=video 0 RTP/AVP 96\r\nc=IN IP4 0.0.0.0\r\n")
            if (codec == VideoCodec.HEVC) {
                val vps = vc.sets.firstOrNull { Nal.h265Type(it, 0) == 32 }
                val sps = vc.sets.firstOrNull { Nal.h265Type(it, 0) == 33 }
                val pps = vc.sets.firstOrNull { Nal.h265Type(it, 0) == 34 }
                sdp.append("a=rtpmap:96 H265/90000\r\n")
                if (vps != null && sps != null && pps != null) sdp.append("a=fmtp:96 sprop-vps=${b64(vps)};sprop-sps=${b64(sps)};sprop-pps=${b64(pps)}\r\n")
            } else {
                val sps = vc.sets.firstOrNull { Nal.h264Type(it, 0) == 7 }
                val pps = vc.sets.firstOrNull { Nal.h264Type(it, 0) == 8 }
                sdp.append("a=rtpmap:96 H264/90000\r\n")
                if (sps != null && pps != null && sps.size >= 4) {
                    val profile = "%02X%02X%02X".format(sps[1], sps[2], sps[3])
                    sdp.append("a=fmtp:96 packetization-mode=1;profile-level-id=$profile;sprop-parameter-sets=${b64(sps)},${b64(pps)}\r\n")
                }
            }
            sdp.append("a=control:track1\r\n")
            audioConfig?.let { ac ->
                val hex = ac.specific.joinToString("") { "%02x".format(it) }
                sdp.append("m=audio 0 RTP/AVP 97\r\nc=IN IP4 0.0.0.0\r\na=rtpmap:97 MPEG4-GENERIC/48000/2\r\n")
                sdp.append("a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=$hex\r\n")
                sdp.append("a=control:track2\r\n")
            }
            reply(cseq, extra = "Content-Base: ${uri.trimEnd('/')}/\r\nContent-Type: application/sdp\r\n", body = sdp.toString())
        }

        private fun setup(cseq: String?, uri: String, transport: String) {
            val track = if (uri.trimEnd('/').endsWith("track2")) audio else video
            val wasReady = video.ready || audio.ready
            val interleaved = Regex("interleaved=(\\d+)-(\\d+)").find(transport)
            val clientPort = Regex("client_port=(\\d+)-(\\d+)").find(transport)
            val answer = when {
                transport.contains("TCP", true) -> {
                    track.channel = interleaved?.groupValues?.get(1)?.toInt() ?: if (track === video) 0 else 2
                    "RTP/AVP/TCP;unicast;interleaved=${track.channel}-${track.channel + 1}"
                }
                clientPort != null -> {
                    val udp = DatagramSocket()
                    track.udp = udp
                    track.address = socket.inetAddress
                    track.clientPort = clientPort.groupValues[1].toInt()
                    "RTP/AVP;unicast;client_port=${clientPort.groupValues[1]}-${clientPort.groupValues[2]};server_port=${udp.localPort}-${udp.localPort + 1}"
                }
                else -> return reply(cseq, "461 Unsupported Transport")
            }
            if (!wasReady) clientCount.value = clientCount.value + 1
            reply(cseq, extra = "Transport: $answer;ssrc=${"%08X".format(track.ssrc)}\r\nSession: $session;timeout=60\r\n")
        }

        // ---- RTP

        private fun rtp(track: Track, marker: Boolean, timestamp: Long, write: (Bytes) -> Unit) {
            if (!track.ready) return
            val b = Bytes(1500)
            if (track.channel >= 0) b.u8('$'.code).u8(track.channel).u16(0)
            b.u8(0x80).u8((if (marker) 0x80 else 0) or track.payloadType).u16(track.sequence and 0xFFFF).u32(timestamp).u32(track.ssrc)
            track.sequence++
            write(b)
            val data = b.toByteArray()
            track.packets++
            if (track.channel >= 0) {
                val len = data.size - 4
                data[2] = (len shr 8).toByte(); data[3] = len.toByte()
                track.octets += len - 12
                if (!queue.offer(data)) { needKey = true; queue.clear() }      // client too slow: resynchronise at a keyframe
            } else {
                track.octets += data.size - 12
                runCatching { track.udp?.send(DatagramPacket(data, data.size, track.address, track.clientPort)) }
            }
        }

        private fun senderReport(track: Track, ptsUs: Long, timestamp: Long) {
            val now = System.currentTimeMillis()
            if (now - track.lastReport < 5000 || !track.ready) return
            track.lastReport = now
            val wallUs = now * 1000 - (System.nanoTime() / 1000 - ptsUs)
            val seconds = wallUs / 1_000_000 + 2208988800L
            val fraction = (wallUs % 1_000_000) * 4294967296L / 1_000_000
            val b = Bytes(40)
            if (track.channel >= 0) b.u8('$'.code).u8(track.channel + 1).u16(28)
            b.u8(0x80).u8(200).u16(6).u32(track.ssrc).u32(seconds).u32(fraction).u32(timestamp).u32(track.packets).u32(track.octets)
            val data = b.toByteArray()
            if (track.channel >= 0) queue.offer(data)
            else runCatching { track.udp?.send(DatagramPacket(data, data.size, track.address, track.clientPort + 1)) }
        }

        override fun onVideoConfig(config: VideoConfig) { videoConfig = config }
        override fun onAudioConfig(config: AudioConfig) { audioConfig = config }

        override fun onVideoPacket(packet: VideoPacket) {
            if (!playing) return
            if (needKey) { if (!packet.key) return else needKey = false }
            val ts = packet.ptsUs * 9 / 100
            val hevc = codec == VideoCodec.HEVC
            val nals = ArrayList<IntArray>()
            Nal.forEach(packet.data, 0, packet.data.size) { s, n -> if (n > 0) nals += intArrayOf(s, n) }
            if (packet.key && !Nal.hasParameterSets(packet.data, 0, packet.data.size, hevc)) {
                videoConfig?.sets?.forEach { set -> rtp(video, false, ts) { it.raw(set) } }
            }
            for ((i, nal) in nals.withIndex()) {
                val last = i == nals.size - 1
                val start = nal[0]
                val size = nal[1]
                val d = packet.data
                if (size <= MTU) {
                    rtp(video, last, ts) { it.raw(d, start, size) }
                    continue
                }
                val headerSize = if (hevc) 2 else 1
                var offset = start + headerSize
                val end = start + size
                while (offset < end) {
                    val chunk = minOf(MTU - 3, end - offset)
                    val first = offset == start + headerSize
                    val final = offset + chunk == end
                    val flags = (if (first) 0x80 else 0) or (if (final) 0x40 else 0)
                    rtp(video, last && final, ts) { b ->
                        if (hevc) {
                            b.u8((d[start].toInt() and 0x81) or (49 shl 1)).u8(d[start + 1].toInt()).u8(flags or Nal.h265Type(d, start))
                        } else {
                            b.u8((d[start].toInt() and 0xE0) or 28).u8(flags or Nal.h264Type(d, start))
                        }
                        b.raw(d, offset, chunk)
                    }
                    offset += chunk
                }
            }
            senderReport(video, packet.ptsUs, ts)
        }

        override fun onAudioPacket(packet: AudioPacket) {
            if (!playing || needKey) return
            val ts = packet.ptsUs * 48 / 1000
            rtp(audio, true, ts) { it.u16(16).u16(packet.data.size shl 3).raw(packet.data) }
            senderReport(audio, packet.ptsUs, ts)
        }
    }

    companion object {
        private const val TAG = "RtspServer"
        private const val REALM = "HardLine"
        private const val MTU = 1400
    }
}
