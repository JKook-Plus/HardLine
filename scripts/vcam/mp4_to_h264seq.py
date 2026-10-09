#!/usr/bin/env python3
"""Extract the H.264 track of an MP4 into the record format uvc_usbip --h264 reads:
repeated [u32 big-endian length][Annex-B access unit], with SPS/PPS prepended to every
sync sample so the sequence can be looped from any keyframe.

  mp4_to_h264seq.py in.mp4 out.h264seq
"""
import struct
import sys

CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl"}


def find(data, start, end, want, out):
    pos = start
    while pos + 8 <= end:
        size, kind = struct.unpack_from(">I4s", data, pos)
        head = 8
        if size == 1:  # 64-bit box size
            size, head = struct.unpack_from(">Q", data, pos + 8)[0], 16
        elif size == 0:
            size = end - pos
        if size < head:
            break
        if kind in want:
            out[kind] = (pos + head, pos + size)
        if kind in CONTAINERS:
            find(data, pos + head, pos + size, want, out)
        pos += size
    return out


def main():
    data = open(sys.argv[1], "rb").read()
    b = find(data, 0, len(data), {b"stsd", b"stsz", b"stsc", b"stco", b"co64", b"stss"}, {})

    s, e = b[b"stsd"]
    cfg = data.find(b"avcC", s, e) + 4
    nal_len = (data[cfg + 4] & 3) + 1
    pos, params = cfg + 5, []
    for mask in (0x1F, 0xFF):  # SPS list, then PPS list
        count = data[pos] & mask
        pos += 1
        for _ in range(count):
            n = struct.unpack_from(">H", data, pos)[0]
            params.append(data[pos + 2:pos + 2 + n])
            pos += 2 + n
    header = b"".join(b"\0\0\0\1" + p for p in params)

    s, _ = b[b"stsz"]
    fixed, count = struct.unpack_from(">II", data, s + 4)
    sizes = [fixed] * count if fixed else list(struct.unpack_from(f">{count}I", data, s + 12))
    s, _ = b[b"stsc"]
    n = struct.unpack_from(">I", data, s + 4)[0]
    stsc = [struct.unpack_from(">III", data, s + 8 + 12 * i) for i in range(n)]
    if b"stco" in b:
        s, _ = b[b"stco"]
        n = struct.unpack_from(">I", data, s + 4)[0]
        chunks = list(struct.unpack_from(f">{n}I", data, s + 8))
    else:
        s, _ = b[b"co64"]
        n = struct.unpack_from(">I", data, s + 4)[0]
        chunks = list(struct.unpack_from(f">{n}Q", data, s + 8))
    sync = set()
    if b"stss" in b:
        s, _ = b[b"stss"]
        n = struct.unpack_from(">I", data, s + 4)[0]
        sync = set(struct.unpack_from(f">{n}I", data, s + 8))

    out, sample = open(sys.argv[2], "wb"), 0
    for ci, offset in enumerate(chunks, start=1):
        per_chunk = [e for e in stsc if e[0] <= ci][-1][1]
        for _ in range(per_chunk):
            if sample >= len(sizes):
                break
            raw, au, p = data[offset:offset + sizes[sample]], b"", 0
            while p + nal_len <= len(raw):
                n = int.from_bytes(raw[p:p + nal_len], "big")
                au += b"\0\0\0\1" + raw[p + nal_len:p + nal_len + n]
                p += nal_len + n
            if (sample + 1) in sync or (not sync and sample == 0):
                au = header + au
            out.write(struct.pack(">I", len(au)) + au)
            offset += sizes[sample]
            sample += 1
    print(f"{sample} access units, {len(sync)} sync samples, SPS/PPS {[len(p) for p in params]} bytes")


if __name__ == "__main__":
    main()
