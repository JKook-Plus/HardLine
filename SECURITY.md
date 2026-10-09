# Security

## Reporting a problem

Please do not open a public issue for a security problem. Use
[Report a vulnerability](https://github.com/JKook-Plus/HardLine/security/advisories/new) on the
repository's Security tab, which opens a private report that only the maintainers can read.

Say what the problem is, how to reproduce it and which version you tested.

Only the latest release gets fixes.

## What to know before you expose the app to a network

HardLine runs servers on your phone when you ask it to. They are meant for a network you trust.

- The web server and the RTSP server ask for a user name and password. HardLine generates a random
  password on first start. You can change it, and you can remove it, which lets anyone on the
  network watch.
- Without HTTPS, the web server uses HTTP Basic authentication, so the password and the video cross
  the network unencrypted. Turn on HTTPS in Settings, Web server and encoders if that matters.
- With HTTPS on and no certificate of your own, HTTPS uses a self-signed certificate that HardLine
  generates. Browsers will warn about it, and it does not prove to a viewer that they reached your
  phone.
- UPnP port mapping asks your router to open the server's ports to the internet. It is off unless
  you turn it on. Think before you do.
- RTMP without TLS and SRT without a passphrase send the stream unencrypted.
