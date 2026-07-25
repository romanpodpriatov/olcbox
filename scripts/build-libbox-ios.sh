#!/usr/bin/env bash
# Libbox.xcframework — sing-box as a library, to run inside the NetworkExtension.
#
# iOS cannot exec a core binary the way Android and desktop do, so the tun has to
# be owned in-process. This is SFI's design (sing-box for iOS), and libbox is the
# same interface it uses.
#
# The sagernet gomobile fork and Go 1.23 are both load-bearing: upstream gomobile
# has no -libname, and x/tools v0.16.0 does not build on Go 1.24. That pairing was
# learned the hard way on the Android libbox work — do not "upgrade" either half
# without checking the other.
#
# gvisor is ON here because libbox needs a userspace network stack to own packet
# flow. It is also the main suspect if the extension breaches its memory limit
# (~15-50 MB); the fallback is a system-stack build, and that trade is decided on
# a device, not here.
set -euo pipefail

SINGBOX_VERSION="${SINGBOX_VERSION:?set SINGBOX_VERSION, e.g. 1.11.15}"
OUT="${1:?usage: build-libbox-ios.sh <output-dir>}"

mkdir -p "$OUT"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "== sing-box v${SINGBOX_VERSION} =="
git clone --depth 1 --branch "v${SINGBOX_VERSION}" \
  https://github.com/SagerNet/sing-box "$work/sing-box"

echo "== gomobile (sagernet fork) =="
go install github.com/sagernet/gomobile/cmd/gomobile@latest
go install github.com/sagernet/gomobile/cmd/gobind@latest
export PATH="$(go env GOPATH)/bin:$PATH"

cd "$work/sing-box"
gomobile init

echo "== bind =="
gomobile bind -v \
  -target=ios \
  -tags "with_gvisor,with_quic,with_utls,with_clash_api" \
  -ldflags "-s -w" \
  -o "$OUT/Libbox.xcframework" \
  ./experimental/libbox

echo "== slices produced =="
ls -1 "$OUT/Libbox.xcframework"
test -d "$OUT/Libbox.xcframework/ios-arm64" \
  || { echo "no device slice — the bind did not target iOS properly"; exit 1; }
