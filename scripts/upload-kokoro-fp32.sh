#!/usr/bin/env bash
# Publishes the optional fp32 Kokoro model to Supabase Storage.
#
# The phone otherwise unpacks a 350 MB .tar.bz2 from GitHub, which costs
# minutes of bzip2 on-device. A plain model.onnx is one resumable download.
#
# Usage:
#   SUPABASE_URL=https://<project>.supabase.co \
#   SUPABASE_SERVICE_ROLE_KEY=... ./scripts/upload-kokoro-fp32.sh
#
# The service-role key is needed because the bucket is written once, from a
# developer machine. Never ship it in the app.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/.cache/kokoro-fp32"
MODEL="$WORK/model.onnx"
ARCHIVE="$WORK/kokoro-multi-lang-v1_0.tar.bz2"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2"

: "${SUPABASE_URL:?set SUPABASE_URL (https://<project>.supabase.co)}"
: "${SUPABASE_SERVICE_ROLE_KEY:?set SUPABASE_SERVICE_ROLE_KEY}"

mkdir -p "$WORK"

if [[ ! -f "$MODEL" ]]; then
  if [[ ! -f "$ARCHIVE" ]]; then
    echo "Downloading the Kokoro archive (~350 MB)…"
    curl -fL --progress-bar "$URL" -o "$ARCHIVE"
  fi
  echo "Extracting model.onnx…"
  tar -xjf "$ARCHIVE" -C "$WORK" --strip-components=1 kokoro-multi-lang-v1_0/model.onnx
fi

SIZE=$(wc -c <"$MODEL" | tr -d ' ')
echo "model.onnx is $SIZE bytes"
if [[ "$SIZE" -lt 300000000 ]]; then
  echo "That file looks too small; refusing to upload." >&2
  exit 1
fi

OBJECT="voice-models/kokoro-multi-lang-v1_0/model.onnx"
echo "Uploading to $SUPABASE_URL/storage/v1/object/$OBJECT …"
curl -f --progress-bar -X POST \
  "$SUPABASE_URL/storage/v1/object/$OBJECT" \
  -H "Authorization: Bearer $SUPABASE_SERVICE_ROLE_KEY" \
  -H "Content-Type: application/octet-stream" \
  -H "x-upsert: true" \
  --data-binary "@$MODEL"

PUBLIC="$SUPABASE_URL/storage/v1/object/public/$OBJECT"
echo
echo "Public URL: $PUBLIC"
curl -fsSI "$PUBLIC" | head -n 5 || {
  echo "Upload finished but the public URL is not readable. Check the bucket is public." >&2
  exit 1
}
