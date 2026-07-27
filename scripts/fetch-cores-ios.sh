#!/usr/bin/env bash
# Puts Cores.xcframework where the Xcode extension target expects it.
#
# One framework holding sing-box, Xray and olcRTC — they cannot be linked apart,
# see build-cores-ios.sh. Building it needs Go, gomobile and a macOS machine, so
# this looks for one already built, in the order that costs least:
#
#   1. the destination itself   — nothing to do
#   2. the local cache          — a copy; survives `clean`
#   3. the release tag          — a download
#
# The cache exists because the destination is inside `sharedUI/build`, which is
# Gradle's to delete and which Gradle does delete: it is the declared output of
# fetchCoresIosXcframework, and a stale-output sweep once removed a framework
# that had taken twenty minutes to build and could not be re-downloaded, because
# the release for that version pair did not exist yet. A locally built framework
# now lands in the cache too, so losing the build directory costs a copy.
#
# The tag names the versions it was built from, so a version bump is a new tag
# rather than a silent swap — and a new cache entry rather than a stale hit.
set -euo pipefail

DEST="${1:?usage: fetch-cores-ios.sh <destination-dir>}"
TAG="${CORES_RELEASE_TAG:-ios-cores-sb1.13.14-lxv1.260711.0-rtc42ae4e0c6a1a}"
ASSET="Cores-ios.zip"
URL="https://github.com/romanpodpriatov/olcbox/releases/download/${TAG}/${ASSET}"
# Same default as build-cores-ios.sh writes to. Keyed by tag: two version pairs
# never share an entry.
CACHE="${CORES_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/olcbox/cores}/${TAG}"

if [ -d "${DEST}/Cores.xcframework/ios-arm64" ]; then
    echo "Cores.xcframework already present in ${DEST}"
    exit 0
fi

mkdir -p "${DEST}"

if [ -d "${CACHE}/Cores.xcframework/ios-arm64" ]; then
    echo "== restoring Cores.xcframework from ${CACHE} =="
    rm -rf "${DEST}/Cores.xcframework"
    cp -R "${CACHE}/Cores.xcframework" "${DEST}/Cores.xcframework"
    echo "== ready: ${DEST}/Cores.xcframework =="
    exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

echo "== fetching ${ASSET} from ${TAG} =="
# A missing release is the expected failure right after a version bump, and
# `curl -f` alone reports it as a bare exit 22 from somewhere inside an Xcode
# build phase. Say which tag is missing and what produces it.
curl -fsSL -o "${work}/${ASSET}" "${URL}" || {
    echo "no ${ASSET} published at tag ${TAG}, and nothing cached in ${CACHE}."
    echo "Build it yourself (needs Go and Xcode, ~20 min):"
    echo "  bash scripts/build-cores-ios.sh \"${DEST}\""
    echo "or run the 'iOS Frameworks' workflow to build and publish it once."
    exit 1
}
unzip -q -o "${work}/${ASSET}" -d "${DEST}"

test -d "${DEST}/Cores.xcframework/ios-arm64" \
    || { echo "downloaded archive has no ios-arm64 slice"; exit 1; }

# Cache what was just downloaded, so the next sweep of the build directory is a
# copy rather than a download.
mkdir -p "${CACHE}"
rm -rf "${CACHE}/Cores.xcframework"
cp -R "${DEST}/Cores.xcframework" "${CACHE}/Cores.xcframework"

echo "== ready: ${DEST}/Cores.xcframework =="
