package dev.hardline.usb

import java.nio.ByteBuffer

interface VideoListener {
    /** [data] is only valid during the call. */
    fun onFrame(data: ByteBuffer, size: Int, width: Int, height: Int, ptsNs: Long)
    fun onButton(button: Int, state: Int)
}

interface AudioListener {
    /** Interleaved 16-bit stereo; [data] is only valid during the call. */
    fun onAudio(data: ByteBuffer, frames: Int, sampleRate: Int)
}

object UsbNative {
    init {
        System.loadLibrary("usb1")
        System.loadLibrary("usbcam")
    }

    @JvmStatic external fun open(fd: Int, video: Boolean): Long
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun lastError(): String
    @JvmStatic external fun describe(handle: Long): String
    @JvmStatic external fun startVideo(handle: Long, streamInterface: Int, format: Int, frame: Int, interval: Int, listener: VideoListener): Int
    @JvmStatic external fun stopVideo(handle: Long)
    @JvmStatic external fun control(handle: Long, unit: Int, selector: Int, request: Int, data: ByteArray): Int
    @JvmStatic external fun startAudio(handle: Long, wantedRate: Int, listener: AudioListener): Int
    @JvmStatic external fun stopAudio(handle: Long)
}
