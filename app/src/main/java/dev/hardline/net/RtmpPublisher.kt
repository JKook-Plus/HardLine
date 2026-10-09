package dev.hardline.net

import android.util.Log
import dev.hardline.media.VideoCodec
import dev.hardline.service.CameraController
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory

/** A saved live-streaming destination. */
data class PushTarget(
    val id: String, val title: String, val url: String, val key: String,
    val srtMode: String = "caller", val srtLatencyMs: Int = -1, val srtPassphrase: String = "",
) {
    val isSrt: Boolean get() = url.startsWith("srt://", true)
    val full: String get() = if (key.isEmpty() || isSrt) url else url.trimEnd('/') + "/" + key

    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("url", url).put("key", key)
        .put("srtMode", srtMode).put("srtLatencyMs", srtLatencyMs).put("srtPassphrase", srtPassphrase)

    companion object {
        fun list(json: String): List<PushTarget> = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                PushTarget(o.getString("id"), o.optString("title"), o.optString("url"), o.optString("key"),
                    o.optString("srtMode", "caller"), o.optInt("srtLatencyMs", -1), o.optString("srtPassphrase"))
            }
        }.getOrDefault(emptyList())

        fun json(list: List<PushTarget>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun isValidUrl(url: String): Boolean = listOf("rtmp://", "rtmps://", "srt://").any { url.startsWith(it, true) } &&
            runCatching { URI(url).host }.getOrNull() != null
    }
}

/** Something that pushes the live stream to a remote ingest and keeps retrying. */
interface Publisher {
    val target: PushTarget
    val status: MutableStateFlow<String>
    fun start()
    fun stop()
}

/** Publishes to an RTMP or RTMPS ingest: H.264 in the classic layout, HEVC and AV1 with FourCC signalling. */
class RtmpPublisher(private val c: CameraController, override val target: PushTarget, private val codec: VideoCodec) : Publisher {
    override val status = MutableStateFlow("Idle")
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var socket: Socket? = null

