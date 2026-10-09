#!/usr/bin/env bash
# Downloads the native dependencies into third_party/ (not committed), checks them against the
# pinned checksums and applies the patches in app/src/main/cpp/patches. Gradle runs this before
# the native build; it does nothing when the sources are already there.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/third_party"
PATCHES="$ROOT/app/src/main/cpp/patches"

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}

# fetch <directory in the archive> <url> <sha256>
fetch() {
  local dir="$1" url="$2" want="$3" tmp got
  # The stamp names the checksum and the patches, so changing either fetches again.
  local stamp="$want $(cat "$PATCHES/$dir"-*.patch 2>/dev/null | sha256 /dev/stdin)"
  [[ -f "$DEST/$dir/.fetched" && "$(cat "$DEST/$dir/.fetched")" == "$stamp" ]] && return
  echo "fetching $dir"
  tmp="$(mktemp)"
  trap 'rm -f "$tmp"' RETURN
  curl -fsSL --retry 3 -o "$tmp" "$url"
  got="$(sha256 "$tmp")"
  [[ "$got" == "$want" ]] || { echo "$dir: checksum mismatch (got $got, expected $want)" >&2; exit 1; }
  rm -rf "${DEST:?}/$dir"
  mkdir -p "$DEST"
  tar -xf "$tmp" -C "$DEST"
  for p in "$PATCHES/$dir"-*.patch; do
    [[ -e "$p" ]] && patch -s -d "$DEST/$dir" -p1 < "$p"
  done
  echo "$stamp" > "$DEST/$dir/.fetched"
}

fetch libusb-1.0.30 https://github.com/libusb/libusb/releases/download/v1.0.30/libusb-1.0.30.tar.bz2 \
  fea36f34f9156400209595e300840767ab1a385ede1dc7ee893015aea9c6dbaf
fetch libuvc-0.0.8 https://github.com/libuvc/libuvc/archive/refs/tags/v0.0.8.tar.gz \
  abe134716f4c53fe60db2004b42adf6af60e64e45808135acfa4311454371ece
