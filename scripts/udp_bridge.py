#!/usr/bin/env python3
"""Host half of the UDP bridge into the emulator (the guest half is scripts/vcam/udprelay.c).

  udp_bridge.py <guest-facing-port> <client-facing-port>

A client that sends to the client-facing port reaches the app's UDP port in the guest.
"""
import select
import socket
import sys

guest_port, client_port = int(sys.argv[1]), int(sys.argv[2])
g = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); g.bind(("127.0.0.1", guest_port))
c = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); c.bind(("127.0.0.1", client_port))
guest = client = None
while True:
    for s in select.select([g, c], [], [])[0]:
        data, addr = s.recvfrom(4096)
        if s is g:
            guest = addr
            if len(data) >= 16 and client:
                c.sendto(data, client)
        else:
            client = addr
            if guest:
                g.sendto(data, guest)
