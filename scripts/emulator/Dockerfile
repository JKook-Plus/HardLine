# Minimal runtime for the headless Android emulator. The SDK and AVD are bind-mounted
# from the host; this image only supplies the handful of system libraries qemu links.
FROM debian:trixie-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends libx11-6 libx11-xcb1 libxcb1 libbsd0 libmd0 ca-certificates \
 && rm -rf /var/lib/apt/lists/*
