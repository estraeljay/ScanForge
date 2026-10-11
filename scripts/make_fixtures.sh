#!/usr/bin/env bash
# Generates SYNTHETIC test fixtures with the core CLI: capture -> reconstruction -> packages and exports.
# Usage: scripts/make_fixtures.sh <out-dir>
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:?usage: make_fixtures.sh <out-dir>}"
CLI="$ROOT/core/cli/build/install/scanforge-cli/bin/scanforge-cli"
if [[ ! -x "$CLI" ]]; then (cd "$ROOT/core" && gradle -q :cli:installDist); fi
rm -rf "$OUT"; mkdir -p "$OUT"
"$CLI" synth "$OUT/capture" --shape box >/dev/null 2>&1
ID=$("$CLI" reconstruct "$OUT/capture" "$OUT/store" --name "Synthetic Box" 2>/dev/null | tail -1)
"$CLI" package "$OUT/store" "$ID" "$OUT/box.sf3d" >/dev/null 2>&1
"$CLI" package "$OUT/store" "$ID" "$OUT/box_full.sf3d" --include-capture >/dev/null 2>&1
for f in ply obj stl glb; do "$CLI" export "$OUT/store" "$ID" "$f" "$OUT/export_yup_m" >/dev/null 2>&1; done
"$CLI" export "$OUT/store" "$ID" ply "$OUT/export_zup_mm" --axis z_up --unit mm >/dev/null 2>&1
"$CLI" export "$OUT/store" "$ID" stl "$OUT/export_zup_mm" --axis z_up --unit mm >/dev/null 2>&1
"$CLI" export "$OUT/store" "$ID" obj "$OUT/export_zup_cm" --axis z_up --unit cm >/dev/null 2>&1
"$CLI" calibrate "$OUT/store" "$ID" --measured 0.1 --reference 0.11 >/dev/null 2>&1
"$CLI" package "$OUT/store" "$ID" "$OUT/box_calibrated.sf3d" >/dev/null 2>&1
echo "$ID" > "$OUT/project_id"
echo "Fixtures (SYNTHETIC) written to $OUT"
