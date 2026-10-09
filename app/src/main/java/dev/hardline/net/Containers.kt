package dev.hardline.net

import dev.hardline.media.Nal
import dev.hardline.media.VideoCodec
import dev.hardline.media.VideoConfig
import dev.hardline.media.VideoPacket
import java.io.ByteArrayOutputStream

/** A growable big-endian byte writer. */
class Bytes(initial: Int = 256) {
    private val out = ByteArrayOutputStream(initial)
    val size: Int get() = out.size()
    fun u8(v: Int) = apply { out.write(v) }
    fun u16(v: Int) = apply { out.write(v shr 8); out.write(v) }
    fun u24(v: Int) = apply { out.write(v shr 16); out.write(v shr 8); out.write(v) }
    fun u32(v: Long) = apply { out.write((v shr 24).toInt()); out.write((v shr 16).toInt()); out.write((v shr 8).toInt()); out.write(v.toInt()) }
    fun u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
    fun u32le(v: Int) = apply { out.write(v); out.write(v shr 8); out.write(v shr 16); out.write(v shr 24) }
    fun u64le(v: Long) = apply { for (i in 0 until 8) out.write((v shr (8 * i)).toInt()) }
    fun f64(v: Double) = apply { val b = java.lang.Double.doubleToLongBits(v); for (i in 7 downTo 0) out.write((b shr (8 * i)).toInt()) }
    fun raw(b: ByteArray, offset: Int = 0, length: Int = b.size) = apply { out.write(b, offset, length) }
    fun ascii(s: String) = raw(s.toByteArray(Charsets.ISO_8859_1))
    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Action Message Format 0, as used by FLV metadata and RTMP commands. */
object Amf {
    fun string(b: Bytes, s: String) = b.u8(2).u16(s.toByteArray().size).raw(s.toByteArray())
    fun number(b: Bytes, v: Double) = b.u8(0).f64(v)
    fun bool(b: Bytes, v: Boolean) = b.u8(1).u8(if (v) 1 else 0)
    fun nil(b: Bytes) = b.u8(5)

    private fun properties(b: Bytes, map: Map<String, Any?>) {
        for ((k, v) in map) {
            b.u16(k.toByteArray().size).raw(k.toByteArray())
            value(b, v)
        }
        b.u24(9)
    }

    fun obj(b: Bytes, map: Map<String, Any?>) = properties(b.u8(3), map)
    fun array(b: Bytes, map: Map<String, Any?>) = properties(b.u8(8).u32(map.size), map)

    fun value(b: Bytes, v: Any?) {
        when (v) {
            null -> nil(b)
            is String -> string(b, v)
            is Boolean -> bool(b, v)
            is Number -> number(b, v.toDouble())
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") obj(b, v as Map<String, Any?>)
            else -> error("unsupported AMF value")
        }
    }

    /** Decodes a sequence of AMF0 values (numbers, booleans, strings, objects, null). */
    fun decode(data: ByteArray): List<Any?> {
        val out = ArrayList<Any?>()
        var p = 0
        fun u16() = ((data[p].toInt() and 0xFF) shl 8 or (data[p + 1].toInt() and 0xFF)).also { p += 2 }
        fun str(): String { val n = u16(); return String(data, p, n).also { p += n } }
        fun value(): Any? = when (data[p++].toInt()) {
            0 -> java.lang.Double.longBitsToDouble((0 until 8).fold(0L) { acc, i -> acc shl 8 or (data[p + i].toLong() and 0xFF) }).also { p += 8 }
            1 -> data[p++].toInt() != 0
            2 -> str()
            3, 8 -> {
                if (data[p - 1].toInt() == 8) p += 4
                val m = LinkedHashMap<String, Any?>()
                while (p + 2 < data.size) {
                    val key = str()
                    if (key.isEmpty() && data[p].toInt() == 9) { p++; break }
                    m[key] = value()
                }
                m
            }
            5, 6 -> null
            else -> throw IllegalArgumentException("AMF type")
        }
        try { while (p < data.size) out += value() } catch (_: Exception) { }
        return out
    }
}

/** Removes H.264/H.265 emulation-prevention bytes so header fields can be read. */
internal fun unescapeRbsp(nal: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(nal.size)
    var zeros = 0
    for (b in nal) {
        if (zeros >= 2 && b.toInt() == 3) { zeros = 0; continue }
        zeros = if (b.toInt() == 0) zeros + 1 else 0
        out.write(b.toInt())
    }
    return out.toByteArray()
}

/** Builds FLV tag bodies (also the payload of RTMP audio/video messages). */
object Flv {
    fun fileHeader(audio: Boolean, video: Boolean): ByteArray =
        Bytes().ascii("FLV").u8(1).u8((if (audio) 4 else 0) or (if (video) 1 else 0)).u32(9).u32(0).toByteArray()

