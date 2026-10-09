package dev.hardline.net

import dev.hardline.media.VideoCodec
import dev.hardline.media.VideoConfig
import dev.hardline.media.VideoPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
private fun ByteArray.u32le(at: Int) = (0 until 4).fold(0L) { acc, i -> acc or ((this[at + i].toLong() and 0xFF) shl (8 * i)) }
private fun ByteArray.u32be(at: Int) = (0 until 4).fold(0L) { acc, i -> acc shl 8 or (this[at + i].toLong() and 0xFF) }

class BytesTest {
    @Test
    fun `writes big endian unless told otherwise`() {
        val out = Bytes().u8(0x01).u16(0x0203).u24(0x040506).u32(0x0708090A).u32le(0x0B0C0D0E).ascii("ok").toByteArray()
        assertArrayEquals(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 0x0E, 0x0D, 0x0C, 0x0B, 'o'.code, 'k'.code), out)
    }

    @Test
    fun `keeps the top bit of an unsigned 32 bit value`() {
        assertArrayEquals(bytes(0xFF, 0xFF, 0xFF, 0xFE), Bytes().u32(-2).toByteArray())
    }
}

class AmfTest {
    @Test
    fun `values survive a round trip`() {
        val b = Bytes()
        Amf.string(b, "connect")
        Amf.number(b, 1.0)
        Amf.obj(b, linkedMapOf("app" to "live", "audio" to true, "width" to 1280, "nothing" to null))
        val decoded = Amf.decode(b.toByteArray())
        assertEquals(listOf("connect", 1.0, linkedMapOf("app" to "live", "audio" to true, "width" to 1280.0, "nothing" to null)), decoded)
    }

    @Test
    fun `an ECMA array decodes like an object`() {
        val b = Bytes()
        Amf.array(b, linkedMapOf("duration" to 0.0, "stereo" to true))
        assertEquals(listOf(linkedMapOf("duration" to 0.0, "stereo" to true)), Amf.decode(b.toByteArray()))
    }
}

class FlvTest {
    private val sps = bytes(0x67, 0x42, 0xC0, 0x1E, 0xAB)
    private val pps = bytes(0x68, 0xCE, 0x3C, 0x80)

    @Test
    fun `file header announces the tracks present`() {
        assertArrayEquals(bytes('F'.code, 'L'.code, 'V'.code, 1, 5, 0, 0, 0, 9, 0, 0, 0, 0), Flv.fileHeader(audio = true, video = true))
        assertEquals(1, Flv.fileHeader(audio = false, video = true)[4].toInt())
    }

    @Test
    fun `tag carries its size, an extended timestamp and the back pointer`() {
        val body = bytes(1, 2, 3)
        val tag = Flv.tag(9, 0x01234567, body)
        assertArrayEquals(bytes(9, 0, 0, 3, 0x23, 0x45, 0x67, 0x01, 0, 0, 0, 1, 2, 3, 0, 0, 0, 14), tag)
    }

    @Test
    fun `H264 sequence header is an AVCDecoderConfigurationRecord`() {
        val header = Flv.videoHeader(VideoConfig(VideoCodec.H264, 640, 480, listOf(sps, pps), null))!!
        val expected = bytes(0x17, 0, 0, 0, 0, 1, 0x42, 0xC0, 0x1E, 0xFF, 0xE1, 0, 5) + sps + bytes(1, 0, 4) + pps
        assertArrayEquals(expected, header)
    }

    @Test
    fun `no sequence header without both parameter sets`() {
        assertNull(Flv.videoHeader(VideoConfig(VideoCodec.H264, 640, 480, listOf(sps), null)))
    }

    @Test
    fun `frames become length prefixed and lose their parameter sets`() {
        val annexB = bytes(0, 0, 0, 1) + sps + bytes(0, 0, 0, 1) + pps + bytes(0, 0, 0, 1, 0x65, 0x11, 0x22)
        val frame = Flv.videoFrame(VideoCodec.H264, VideoPacket(annexB, 0, key = true))
        assertArrayEquals(bytes(0x17, 1, 0, 0, 0, 0, 0, 0, 3, 0x65, 0x11, 0x22), frame)
        val delta = Flv.videoFrame(VideoCodec.H264, VideoPacket(bytes(0, 0, 1, 0x41, 0x33), 0, key = false))
        assertArrayEquals(bytes(0x27, 1, 0, 0, 0, 0, 0, 0, 2, 0x41, 0x33), delta)
    }

