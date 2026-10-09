#!/usr/bin/env python3
"""Minimal RTMP ingest: accepts one publisher, answers the handshake and the connect /
createStream / publish commands, then logs what arrives (metadata, codec headers, tag counts).

  rtmp_sink.py [port=1935] [seconds=12]
"""
import os
import socket
import struct
import sys
import time


def amf_decode(buf, pos=0):
    t = buf[pos]
    pos += 1
    if t == 0x00:
        return struct.unpack_from(">d", buf, pos)[0], pos + 8
    if t == 0x01:
        return bool(buf[pos]), pos + 1
    if t == 0x02:
        n = struct.unpack_from(">H", buf, pos)[0]
        return buf[pos + 2:pos + 2 + n].decode(errors="replace"), pos + 2 + n
    if t in (0x03, 0x08):
        if t == 0x08:
            pos += 4
        obj = {}
        while True:
            n = struct.unpack_from(">H", buf, pos)[0]
            pos += 2
            if n == 0 and buf[pos] == 0x09:
                return obj, pos + 1
            key = buf[pos:pos + n].decode(errors="replace")
            obj[key], pos = amf_decode(buf, pos + n)
    if t in (0x05, 0x06):
        return None, pos
    raise ValueError(f"unsupported AMF0 type {t:#x}")


def amf_all(buf):
    out, pos = [], 0
    while pos < len(buf):
        try:
            v, pos = amf_decode(buf, pos)
        except (ValueError, IndexError, struct.error):
            break
        out.append(v)
    return out


def amf(*values):
    out = b""
    for v in values:
        if v is None:
            out += b"\x05"
        elif isinstance(v, bool):
            out += b"\x01" + bytes([v])
        elif isinstance(v, (int, float)):
            out += b"\x00" + struct.pack(">d", v)
        elif isinstance(v, str):
            out += b"\x02" + struct.pack(">H", len(v)) + v.encode()
        elif isinstance(v, dict):
            out += b"\x03"
            for k, x in v.items():
                out += struct.pack(">H", len(k)) + k.encode() + amf(x)
            out += b"\x00\x00\x09"
    return out


class Conn:
    def __init__(self, sock):
        self.s, self.buf, self.chunk_in = sock, b"", 128
        self.state = {}  # csid -> [timestamp, length, type, stream, partial]

    def read(self, n):
        while len(self.buf) < n:
            d = self.s.recv(65536)
            if not d:
                raise EOFError
            self.buf += d
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def send(self, mtype, payload, stream=0, csid=3):
        head = bytes([csid]) + b"\0\0\0" + len(payload).to_bytes(3, "big") + bytes([mtype]) + struct.pack("<I", stream)
        out = head + payload[:128]
        for i in range(128, len(payload), 128):
            out += bytes([0xC0 | csid]) + payload[i:i + 128]
        self.s.sendall(out)

    def message(self):
        while True:
            b0 = self.read(1)[0]
            fmt, csid = b0 >> 6, b0 & 0x3F
            if csid == 0:
                csid = 64 + self.read(1)[0]
            elif csid == 1:
                csid = 64 + int.from_bytes(self.read(2), "little")
            st = self.state.setdefault(csid, [0, 0, 0, 0, b""])
            if fmt <= 2:
                ts = int.from_bytes(self.read(3), "big")
                if fmt <= 1:
                    st[1] = int.from_bytes(self.read(3), "big")
                    st[2] = self.read(1)[0]
                if fmt == 0:
                    st[3] = struct.unpack("<I", self.read(4))[0]
                if ts == 0xFFFFFF:
                    ts = struct.unpack(">I", self.read(4))[0]
                st[0] = ts if fmt == 0 else st[0] + ts
            st[4] += self.read(min(self.chunk_in, st[1] - len(st[4])))
            if len(st[4]) >= st[1]:
                payload, st[4] = st[4], b""
                if st[2] == 1:
                    self.chunk_in = struct.unpack(">I", payload)[0] & 0x7FFFFFFF
                    continue
                return st[2], st[3], st[0], payload


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 1935
    seconds = float(sys.argv[2]) if len(sys.argv) > 2 else 12
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(1)
    print(f"rtmp sink listening on 127.0.0.1:{port}", flush=True)
    sock, _ = srv.accept()
    c = Conn(sock)

    c0c1 = c.read(1537)
    print(f"handshake: version {c0c1[0]}, C1 time {int.from_bytes(c0c1[1:5], 'big')}, zero field {c0c1[5:9].hex()}")
    sock.sendall(b"\x03" + b"\0" * 8 + os.urandom(1528) + c0c1[1:])
    c.read(1536)

    counts, first, end = {}, {}, None
    while end is None or time.time() < end:
        try:
            mtype, stream, ts, payload = c.message()
        except EOFError:
            print("publisher closed the connection")
            break
        if mtype == 20:
            cmd = amf_all(payload)
            name, tx = cmd[0], cmd[1]
            print(f"command {name!r} tx={tx:g} args={cmd[2:]}")
            if name == "connect":
                c.send(5, struct.pack(">I", 5000000), csid=2)
                c.send(6, struct.pack(">IB", 5000000, 2), csid=2)
                c.send(20, amf("_result", tx, {"fmsVer": "FMS/3,0,1,123", "capabilities": 31.0},
                               {"level": "status", "code": "NetConnection.Connect.Success",
                                "description": "Connection succeeded.", "objectEncoding": 0.0}))
            elif name == "createStream":
                c.send(20, amf("_result", tx, None, 1.0))
            elif name == "publish":
                c.send(20, amf("onStatus", 0.0, None, {"level": "status", "code": "NetStream.Publish.Start",
                                                        "description": "Start publishing"}), stream=1, csid=5)
                end = time.time() + seconds
            elif name in ("releaseStream", "FCPublish"):
                c.send(20, amf("_result", tx, None, None))
        elif mtype == 18:
            print(f"data message: {amf_all(payload)}")
        elif mtype in (8, 9):
            kind = "audio" if mtype == 8 else "video"
            if mtype == 9:
                enhanced = payload[0] & 0x80
                key = (f"video enhanced fourcc={payload[1:5].decode(errors='replace')} pkt={payload[0] & 15}" if enhanced
                       else f"video codec={payload[0] & 15} {'seq-header' if payload[1] == 0 else 'nalu'}")
                counts["keyframes"] = counts.get("keyframes", 0) + (((payload[0] >> 4) & 7) == 1 and payload[1] != 0)
            else:
                key = f"audio fmt={payload[0] >> 4} {'seq-header' if payload[1] == 0 else 'raw'}"
            counts[key] = counts.get(key, 0) + 1
            first.setdefault(key, (ts, payload[:24].hex()))
            counts[kind + " last ts"] = ts
        else:
            print(f"message type {mtype} ({len(payload)} bytes): {payload[:16].hex()}")
    print("counts:", counts)
    for k, (ts, head) in first.items():
        print(f"first {k}: ts={ts} bytes={head}")


if __name__ == "__main__":
    main()
