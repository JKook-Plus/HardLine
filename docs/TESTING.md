# Testing

There are three layers: JVM unit tests, the app on an emulator with a virtual USB camera, and real
hardware.

## Unit tests

```sh
./gradlew testDebugUnitTest
```

The tests in `app/src/test` cover the code that has an exact right answer: NAL unit parsing, the
FLV, MPEG-TS and Ogg writers, AMF encoding, and the table of UVC controls. They run on the JVM and
need no device. CI runs them on every push.

## The virtual camera

The Android emulator has no USB port to plug a camera into, so the repository brings its own
camera. `scripts/vcam/uvc_usbip.c` is a UVC webcam written as a USB/IP device server. The
emulator's kernel includes `vhci_hcd`, the USB/IP virtual host controller. Once
`scripts/vcam/vhci_attach.c` hands the kernel a socket connected to the server, Android sees a USB
camera being plugged in. The app asks for permission, opens the device and streams from it through
the same libusb and libuvc code it uses on a phone.

### What you need

- A Linux host with KVM.
- The Android SDK with the emulator and the `system-images;android-35;google_apis;x86_64` image.
  The image must be a `google_apis` one, because the rig needs `adb root`.
- `gcc` with a static C library, to build the tool that runs inside the emulator.
- Docker, only if your user cannot open `/dev/kvm`, and for `scripts/ff.sh`.

Create the emulator once:

```sh
source scripts/env.sh
sdkmanager "emulator" "platform-tools" "system-images;android-35;google_apis;x86_64"
avdmanager create avd -n usbcam_api35 -k "system-images;android-35;google_apis;x86_64" -d pixel_6
```

### A session

```sh
scripts/emulator.sh start          # boots headless and waits for Android
scripts/vcam.sh build              # once: compiles the camera and the guest tools into out/vcam
scripts/vcam.sh prepare            # once per boot: USB host feature, permissive SELinux
./gradlew installDebug
scripts/vcam.sh attach --audio     # plug the camera in
python3 scripts/ui.py tap button1  # accept Android's "Open HardLine to handle this device?"
python3 scripts/ui.py shot preview # screenshot to out/screenshots/preview.png
scripts/vcam.sh detach             # unplug
scripts/emulator.sh stop
```

Always detach before you reinstall, force-stop or uninstall the app. Killing the app in the middle
of a stream locks up the kernel's USB/IP driver, and the emulator then needs a restart.

### What the camera can do

The camera shows moving colour bars with a frame counter, at 640x480 and 1280x720 in MJPEG and
640x480 and 320x240 in YUY2, at 30 or 15 frames a second. Options to `vcam.sh attach`:

| Option | Effect |
|---|---|
| `--bulk` | Use a bulk endpoint for video instead of an isochronous one |
| `--audio` | Add a USB Audio Class microphone that plays a 440 Hz tone |
| `--h264 FILE` | Add a frame-based H.264 format that loops the access units in `FILE` |
| `--only mjpg\|yuy2\|h264` | Offer one format only |
| `--no-button` | Leave out the status endpoint that carries the camera button |

`scripts/vcam/mp4_to_h264seq.py in.mp4 out.h264seq` makes the file for `--h264` from any H.264 MP4,
such as one of the app's own recordings.

The server logs every control request to `out/vcam/usbip.log`, which is the quickest way to see
what the app asked a camera for.

Two things are driven from outside while it runs:

- `scripts/vcam.sh button` presses and releases the camera's button. `button press` and
  `button release` send one half.
- `touch /tmp/vcam-still` freezes the picture and `rm /tmp/vcam-still` lets it move again. Use it
  to test motion detection.

### Driving the app

| Script | Purpose |
|---|---|
| `scripts/ui.py` | Dump the visible view tree, tap by text or id, type, press keys, take screenshots |
| `scripts/setpref.py key=value` | Change a setting of the running debug build without going through the screens |
| `scripts/rtsp_probe.py` | A small RTSP client: Digest sign-in, SDP, and a count of the RTP packets that arrive |
| `scripts/rtmp_sink.py` | An RTMP ingest that accepts one publisher and logs what it sends |
| `scripts/smtp_sink.py` | An SMTP server that saves each message to a file |
| `scripts/ftp_sink.py` | An FTP server with one user that saves uploads |
| `scripts/udp_bridge.py` | The host half of a UDP path into the emulator, for SRT. The guest half is `out/vcam/udprelay` |
| `scripts/mp4info.py` | Print the tracks, codecs, duration and sample counts of an MP4 |
| `scripts/ff.sh ffprobe ...` | Run `ffprobe` or `ffmpeg` from a container, with the repository mounted at `/w` |

From the emulator, the host is `10.0.2.2`. To reach the app's servers from the host, forward the
ports:

```sh
adb forward tcp:18081 tcp:8081
adb forward tcp:18554 tcp:8554
curl -u admin:PASSWORD http://127.0.0.1:18081/api/status
python3 scripts/rtsp_probe.py rtsp://127.0.0.1:18554/live admin PASSWORD
```

### Things that will trip you up

- `adb install --abi arm64-v8a` makes the x86_64 emulator run the app's ARM libraries through its
  translator. That is the same native code a phone runs.
- The emulator's software HEVC encoder stops at 512x512. Test HEVC at 320x240.
- `ui.py tap <text>` taps the first node that contains the text. System dialogs are easier to hit
  by button id, such as `button1` for the positive button.
- `USB=vid:pid scripts/emulator.sh start` passes a real USB device from the host through to the
  emulator. The host's own driver lets go of the device and does not take it back, so re-plug it
  afterwards.

## Real hardware

The emulator cannot tell you how a camera behaves on a phone's USB port: power draw, cable quality
and timing all differ. Before a release, check at least preview, a recording and the web server on
a real phone with a real camera.

If a camera misbehaves, the device details sheet in the app shows its descriptors. Attach that to
the issue, along with the output of `adb logcat -s usbcam`.
