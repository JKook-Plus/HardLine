#!/usr/bin/env python3
"""Minimal RTSP client: DESCRIBE (with Digest auth), SETUP over TCP interleaved, PLAY, then
summarise the RTP packets received for a few seconds.

  rtsp_probe.py rtsp://host:port/path [user] [password] [seconds=4]
"""
import hashlib
import re
import socket
import struct
import sys
import time
from urllib.parse import urlparse


class Rtsp:
    def __init__(self, url, user, password):
        u = urlparse(url)
        self.url, self.user, self.password = url, user, password
        self.sock = socket.create_connection((u.hostname, u.port or 554), timeout=25)
        self.cseq, self.auth, self.session, self.buf = 0, None, None, b""

    def _digest(self, method, uri):
        if not self.auth:
            return ""
        ha1 = hashlib.md5(f"{self.user}:{self.auth['realm']}:{self.password}".encode()).hexdigest()
        ha2 = hashlib.md5(f"{method}:{uri}".encode()).hexdigest()
        resp = hashlib.md5(f"{ha1}:{self.auth['nonce']}:{ha2}".encode()).hexdigest()
        return (f'Authorization: Digest username="{self.user}", realm="{self.auth["realm"]}", '
                f'nonce="{self.auth["nonce"]}", uri="{uri}", response="{resp}"\r\n')

    def _read_response(self):
        while b"\r\n\r\n" not in self.buf:
            self.buf += self.sock.recv(65536)
        head, self.buf = self.buf.split(b"\r\n\r\n", 1)
        head = head.decode(errors="replace")
        m = re.search(r"Content-Length:\s*(\d+)", head, re.I)
        body = b""
        if m:
            n = int(m.group(1))
            while len(self.buf) < n:
                self.buf += self.sock.recv(65536)
            body, self.buf = self.buf[:n], self.buf[n:]
        return head, body.decode(errors="replace")

    def request(self, method, uri=None, extra=""):
        uri = uri or self.url
        for _ in range(2):
            self.cseq += 1
            msg = f"{method} {uri} RTSP/1.0\r\nCSeq: {self.cseq}\r\nUser-Agent: rtsp_probe\r\n"
            if self.session:
                msg += f"Session: {self.session}\r\n"
            msg += self._digest(method, uri) + extra + "\r\n"
            self.sock.sendall(msg.encode())
            head, body = self._read_response()
            if " 401 " in head.split("\r\n")[0] and not self.auth:
                m = re.search(r'Digest realm="([^"]+)",\s*nonce="([^"]+)"', head)
                if m and self.user:
                    self.auth = {"realm": m.group(1), "nonce": m.group(2)}
                    continue
            return head, body
        return head, body


def main():
    url = sys.argv[1]
    user = sys.argv[2] if len(sys.argv) > 2 else ""
    password = sys.argv[3] if len(sys.argv) > 3 else ""
    seconds = float(sys.argv[4]) if len(sys.argv) > 4 else 4
    c = Rtsp(url, user, password)

    head, sdp = c.request("DESCRIBE", extra="Accept: application/sdp\r\n")
    print(head.split("\r\n")[0])
    print(sdp.strip())
    if " 200 " not in head.split("\r\n")[0]:
        return
    base = re.search(r"Content-Base:\s*(\S+)", head, re.I)
    base = base.group(1) if base else url + "/"
    controls = re.findall(r"^a=control:(\S+)", sdp, re.M)
    tracks = [x for x in controls if x != "*"]
    for i, track in enumerate(tracks):
        uri = track if track.startswith("rtsp://") else base.rstrip("/") + "/" + track
        head, _ = c.request("SETUP", uri, f"Transport: RTP/AVP/TCP;unicast;interleaved={2*i}-{2*i+1}\r\n")
        m = re.search(r"Session:\s*([^;\r\n]+)", head, re.I)
        if m:
            c.session = m.group(1).strip()
        t = re.search(r"Transport:\s*(.+)", head, re.I)
        print(f"SETUP {track}: {head.split(chr(13))[0]} | {t.group(1).strip() if t else ''}")
    head, _ = c.request("PLAY", base, "Range: npt=0.000-\r\n")
    print("PLAY:", head.split("\r\n")[0])

    stats, end = {}, time.time() + seconds
    c.sock.settimeout(1.0)
    while time.time() < end:
        try:
            c.buf += c.sock.recv(65536)
        except socket.timeout:
            continue
        while len(c.buf) >= 4:
            if c.buf[0] != 0x24:
                nxt = c.buf.find(b"$", 1)
                c.buf = c.buf[nxt:] if nxt > 0 else b""
                continue
            ch, size = c.buf[1], struct.unpack(">H", c.buf[2:4])[0]
            if len(c.buf) < 4 + size:
                break
            pkt, c.buf = c.buf[4:4 + size], c.buf[4 + size:]
            if ch % 2 or len(pkt) < 13:
                continue
            pt, marker, ts = pkt[1] & 0x7F, pkt[1] >> 7, struct.unpack(">I", pkt[4:8])[0]
            s = stats.setdefault((ch, pt), dict(pkts=0, bytes=0, frames=0, first=ts, last=ts, nal=set()))
            s["pkts"] += 1
            s["bytes"] += len(pkt) - 12
            s["frames"] += marker
            s["last"] = ts
            s["nal"].add(pkt[12] & 0x1F)
    for (ch, pt), s in sorted(stats.items()):
        print(f"channel {ch} payload-type {pt}: {s['pkts']} packets, {s['bytes']} bytes, "
              f"{s['frames']} marker bits, rtp-ts span {s['last'] - s['first']}, "
              f"first-byte low5 values {sorted(s['nal'])}")
    c.request("TEARDOWN", base)


if __name__ == "__main__":
    main()
