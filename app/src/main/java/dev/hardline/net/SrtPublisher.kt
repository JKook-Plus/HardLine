package dev.hardline.net

import android.util.Log
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
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Packs H.264/HEVC video and AAC audio into an MPEG transport stream. */
class TsMuxer(private val hevc: Boolean, private val withAudio: Boolean, private val out: (ByteArray) -> Unit) {
    private val counters = HashMap<Int, Int>()
    private var baseUs = -1L
    private var lastTables = 0L
    private val packet = ByteArray(188)

    private fun crc(data: ByteArray, length: Int): Int {
        var crc = -1
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }

    private fun section(pid: Int, body: ByteArray) {
        val s = Bytes().raw(body)
        val withCrc = s.u32(crc(body, body.size)).toByteArray()
        packet.fill(0xFF.toByte())
        packet[0] = 0x47; packet[1] = (0x40 or (pid shr 8)).toByte(); packet[2] = pid.toByte()
        packet[3] = (0x10 or next(pid)).toByte(); packet[4] = 0
        System.arraycopy(withCrc, 0, packet, 5, withCrc.size)
        out(packet.copyOf())
    }

    private fun next(pid: Int): Int = (counters[pid] ?: 0).also { counters[pid] = (it + 1) and 0x0F }

    private fun tables() {
        section(0, byteArrayOf(0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, 0x00, 0x01, 0xF0.toByte(), 0x00))
        val streams = Bytes().u8(if (hevc) 0x24 else 0x1B).u8(0xE0 or (VIDEO_PID shr 8)).u8(VIDEO_PID and 0xFF).u8(0xF0).u8(0)
        if (withAudio) streams.u8(0x0F).u8(0xE0 or (AUDIO_PID shr 8)).u8(AUDIO_PID and 0xFF).u8(0xF0).u8(0)
        val length = 9 + streams.size + 4
        section(PMT_PID, Bytes().u8(0x02).u8(0xB0 or (length shr 8)).u8(length and 0xFF).u16(1).u8(0xC1).u8(0).u8(0)
            .u8(0xE0 or (VIDEO_PID shr 8)).u8(VIDEO_PID and 0xFF).u8(0xF0).u8(0).raw(streams.toByteArray()).toByteArray())
    }

    private fun pes(pid: Int, streamId: Int, pts90: Long, payload: ByteArray, pcr: Boolean, keyframe: Boolean) {
        val header = Bytes(19).u8(0).u8(0).u8(1).u8(streamId)
        val pesLength = if (streamId == 0xE0) 0 else payload.size + 8
        header.u16(pesLength).u8(0x80).u8(0x80).u8(5)
            .u8(0x21 or ((pts90 shr 29).toInt() and 0x0E)).u8((pts90 shr 22).toInt() and 0xFF).u8(0x01 or ((pts90 shr 14).toInt() and 0xFE))
            .u8((pts90 shr 7).toInt() and 0xFF).u8(0x01 or ((pts90 shl 1).toInt() and 0xFE))
        val data = header.toByteArray() + payload
        var offset = 0
        var first = true
        while (offset < data.size) {
            packet[0] = 0x47
            packet[1] = ((if (first) 0x40 else 0) or (pid shr 8)).toByte()
            packet[2] = pid.toByte()
            var p = 4
            val left = data.size - offset
            var adaptation = 0
            if (first && pcr) adaptation = 8
            val room = 184 - adaptation
            if (left < room) adaptation = 184 - left
            if (adaptation > 0) {
                packet[3] = (0x30 or next(pid)).toByte()
                packet[4] = (adaptation - 1).toByte()
                if (adaptation > 1) {
                    packet[5] = 0
                    var q = 6
                    if (first && pcr) {
                        packet[5] = (0x10 or (if (keyframe) 0x40 else 0)).toByte()
                        val base = maxOf(0L, pts90 - PTS_OFFSET)
                        packet[6] = (base shr 25).toByte(); packet[7] = (base shr 17).toByte(); packet[8] = (base shr 9).toByte()
                        packet[9] = (base shr 1).toByte(); packet[10] = (((base and 1) shl 7) or 0x7E).toByte(); packet[11] = 0
                        q = 12
                    }
                    for (i in q until 4 + adaptation) packet[i] = 0xFF.toByte()
                }
                p = 4 + adaptation
            } else {
                packet[3] = (0x10 or next(pid)).toByte()
            }
            val n = 188 - p
            System.arraycopy(data, offset, packet, p, n)
            offset += n
            first = false
            out(packet.copyOf())
        }
    }

