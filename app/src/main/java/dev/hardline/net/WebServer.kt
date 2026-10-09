package dev.hardline.net

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import dev.hardline.core.Keys
import dev.hardline.gl.Pipeline
import dev.hardline.media.AudioConfig
import dev.hardline.media.AudioPacket
import dev.hardline.media.AudioSink
import dev.hardline.media.VideoCodec
import dev.hardline.service.CameraController
import dev.hardline.usb.PixelKind
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The built-in web server: a small control page, Motion-JPEG and FLV video, Ogg/Opus audio,
 * still pictures, the list of saved files, and a JSON API to drive the camera remotely.
 */
class WebServer(private val c: CameraController) {
    data class Client(val kind: String, val address: String, val agent: String)

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: InputStream)

    val running = MutableStateFlow(false)
    val clients = MutableStateFlow<List<Client>>(emptyList())
    @Volatile var secure = false
        private set
    @Volatile var port = 0
        private set

    private var server: ServerSocket? = null
    private val workers = Executors.newCachedThreadPool { Thread(it, "http").apply { isDaemon = true } }
    private val clientList = CopyOnWriteArrayList<Client>()
    private val mjpeg = MjpegSource()

    /** Starts listening. Returns an error message, or null on success. */
    fun start(): String? {
        if (running.value) return null
        return try {
            val p = c.settings[Keys.httpPort]
            val s = Tls.serverSocket(c.app, c.settings, p)
            server = s
            secure = c.settings[Keys.https]
            port = p
            running.value = true
            Thread({ acceptLoop(s) }, "http-accept").start()
            null
        } catch (e: Exception) {
            Log.w(TAG, "start", e)
            "The web server could not start on port ${c.settings[Keys.httpPort]}: ${e.message}"
        }
    }

    fun stop() {
        running.value = false
        runCatching { server?.close() }
        server = null
        clientList.clear()
        clients.value = emptyList()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running.value) {
            val socket = try { s.accept() } catch (_: Exception) { break }
            workers.execute {
                try {
                    socket.soTimeout = 15_000
                    socket.tcpNoDelay = true
                    handle(socket)
                } catch (e: Exception) {
                    Log.d(TAG, "client: ${e.message}")
                } finally {
                    runCatching { socket.close() }
                }
            }
        }
    }

    // ------------------------------------------------------------------ request handling

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var state = 0
        while (state < 4) {
            val b = input.read()
            if (b < 0 || head.size() > 16384) return null
            head.write(b)
            state = if ((state % 2 == 0 && b == '\r'.code) || (state % 2 == 1 && b == '\n'.code)) state + 1 else if (b == '\r'.code) 1 else 0
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val first = lines[0].split(" ")
        if (first.size < 2) return null
        val target = first[1]
        val path = URLDecoder.decode(target.substringBefore('?'), "UTF-8")
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        return Request(first[0].uppercase(), path, query, headers, input)
    }

    private fun authorised(r: Request): Boolean {
        val user = c.settings[Keys.serverUser]
        val password = c.settings[Keys.serverPassword]
        if (user.isEmpty() && password.isEmpty()) return true
        val given = r.headers["authorization"]?.takeIf { it.startsWith("Basic ", true) }?.substring(6)?.trim() ?: return false
        val expected = Base64.encodeToString("$user:$password".toByteArray(), Base64.NO_WRAP)
        return MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())
    }

    private fun head(out: OutputStream, status: String, type: String, length: Long = -1, extra: String = "") {
        val sb = StringBuilder("HTTP/1.1 $status\r\nServer: HardLine\r\nContent-Type: $type\r\n")
        if (length >= 0) sb.append("Content-Length: $length\r\n")
        sb.append("Cache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n").append(extra).append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun send(out: OutputStream, status: String, type: String, body: ByteArray) {
        head(out, status, type, body.size.toLong())
        out.write(body)
        out.flush()
    }

    private fun json(out: OutputStream, o: JSONObject, status: String = "200 OK") = send(out, status, "application/json", o.toString().toByteArray())
    private fun ok(out: OutputStream, message: String = "ok") = json(out, JSONObject().put("ok", true).put("message", message))
    private fun fail(out: OutputStream, status: String, message: String) = json(out, JSONObject().put("ok", false).put("message", message), status)

    private fun handle(socket: Socket) {
        val input = socket.getInputStream().buffered()
        val out = socket.getOutputStream().buffered(65536)
        val r = readRequest(input) ?: return
        if (!authorised(r)) {
            head(out, "401 Unauthorized", "text/plain", 0, "WWW-Authenticate: Basic realm=\"HardLine\"\r\n")
            out.flush()
            return
        }
        val agent = r.headers["user-agent"] ?: ""
        val address = socket.inetAddress.hostAddress ?: "?"
        when {
            r.method == "OPTIONS" -> {
                head(out, "204 No Content", "text/plain", 0, "Access-Control-Allow-Methods: GET, POST, PUT, DELETE\r\nAccess-Control-Allow-Headers: *\r\n")
                out.flush()
            }
            r.path == "/" || r.path == "/index.html" -> asset(out, "index.html")
            r.path.startsWith("/assets/") -> asset(out, r.path.removePrefix("/assets/"))
            r.path == "/video" || r.path == "/video.mjpg" -> tracked(Client("MJPEG", address, agent)) { serveMjpeg(socket, out) }
            r.path == "/live.flv" -> tracked(Client("FLV", address, agent)) { serveFlv(socket, out) }
            r.path == "/audio.opus" -> tracked(Client("Opus", address, agent)) { serveOpus(socket, out) }
            r.path == "/snapshot.jpg" -> serveSnapshot(out)
            r.path == "/talk" && (r.method == "PUT" || r.method == "POST") -> talk(r, out)
            r.path.startsWith("/files/") -> serveFile(r, out)
            r.path.startsWith("/api/") -> api(r, out)
            else -> send(out, "404 Not Found", "text/plain", "Not found".toByteArray())
        }
    }

    private inline fun tracked(client: Client, block: () -> Unit) {
        clientList += client
        clients.value = clientList.toList()
        try { block() } finally {
            clientList -= client
            clients.value = clientList.toList()
        }
    }

    private fun asset(out: OutputStream, name: String) {
        if (".." in name) return send(out, "404 Not Found", "text/plain", ByteArray(0))
        val bytes = runCatching { c.app.assets.open("www/$name").use { it.readBytes() } }.getOrNull()
            ?: return send(out, "404 Not Found", "text/plain", "Not found".toByteArray())
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"; "js" -> "text/javascript"; "css" -> "text/css"; "svg" -> "image/svg+xml"; "png" -> "image/png"
            else -> "application/octet-stream"
        }
        send(out, "200 OK", type, bytes)
    }

    // ------------------------------------------------------------------ live streams

    private fun serveMjpeg(socket: Socket, out: OutputStream) {
        head(out, "200 OK", "multipart/x-mixed-replace; boundary=frame")
        out.flush()
        socket.soTimeout = 0
        mjpeg.acquire()
        try {
            var seq = 0L
            while (running.value) {
                val next = mjpeg.next(seq, 5000) ?: continue
                seq = next.first
                out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${next.second.size}\r\n\r\n".toByteArray())
                out.write(next.second)
                out.write("\r\n".toByteArray())
                out.flush()
            }
        } finally {
            mjpeg.release()
        }
    }

    private fun serveSnapshot(out: OutputStream) {
        val q = LinkedBlockingQueue<Array<ByteArray?>>(1)
        c.snapshots.jpeg(90) { q.offer(arrayOf(it)) }
        val jpeg = q.poll(5, TimeUnit.SECONDS)?.get(0) ?: return fail(out, "503 Service Unavailable", "No picture available")
        send(out, "200 OK", "image/jpeg", jpeg)
    }

    private fun serveFlv(socket: Socket, out: OutputStream) {
        val codec = VideoCodec.of(c.settings[Keys.flvCodec])
        val broadcast = c.streams.getValue(codec)
        val queue = LinkedBlockingQueue<ByteArray>(900)
        var overflow = false
        val audio = c.audio.hasInput
        val session = FlvSession({ c.pipeline.fps.value }, audio) { type, ts, body, droppable ->
            if (!queue.offer(Flv.tag(type, ts, body)) && !droppable) overflow = true
        }
        head(out, "200 OK", "video/x-flv")
        out.write(Flv.fileHeader(audio, true))
        out.flush()
        socket.soTimeout = 0
        broadcast.subscribe(session)
        if (audio) c.aac.subscribe(session)
        try {
            if (broadcast.lastError != null) return
            while (running.value && !overflow) {
                val tag = queue.poll(2, TimeUnit.SECONDS) ?: continue
                out.write(tag)
                if (queue.isEmpty()) out.flush()
            }
        } finally {
            broadcast.unsubscribe(session)
            c.aac.unsubscribe(session)
        }
    }

    private fun serveOpus(socket: Socket, out: OutputStream) {
        if (!c.audio.hasInput) return fail(out, "404 Not Found", "No audio source")
        val queue = LinkedBlockingQueue<ByteArray>(500)
        val ogg = OggOpus()
        val sink = object : AudioSink {
            override fun onAudioConfig(config: AudioConfig) { queue.offer(ogg.headers(config.specific)) }
            override fun onAudioPacket(packet: AudioPacket) { queue.offer(ogg.audio(packet.data)) }
        }
        head(out, "200 OK", "audio/ogg")
        out.flush()
        socket.soTimeout = 0
        c.opus.subscribe(sink)
        try {
            while (running.value) {
                val page = queue.poll(2, TimeUnit.SECONDS) ?: continue
                out.write(page)
                out.flush()
            }
        } finally {
            c.opus.unsubscribe(sink)
        }
    }

    // ------------------------------------------------------------------ files, talk-back, API

    private fun serveFile(r: Request, out: OutputStream) {
        val name = r.path.removePrefix("/files/")
        val entry = c.storage.list().firstOrNull { it.name == name } ?: return send(out, "404 Not Found", "text/plain", "Not found".toByteArray())
        if (r.method == "DELETE") return if (c.storage.delete(entry)) ok(out) else fail(out, "500 Internal Server Error", "Could not delete")
        val pfd = c.storage.open(entry) ?: return send(out, "404 Not Found", "text/plain", "Not found".toByteArray())
        pfd.use {
            FileInputStream(it.fileDescriptor).use { input ->
                val size = input.channel.size()
                var start = 0L
                var end = size - 1
                val range = r.headers["range"]?.removePrefix("bytes=")
                if (range != null) {
                    start = range.substringBefore('-').toLongOrNull() ?: 0
                    end = range.substringAfter('-').toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
                }
                val type = if (entry.video) "video/mp4" else "image/jpeg"
                if (range != null) head(out, "206 Partial Content", type, end - start + 1, "Accept-Ranges: bytes\r\nContent-Range: bytes $start-$end/$size\r\n")
                else head(out, "200 OK", type, size, "Accept-Ranges: bytes\r\n")
                input.channel.position(start)
                val buf = ByteArray(65536)
                var left = end - start + 1
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
                out.flush()
            }
        }
    }

    /** Talk-back: an uploaded audio clip is played on the phone's speaker. */
    private fun talk(r: Request, out: OutputStream) {
        val length = r.headers["content-length"]?.toLongOrNull() ?: 0
        if (length < 64 || length > 20_000_000) return fail(out, "400 Bad Request", "Send an audio file as the request body")
        val type = r.headers["content-type"] ?: ""
        val ext = when { "webm" in type -> "webm"; "ogg" in type -> "ogg"; "wav" in type -> "wav"; "mpeg" in type -> "mp3"; else -> "m4a" }
        val file = File(c.app.cacheDir, "talk-${System.currentTimeMillis()}.$ext")
        file.outputStream().use { o ->
            val buf = ByteArray(16384)
            var left = length
            while (left > 0) {
                val n = r.body.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n <= 0) break
                o.write(buf, 0, n)
                left -= n
            }
        }
        c.playTalkBack(file)
        ok(out)
    }

    private fun api(r: Request, out: OutputStream) {
        fun flag(): Boolean? = r.query["on"]?.let { it == "1" || it.equals("true", true) }
        when (r.path) {
            "/api/status" -> json(out, status())
            "/api/snapshot" -> {
                val q = LinkedBlockingQueue<Array<String?>>(1)
                c.takeSnapshot { q.offer(arrayOf(it)) }
                val name = q.poll(8, TimeUnit.SECONDS)?.get(0)
                if (name != null) json(out, JSONObject().put("ok", true).put("name", name)) else fail(out, "503 Service Unavailable", "No picture available")
            }
            "/api/record" -> { c.setRecording(flag() ?: !c.recorder.isRecording); ok(out) }
            "/api/motion" -> { c.setMotionDetection(flag() ?: !c.motionEnabled.value); ok(out) }
            "/api/format" -> {
                val choices = c.formatChoices()
                val sel = r.query["index"]?.toIntOrNull()?.let(choices::getOrNull) ?: return fail(out, "400 Bad Request", "Unknown format index")
                c.select(sel)
                ok(out)
            }
            "/api/files" -> json(out, JSONObject().put("files", JSONArray().apply {
                for (e in c.storage.list()) put(JSONObject().put("name", e.name).put("size", e.size).put("modified", e.modified).put("video", e.video).put("motion", e.motion))
            }))
            else -> fail(out, "404 Not Found", "Unknown API call")
        }
    }

    private fun status(): JSONObject {
        val choices = c.formatChoices()
        val sel = c.selection.value
        val rec = c.recorder.state.value
        return JSONObject()
            .put("title", c.pageTitle())
            .put("device", c.session.value?.displayName ?: JSONObject.NULL)
            .put("format", sel?.label ?: JSONObject.NULL)
            .put("formats", JSONArray().apply { choices.forEach { put(it.label) } })
            .put("current", choices.indexOf(sel))
            .put("fps", c.pipeline.fps.value.toDouble())
            .put("recording", rec != null)
            .put("recordingMotion", rec?.motion == true)
            .put("motion", c.motionEnabled.value)
            .put("audio", c.audio.hasInput)
            .put("battery", c.batteryPercent())
            .put("flvCodec", VideoCodec.of(c.settings[Keys.flvCodec]).label)
            .put("rtsp", c.rtspUrl() ?: JSONObject.NULL)
            .put("clients", JSONArray().apply { clientList.forEach { put(JSONObject().put("kind", it.kind).put("address", it.address).put("agent", it.agent)) } })
    }

    // ------------------------------------------------------------------ Motion-JPEG source

    /** Produces JPEG frames only while someone is watching: re-encoded from the pipeline, or the camera's own. */
    private inner class MjpegSource {
        private val lock = Object()
        private var frame: ByteArray? = null
        private var seq = 0L
        private var users = 0
        private var bitmap: Bitmap? = null
        @Volatile private var busy = false
        private val encoder = Executors.newSingleThreadExecutor { Thread(it, "mjpeg-encoder") }
        private val cameraFrames: (PixelKind, ByteArray, Int, Long) -> Unit = { kind, data, length, _ ->
            if (kind == PixelKind.MJPEG && c.mjpegPassthrough()) publish(data.copyOf(length))
        }

        fun acquire() = synchronized(lock) {
            if (users++ == 0) {
                c.addCompressedListener(cameraFrames)
                c.pipeline.setTap("mjpeg", Pipeline.Tap(0, 30_000_000L) { buffer, width, height, _ ->
                    if (busy || c.mjpegPassthrough()) return@Tap
                    busy = true
                    val bmp = bitmap?.takeIf { it.width == width && it.height == height }
                        ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
                    bmp.copyPixelsFromBuffer(buffer)
                    encoder.execute {
                        try {
                            val out = ByteArrayOutputStream(width * height / 6)
                            bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
                            publish(out.toByteArray())
                        } finally {
                            busy = false
                        }
                    }
                })
            }
        }

        fun release() = synchronized(lock) {
            if (--users == 0) {
                c.removeCompressedListener(cameraFrames)
                c.pipeline.setTap("mjpeg", null)
            }
        }

        private fun publish(jpeg: ByteArray) = synchronized(lock) {
            frame = jpeg
            seq++
            lock.notifyAll()
        }

        fun next(after: Long, timeoutMs: Long): Pair<Long, ByteArray>? = synchronized(lock) {
            if (seq == after) lock.wait(timeoutMs)
            val f = frame
            if (seq == after || f == null) null else seq to f
        }
    }

    companion object {
        private const val TAG = "WebServer"
    }
}
