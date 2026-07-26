#!/usr/bin/env bash
# Fetches the prebuilt LibXray.xcframework instead of building it here.
#
# Same arrangement as fetch-libbox-ios.sh, and for the same reason: the iOS
# Frameworks workflow built it once and published it to a release tag, so every
# build after the first just downloads it. LibXray exists for exactly one
# transport — sing-box cannot speak xhttp — so it is fetched alongside libbox
# rather than instead of it.
set -euo pipefail

DEST="${1:?usage: fetch-libxray-ios.sh <destination-dir>}"
TAG="${LIBBOX_RELEASE_TAG:-ios-frameworks-sb1.11.15-xray25.3.6}"
ASSET="LibXray-ios.zip"
URL="https://github.com/romanpodpriatov/olcbox/releases/download/${TAG}/${ASSET}"

if [ -d "${DEST}/LibXray.xcframework/ios-arm64" ]; then
    echo "LibXray.xcframework already present in ${DEST}"
    exit 0
fi

mkdir -p "${DEST}"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

echo "== fetching ${ASSET} from ${TAG} =="
curl -fsSL -o "${work}/${ASSET}" "${URL}"
unzip -q -o "${work}/${ASSET}" -d "${DEST}"

test -d "${DEST}/LibXray.xcframework/ios-arm64" \
    || { echo "downloaded archive has no ios-arm64 slice"; exit 1; }
echo "== ready: ${DEST}/LibXray.xcframework =="
