#!/usr/bin/env bash
# Builds the installable extension zip into dist/. Uses `blender --command extension build` when a Blender
# binary is available, otherwise an equivalent zip of the extension directory.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/blender/scanforge3d"
VERSION=$(grep '^version' "$SRC/blender_manifest.toml" | head -1 | sed -E 's/.*"(.*)".*/\1/')
mkdir -p "$ROOT/dist"
OUT="$ROOT/dist/scanforge3d-$VERSION.zip"
if command -v blender >/dev/null 2>&1; then
  blender --command extension build --source-dir "$SRC" --output-filepath "$OUT"
else
  (cd "$SRC" && rm -f "$OUT" && zip -qr "$OUT" . -x '__pycache__/*' '*/__pycache__/*' '*.pyc')
fi
echo "$OUT"
