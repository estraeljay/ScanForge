#!/usr/bin/env bash
# Runs the Blender add-on test suite headlessly with the `bpy` module (Blender as a Python module).
# Usage: scripts/test_blender.sh [python-with-bpy]   (default: $BPY_PYTHON or python3)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PY="${1:-${BPY_PYTHON:-python3}}"
FIX="${SF_FIXTURES:-$ROOT/build/fixtures}"
[[ -f "$FIX/box.sf3d" ]] || "$ROOT/scripts/make_fixtures.sh" "$FIX"
SF_FIXTURES="$FIX" SF_CLI="$ROOT/core/cli/build/install/scanforge-cli/bin/scanforge-cli" \
  "$PY" "$ROOT/blender/tests/run_tests.py"
