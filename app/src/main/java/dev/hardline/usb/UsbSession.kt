package dev.hardline.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager

/** An opened USB device: video, camera controls and (optionally) its audio interface. */
class UsbSession private constructor(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val handle: Long,
    val description: DeviceDescription,
) {
    @Volatile private var closed = false

    val key: String = "%04X-%04X".format(device.vendorId, device.productId)
    val displayName: String = device.productName?.takeIf { it.isNotBlank() } ?: "USB device $key"
    val hasVideo: Boolean get() = description.streams.any { it.formats.isNotEmpty() }
    val hasAudio: Boolean get() = description.audio.isNotEmpty()

    fun startVideo(stream: StreamInterface, format: VideoFormat, frame: FrameSize, interval: Int, listener: VideoListener): String? {
        if (closed) return "device closed"
        val r = UsbNative.startVideo(handle, stream.number, format.index, frame.index, interval, listener)
        return if (r == 0) null else UsbNative.lastError()
    }

    fun stopVideo() {
        if (!closed) UsbNative.stopVideo(handle)
    }

    /** Returns the sample rate in use, or null with the reason in [UsbNative.lastError]. */
    fun startAudio(wantedRate: Int, listener: AudioListener): Int? {
        if (closed) return null
        val r = UsbNative.startAudio(handle, wantedRate, listener)
        return if (r > 0) r else null
    }

    fun stopAudio() {
        if (!closed) UsbNative.stopAudio(handle)
    }

    fun supports(c: CameraControl): Boolean {
        val unit = if (c.camera) description.cameraTerminal else description.processingUnit
        val bits = if (c.camera) description.cameraControls else description.processingControls
        return unit != 0 && (bits shr c.bit) and 1L == 1L
    }

    private fun query(c: CameraControl, request: Int): Int? {
        val unit = if (c.camera) description.cameraTerminal else description.processingUnit
        val buf = ByteArray(c.size)
        if (closed || UsbNative.control(handle, unit, c.selector, request, buf) < c.size) return null
        var v = 0L
        for (i in 0 until c.valueSize) v = v or ((buf[c.offset + i].toLong() and 0xFF) shl (8 * i))
        if (c.signed) {
            val shift = 64 - 8 * c.valueSize
            v = (v shl shift) shr shift
        }
        return v.toInt()
    }

    fun range(c: CameraControl): ControlRange? {
        val cur = query(c, UVC_GET_CUR) ?: return null
        if (c.kind != CameraControl.Kind.RANGE) return ControlRange(0, 255, query(c, UVC_GET_DEF) ?: cur, 1, cur)
        val min = query(c, UVC_GET_MIN) ?: return null
        val max = query(c, UVC_GET_MAX) ?: return null
        if (max <= min) return null
        return ControlRange(min, max, query(c, UVC_GET_DEF) ?: cur, (query(c, UVC_GET_RES) ?: 1).coerceAtLeast(1), cur)
    }

    fun get(c: CameraControl): Int? = query(c, UVC_GET_CUR)

    fun set(c: CameraControl, value: Int): Boolean {
        val unit = if (c.camera) description.cameraTerminal else description.processingUnit
        val buf = ByteArray(c.size)
        if (closed) return false
        // Controls that share one request (pan/tilt) must keep the other half unchanged.
        if (c.size != c.valueSize) UsbNative.control(handle, unit, c.selector, UVC_GET_CUR, buf)
        for (i in 0 until c.valueSize) buf[c.offset + i] = (value shr (8 * i)).toByte()
        return UsbNative.control(handle, unit, c.selector, UVC_SET_CUR, buf) >= 0
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        UsbNative.close(handle)
        connection.close()
    }

    companion object {
        fun isVideo(d: UsbDevice) = (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }
        fun isAudio(d: UsbDevice) = (0 until d.interfaceCount).any {
            d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO && d.getInterface(it).interfaceSubclass == 2
        }

        /** Opens [device]; on failure returns the reason. */
        fun open(manager: UsbManager, device: UsbDevice): Result<UsbSession> {
            val connection = manager.openDevice(device) ?: return Result.failure(IllegalStateException("The system refused to open the device"))
            val handle = UsbNative.open(connection.fileDescriptor, isVideo(device))
            if (handle == 0L) {
                connection.close()
                return Result.failure(IllegalStateException(UsbNative.lastError()))
            }
            val description = runCatching { DeviceDescription.parse(UsbNative.describe(handle)) }.getOrElse {
                UsbNative.close(handle)
                connection.close()
                return Result.failure(it)
            }
            return Result.success(UsbSession(device, connection, handle, description))
        }
    }
}
