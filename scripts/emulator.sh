#!/usr/bin/env bash
# Start/stop the headless test emulator.
#   emulator.sh start   boot the AVD and wait until Android is ready
#   emulator.sh stop    shut it down
#
# The emulator needs /dev/kvm. If this user can open it, the emulator runs directly.
# Otherwise it runs in a container that is handed /dev/kvm (nothing else), as this
# user's uid, with the SDK mounted read-only. To make the native path work instead:
#   sudo usermod -aG kvm "$USER"   (then log in again)
#
# USB=vid:pid emulator.sh start   also hands one USB device of this machine to the guest, to test
# with a real camera. That needs write access to its /dev/bus/usb node, which only the node's
# group has, so it always uses the container. The host's own driver lets go of the device and
# does not take it back when the emulator stops: re-plug the device to use it on the host again.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/env.sh"
AVD="${AVD:-usbcam_api35}"
NAME=usbcam-emulator
IMAGE=usbcam-emulator:latest
LOG="$ROOT/out/emulator.log"
ARGS=(-avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics
      -gpu swiftshader_indirect -camera-back emulated -camera-front emulated
      -memory 4096 -cores 4)

start() {
  # A crashed emulator leaves pid lock files behind; inside a container the recorded
  # pid is always "alive", so clear them once we know nothing is running.
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  rm -f "$HOME/.android/avd/$AVD.avd"/*.lock
  local usb=()
  if [[ -n "${USB:-}" ]]; then
    local node
    node="$(lsusb -d "$USB" | sed -nE '1s#^Bus ([0-9]+) Device ([0-9]+):.*#/dev/bus/usb/\1/\2#p')"
    [[ -n "$node" ]] || { echo "USB device $USB is not plugged in" >&2; exit 1; }
    usb=(--device "$node" --group-add "$(stat -c %g "$node")")
    ARGS+=(-usb-passthrough "vendorid=0x${USB%%:*},productid=0x${USB##*:}")
  fi
  if [[ -z "${USB:-}" && -r /dev/kvm && -w /dev/kvm ]]; then
    nohup "$ANDROID_HOME/emulator/emulator" "${ARGS[@]}" >"$LOG" 2>&1 &
    echo "emulator started natively (pid $!)"
  else
    docker image inspect "$IMAGE" >/dev/null 2>&1 || docker build -q -t "$IMAGE" "$ROOT/scripts/emulator"
    docker run -d --init --name "$NAME" \
      --device /dev/kvm --user "$(id -u):$(id -g)" --group-add "$(stat -c %g /dev/kvm)" "${usb[@]}" \
      --network host --tmpfs /tmp:exec \
      -e HOME=/tmp -e USER="$USER" \
      -e ANDROID_HOME="$ANDROID_HOME" -e ANDROID_SDK_ROOT="$ANDROID_HOME" \
      -e ANDROID_EMULATOR_HOME="$HOME/.android" -e ANDROID_AVD_HOME="$HOME/.android/avd" \
      -v "$ANDROID_HOME:$ANDROID_HOME:ro" -v "$HOME/.android:$HOME/.android" \
      "$IMAGE" "$ANDROID_HOME/emulator/emulator" "${ARGS[@]}" >/dev/null
    echo "emulator started in container '$NAME' (docker logs $NAME)"
  fi
  adb start-server >/dev/null 2>&1
  for _ in $(seq 150); do
    [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]] && break
    sleep 2
  done
  [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]] || { echo "emulator did not boot within 5 minutes" >&2; exit 1; }
  echo "boot completed: $(adb shell getprop ro.build.version.release | tr -d '\r') / $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
}

stop() {
  adb emu kill >/dev/null 2>&1 || true
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  pkill -u "$(id -u)" -f "qemu-system-x86_64.*-avd $AVD" 2>/dev/null || true
  echo "emulator stopped"
}

case "${1:-}" in start) start ;; stop) stop ;; *) echo "usage: $0 start|stop" >&2; exit 2 ;; esac