    fun video(config: VideoConfig?, p: VideoPacket) {
        if (baseUs < 0) baseUs = p.ptsUs
        val now = System.currentTimeMillis()
        if (p.key || now - lastTables > 500) {
            lastTables = now
            tables()
        }
        val au = ByteArrayOutputStream(p.data.size + 64)
        au.write(if (hevc) byteArrayOf(0, 0, 0, 1, 0x46, 0x01, 0x50) else byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte()))
        if (p.key && config != null && !Nal.hasParameterSets(p.data, 0, p.data.size, hevc)) {
            for (set in config.sets) { au.write(byteArrayOf(0, 0, 0, 1)); au.write(set) }
        }
        au.write(p.data)
        pes(VIDEO_PID, 0xE0, (p.ptsUs - baseUs) * 9 / 100 + PTS_OFFSET, au.toByteArray(), pcr = true, keyframe = p.key)
    }

    fun audio(p: AudioPacket) {
        if (baseUs < 0 || p.ptsUs < baseUs) return
        val len = p.data.size + 7
        val adts = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x4C, (0x80 or (len shr 11)).toByte(), (len shr 3).toByte(), (((len and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte())
        pes(AUDIO_PID, 0xC0, (p.ptsUs - baseUs) * 9 / 100 + PTS_OFFSET, adts + p.data, pcr = false, keyframe = false)
    }

    companion object {
        private const val PMT_PID = 0x1000
        private const val VIDEO_PID = 0x100
        private const val AUDIO_PID = 0x101
        private const val PTS_OFFSET = 27000L        // 300 ms ahead of the clock reference
    }
}

/** The stream cipher of an SRT connection: AES in counter mode with a key agreed during the handshake. */
internal class SrtCrypto(private val salt: ByteArray, private val key: ByteArray, val message: ByteArray) {
    private val spec = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/CTR/NoPadding")

    /** The counter block is the salt with the packet sequence number mixed in; its low 16 bits count blocks. */
    fun encrypt(sequence: Int, payload: ByteArray): ByteArray {
        val iv = ByteArray(16)
        System.arraycopy(salt, 0, iv, 0, 14)
        for (i in 0 until 4) iv[10 + i] = (iv[10 + i].toInt() xor (sequence ushr (24 - 8 * i))).toByte()
        cipher.init(Cipher.ENCRYPT_MODE, spec, IvParameterSpec(iv))
        return cipher.doFinal(payload)
    }

    companion object {
        private val WRAP_IV = ByteArray(8) { 0xA6.toByte() }

        private fun keyEncryptingKey(passphrase: String, salt: ByteArray, length: Int): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(PBEKeySpec(passphrase.toCharArray(), salt.copyOfRange(8, 16), 2048, length * 8)).encoded

        /** AES key wrap (RFC 3394). */
        private fun wrap(kek: ByteArray, plain: ByteArray): ByteArray {
            val aes = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES")) }
            val n = plain.size / 8
            var a = WRAP_IV.copyOf()
            val r = plain.copyOf()
            for (j in 0..5) for (i in 1..n) {
                val b = aes.doFinal(a + r.copyOfRange((i - 1) * 8, i * 8))
                a = b.copyOfRange(0, 8)
                val t = n * j + i
                a[7] = (a[7].toInt() xor t).toByte()
                a[6] = (a[6].toInt() xor (t shr 8)).toByte()
                System.arraycopy(b, 8, r, (i - 1) * 8, 8)
            }
            return a + r
        }

        private fun unwrap(kek: ByteArray, wrapped: ByteArray): ByteArray? {
            val aes = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES")) }
            val n = wrapped.size / 8 - 1
            var a = wrapped.copyOfRange(0, 8)
            val r = wrapped.copyOfRange(8, wrapped.size)
            for (j in 5 downTo 0) for (i in n downTo 1) {
                val t = n * j + i
                a[7] = (a[7].toInt() xor t).toByte()
                a[6] = (a[6].toInt() xor (t shr 8)).toByte()
                val b = aes.doFinal(a + r.copyOfRange((i - 1) * 8, i * 8))
                a = b.copyOfRange(0, 8)
                System.arraycopy(b, 8, r, (i - 1) * 8, 8)
            }
            return if (a.contentEquals(WRAP_IV)) r else null
        }

        /** Creates a fresh 128-bit key and the key-material message that carries it to the peer. */
        fun create(passphrase: String): SrtCrypto {
            val random = SecureRandom()
            val salt = ByteArray(16).also(random::nextBytes)
            val key = ByteArray(16).also(random::nextBytes)
            val wrapped = wrap(keyEncryptingKey(passphrase, salt, key.size), key)
            val message = Bytes(32 + wrapped.size).u8(0x12).u16(0x2029).u8(1).u32(0).u8(2).u8(0).u8(2).u8(0).u16(0).u8(4).u8(key.size / 4)
                .raw(salt).raw(wrapped).toByteArray()
            return SrtCrypto(salt, key, message)
        }

        /** Opens the key-material message of a peer. Returns null when the passphrase does not fit. */
        fun open(passphrase: String, message: ByteArray): SrtCrypto? {
            if (message.size < 16 || message[0].toInt() != 0x12 || message[8].toInt() != 2) return null
            val both = message[3].toInt() and 3 == 3
            val saltLength = (message[14].toInt() and 0xFF) * 4
            val keyLength = (message[15].toInt() and 0xFF) * 4
            val wrappedLength = (if (both) 2 else 1) * keyLength + 8
            if (saltLength != 16 || keyLength !in listOf(16, 24, 32) || message.size < 16 + saltLength + wrappedLength) return null
            val salt = message.copyOfRange(16, 32)
            val keys = unwrap(keyEncryptingKey(passphrase, salt, keyLength), message.copyOfRange(32, 32 + wrappedLength)) ?: return null
            // With two keys in the message the even one comes first; that is the one used for sending.
            val odd = message[3].toInt() and 3 == 2
            return SrtCrypto(salt, keys.copyOfRange(0, keyLength), message.copyOf(32 + wrappedLength)).also { it.odd = odd }
        }
    }

    var odd = false
        private set

    /** The key flag carried in every encrypted data packet. */
    val flag: Long get() = if (odd) 0x10000000L else 0x08000000L
}

/**
 * Publishes an MPEG transport stream over SRT (live transmission, with retransmission of lost
 * packets on request). The phone either calls a listening server or listens for a receiver that
 * calls in; a passphrase turns on AES encryption.
 */
class SrtPublisher(private val c: CameraController, override val target: PushTarget, private val codec: VideoCodec) : Publisher {
    override val status = MutableStateFlow("Idle")
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var socket: DatagramSocket? = null

    private class Link(val peerId: Int, val firstSequence: Int, val crypto: SrtCrypto?)

    override fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "srt-publisher").apply { start() }
    }

    override fun stop() {
        running = false
        runCatching { socket?.close() }
        thread?.interrupt()
        status.value = "Idle"
    }

    private fun loop() {
        var attempt = 0
        while (running) {
            val listener = target.srtMode == "listener"
            status.value = if (listener) "Waiting for a receiver" else if (attempt == 0) "Connecting" else "Reconnecting"
            try {
                session(listener)
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "push failed: ${e.message}")
                status.value = "${if (listener) "Waiting for a receiver" else "Reconnecting"} (${e.message ?: e.javaClass.simpleName})"
            } finally {
                runCatching { socket?.close() }
            }
            attempt++
            if (running) runCatching { Thread.sleep(if (listener) 500 else 3000) }
        }
    }

    private val start = System.nanoTime()
    private fun clock() = ((System.nanoTime() - start) / 1000).toInt()
    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, at: Int) = (u16(b, at) shl 16) or u16(b, at + 2)

    private fun handshake(destination: Int, version: Int, encryption: Int, extension: Int, isn: Int, type: Int, id: Int, cookie: Int, extras: ByteArray = ByteArray(0)): ByteArray =
        Bytes(96 + extras.size).u32(0x80000000L).u32(0).u32(clock()).u32(destination)
            .u32(version).u16(encryption).u16(extension).u32(isn).u32(1500).u32(8192).u32(type).u32(id).u32(cookie)
            .u32(0x0100007F).u32(0).u32(0).u32(0).raw(extras).toByteArray()

    /** The extension blocks that follow a handshake of version 5, by type. */
    private fun extensions(packet: ByteArray): Map<Int, ByteArray> {
        val out = HashMap<Int, ByteArray>()
        var i = 64
        while (i + 4 <= packet.size) {
            val length = u16(packet, i + 2) * 4
            if (i + 4 + length > packet.size) break
            out[u16(packet, i)] = packet.copyOfRange(i + 4, i + 4 + length)
            i += 4 + length
        }
        return out
    }

    private fun receive(udp: DatagramSocket): Pair<ByteArray, SocketAddress> {
        val buf = ByteArray(1500)
        val p = DatagramPacket(buf, buf.size)
        udp.receive(p)
        return buf.copyOf(p.length) to p.socketAddress
    }

    /** Calls a listening server: induction, then conclusion with our settings, stream id and key. */
    private fun call(udp: DatagramSocket, uri: URI, latency: Int, streamId: String, passphrase: String): Link {
        udp.connect(InetSocketAddress(uri.host, if (uri.port > 0) uri.port else 9000))
        udp.soTimeout = 3000
        val random = SecureRandom()
        val ownId = random.nextInt() and 0x3FFFFFFF
        val isn = random.nextInt() and 0x7FFFFFFF
        fun send(b: ByteArray) = udp.send(DatagramPacket(b, b.size))

        send(handshake(0, 4, 0, 2, isn, 1, ownId, 0))
        val induction = receive(udp).first
        if (induction.size < 64 || u32(induction, 16) != 5) throw IllegalStateException("not an SRT listener")
        val cookie = u32(induction, 44)
        val crypto = passphrase.takeIf { it.isNotEmpty() }?.let(SrtCrypto::create)
        val ext = Bytes().u16(1).u16(3).u32(VERSION).u32(FLAGS).u16(latency).u16(latency)
        var flags = 1
        if (crypto != null) {
            ext.u16(3).u16(crypto.message.size / 4).raw(crypto.message)
            flags = flags or 2
        }
        if (streamId.isNotEmpty()) {
            val raw = streamId.toByteArray()
            val padded = raw.copyOf((raw.size + 3) / 4 * 4)
            ext.u16(5).u16(padded.size / 4)
            for (i in padded.indices step 4) ext.u8(padded[i + 3].toInt()).u8(padded[i + 2].toInt()).u8(padded[i + 1].toInt()).u8(padded[i].toInt())
            flags = flags or 4
        }
        val conclusionRequest = handshake(0, 5, if (crypto != null) 2 else 0, flags, isn, -1, ownId, cookie, ext.toByteArray())
        send(conclusionRequest)
        var conclusion = receive(udp).first
        // A repeated induction reply may still be on its way; skip it.
        if (conclusion.size >= 64 && u32(conclusion, 36) == 1) { send(conclusionRequest); conclusion = receive(udp).first }
        val result = if (conclusion.size >= 64) u32(conclusion, 36) else 0
        if (result != -1) throw IllegalStateException(when {
            result == 1010 || result == 1011 -> "the passphrase was not accepted"
            result >= 1000 -> "rejected by the server (code $result)"
            else -> "handshake failed"
        })
        if (crypto != null) {
            val reply = extensions(conclusion)[4]
            if (reply == null || reply.size <= 4) throw IllegalStateException("the passphrase was not accepted")
        }
        return Link(u32(conclusion, 40), isn, crypto)
    }

    /** Waits on [port] for a receiver to call in and answers its handshake. */
    private fun listen(udp: DatagramSocket, latency: Int, passphrase: String): Link {
        udp.soTimeout = 1000
        val random = SecureRandom()
        val ownId = random.nextInt() and 0x3FFFFFFF
        val cookie = random.nextInt()
        while (running) {
            val (p, from) = try { receive(udp) } catch (_: SocketTimeoutException) { continue }
            if (p.size < 64 || u32(p, 0) != 0x80000000.toInt()) continue
            val peerId = u32(p, 40)
            fun send(b: ByteArray) = udp.send(DatagramPacket(b, b.size, from))
            when (u32(p, 36)) {
                1 -> send(handshake(peerId, 5, 0, 0x4A17, u32(p, 24), 1, ownId, cookie))
                -1 -> {
                    if (u32(p, 44) != cookie || u32(p, 16) != 5) continue
                    val ext = extensions(p)
                    val request = ext[1] ?: continue
                    val key = ext[3]
                    val reject: (Int, String) -> Nothing = { code, why ->
                        send(handshake(peerId, 5, 0, 0, u32(p, 24), code, ownId, cookie))
                        throw IllegalStateException(why)
                    }
                    val crypto = when {
                        passphrase.isEmpty() && key != null -> reject(1011, "the receiver uses a passphrase but none is set here")
                        passphrase.isNotEmpty() && key == null -> reject(1011, "the receiver did not use the passphrase")
                        key != null -> SrtCrypto.open(passphrase, key) ?: reject(1010, "the receiver's passphrase is different")
                        else -> null
                    }
                    val agreed = maxOf(latency, if (request.size >= 12) maxOf(u16(request, 8), u16(request, 10)) else 0)
                    val reply = Bytes().u16(2).u16(3).u32(VERSION).u32(FLAGS).u16(agreed).u16(agreed)
                    if (crypto != null) reply.u16(4).u16(crypto.message.size / 4).raw(crypto.message)
                    // The sending sequence starts at the number the caller proposed.
                    send(handshake(peerId, 5, u16(p, 20), if (crypto != null) 3 else 1, u32(p, 24), -1, ownId, cookie, reply.toByteArray()))
                    udp.connect(from)
                    return Link(peerId, u32(p, 24), crypto)
                }
            }
        }
        throw IllegalStateException("stopped")
    }

    private fun session(listener: Boolean) {
        val uri = URI(target.url)
        val query = (uri.rawQuery ?: "").split('&').filter { '=' in it }.associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val streamId = target.key.ifEmpty { query["streamid"] ?: "" }
        val latency = (if (target.srtLatencyMs > 0) target.srtLatencyMs else query["latency"]?.toIntOrNull() ?: 120).coerceIn(20, 8000)
        val passphrase = target.srtPassphrase.ifEmpty { query["passphrase"] ?: "" }

        val udp = if (listener) DatagramSocket(if (uri.port > 0) uri.port else 9000) else DatagramSocket()
        socket = udp
        val link = if (listener) listen(udp, latency, passphrase) else call(udp, uri, latency, streamId, passphrase)
        udp.soTimeout = 3000
        val peerId = link.peerId
        fun send(b: ByteArray) = udp.send(DatagramPacket(b, b.size))

        val history = arrayOfNulls<ByteArray>(HISTORY)
        var sequence = link.firstSequence
        var message = 1
        val sendLock = Any()
        val queue = LinkedBlockingQueue<ByteArray>(4000)
        var overflow = false
        val lastHeard = AtomicLong(System.currentTimeMillis())

        fun control(type: Int, info: Int, body: ByteArray = ByteArray(0)) =
            send(Bytes(32).u32(0x80000000L or (type.toLong() shl 16)).u32(info).u32(clock()).u32(peerId).raw(body).toByteArray())

        val reader = Thread({
            try {
                while (running && !udp.isClosed) {
                    val p = try { receive(udp).first } catch (_: SocketTimeoutException) { continue }
                    if (p.size < 16 || p[0].toInt() and 0x80 == 0) continue
                    lastHeard.set(System.currentTimeMillis())
                    when (((p[0].toInt() and 0x7F) shl 8) or (p[1].toInt() and 0xFF)) {
                        2 -> if (u32(p, 4) != 0) control(6, u32(p, 4))                      // ACK -> ACKACK
                        3 -> synchronized(sendLock) {                                       // NAK: resend what was lost
                            var i = 16
                            while (i + 4 <= p.size) {
                                var from = u32(p, i)
                                var to = from
                                if (from < 0 && i + 8 <= p.size) { from = from and 0x7FFFFFFF; to = u32(p, i + 4); i += 4 }
                                i += 4
                                var s = from
                                var guard = 0
                                while (guard++ < HISTORY) {
                                    history[s % HISTORY]?.takeIf { u32(it, 0) == s }?.let { old ->
                                        val again = old.copyOf()
                                        again[4] = (again[4].toInt() or 0x04).toByte()
                                        send(again)
                                    }
                                    if (s == to) break
                                    s = (s + 1) and 0x7FFFFFFF
                                }
                            }
                        }
                        5 -> { overflow = true; return@Thread }                              // shutdown
                    }
                }
            } catch (_: Exception) {
            }
        }, "srt-reader").apply { isDaemon = true; start() }

        val group = ByteArrayOutputStream(1316)
        val audio = c.audio.hasInput
        val broadcast = c.streams.getValue(codec)
        var config: VideoConfig? = null
        var started = false
        val mux = TsMuxer(codec == VideoCodec.HEVC, audio) { ts ->
            group.write(ts)
            if (group.size() >= 1316) {
                if (!queue.offer(group.toByteArray())) overflow = true
                group.reset()
            }
        }
        val sink = object : VideoSink, AudioSink {
            override fun onVideoConfig(config_: VideoConfig) { config = config_ }
            override fun onAudioConfig(config: AudioConfig) = Unit
            override fun onVideoPacket(packet: VideoPacket): Unit = synchronized(mux) {
                if (!started) { if (!packet.key) return else started = true }
                mux.video(config, packet)
            }

            override fun onAudioPacket(packet: AudioPacket) = synchronized(mux) { if (started) mux.audio(packet) }
        }
        broadcast.subscribe(sink)
        if (audio) c.aac.subscribe(sink)
        try {
            broadcast.lastError?.let { throw IllegalStateException(it) }
            broadcast.requestKeyFrame()
            status.value = if (link.crypto != null) "Live (encrypted)" else "Live"
            var lastKeepAlive = System.currentTimeMillis()
            val keyFlag = link.crypto?.flag ?: 0L
            while (running && !overflow) {
                val payload = queue.poll(500, TimeUnit.MILLISECONDS)
                val now = System.currentTimeMillis()
                if (now - lastHeard.get() > 8000) throw IllegalStateException("connection timed out")
                if (payload == null) {
                    if (now - lastKeepAlive > 1000) { control(1, 0, ByteArray(4)); lastKeepAlive = now }
                    continue
                }
                val body = link.crypto?.encrypt(sequence, payload) ?: payload
                val data = Bytes(body.size + 16).u32(sequence).u32(0xC0000000L or keyFlag or (message.toLong() and 0x03FFFFFF)).u32(clock()).u32(peerId).raw(body).toByteArray()
                synchronized(sendLock) {
                    history[sequence % HISTORY] = data
                    send(data)
                }
                sequence = (sequence + 1) and 0x7FFFFFFF
                message = if (message >= 0x03FFFFFF) 1 else message + 1
            }
            if (overflow) throw IllegalStateException("connection closed")
        } finally {
            broadcast.unsubscribe(sink)
            c.aac.unsubscribe(sink)
            runCatching { control(5, 0) }
            runCatching { udp.close() }
            reader.join(500)
        }
    }

    companion object {
        private const val TAG = "SrtPublisher"
        private const val HISTORY = 4096
        private const val VERSION = 0x00010401L
        private const val FLAGS = 0xBFL
    }
}