    fun tag(type: Int, timestampMs: Long, body: ByteArray): ByteArray =
        Bytes(body.size + 16).u8(type).u24(body.size).u24((timestampMs and 0xFFFFFF).toInt()).u8((timestampMs shr 24).toInt() and 0xFF)
            .u24(0).raw(body).u32(body.size + 11).toByteArray()

    fun metadata(config: VideoConfig?, fps: Float, audio: Boolean): ByteArray {
        val b = Bytes()
        Amf.string(b, "onMetaData")
        val m = LinkedHashMap<String, Any?>()
        m["encoder"] = "HardLine"
        m["duration"] = 0.0
        if (config != null) {
            m["width"] = config.width; m["height"] = config.height; m["framerate"] = fps
            m["videocodecid"] = when (config.codec) {
                VideoCodec.H264 -> 7.0
                VideoCodec.HEVC -> fourCc("hvc1").toDouble()
                VideoCodec.AV1 -> fourCc("av01").toDouble()
            }
        }
        if (audio) {
            m["audiocodecid"] = 10.0; m["audiosamplerate"] = 48000.0; m["audiosamplesize"] = 16.0; m["stereo"] = true
        }
        Amf.array(b, m)
        return b.toByteArray()
    }

    private fun fourCc(s: String): Long = s.fold(0L) { acc, c -> acc shl 8 or c.code.toLong() }

    fun audioHeader(specific: ByteArray): ByteArray = Bytes().u8(0xAF).u8(0).raw(specific).toByteArray()
    fun audioFrame(data: ByteArray): ByteArray = Bytes(data.size + 2).u8(0xAF).u8(1).raw(data).toByteArray()

    fun videoHeader(config: VideoConfig): ByteArray? = when (config.codec) {
        VideoCodec.H264 -> {
            val sps = config.sets.firstOrNull { Nal.h264Type(it, 0) == 7 }
            val pps = config.sets.firstOrNull { Nal.h264Type(it, 0) == 8 }
            if (sps == null || pps == null || sps.size < 4) null
            else Bytes().u8(0x17).u8(0).u24(0)
                .u8(1).u8(sps[1].toInt()).u8(sps[2].toInt()).u8(sps[3].toInt()).u8(0xFF)
                .u8(0xE1).u16(sps.size).raw(sps).u8(1).u16(pps.size).raw(pps).toByteArray()
        }
        VideoCodec.HEVC -> hevcRecord(config)?.let { Bytes().u8(0x80 or 0x10 or 0).ascii("hvc1").raw(it).toByteArray() }
        VideoCodec.AV1 -> config.sets.firstOrNull()?.let { Bytes().u8(0x80 or 0x10 or 0).ascii("av01").raw(av1Record(it)).toByteArray() }
    }

    /** One access unit. H.264 uses the classic layout; HEVC and AV1 the enhanced (FourCC) one. */
    fun videoFrame(codec: VideoCodec, packet: VideoPacket): ByteArray {
        val frame = if (packet.key) 0x10 else 0x20
        val b = Bytes(packet.data.size + 32)
        when (codec) {
            VideoCodec.H264 -> { b.u8(frame or 7).u8(1).u24(0); lengthPrefixed(b, packet.data, false) }
            VideoCodec.HEVC -> { b.u8(0x80 or frame or 3).ascii("hvc1"); lengthPrefixed(b, packet.data, true) }
            VideoCodec.AV1 -> b.u8(0x80 or frame or 1).ascii("av01").raw(packet.data)
        }
        return b.toByteArray()
    }

