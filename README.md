<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="branding/logo-dark.svg">
    <img src="branding/logo.svg" alt="HardLine" width="360">
  </picture>
</p>

<p align="center">
  View, record and stream USB cameras from an Android phone.<br>
  No root, no ads, no account.
</p>

<p align="center">
  <a href="https://github.com/JKook-Plus/HardLine/actions/workflows/ci.yml"><img src="https://github.com/JKook-Plus/HardLine/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/JKook-Plus/HardLine/releases/latest"><img src="https://img.shields.io/github/v/release/JKook-Plus/HardLine?label=release" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0 or newer">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="GPL 3.0 licence"></a>
</p>

<p align="center">
  <img src="docs/images/preview.png" width="200" alt="Live preview of a camera">
  <img src="docs/images/adjust.png" width="200" alt="Picture controls">
  <img src="docs/images/server.png" width="200" alt="Web server addresses and QR code">
  <img src="docs/images/settings.png" width="200" alt="Settings">
</p>

Plug a webcam, endoscope, microscope or HDMI capture adapter into the phone's USB port and HardLine
shows the picture. From there it records, takes stills, and serves the camera to the rest of your
network as a web page, an RTSP stream, or a push to an RTMP or SRT server.

It talks to the camera itself, through Android's USB host API and [libuvc](https://github.com/libuvc/libuvc),
so it does not depend on the phone having external-camera support and it does not need root.

## Features

**Cameras**

- Any USB Video Class camera. Uncompressed YUY2, UYVY, NV12, NV21, I420 and 8-bit grey, plus MJPEG,
  H.264 and H.265.
- Isochronous and bulk transfer, so ordinary webcams and bulk-only devices both work.
- The camera's own controls: brightness, contrast, saturation, hue, sharpness, gamma, white
  balance, gain, exposure, focus, zoom, pan and tilt. HardLine remembers them per camera.
- USB audio from the camera or a separate USB sound device, mixed with the phone's microphone or
  the phone's own sound if you want.
- The button on the camera takes a picture, starts a recording or shoots a burst. Headset and
  media keys can do the same.

**Recording**

- MP4 with H.264, HEVC or AV1 video and AAC sound, encoded by the phone's hardware.
- Files split by length, loop recording inside a storage budget, and a 4 GB split for FAT cards.
- JPEG stills with optional GPS position in the EXIF data.
- Motion detection that starts a recording, sends an e-mail, or uploads the file over FTP.

**Streaming**

- A built-in web page to watch and control the camera from any browser.
- Motion-JPEG, HTTP-FLV and Ogg/Opus over HTTP, with optional HTTPS.
- An RTSP server with H.264 or HEVC video and AAC sound, for VLC, OBS and network video recorders.
- Live push to RTMP and RTMPS servers, and SRT as caller or listener with optional encryption.
- The web server announces itself on the local network with mDNS. UPnP port mapping is there too,
  and stays off until you turn it on.

**On screen**

- Text overlay with time, battery, GPS position, speed or your own text, and an image watermark.
- The phone's own camera or its screen as an inset in the corner of the picture.
- Rotate, mirror, change the aspect ratio, deinterlace, and a side-by-side view for headsets.
- Picture-in-picture, a floating window, and a view on the lock screen.

## Install

