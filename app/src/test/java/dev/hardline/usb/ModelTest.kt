package dev.hardline.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    private fun kind(fourcc: String) = VideoFormat(1, fourcc, emptyList()).kind

    @Test
    fun `format codes map to pixel layouts`() {
        assertEquals(PixelKind.MJPEG, kind("MJPG"))
        assertEquals(PixelKind.YUYV, kind("YUY2"))
        assertEquals(PixelKind.GRAY8, kind("Y8  "))
        assertEquals(PixelKind.H265, kind("HEVC"))
        assertEquals(PixelKind.H265, kind("H265"))
        assertEquals(PixelKind.UNSUPPORTED, kind("BY8 "))
    }

    @Test
    fun `compressed formats get readable labels`() {
        assertEquals("H.264", VideoFormat(1, "H264", emptyList()).label)
        assertEquals("NV12", VideoFormat(1, "NV12", emptyList()).label)
    }

    @Test
    fun `a frame size prints as width by height`() {
        assertEquals("1280x720", FrameSize(1, 1280, 720, 333333, listOf(333333)).toString())
    }

    @Test
    fun `range controls accept what the camera reports`() {
        val range = ControlRange(min = -64, max = 64, default = 0, step = 1, current = 0)
        assertTrue(CameraControl.BRIGHTNESS.accepts(-64, range))
        assertFalse(CameraControl.BRIGHTNESS.accepts(65, range))
    }

    @Test
    fun `switches and menus ignore a nonsense range`() {
        // Seen on a cheap endoscope: the power-line menu reports a default of 0x80.
        val nonsense = ControlRange(min = 0, max = 0, default = 0x80, step = 0, current = 0x80)
        assertTrue(CameraControl.POWER_LINE.accepts(2, nonsense))
        assertFalse(CameraControl.POWER_LINE.accepts(0x80, nonsense))
        assertTrue(CameraControl.FOCUS_AUTO.accepts(1, nonsense))
        assertFalse(CameraControl.FOCUS_AUTO.accepts(2, nonsense))
        assertTrue(CameraControl.EXPOSURE_MODE.accepts(8, nonsense))
        assertFalse(CameraControl.EXPOSURE_MODE.accepts(3, nonsense))
    }

    @Test
    fun `pan and tilt share one control and split its value`() {
        assertEquals(CameraControl.PAN.selector, CameraControl.TILT.selector)
        assertEquals(0, CameraControl.PAN.offset)
        assertEquals(4, CameraControl.TILT.offset)
        assertEquals(8, CameraControl.PAN.size)
        assertEquals(4, CameraControl.PAN.valueSize)
    }
}
