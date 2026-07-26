#!/usr/bin/env bash
# Fetches the prebuilt Libbox.xcframework instead of building it here.
#
# Building it needs Go, the sagernet gomobile fork and about twenty minutes of a
# macOS machine. The iOS Frameworks workflow already did that once and published
# the result to a release tag, so every build after the first just downloads it.
# The tag names the versions it was built from, so a version bump is a new tag
# rather than a silent swap.
set -euo pipefail

DEST="${1:?usage: fetch-libbox-ios.sh <destination-dir>}"
TAG="${LIBBOX_RELEASE_TAG:-ios-frameworks-sb1.11.15-xray25.3.6}"
ASSET="Libbox-ios.zip"
URL="https://github.com/romanpodpriatov/olcbox/releases/download/${TAG}/${ASSET}"

if [ -d "${DEST}/Libbox.xcframework/ios-arm64" ]; then
    echo "Libbox.xcframework already present in ${DEST}"
    exit 0
fi

mkdir -p "${DEST}"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

echo "== fetching ${ASSET} from ${TAG} =="
curl -fsSL -o "${work}/${ASSET}" "${URL}"
unzip -q -o "${work}/${ASSET}" -d "${DEST}"

test -d "${DEST}/Libbox.xcframework/ios-arm64" \
    || { echo "downloaded archive has no ios-arm64 slice"; exit 1; }
echo "== ready: ${DEST}/Libbox.xcframework =="