    override fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "rtmp-publisher").apply { start() }
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
            status.value = if (attempt == 0) "Connecting" else "Reconnecting"
            try {
                session()
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "push failed: ${e.message}")
                status.value = "Reconnecting (${e.message ?: e.javaClass.simpleName})"
            }
            attempt++
            if (running) runCatching { Thread.sleep(3000) }
        }
    }

    private fun session() {
        val uri = URI(target.full)
        val tls = uri.scheme.equals("rtmps", true)
        val port = if (uri.port > 0) uri.port else if (tls) 443 else 1935
        val path = uri.rawPath.trim('/')
        val app = path.substringBeforeLast('/', path)
        val stream = (if ('/' in path) path.substringAfterLast('/') else "") + (uri.rawQuery?.let { "?$it" } ?: "")
        val tcUrl = "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}/$app"

        val s = if (tls) SSLSocketFactory.getDefault().createSocket() else Socket()
        socket = s
        s.connect(InetSocketAddress(uri.host, port), 8000)
        s.tcpNoDelay = true
        s.soTimeout = 15_000
        val out = s.getOutputStream().buffered(65536)
        val input = DataInputStream(s.getInputStream().buffered())

        // Handshake
        val c1 = ByteArray(1536).also { SecureRandom().nextBytes(it); it.fill(0, 0, 8) }
        out.write(3); out.write(c1); out.flush()
        input.readByte()
        val s1 = ByteArray(1536).also(input::readFully)
        input.readFully(ByteArray(1536))
        out.write(s1); out.flush()

        val reader = ChunkReader(input)
        message(out, 2, 1, 0, 0, Bytes().u32(CHUNK).toByteArray())
        command(out, 3, 0, "connect", 1.0, linkedMapOf(
            "app" to app, "type" to "nonprivate", "flashVer" to "FMLE/3.0 (compatible; HardLine)", "tcUrl" to tcUrl,
        ))
        out.flush()
        awaitResult(reader, 1.0)
        command(out, 3, 0, "releaseStream", 2.0, null, stream)
        command(out, 3, 0, "FCPublish", 3.0, null, stream)
        command(out, 3, 0, "createStream", 4.0, null)
        out.flush()
        val streamId = ((awaitResult(reader, 4.0).getOrNull(3) as? Double) ?: 1.0).toInt()
        command(out, 5, streamId, "publish", 5.0, null, stream, "live")
        out.flush()
        awaitStatus(reader)

        s.soTimeout = 0
        // Keep consuming whatever the server sends (acknowledgements, pings) so its buffers never fill.
        Thread({ runCatching { while (running) reader.next() }; runCatching { s.close() } }, "rtmp-reader").apply { isDaemon = true; start() }

        val queue = LinkedBlockingQueue<Triple<Int, Long, ByteArray>>(900)
        var overflow = false
        val audio = c.audio.hasInput
        val flv = FlvSession({ c.pipeline.fps.value }, audio) { type, ts, body, droppable ->
            val payload = if (type == FlvSession.TYPE_SCRIPT) Bytes().also { Amf.string(it, "@setDataFrame") }.raw(body).toByteArray() else body
            if (!queue.offer(Triple(type, ts, payload)) && !droppable) overflow = true
        }
        val broadcast = c.streams.getValue(codec)
        broadcast.subscribe(flv)
        if (audio) c.aac.subscribe(flv)
        try {
            broadcast.lastError?.let { throw IllegalStateException(it) }
            status.value = "Live"
            while (running && !overflow && !s.isClosed) {
                val (type, ts, body) = queue.poll(2, TimeUnit.SECONDS) ?: continue
                message(out, if (type == FlvSession.TYPE_AUDIO) 4 else 6, type, streamId, ts, body)
                if (queue.isEmpty()) out.flush()
            }
            if (overflow) throw IllegalStateException("network too slow")
        } finally {
            broadcast.unsubscribe(flv)
            c.aac.unsubscribe(flv)
            runCatching { s.close() }
        }
    }

    private fun command(out: OutputStream, csid: Int, streamId: Int, name: String, tx: Double, obj: Map<String, Any?>?, vararg args: Any?) {
        val b = Bytes()
        Amf.string(b, name)
        Amf.number(b, tx)
        Amf.value(b, obj)
        for (a in args) Amf.value(b, a)
        message(out, csid, 20, streamId, 0, b.toByteArray())
    }

    /** Writes one message as type-0 and type-3 chunks. */
    private fun message(out: OutputStream, csid: Int, type: Int, streamId: Int, timestamp: Long, payload: ByteArray) {
        val extended = timestamp >= 0xFFFFFF
        val head = Bytes(18).u8(csid).u24(if (extended) 0xFFFFFF else timestamp.toInt()).u24(payload.size).u8(type).u32le(streamId)
        if (extended) head.u32(timestamp)
        out.write(head.toByteArray())
        var offset = 0
        while (offset < payload.size) {
            if (offset > 0) {
                out.write(0xC0 or csid)
                if (extended) out.write(Bytes(4).u32(timestamp).toByteArray())
            }
            val n = minOf(CHUNK, payload.size - offset)
            out.write(payload, offset, n)
            offset += n
        }
    }

    private fun awaitResult(reader: ChunkReader, tx: Double): List<Any?> {
        repeat(50) {
            val (type, body) = reader.next()
            if (type != 20) return@repeat
            val values = Amf.decode(body)
            if (values.getOrNull(1) == tx) {
                if (values.firstOrNull() == "_error") throw IllegalStateException("server refused the connection")
                return values
            }
        }
        throw IllegalStateException("no reply from server")
    }

    private fun awaitStatus(reader: ChunkReader) {
        repeat(50) {
            val (type, body) = reader.next()
            if (type != 20) return@repeat
            val values = Amf.decode(body)
            if (values.firstOrNull() == "onStatus") {
                val code = (values.lastOrNull() as? Map<*, *>)?.get("code") as? String ?: ""
                if (code.startsWith("NetStream.Publish.Start")) return
                throw IllegalStateException(code.ifEmpty { "publish refused" })
            }
        }
    }

    /** Reassembles the server's chunk stream into messages. */
    private class ChunkReader(private val input: DataInputStream) {
        private class State { var timestamp = 0L; var length = 0; var type = 0; var data = java.io.ByteArrayOutputStream() }
        private val states = HashMap<Int, State>()
        private var chunkSize = 128

        fun next(): Pair<Int, ByteArray> {
            while (true) {
                val b0 = input.readUnsignedByte()
                val fmt = b0 shr 6
                var csid = b0 and 0x3F
                if (csid == 0) csid = 64 + input.readUnsignedByte() else if (csid == 1) csid = 64 + input.readUnsignedByte() + (input.readUnsignedByte() shl 8)
                val st = states.getOrPut(csid) { State() }
                if (fmt <= 2) {
                    var ts = (input.readUnsignedByte() shl 16 or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()).toLong()
                    if (fmt <= 1) {
                        st.length = input.readUnsignedByte() shl 16 or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                        st.type = input.readUnsignedByte()
                    }
                    if (fmt == 0) input.readFully(ByteArray(4))
                    if (ts == 0xFFFFFFL) ts = input.readInt().toLong() and 0xFFFFFFFFL
                    st.timestamp = if (fmt == 0) ts else st.timestamp + ts
                }
                val want = minOf(chunkSize, st.length - st.data.size())
                val buf = ByteArray(want).also(input::readFully)
                st.data.write(buf)
                if (st.data.size() >= st.length) {
                    val payload = st.data.toByteArray()
                    st.data.reset()
                    if (st.type == 1 && payload.size >= 4) {
                        chunkSize = ((payload[0].toInt() and 0x7F) shl 24) or ((payload[1].toInt() and 0xFF) shl 16) or ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
                        continue
                    }
                    return st.type to payload
                }
            }
        }
    }

    companion object {
        private const val TAG = "RtmpPublisher"
        private const val CHUNK = 4096
    }
}
