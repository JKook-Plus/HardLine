#!/usr/bin/env python3
"""A throw-away FTP server for tests: one user, passive mode, uploads saved under a directory.

  ftp_sink.py [port=2121] [root=/tmp/ftp-sink] [user=cam] [password=secret]
"""
import os
import socket
import socketserver
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 2121
ROOT = sys.argv[2] if len(sys.argv) > 2 else "/tmp/ftp-sink"
USER = sys.argv[3] if len(sys.argv) > 3 else "cam"
PASSWORD = sys.argv[4] if len(sys.argv) > 4 else "secret"
os.makedirs(ROOT, exist_ok=True)


class Handler(socketserver.StreamRequestHandler):
    def say(self, line):
        self.wfile.write((line + "\r\n").encode())

    def path(self, name):
        relative = name if name.startswith("/") else self.cwd + "/" + name
        full = os.path.normpath(os.path.join(ROOT, relative.lstrip("/")))
        return full if full.startswith(ROOT) else ROOT

    def handle(self):
        self.cwd, user, authed, passive = "/", None, False, None
        self.say("220 sink")
        while True:
            line = self.rfile.readline()
            if not line:
                break
            text = line.decode(errors="replace").strip()
            verb, _, arg = text.partition(" ")
            verb = verb.upper()
            if verb == "USER":
                user = arg; self.say("331 password please")
            elif verb == "PASS":
                authed = user == USER and arg == PASSWORD
                self.say("230 welcome" if authed else "530 wrong user name or password")
            elif verb == "QUIT":
                self.say("221 bye"); break
            elif not authed:
                self.say("530 sign in first")
            elif verb in ("SYST",):
                self.say("215 UNIX Type: L8")
            elif verb in ("TYPE", "MODE", "STRU", "NOOP"):
                self.say("200 ok")
            elif verb == "PWD":
                self.say('257 "%s"' % self.cwd)
            elif verb == "CWD":
                target = self.path(arg)
                if os.path.isdir(target):
                    self.cwd = "/" if target == ROOT else "/" + os.path.relpath(target, ROOT)
                    self.say("250 ok")
                else:
                    self.say("550 no such folder")
            elif verb == "MKD":
                os.makedirs(self.path(arg), exist_ok=True); self.say('257 "%s" created' % arg)
            elif verb in ("PASV", "EPSV"):
                passive = socket.socket(); passive.bind(("127.0.0.1", 0)); passive.listen(1)
                port = passive.getsockname()[1]
                self.say("229 Entering Extended Passive Mode (|||%d|)" % port if verb == "EPSV"
                         else "227 Entering Passive Mode (127,0,0,1,%d,%d)" % (port >> 8, port & 255))
            elif verb == "STOR" and passive:
                self.say("150 send it")
                conn, _ = passive.accept()
                target = self.path(arg)
                with open(target, "wb") as f:
                    while True:
                        chunk = conn.recv(65536)
                        if not chunk:
                            break
                        f.write(chunk)
                conn.close(); passive.close(); passive = None
                print("stored", target, os.path.getsize(target), "bytes", flush=True)
                self.say("226 stored")
            else:
                self.say("502 not supported")


socketserver.ThreadingTCPServer.allow_reuse_address = True
socketserver.ThreadingTCPServer(("127.0.0.1", PORT), Handler).serve_forever()
