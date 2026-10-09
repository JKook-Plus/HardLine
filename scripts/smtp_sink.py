#!/usr/bin/env python3
"""A throw-away SMTP server for tests: accepts any login and saves each message to a file.

  smtp_sink.py [port=2525] [output-dir=/tmp/smtp-sink]
"""
import base64
import os
import socketserver
import sys
import time

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 2525
OUT = sys.argv[2] if len(sys.argv) > 2 else "/tmp/smtp-sink"
os.makedirs(OUT, exist_ok=True)


class Handler(socketserver.StreamRequestHandler):
    def say(self, line):
        self.wfile.write((line + "\r\n").encode())

    def handle(self):
        self.say("220 sink ESMTP")
        log = []
        while True:
            line = self.rfile.readline()
            if not line:
                break
            text = line.decode(errors="replace").strip()
            verb = text.split(" ")[0].upper()
            log.append(text if verb != "AUTH" else "AUTH ...")
            if verb == "EHLO":
                self.say("250-sink"); self.say("250 AUTH PLAIN LOGIN")
            elif verb == "AUTH":
                parts = text.split(" ")
                if parts[1].upper() == "LOGIN":
                    if len(parts) < 3:
                        self.say("334 VXNlcm5hbWU6"); user = self.rfile.readline()
                    self.say("334 UGFzc3dvcmQ6"); self.rfile.readline()
                elif len(parts) < 3:
                    self.say("334 "); self.rfile.readline()
                self.say("235 ok")
            elif verb == "DATA":
                self.say("354 go ahead")
                body = b""
                while True:
                    chunk = self.rfile.readline()
                    if chunk in (b".\r\n", b".\n", b""):
                        break
                    body += chunk
                name = os.path.join(OUT, "mail-%d.eml" % int(time.time() * 1000))
                open(name, "wb").write(body)
                print("message", len(body), "bytes ->", name, "|", " / ".join(log), flush=True)
                self.say("250 queued")
            elif verb == "QUIT":
                self.say("221 bye"); break
            else:
                self.say("250 ok")


socketserver.ThreadingTCPServer.allow_reuse_address = True
socketserver.ThreadingTCPServer(("127.0.0.1", PORT), Handler).serve_forever()
