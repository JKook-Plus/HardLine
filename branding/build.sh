#!/usr/bin/env bash
# Rebuilds the icon and logo from branding/build.py: SVG and PNG files in branding/, the launcher
# and notification drawables in app/src/main/res, and the store images in fastlane/.
#
# Needs Docker. The wordmark font (Barlow Condensed, SIL Open Font License) is fetched once into
# out/fonts; the logos contain outlines, not the font.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FONTS="$ROOT/out/fonts"
IMAGE=hardline-brand:latest

for spec in barlow-condensed:600; do
  dir="$FONTS/${spec%%:*}-${spec##*:}"
  [[ -s "$dir/font.ttf" ]] && continue
  mkdir -p "$dir"
  curl -fsSL -o "$dir/font.ttf" "https://cdn.jsdelivr.net/fontsource/fonts/${spec%%:*}@latest/latin-${spec##*:}-normal.ttf"
done

docker image inspect "$IMAGE" >/dev/null 2>&1 || docker build -q -t "$IMAGE" - <<'EOF'
FROM python:3.12-alpine
RUN apk add --no-cache cairo && pip install --no-cache-dir --root-user-action=ignore fonttools uharfbuzz cairosvg
EOF

docker run --rm -u "$(id -u):$(id -g)" -v "$ROOT:/repo" -v "$FONTS:/fonts:ro" "$IMAGE" python -I /repo/branding/build.py /fonts /repo
