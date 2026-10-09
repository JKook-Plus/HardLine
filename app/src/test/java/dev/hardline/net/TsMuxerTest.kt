package dev.hardline.net

import dev.hardline.media.AudioPacket
import dev.hardline.media.VideoCodec
import dev.hardline.media.VideoConfig
import dev.hardline.media.VideoPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TsMuxerTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun pid(p: ByteArray) = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
    private fun startsUnit(p: ByteArray) = p[1].toInt() and 0x40 != 0

    /** What a transport packet carries after its header and adaptation field. */
    private fun payload(p: ByteArray): ByteArray {
        val control = (p[3].toInt() shr 4) and 3
        val start = if (control and 2 != 0) 5 + (p[4].toInt() and 0xFF) else 4
        return if (control and 1 != 0) p.copyOfRange(start, 188) else ByteArray(0)
    }

    /** MPEG CRC-32 over a whole section, checksum included, is zero when the section is intact. */
    private fun sectionIntact(p: ByteArray): Boolean {
        val start = 5 + (p[4].toInt() and 0xFF)                      // after the pointer field
        val length = 3 + (((p[start + 1].toInt() and 0x0F) shl 8) or (p[start + 2].toInt() and 0xFF))
        var crc = -1
        for (i in start until start + length) {
            crc = crc xor ((p[i].toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc == 0
    }

    private val sps = bytes(0x67, 0x42, 0xC0, 0x1E)
    private val pps = bytes(0x68, 0xCE, 0x3C, 0x80)
    private val config = VideoConfig(VideoCodec.H264, 640, 480, listOf(sps, pps), null)

    private fun mux(audio: Boolean, block: TsMuxer.() -> Unit): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        TsMuxer(hevc = false, withAudio = audio) { out += it }.block()
        return out
    }

    @Test
    fun `every packet is 188 bytes and starts with the sync byte`() {
        val packets = mux(audio = true) {
            video(config, VideoPacket(bytes(0, 0, 0, 1, 0x65) + ByteArray(1000) { 7 }, 0, key = true))
            audio(AudioPacket(ByteArray(300) { 3 }, 10_000))
        }
        assertTrue(packets.size > 6)
        for (p in packets) {
            assertEquals(188, p.size)
            assertEquals(0x47, p[0].toInt())
        }
    }

    @Test
    fun `a key frame is preceded by intact program tables`() {
        val packets = mux(audio = true) { video(config, VideoPacket(bytes(0, 0, 0, 1, 0x65, 1, 2, 3), 0, key = true)) }
        assertEquals(listOf(0x0000, 0x1000, 0x0100), packets.map(::pid))
        assertTrue(sectionIntact(packets[0]))
        assertTrue(sectionIntact(packets[1]))
        // The map lists H.264 video on 0x100 and AAC audio on 0x101.
        val pmt = packets[1]
        assertEquals(listOf(0x1B, 0xE1, 0x00), (17..19).map { pmt[it].toInt() and 0xFF })
        assertEquals(listOf(0x0F, 0xE1, 0x01), (22..24).map { pmt[it].toInt() and 0xFF })
    }

    @Test
    fun `continuity counters count per stream and wrap at sixteen`() {
        val packets = mux(audio = false) { video(config, VideoPacket(bytes(0, 0, 0, 1, 0x65) + ByteArray(4000) { 9 }, 0, key = true)) }
        val video = packets.filter { pid(it) == 0x100 }
        assertTrue(video.size > 16)
        assertEquals(video.indices.map { it and 0x0F }, video.map { it[3].toInt() and 0x0F })
    }

    @Test
    fun `the access unit comes back out with a delimiter and its parameter sets`() {
        val frame = bytes(0, 0, 0, 1, 0x65) + ByteArray(500) { (it % 251).toByte() }
        val packets = mux(audio = false) { video(config, VideoPacket(frame, 0, key = true)) }
        val video = packets.filter { pid(it) == 0x100 }
        assertTrue(startsUnit(video.first()))
        assertTrue(video.drop(1).none(::startsUnit))
        val pes = video.map(::payload).reduce { a, b -> a + b }
        assertArrayEquals(bytes(0, 0, 1, 0xE0), pes.copyOfRange(0, 4))
        val headerLength = 9 + (pes[8].toInt() and 0xFF)
        val expected = bytes(0, 0, 0, 1, 0x09, 0xF0) + bytes(0, 0, 0, 1) + sps + bytes(0, 0, 0, 1) + pps + frame
        assertArrayEquals(expected, pes.copyOfRange(headerLength, pes.size))
    }

    @Test
    fun `timestamps are in 90 kHz units, ahead of the clock reference`() {
        val packets = mux(audio = false) {
            video(config, VideoPacket(bytes(0, 0, 0, 1, 0x65, 1), 1_000_000, key = true))
            video(config, VideoPacket(bytes(0, 0, 0, 1, 0x41, 1), 1_040_000, key = false))
        }
        fun pts(p: ByteArray): Long {
            val b = payload(p)
            return ((b[9].toLong() and 0x0E) shl 29) or ((b[10].toLong() and 0xFF) shl 22) or ((b[11].toLong() and 0xFE) shl 14) or
                ((b[12].toLong() and 0xFF) shl 7) or ((b[13].toLong() and 0xFE) shr 1)
        }
        val video = packets.filter { pid(it) == 0x100 && startsUnit(it) }
        assertEquals(listOf(27_000L, 27_000L + 3_600L), video.map(::pts))
    }

    @Test
    fun `audio is wrapped in ADTS and dropped until video has started`() {
        val packets = mux(audio = true) {
            audio(AudioPacket(ByteArray(50), 0))
            video(config, VideoPacket(bytes(0, 0, 0, 1, 0x65, 1), 20_000, key = true))
            audio(AudioPacket(ByteArray(50) { 5 }, 20_000))
        }
        val audio = packets.filter { pid(it) == 0x101 }
        assertEquals(1, audio.size)
        val pes = payload(audio[0])
        val adts = pes.copyOfRange(9 + (pes[8].toInt() and 0xFF), pes.size)
        assertEquals(57, adts.size)
        assertEquals(0xFFF1, ((adts[0].toInt() and 0xFF) shl 8) or (adts[1].toInt() and 0xFF))
        val frameLength = ((adts[3].toInt() and 0x03) shl 11) or ((adts[4].toInt() and 0xFF) shl 3) or ((adts[5].toInt() and 0xFF) shr 5)
        assertEquals(57, frameLength)
    }
}
