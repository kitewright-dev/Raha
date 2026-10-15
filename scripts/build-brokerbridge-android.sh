#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="$ROOT/native/brokerbridge"
OUT="$ROOT/app/libs/brokerbridge.aar"

: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to Android NDK 29.0.14206865}"

mkdir -p "$ROOT/app/libs"
cd "$MODULE"

GOWORK=off go mod download
gomobile init
gomobile bind \
  -target=android \
  -androidapi=26 \
  -javapkg=ir.raha.nativebridge \
  -o "$OUT" \
  .

echo "Built $OUT"
