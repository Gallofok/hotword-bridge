#!/usr/bin/env bash
set -euo pipefail

# openWakeWord's shared feature models (mel spectrogram + speech embedding).
# Downloaded at build time and checked against pinned SHA256 values; they are
# not committed. Their licence is CC BY-NC-SA 4.0 (non-commercial), see
# https://github.com/dscripka/openWakeWord#license
# The wake-word classifier itself (hey_chatgpt.onnx) is committed in the repo.
RELEASE="https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
DEST="app/src/main/assets/oww"

mkdir -p "$DEST"
fetch() {
  local name="$1" sha="$2"
  if ! echo "$sha  $DEST/$name" | sha256sum -c - >/dev/null 2>&1; then
    curl -fL --retry 3 --retry-all-errors "$RELEASE/$name" -o "$DEST/$name"
  fi
  echo "$sha  $DEST/$name" | sha256sum -c -
}
fetch melspectrogram.onnx ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f
fetch embedding_model.onnx 70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f
test -s "$DEST/hey_chatgpt.onnx" || { echo "missing $DEST/hey_chatgpt.onnx" >&2; exit 1; }
echo "openWakeWord models ready in $DEST"
