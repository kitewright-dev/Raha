#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE="$ROOT/native/rahacore"
OUT="$ROOT/app/libs/rahacore.aar"

: "${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to an Android NDK}"

mkdir -p "$ROOT/app/libs"
cd "$MODULE"

export GOWORK=off
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
go mod tidy
go vet ./...
# gomobile needs x/mobile in the module graph; a tool directive keeps it there.
go get -tool golang.org/x/mobile/cmd/gobind@latest
"$(go env GOPATH)/bin/gomobile" init
"$(go env GOPATH)/bin/gomobile" bind \
  -target=android/arm64,android/arm \
  -androidapi=26 \
  -javapkg=ir.raha.nativebridge \
  -ldflags="-s -w -buildid= -checklinkname=0" \
  -trimpath \
  -o "$OUT" \
  .

echo "Built $OUT"
