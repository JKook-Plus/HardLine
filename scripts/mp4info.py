#!/usr/bin/env python3
"""Print the structure of an MP4 file: tracks, codecs, dimensions, duration, sample counts."""
import struct
import sys

CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl", b"edts", b"udta", b"mvex", b"dinf"}


def boxes(data, start, end):
    pos = start
    while pos + 8 <= end:
        size, kind = struct.unpack_from(">I4s", data, pos)
        head = 8
        if size == 1:
            size = struct.unpack_from(">Q", data, pos + 8)[0]
            head = 16
        elif size == 0:
            size = end - pos
        if size < head:
            break
        yield kind, pos + head, pos + size
        pos += size


def walk(data, start, end, depth, out):
    for kind, body, stop in boxes(data, start, end):
        if kind == b"ftyp":
            out.append(f"ftyp major={data[body:body+4].decode()} compatible={data[body+8:stop].decode(errors='replace')}")
        elif kind == b"mvhd":
            v = data[body]
            ts, dur = struct.unpack_from(">II", data, body + (20 if v else 12)) if not v else struct.unpack_from(">IQ", data, body + 20)
            out.append(f"movie duration={dur/ts:.2f}s timescale={ts}")
        elif kind == b"tkhd":
            w, h = struct.unpack_from(">II", data, stop - 8)
            out.append(f"  track {w >> 16}x{h >> 16}")
        elif kind == b"mdhd":
            v = data[body]
            ts, dur = struct.unpack_from(">II", data, body + 12) if not v else struct.unpack_from(">IQ", data, body + 20)
            out.append(f"    media duration={dur/ts:.2f}s timescale={ts}")
        elif kind == b"hdlr":
            out.append(f"    handler={data[body+8:body+12].decode()}")
        elif kind == b"stsd":
            n = struct.unpack_from(">I", data, body + 4)[0]
            pos = body + 8
            for _ in range(n):
                size, fourcc = struct.unpack_from(">I4s", data, pos)
                extra = ""
                if fourcc in (b"avc1", b"hvc1", b"hev1", b"av01"):
                    w, h = struct.unpack_from(">HH", data, pos + 32)
                    extra = f" {w}x{h}"
                    cfg = data.find(b"avcC", pos, pos + size)
                    if cfg > 0:
                        extra += f" profile={data[cfg+5]} level={data[cfg+7]}"
                elif fourcc == b"mp4a":
                    ch, bits = struct.unpack_from(">HH", data, pos + 24)
                    rate = struct.unpack_from(">I", data, pos + 32)[0] >> 16
                    extra = f" {ch}ch {bits}bit {rate}Hz"
                out.append(f"    codec={fourcc.decode()}{extra}")
                pos += size
        elif kind == b"stsz":
            out.append(f"    samples={struct.unpack_from('>I', data, body + 8)[0]}")
        elif kind == b"stss":
            out.append(f"    keyframes={struct.unpack_from('>I', data, body + 4)[0]}")
        elif kind in CONTAINERS:
            walk(data, body, stop, depth + 1, out)


if __name__ == "__main__":
    for path in sys.argv[1:]:
        data = open(path, "rb").read()
        out = [f"{path.rsplit('/', 1)[-1]} ({len(data)} bytes) top-level: " + " ".join(k.decode() for k, _, _ in boxes(data, 0, len(data)))]
        walk(data, 0, len(data), 0, out)
        print("\n".join(out))
