#!/usr/bin/env bash
# LibXray.xcframework — Xray as a library.
#
# It exists for exactly one transport: sing-box cannot speak xhttp, so xhttp
# locations run a libXray instance as a local SOCKS behind libbox. Everything
# else on iOS is a native libbox outbound, and a second core in a memory-capped
# extension is a cost worth paying only for that gap.
#
# Same gomobile pairing as the libbox build, for the same reasons.
set -euo pipefail

XRAY_VERSION="${XRAY_VERSION:?set XRAY_VERSION, e.g. 25.3.6}"
OUT="${1:?usage: build-libxray-ios.sh <output-dir>}"

mkdir -p "$OUT"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "== libXray, pinned to xray-core v${XRAY_VERSION} =="
git clone --depth 1 https://github.com/XTLS/libXray "$work/libXray"
cd "$work/libXray"

# libXray tracks its own release cadence; pin the core so the framework matches
# the Xray version the shared XrayConfig builder is CI-validated against.
go mod edit -require="github.com/xtls/xray-core@v${XRAY_VERSION}"
go mod tidy

echo "== gomobile (sagernet fork) =="
go install github.com/sagernet/gomobile/cmd/gomobile@latest
go install github.com/sagernet/gomobile/cmd/gobind@latest
export PATH="$(go env GOPATH)/bin:$PATH"
gomobile init

echo "== bind =="
gomobile bind -v -target=ios -ldflags "-s -w" \
  -o "$OUT/LibXray.xcframework" .

echo "== slices produced =="
ls -1 "$OUT/LibXray.xcframework"
test -d "$OUT/LibXray.xcframework/ios-arm64" \
  || { echo "no device slice — the bind did not target iOS properly"; exit 1; }
