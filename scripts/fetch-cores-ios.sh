#!/usr/bin/env bash
# Fetches the prebuilt Cores.xcframework instead of building it here.
#
# One framework holding both sing-box and Xray — they cannot be linked as two,
# see build-cores-ios.sh. Building it needs Go, gomobile and a macOS machine;
# the iOS Frameworks workflow did that once and published the result to a
# release tag, so every build after the first just downloads it. The tag names
# the versions it was built from, so a version bump is a new tag rather than a
# silent swap.
set -euo pipefail

DEST="${1:?usage: fetch-cores-ios.sh <destination-dir>}"
TAG="${CORES_RELEASE_TAG:-ios-cores-sb1.11.15-lxv1.260711.0}"
ASSET="Cores-ios.zip"
URL="https://github.com/romanpodpriatov/olcbox/releases/download/${TAG}/${ASSET}"

if [ -d "${DEST}/Cores.xcframework/ios-arm64" ]; then
    echo "Cores.xcframework already present in ${DEST}"
    exit 0
fi

mkdir -p "${DEST}"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

echo "== fetching ${ASSET} from ${TAG} =="
# A missing release is the expected failure right after a version bump, and
# `curl -f` alone reports it as a bare exit 22 from somewhere inside an Xcode
# build phase. Say which tag is missing and what produces it.
curl -fsSL -o "${work}/${ASSET}" "${URL}" || {
    echo "no ${ASSET} published at tag ${TAG}."
    echo "Build it yourself (needs Go and Xcode, ~20 min):"
    echo "  bash scripts/build-cores-ios.sh \"${DEST}\""
    echo "or run the 'iOS Frameworks' workflow to build and publish it once."
    exit 1
}
unzip -q -o "${work}/${ASSET}" -d "${DEST}"

test -d "${DEST}/Cores.xcframework/ios-arm64" \
    || { echo "downloaded archive has no ios-arm64 slice"; exit 1; }
echo "== ready: ${DEST}/Cores.xcframework =="
