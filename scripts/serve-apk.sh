#!/usr/bin/env bash
# Offers dist/ (an APK and a download page) to phones on the local network, from an nginx
# container. Handy for installing a build on a phone that is not connected by cable.
#   serve-apk.sh start [hours=24]   serve on port 8765, stopping by itself after the given time
#   serve-apk.sh stop
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NAME=hardline-download
PORT="${PORT:-8765}"

addresses() { ip -4 -o addr show scope global | awk '{print $2, $4}' | grep -vE '^(docker|br-|veth)' | awk '{sub(/\/.*/, "", $2); print $2}'; }

case "${1:-}" in
  start)
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    publish=()
    for a in $(addresses); do publish+=(-p "$a:$PORT:80"); done
    docker run -d --rm --name "$NAME" "${publish[@]}" -v "$ROOT/dist:/usr/share/nginx/html:ro" \
      -v "$ROOT/scripts/serve-apk.nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
      nginx:alpine sh -c "timeout $(( ${2:-24} * 3600 )) nginx -g 'daemon off;'" >/dev/null
    for a in $(addresses); do echo "http://$a:$PORT/"; done ;;
  stop) docker rm -f "$NAME" >/dev/null 2>&1 || true; echo stopped ;;
  *) echo "usage: $0 start [hours]|stop" >&2; exit 2 ;;
esac
