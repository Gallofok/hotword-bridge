#!/usr/bin/env bash
set -euo pipefail

# Third-party binaries are downloaded by GitHub Actions, not committed to Git.
# Pin the sherpa-onnx runtime to a release with a documented SHA256.
AAR_VERSION="2.0.0"
AAR_SHA256="f0f8716982d27cb4ce695e3934018b1342d9d3d6fdb6ef1f1658fe8bad50b4fc"
MODEL="sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"

mkdir -p app/libs app/src/main/assets/kws
curl -fL --retry 3 --retry-all-errors \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${AAR_VERSION}/sherpa-onnx-${AAR_VERSION}.aar" \
  -o app/libs/sherpa-onnx.aar
echo "${AAR_SHA256}  app/libs/sherpa-onnx.aar" | sha256sum -c -

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl -fL --retry 3 --retry-all-errors \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/${MODEL}.tar.bz2" \
  -o "$tmp/model.tar.bz2"
tar -xjf "$tmp/model.tar.bz2" -C "$tmp"
src="$tmp/$MODEL"
dst="app/src/main/assets/kws"
for f in \
  encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx \
  decoder-epoch-12-avg-2-chunk-16-left-64.onnx \
  joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx \
  tokens.txt bpe.model; do
  test -s "$src/$f" || { echo "Missing model file: $f" >&2; exit 1; }
  cp "$src/$f" "$dst/$f"
done

# Use sherpa-onnx's own real-world audio recordings for reproducible CI tests.
# They are NOT packaged into the Android APK.
mkdir -p test-fixtures/kws
for f in 0.wav 1.wav; do
  test -s "$src/test_wavs/$f" || { echo "Missing official test recording $f" >&2; exit 1; }
  cp "$src/test_wavs/$f" "test-fixtures/kws/$f"
done
cp "$src/test_wavs/test_keywords.txt" test-fixtures/kws/official_keywords.txt

python3 scripts/generate-keywords.py "$dst/bpe.model" "$dst/tokens.txt" "$dst/keywords.txt"
echo "Prepared sherpa-onnx Android AAR and offline keyword-spotting assets."
