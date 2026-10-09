# Architecture

HardLine is one Android app module, `app`, written in Kotlin with a small C++ bridge to libusb and
libuvc. This page follows a frame from the USB cable to the screen, a file and the network, and
says which package does what.

## The path of a frame

```
USB camera
   │  Android UsbManager opens the device and hands over a file descriptor
   ▼
usbcam.cpp          libusb + libuvc, one event thread per device
   │  VideoListener.onFrame(ByteBuffer)      AudioListener.onAudio(ByteBuffer)
   ▼                                              ▼
VideoIngest         routes by pixel format     AudioEngine      mixes USB, microphone, system sound
   │  raw ─────────────────────────┐              │  48 kHz stereo, 20 ms blocks
   │  MJPEG ──► MjpegDecoder ──────┤              ▼
   │  H.264/5 ► H26xDecoder ───────┤           AudioBroadcast   AAC and Opus encoders
   ▼                               ▼
                 Pipeline           one OpenGL thread: convert to RGBA, flip, deinterlace, overlays
                    │
       ┌────────────┼──────────────┬───────────────────┐
       ▼            ▼              ▼                   ▼
    preview     VideoBroadcast   Snapshotter       MotionDetector
    windows     encoder input    JPEG stills       small grey copy
                surfaces
                    │
       ┌────────────┼──────────────┬───────────────────┐
       ▼            ▼              ▼                   ▼
    Recorder     RtspServer     WebServer          RtmpPublisher
    MP4 files    RTP            FLV, MJPEG, Opus   SrtPublisher
```

The pipeline composes the picture once. The screen, the lock-screen view, the floating window and
every encoder draw that same composed texture, so an overlay costs the same whether one consumer
is attached or six.

Encoders exist only while something needs them. `VideoBroadcast` starts a `MediaCodec` encoder when
the first consumer subscribes and stops it when the last one leaves. There is one broadcast for
recording and one per codec for streaming, so an RTSP client, an FLV viewer and an RTMP push that
all use H.264 share a single encoder. When the camera already sends H.264 or H.265 and the user
turns on pass-through, the broadcast forwards the camera's own stream and no encoder runs.

## Packages

All paths are under `app/src/main/java/dev/hardline/`.

| Package | Contents |
|---|---|
| `usb` | `UsbNative` is the JNI surface. `UsbSession` is one opened device: formats, controls, audio. `Model.kt` holds the UVC data types and the table of standard controls. |
| `gl` | `EglCore` and `Pipeline`. The shaders for each pixel format live in `Pipeline.kt`. |
| `media` | Decoders, encoders, the audio mixer, the MP4 recorder, stills, motion detection and storage. |
| `net` | The web server, RTSP server, RTMP and SRT publishers, the FLV, MPEG-TS and Ogg writers, TLS, UPnP, FTP and mail. |
| `overlay` | Text and watermark layers, and the phone-camera and screen insets. |
| `service` | `CameraController` owns everything above and lives as long as the process. `CameraService` is the foreground service that keeps the process alive. |
| `ui` | Jetpack Compose screens. Activities only observe the controller's state flows. |
| `core` | `Settings` and its typed keys, and the location tracker. |

The native side is `app/src/main/cpp/usbcam.cpp`. It builds two shared libraries: `libusb1.so`,
which is libusb alone so that it stays replaceable under the LGPL, and `libusbcam.so`, which is
libuvc plus the bridge.

## Why the protocols are written in Kotlin

The RTSP server, the RTMP and SRT publishers and the FLV, MPEG-TS and Ogg writers are a few hundred
lines each and use no native media library. That keeps the APK near 3 MB and means the JVM unit
tests in `app/src/test` can check the byte layouts directly. The phone's own `MediaCodec` does all
encoding and decoding.

The cost is that each protocol covers what the app needs and no more. The RTSP server serves one
stream at `/live`. The SRT publisher implements the handshake, encryption and retransmission that a
live sender needs, not the whole specification.

## Threads

- One libusb event thread per open device, started in `usbcam.cpp`. Frame callbacks arrive on it,
  so `VideoIngest` copies or queues and returns.
- One OpenGL thread inside `Pipeline`. Everything that touches a GL object runs there.
- One thread per encoder and per decoder.
- The web and RTSP servers use a thread per client.
- Everything else runs on the main thread or in coroutines owned by `CameraController`.

## Settings

Every user setting is a typed key in `core/Settings.kt`, grouped the way the settings screens show
them. Debug builds include `PrefsReceiver`, which lets a test change a setting of the running app
over `adb`. Release builds do not contain it.
