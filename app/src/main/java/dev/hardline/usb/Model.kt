package dev.hardline.usb

import org.json.JSONObject

/** How the bytes of one frame are laid out, as far as the renderer and the decoders care. */
enum class PixelKind { MJPEG, YUYV, UYVY, NV12, NV21, I420, GRAY8, H264, H265, UNSUPPORTED }

data class FrameSize(val index: Int, val width: Int, val height: Int, val defaultInterval: Int, val intervals: List<Int>) {
    override fun toString() = "${width}x$height"
}

data class VideoFormat(val index: Int, val fourcc: String, val frames: List<FrameSize>) {
    val kind: PixelKind = when (fourcc.trim()) {
        "MJPG" -> PixelKind.MJPEG
        "YUY2" -> PixelKind.YUYV
        "UYVY" -> PixelKind.UYVY
        "NV12" -> PixelKind.NV12
        "NV21" -> PixelKind.NV21
        "I420" -> PixelKind.I420
        "Y800", "Y8", "GREY" -> PixelKind.GRAY8
        "H264" -> PixelKind.H264
        "H265", "HEVC" -> PixelKind.H265
        else -> PixelKind.UNSUPPORTED
    }
    val label: String = when (kind) {
        PixelKind.H264 -> "H.264"
        PixelKind.H265 -> "H.265"
        PixelKind.MJPEG -> "MJPG"
        else -> fourcc.trim()
    }
}

data class StreamInterface(val number: Int, val bulk: Boolean, val stillMethod: Int, val formats: List<VideoFormat>)

data class AudioFormat(val channels: Int, val bits: Int, val version: Int, val rates: List<Int>)

data class DeviceDescription(
    val vendorId: Int, val productId: Int, val bcdUsb: Int, val speed: Int, val uvcVersion: Int,
    val streams: List<StreamInterface>,
    val processingUnit: Int, val processingControls: Long,
    val cameraTerminal: Int, val cameraControls: Long,
    val audio: List<AudioFormat>,
) {
    val speedLabel: String
        get() = when (speed) {
            1 -> "1.5 Mbps"; 2 -> "12 Mbps"; 3 -> "480 Mbps"; 4 -> "5 Gbps"; 5 -> "10 Gbps"; else -> "unknown"
        }

    companion object {
        fun parse(json: String): DeviceDescription {
            val o = JSONObject(json)
            fun ints(a: org.json.JSONArray) = (0 until a.length()).map { a.getInt(it) }
            val streams = o.getJSONArray("streams").let { arr ->
                (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    val formats = s.getJSONArray("formats").let { fa ->
                        (0 until fa.length()).map { k ->
                            val f = fa.getJSONObject(k)
                            val frames = f.getJSONArray("frames").let { ra ->
                                (0 until ra.length()).map { m ->
                                    val r = ra.getJSONObject(m)
                                    val intervals = ints(r.getJSONArray("intervals")).ifEmpty { listOf(r.getInt("defaultInterval")) }
                                    FrameSize(r.getInt("index"), r.getInt("width"), r.getInt("height"), r.getInt("defaultInterval"), intervals)
                                }
                            }
                            VideoFormat(f.getInt("index"), f.getString("fourcc"), frames)
                        }
                    }
                    StreamInterface(s.getInt("interface"), s.getBoolean("bulk"), s.optInt("stillMethod"), formats)
                }
            }
            val pu = o.optJSONObject("processingUnit")
            val ct = o.optJSONObject("cameraTerminal")
            val audio = o.getJSONArray("audio").let { arr ->
                (0 until arr.length()).map { i ->
                    val a = arr.getJSONObject(i)
                    AudioFormat(a.getInt("channels"), a.getInt("bits"), a.getInt("version"), ints(a.getJSONArray("rates")))
                }
            }
            return DeviceDescription(
                o.getInt("vendorId"), o.getInt("productId"), o.getInt("bcdUsb"), o.getInt("speed"), o.optInt("uvcVersion"),
                streams, pu?.getInt("id") ?: 0, pu?.getLong("controls") ?: 0L, ct?.getInt("id") ?: 0, ct?.getLong("controls") ?: 0L, audio,
            )
        }
    }
}

/** A standard UVC control: where it lives, how wide its value is and how to present it. */
enum class CameraControl(
    val label: String, val camera: Boolean, val selector: Int, val bit: Int, val size: Int, val signed: Boolean,
    val kind: Kind = Kind.RANGE, val offset: Int = 0, val valueSize: Int = size,
) {
    BRIGHTNESS("Brightness", false, 0x02, 0, 2, true),
    CONTRAST("Contrast", false, 0x03, 1, 2, false),
    HUE("Hue", false, 0x06, 2, 2, true),
    SATURATION("Saturation", false, 0x07, 3, 2, false),
    SHARPNESS("Sharpness", false, 0x08, 4, 2, false),
    GAMMA("Gamma", false, 0x09, 5, 2, false),
    WHITE_BALANCE("White balance", false, 0x0A, 6, 2, false),
    BACKLIGHT("Backlight compensation", false, 0x01, 8, 2, false),
    GAIN("Gain", false, 0x04, 9, 2, false),
    POWER_LINE("Power line frequency", false, 0x05, 10, 1, false, Kind.MENU),
    HUE_AUTO("Auto hue", false, 0x10, 11, 1, false, Kind.TOGGLE),
    WHITE_BALANCE_AUTO("Auto white balance", false, 0x0B, 12, 1, false, Kind.TOGGLE),
    CONTRAST_AUTO("Auto contrast", false, 0x13, 18, 1, false, Kind.TOGGLE),
    EXPOSURE_MODE("Exposure mode", true, 0x02, 1, 1, false, Kind.MENU),
    EXPOSURE("Exposure", true, 0x04, 3, 4, false),
    FOCUS("Focus", true, 0x06, 5, 2, false),
    IRIS("Iris", true, 0x09, 7, 2, false),
    ZOOM("Zoom", true, 0x0B, 9, 2, false),
    PAN("Pan", true, 0x0D, 11, 8, true, Kind.RANGE, 0, 4),
    TILT("Tilt", true, 0x0D, 11, 8, true, Kind.RANGE, 4, 4),
    ROLL("Roll", true, 0x0F, 13, 2, true),
    FOCUS_AUTO("Auto focus", true, 0x08, 17, 1, false, Kind.TOGGLE),
    PRIVACY("Privacy shutter", true, 0x11, 18, 1, false, Kind.TOGGLE);

    enum class Kind { RANGE, TOGGLE, MENU }
}

data class ControlRange(val min: Int, val max: Int, val default: Int, val step: Int, val current: Int)

/** Whether [value] is one the control can take. Cheap cameras report nonsense defaults for switches and menus. */
fun CameraControl.accepts(value: Int, r: ControlRange): Boolean = when (kind) {
    CameraControl.Kind.RANGE -> value in r.min..r.max
    CameraControl.Kind.TOGGLE -> value in 0..1
    CameraControl.Kind.MENU -> if (this == CameraControl.EXPOSURE_MODE) value in intArrayOf(1, 2, 4, 8) else value in 0..3
}

const val UVC_SET_CUR = 0x01
const val UVC_GET_CUR = 0x81
const val UVC_GET_MIN = 0x82
const val UVC_GET_MAX = 0x83
const val UVC_GET_RES = 0x84
const val UVC_GET_DEF = 0x87