    @Test
    fun `emulation prevention bytes are removed`() {
        assertArrayEquals(bytes(0, 0, 1, 0, 0, 0, 3), unescapeRbsp(bytes(0, 0, 3, 1, 0, 0, 3, 0, 3)))
    }
}

class OggOpusTest {
    /** The Ogg checksum, written the slow way: CRC-32 with polynomial 0x04C11DB7, no reflection, zero start. */
    private fun crc(page: ByteArray): Long {
        var crc = 0
        for (b in page) {
            crc = crc xor ((b.toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc.toLong() and 0xFFFFFFFFL
    }

    private fun pages(stream: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var p = 0
        while (p < stream.size) {
            val segments = stream[p + 26].toInt() and 0xFF
            val payload = (0 until segments).sumOf { stream[p + 27 + it].toInt() and 0xFF }
            out += stream.copyOfRange(p, p + 27 + segments + payload)
            p += 27 + segments + payload
        }
        return out
    }

    private fun checksumHolds(page: ByteArray): Boolean {
        val stored = page.u32le(22)
        val blank = page.copyOf().also { for (i in 22..25) it[i] = 0 }
        return crc(blank) == stored
    }

    @Test
    fun `stream starts with an identification page and a comment page`() {
        val headers = pages(OggOpus(serial = 7).headers(ByteArray(0)))
        assertEquals(2, headers.size)
        for ((i, page) in headers.withIndex()) {
            assertEquals("OggS", String(page, 0, 4))
            assertEquals(7L, page.u32le(14))
            assertEquals(i.toLong(), page.u32le(18))
            assertEquals(true, checksumHolds(page))
        }
        assertEquals(2, headers[0][5].toInt())                      // beginning of stream
        assertEquals("OpusHead", String(headers[0], 28, 8))
        assertEquals("OpusTags", String(headers[1], 28, 8))
    }

    @Test
    fun `comment header states the real length of the vendor string`() {
        val tags = pages(OggOpus().headers(ByteArray(0)))[1]
        val body = tags.copyOfRange(28, tags.size)
        val vendorLength = body.u32le(8).toInt()
        assertEquals(body.size, 8 + 4 + vendorLength + 4)           // magic, length, vendor, comment count
        assertEquals("HardLine", String(body, 12, vendorLength))
        assertEquals(0L, body.u32le(12 + vendorLength))
    }

    @Test
    fun `audio pages advance the granule position and split long packets into segments`() {
        val ogg = OggOpus()
        ogg.headers(ByteArray(0))
        val first = ogg.audio(ByteArray(100) { it.toByte() })
        val second = ogg.audio(ByteArray(600) { 1 }, samples = 480)
        assertEquals(960L, first.u32le(6))
        assertEquals(1440L, second.u32le(6))
        assertEquals(2L, first.u32le(18))
        assertEquals(1, first[26].toInt())
        assertEquals(3, second[26].toInt())                          // 255 + 255 + 90
        assertEquals(listOf(255, 255, 90), (27..29).map { second[it].toInt() and 0xFF })
        assertEquals(true, checksumHolds(first) && checksumHolds(second))
    }
}

class HevcRecordTest {
    @Test
    fun `needs all three parameter sets`() {
        val vps = bytes(32 shl 1, 1, 0x0C)
        val pps = bytes(34 shl 1, 1, 0xC1)
        assertNull(Flv.hevcRecord(VideoConfig(VideoCodec.HEVC, 640, 480, listOf(vps, pps), null)))
    }

    @Test
    fun `copies the profile and level out of the sequence parameter set`() {
        val vps = bytes(32 shl 1, 1, 0x0C)
        val general = bytes(0x01, 0x60, 0, 0, 0, 0x90, 0, 0, 0, 0, 0, 0x5D)     // Main profile, level 3.1
        val sps = bytes(33 shl 1, 1, 0x01) + general + bytes(0xA0, 0x02)
        val pps = bytes(34 shl 1, 1, 0xC1)
        val record = Flv.hevcRecord(VideoConfig(VideoCodec.HEVC, 640, 480, listOf(vps, sps, pps), null))!!
        assertEquals(1, record[0].toInt())
        assertArrayEquals(general, record.copyOfRange(1, 13))
        assertEquals(3, record[22].toInt())                          // three arrays follow
        assertEquals(0x80 or 32, record[23].toInt() and 0xFF)
        assertEquals(vps.size.toLong(), record.u32be(24) and 0xFFFF)
    }
}
