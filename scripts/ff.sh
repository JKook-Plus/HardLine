#!/usr/bin/env bash
# Runs ffprobe/ffmpeg from the jellyfin image (the host has no ffmpeg), with the repo mounted at /w.
#   ff.sh ffprobe -hide_banner /w/out/x.mp4
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
tool="$1"; shift
exec docker run --rm --network host -u "$(id -u):$(id -g)" -v "$ROOT:/w" --entrypoint "/usr/lib/jellyfin-ffmpeg/$tool" jellyfin/jellyfin:latest "$@"
