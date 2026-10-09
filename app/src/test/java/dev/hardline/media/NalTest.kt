package dev.hardline.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NalTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun units(data: ByteArray): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        Nal.forEach(data, 0, data.size) { start, size -> out += data.slice(start until start + size).map { it.toInt() and 0xFF } }
        return out
    }

    @Test
    fun `splits on three and four byte start codes`() {
        val data = bytes(0, 0, 0, 1, 0x67, 0xAA, 0, 0, 1, 0x68, 0xBB, 0, 0, 0, 1, 0x65, 0xCC, 0xDD)
        assertEquals(listOf(listOf(0x67, 0xAA), listOf(0x68, 0xBB), listOf(0x65, 0xCC, 0xDD)), units(data))
    }

    @Test
    fun `a buffer without a start code has no units`() {
        assertEquals(emptyList<List<Int>>(), units(bytes(0x65, 0x01, 0x02, 0x03)))
    }

    @Test
    fun `honours the offset and length it is given`() {
        val data = bytes(0xFF, 0xFF, 0, 0, 1, 0x41, 0x01, 0, 0, 1, 0x41, 0x02)
        val out = ArrayList<Int>()
        Nal.forEach(data, 2, 5) { start, size -> out += start; out += size }
        assertEquals(listOf(5, 2), out)
    }

    @Test
    fun `recognises H264 key frames and parameter sets`() {
        val idr = bytes(0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2, 0, 0, 0, 1, 0x65, 3)
        val delta = bytes(0, 0, 0, 1, 0x41, 3)
        assertTrue(Nal.isKeyFrame(idr, 0, idr.size, hevc = false))
        assertTrue(Nal.hasParameterSets(idr, 0, idr.size, hevc = false))
        assertFalse(Nal.isKeyFrame(delta, 0, delta.size, hevc = false))
        assertFalse(Nal.hasParameterSets(delta, 0, delta.size, hevc = false))
    }

    @Test
    fun `recognises HEVC key frames and parameter sets`() {
        // NAL type is bits 1-6 of the first header byte: 33 = SPS, 19 = IDR_W_RADL, 1 = TRAIL_R.
        val idr = bytes(0, 0, 0, 1, 33 shl 1, 1, 0xAA, 0, 0, 0, 1, 19 shl 1, 1, 0xBB)
        val trail = bytes(0, 0, 0, 1, 1 shl 1, 1, 0xCC)
        assertTrue(Nal.isKeyFrame(idr, 0, idr.size, hevc = true))
        assertTrue(Nal.hasParameterSets(idr, 0, idr.size, hevc = true))
        assertFalse(Nal.isKeyFrame(trail, 0, trail.size, hevc = true))
        assertFalse(Nal.hasParameterSets(trail, 0, trail.size, hevc = true))
    }
}
