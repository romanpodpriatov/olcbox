#!/usr/bin/env bash
# Cores.xcframework — sing-box AND Xray, bound together in one framework.
#
# They have to share a framework, not merely a binary. Each `gomobile bind`
# emits its own copy of the cgo bootstrap and the gomobile seq layer, so linking
# two independently-bound frameworks into one extension fails at link time:
#
#   duplicate symbol '_crosscall2'          duplicate symbol '_IncGoRef'
#   duplicate symbol '__cgo_topofstack'     duplicate symbol '_DestroyRef'
#   duplicate symbol '_x_cgo_bindm'         duplicate symbol '_proxy_error_Error'
#   ... 49 duplicate symbols
#
# There is no linker flag for this, and hiding one copy would be worse than the
# error: two Go runtimes in one process means two schedulers and two sets of
# signal handlers. One bind over both packages gives one runtime and one copy of
# the glue, which is the only correct shape.
#
# The class names do not change — prefixes come from the Go package names, so
# `LibboxBoxService` and `LibXrayInvoke` are still exactly that. Only the module
# to import changes, from two names to `Cores`.
#
# The build tags are load-bearing. Without with_gvisor there is no userspace
# stack for the tun, without with_quic no Hysteria2, without with_utls no
# Reality fingerprinting.
set -euo pipefail

# Defaults are the pinned pair this was verified against, so a laptop run and a
# CI run produce the same framework. The workflow passes the same values because
# it also needs them for the release tag; fetch-cores-ios.sh names that tag.
SINGBOX_VERSION="${SINGBOX_VERSION:-1.13.14}"
LIBXRAY_VERSION="${LIBXRAY_VERSION:-v1.260711.0}"
# Pinned rather than @latest: gobind generates code against the seq package of
# its own version, so the tool and the module dependency below must be the same
# version or the generated bindings compile against the wrong API.
GOMOBILE_VERSION="${GOMOBILE_VERSION:-v0.1.13}"
OUT="${1:?usage: build-cores-ios.sh <output-dir>}"

mkdir -p "$OUT"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

cd "$work"
mkdir cores && cd cores

echo "== wrapper module: sing-box v${SINGBOX_VERSION} + libXray ${LIBXRAY_VERSION} =="
# 1.26.3 is libXray's own floor, stated here rather than left to `go get` to
# raise, so the toolchain requirement is visible before anything downloads.
cat > go.mod <<EOF
module github.com/romanpodpriatov/olcbox-cores

go 1.26.3
EOF

# Imported blank: nothing here calls them, but they must be in the build list
# for gomobile to resolve the package paths passed to bind.
cat > cores.go <<'EOF'
// Package cores exists only to pull both engines into one module so they can
// be bound into a single framework. See build-cores-ios.sh for why.
package cores

import (
	_ "github.com/sagernet/sing-box/experimental/libbox"
	_ "github.com/xtls/libxray"
)
EOF

export GOFLAGS=-mod=mod
go get "github.com/sagernet/sing-box@v${SINGBOX_VERSION}"
go get "github.com/xtls/libxray@${LIBXRAY_VERSION}"
# gobind looks for the bind package through the module being built, so it has
# to be a dependency rather than merely installed.
go get "github.com/sagernet/gomobile/bind@${GOMOBILE_VERSION}"
# Never `go mod tidy` here: nothing imports bind, so tidy would drop it.

# Merging two dependency graphs is the one thing that can go wrong for reasons
# neither project would ever see alone — the cores share sagernet/sing and the
# whole of golang.org/x, and minimal version selection hands both of them
# whichever copy is newer. Print what was chosen: when a build breaks after a
# version bump, this is the line that says why.
echo "== resolved =="
go list -m github.com/sagernet/sing-box github.com/xtls/libxray github.com/xtls/xray-core \
  github.com/sagernet/sing golang.org/x/net golang.org/x/crypto

# Compile both cores against that merged graph before binding them. The bind is
# a 20+ minute step on a runner billed at 10x, and it starts by doing exactly
# this — so a conflict caught here is caught twenty minutes earlier, and with a
# plain Go error instead of gomobile's output.
echo "== pre-flight: do both cores still compile together? =="
go build -tags "with_gvisor,with_quic,with_utls,with_clash_api" ./...

echo "== gomobile ${GOMOBILE_VERSION} (sagernet fork) =="
# If this step fails to build, it is the fork's x/tools pin against a newer Go
# toolchain — the pairing hazard the separate libbox script warned about. The
# fix is a Go version this fork builds on, not a change to anything below.
go install "github.com/sagernet/gomobile/cmd/gomobile@${GOMOBILE_VERSION}"
go install "github.com/sagernet/gomobile/cmd/gobind@${GOMOBILE_VERSION}"
export PATH="$(go env GOPATH)/bin:$PATH"
gomobile init

echo "== bind, both packages in one framework =="
gomobile bind -v \
  -target=ios \
  -tags "with_gvisor,with_quic,with_utls,with_clash_api" \
  -ldflags "-s -w" \
  -o "$OUT/Cores.xcframework" \
  github.com/sagernet/sing-box/experimental/libbox \
  github.com/xtls/libxray

echo "== slices produced =="
ls -1 "$OUT/Cores.xcframework"
test -d "$OUT/Cores.xcframework/ios-arm64" \
  || { echo "no device slice — the bind did not target iOS properly"; exit 1; }

# The whole point of the exercise: both APIs, one framework. A bind that
# silently dropped one package would otherwise only fail much later, in Xcode.
headers="$OUT/Cores.xcframework/ios-arm64/Cores.framework/Headers"
test -f "$headers/Libbox.objc.h" \
  || { echo "no Libbox header — sing-box was not bound"; exit 1; }
test -f "$headers/LibXray.objc.h" \
  || { echo "no LibXray header — Xray was not bound"; exit 1; }
echo "== both APIs present =="

# The generated header is the authority on what the Swift bridge must implement,
# and guessing at it has cost several rounds before: Swift renames some of these
# on import, so the Go source is not the last word. Print the two protocols the
# extension conforms to, so a conformance error can be fixed by reading rather
# than by another build.
echo
echo "== protocols the extension must match =="
sed -n '/@protocol LibboxPlatformInterface /,/@end/p;/@protocol LibboxCommandServerHandler /,/@end/p' \
  "$headers/Libbox.objc.h"