    /** Converts start-code delimited NAL units to 4-byte length prefixes, leaving out parameter sets. */
    private fun lengthPrefixed(b: Bytes, data: ByteArray, hevc: Boolean) {
        Nal.forEach(data, 0, data.size) { start, size ->
            val skip = if (hevc) Nal.h265Type(data, start).let { it in 32..35 } else Nal.h264Type(data, start).let { it in 7..9 }
            if (!skip && size > 0) b.u32(size).raw(data, start, size)
        }
    }

    /** HEVCDecoderConfigurationRecord for 8-bit 4:2:0 streams. */
    fun hevcRecord(config: VideoConfig): ByteArray? {
        val vps = config.sets.firstOrNull { Nal.h265Type(it, 0) == 32 }
        val sps = config.sets.firstOrNull { Nal.h265Type(it, 0) == 33 }
        val pps = config.sets.firstOrNull { Nal.h265Type(it, 0) == 34 }
        if (vps == null || sps == null || pps == null) return null
        val r = unescapeRbsp(sps)
        if (r.size < 15) return null
        val b = Bytes().u8(1).raw(r, 3, 12)                      // profile space/tier/idc, compatibility, constraints, level
            .u8(0xF0).u8(0).u8(0xFC).u8(0xFD).u8(0xF8).u8(0xF8).u16(0).u8(0x0F).u8(3)
        for ((type, nal) in listOf(32 to vps, 33 to sps, 34 to pps)) b.u8(0x80 or type).u16(1).u16(nal.size).raw(nal)
        return b.toByteArray()
    }

    /** AV1CodecConfigurationRecord built from the sequence-header OBU. */
    fun av1Record(sequenceHeader: ByteArray): ByteArray {
        var profile = 0
        var level = 8
        runCatching {
            var p = 1 + (if (sequenceHeader[0].toInt() and 0x04 != 0) 1 else 0)
            if (sequenceHeader[0].toInt() and 0x02 != 0) while (sequenceHeader[p++].toInt() and 0x80 != 0) Unit
            var bit = p * 8
            fun bits(n: Int): Int {
                var v = 0
                repeat(n) { v = v shl 1 or ((sequenceHeader[bit shr 3].toInt() shr (7 - (bit and 7))) and 1); bit++ }
                return v
            }
            profile = bits(3)
            bits(1)
            if (bits(1) == 1) level = bits(5) else if (bits(1) == 0) {      // no timing info
                bits(1); bits(5); bits(12)
                level = bits(5)
            }
        }
        return Bytes().u8(0x81).u8(profile shl 5 or (level and 0x1F)).u8(0x0C).u8(0).raw(sequenceHeader).toByteArray()
    }
}

/** Writes an Ogg stream of Opus packets, one packet per page for low latency. */
class OggOpus(private val serial: Int = 0x55434D53) {
    private var sequence = 0
    private var granule = 0L

    fun headers(opusHead: ByteArray): ByteArray {
        val head = if (opusHead.size >= 19 && String(opusHead, 0, 8) == "OpusHead") opusHead
        else Bytes().ascii("OpusHead").u8(1).u8(2).u8(0x38).u8(0x01).u32le(48000).u8(0).u8(0).u8(0).toByteArray()
        val tags = Bytes().ascii("OpusTags").u32le(VENDOR.length).ascii(VENDOR).u32le(0).toByteArray()
        return page(head, 0, 2) + page(tags, 0, 0)
    }

    fun audio(packet: ByteArray, samples: Int = 960): ByteArray {
        granule += samples
        return page(packet, granule, 0)
    }

    private fun page(payload: ByteArray, granulePos: Long, flags: Int): ByteArray {
        val segments = payload.size / 255 + 1
        val b = Bytes(payload.size + 64).ascii("OggS").u8(0).u8(flags).u64le(granulePos).u32le(serial).u32le(sequence++).u32le(0).u8(segments)
        repeat(segments - 1) { b.u8(255) }
        b.u8(payload.size % 255).raw(payload)
        val out = b.toByteArray()
        var crc = 0
        for (x in out) crc = (crc shl 8) xor CRC[((crc ushr 24) xor (x.toInt() and 0xFF)) and 0xFF]
        out[22] = crc.toByte(); out[23] = (crc shr 8).toByte(); out[24] = (crc shr 16).toByte(); out[25] = (crc shr 24).toByte()
        return out
    }

    companion object {
        private const val VENDOR = "HardLine"
        private val CRC = IntArray(256) { i ->
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            r
        }
    }
}