Download the APK from the [latest release](https://github.com/JKook-Plus/HardLine/releases/latest)
and open it on the phone. Android asks once for permission to install from your browser or file
manager.

[Obtainium](https://github.com/ImranR98/Obtainium) can track releases for you. Add this repository's
address as the app source.

Releases are signed with a certificate whose SHA-256 fingerprint is:

```
29:55:29:2A:E7:CF:6B:76:8F:AB:58:99:10:F6:EC:77:10:B0:D5:88:70:F4:C2:BE:87:91:A6:A2:3B:E5:47:88
```

You can check a download with `apksigner verify --print-certs HardLine-*.apk`.

HardLine needs Android 8.0 or newer and a phone whose USB port works in host mode. Most phones with
USB-C do. The APK carries `arm64-v8a` and `x86_64` code.

## Use

1. Plug the camera in. A USB-C hub or an OTG adapter works. Use a powered hub for cameras that draw
   more than the phone supplies.
2. Android asks whether HardLine may use the device. Allow it, and tick the box if you want
   HardLine to open by itself next time.
3. The preview starts. The buttons along the bottom record, watch for motion, take a picture, start
   the web server and start a live push.

With the web server on, the phone shows its addresses and a QR code. These are the ones you will
use most, with `PHONE` standing for the phone's address:

| What | Address |
|---|---|
| Control page | `http://PHONE:8081/` |
| Video for browsers, Motion-JPEG | `http://PHONE:8081/video` |
| Video with sound, HTTP-FLV | `http://PHONE:8081/live.flv` |
| Sound only, Ogg/Opus | `http://PHONE:8081/audio.opus` |
| Still picture | `http://PHONE:8081/snapshot.jpg` |
| RTSP | `rtsp://PHONE:8554/live` |

The server asks for a user name and password. HardLine generates the password on first use and
shows it on the same sheet as the addresses.

```sh
ffplay "rtsp://admin:PASSWORD@PHONE:8554/live"
vlc "http://admin:PASSWORD@PHONE:8081/live.flv"
curl -u admin:PASSWORD -o still.jpg "http://PHONE:8081/snapshot.jpg"
```

## What it has been tested with

HardLine is at version 0.1 and you should expect bugs. So far it has been tested with:

- A virtual UVC camera on the Android 15 emulator, which is how most of the development happened.
  It covers MJPEG, YUY2 and H.264, isochronous and bulk transfer, USB audio and the camera button.
- One real camera, a 640x480 USB endoscope with the USB ID `349c:8319`.

If you try a camera, please open a [camera report](https://github.com/JKook-Plus/HardLine/issues/new?template=camera_report.yml),
whether it worked or not. The device details sheet in the app has everything the report asks for.

## Build from source

You need JDK 17 or newer, the Android SDK, and `bash`, `curl`, `tar` and `patch` on the path. The
build downloads the NDK, CMake, libusb and libuvc by itself.

```sh
git clone https://github.com/JKook-Plus/HardLine.git
cd HardLine
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests
```

[docs/BUILDING.md](docs/BUILDING.md) covers release signing, Windows, and what the native
dependency script does.

## Test without a camera

The repository includes a virtual USB camera. It is a small C program that speaks the USB/IP
protocol to the emulator's kernel, so Android sees a real UVC device being plugged in and the app
runs its normal USB code against it.

```sh
scripts/emulator.sh start
scripts/vcam.sh build && scripts/vcam.sh prepare
scripts/vcam.sh attach --audio
```

[docs/TESTING.md](docs/TESTING.md) has the full walk-through, including the small RTSP, RTMP, SMTP
and FTP test servers in `scripts/`.

## How it is built

A foreground service owns the camera. Native code built on libusb and libuvc reads frames and hands
them to Kotlin. One OpenGL pipeline turns every pixel format into RGBA, draws the overlays once,
and renders the result to the screen and straight into the hardware encoders. The muxers and
network protocols are plain Kotlin with no native media libraries, which is why the APK is about
3 MB.

[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) walks through the packages and the path of a frame.

## Privacy

HardLine has no analytics, no crash reporting and no advertising. It opens network connections only
for the things you switch on: the web and RTSP servers, live push, e-mail alerts, FTP upload, and
UPnP. It reads the phone's location only while the position overlay, the speed overlay or EXIF
position is on.

## Contributing

Bug reports, camera reports and pull requests are welcome. [CONTRIBUTING.md](CONTRIBUTING.md) says
how to set up, what the code style is and what a pull request should contain. Please report
security problems privately, as described in [SECURITY.md](SECURITY.md).

## Credits

HardLine was inspired by [USB Camera](https://play.google.com/store/apps/details?id=com.shenyaocn.android.usbcamera)
by ShenYao China, and its feature set follows that app closely. HardLine is a separate project with
its own code. It is not affiliated with that app's developer or endorsed by them.

It is built on [libusb](https://libusb.info) and [libuvc](https://github.com/libuvc/libuvc), and
uses Apache Commons Net, JavaMail for Android, ZXing, AndroidX and Kotlin.
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) lists their licences. The wordmark is set in
Barlow Condensed.

## Licence

Copyright © 2026 the HardLine contributors.

HardLine is free software. You may share and change it under the terms of the
[GNU General Public License, version 3](LICENSE). If you distribute a changed version, you must
publish its source under the same licence. HardLine comes with no warranty.
