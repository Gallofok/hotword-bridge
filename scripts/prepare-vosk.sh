#!/usr/bin/env bash
set -euo pipefail

# Official Vosk English model, pinned by the SHA256 of the 0.15 release.
# Downloaded in CI only; not checked into the Git repository.
URL="https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
SHA256="30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"

mkdir -p app/src/main/assets
curl -fL --retry 4 --retry-all-errors --connect-timeout 30 \
  "$URL" -o app/src/main/assets/vosk-model.zip
echo "$SHA256  app/src/main/assets/vosk-model.zip" | sha256sum -c -
unzip -tq app/src/main/assets/vosk-model.zip >/dev/null
unzip -Z1 app/src/main/assets/vosk-model.zip | grep -q '^vosk-model-small-en-us-0.15/am/final.mdl$'
unzip -Z1 app/src/main/assets/vosk-model.zip | grep -q '^vosk-model-small-en-us-0.15/conf/model.conf$'
echo "Vosk 0.15 model archive verified and ready to package."
