#!/usr/bin/env bash
# Virtual UVC webcam for the test emulator (see scripts/vcam/uvc_usbip.c).
#
#   vcam.sh build             compile the device server (host) and the guest tools
#   vcam.sh prepare           per emulator boot: USB host support, permissive SELinux, push tool
#   vcam.sh attach [--bulk]   start the device server and plug the camera in
#   vcam.sh detach            unplug the camera and stop the server
#   vcam.sh button [press|release]   click the camera's button, or send only half of a click
#
# The emulator must be running (scripts/emulator.sh start) and rooted (google_apis image).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/env.sh"
OUT="$ROOT/out/vcam"
PORT="${VCAM_PORT:-3240}"

build() {
  mkdir -p "$OUT"
  gcc -O2 -Wall -Wextra -o "$OUT/uvc_usbip" "$ROOT/scripts/vcam/uvc_usbip.c" -lm
  gcc -O2 -Wall -static -o "$OUT/vhci_attach" "$ROOT/scripts/vcam/vhci_attach.c"
  gcc -O2 -Wall -static -o "$OUT/udprelay" "$ROOT/scripts/vcam/udprelay.c"
}

prepare() {
  adb root >/dev/null; sleep 2
  # vhci_rx runs as the kernel and may not read a socket created by a root shell.
  adb shell setenforce 0
  adb push "$OUT/vhci_attach" /data/local/tmp/vhci_attach >/dev/null 2>&1
  adb shell chmod 755 /data/local/tmp/vhci_attach
  if ! adb shell pm list features | grep -q android.hardware.usb.host; then
    # The image reads extra features from this file (symlinked from /vendor/etc/permissions).
    adb shell 'printf "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<permissions>\n    <feature name=\"android.hardware.usb.host\" />\n</permissions>\n" > /data/system/extra_feature.xml
               chown system:system /data/system/extra_feature.xml; chmod 644 /data/system/extra_feature.xml
               restorecon /data/system/extra_feature.xml; stop; sleep 2; start'
    sleep 8
    for _ in $(seq 120); do
      adb shell pm list features 2>/dev/null | grep -q android.hardware.usb.host && break
      sleep 2
    done
    adb shell setenforce 0
  fi
  adb shell pm list features | grep usb.host
}

attach() {
  pkill -x uvc_usbip 2>/dev/null || true
  sleep 0.5
  setsid nohup "$OUT/uvc_usbip" --port "$PORT" "$@" >"$OUT/usbip.log" 2>&1 &
  sleep 1
  adb shell /data/local/tmp/vhci_attach 10.0.2.2 "$PORT" 0 3
  echo "device log: $OUT/usbip.log"
}

detach() {
  adb shell 'echo 0 > /sys/devices/platform/vhci_hcd.0/detach' 2>/dev/null || true
  pkill -x uvc_usbip 2>/dev/null || true
}

button() {
  touch "/tmp/vcam-button${1:+-$1}"
}

cmd="${1:-}"; shift || true
case "$cmd" in build) build ;; prepare) prepare ;; attach) attach "$@" ;; detach) detach ;; button) button "$@" ;;
  *) echo "usage: $0 build|prepare|attach [--bulk]|detach|button [press|release]" >&2; exit 2 ;; esac
