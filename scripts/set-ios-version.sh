#!/usr/bin/env bash
# Stamp the iOS project with a version, the way CI stamps every other platform.
#
# CI passes MARKETING_VERSION and CURRENT_PROJECT_VERSION to xcodebuild, so its
# builds carry the shared 1.0.<patch> without this script. A build archived from
# Xcode by hand does not: it uses the literals in project.pbxproj, and that is
# the path an App Store submission actually takes. This is what keeps the two
# from drifting — which they did, to a store listing reading 1.0.0 over an app
# whose own about screen said 1.0.24x.
#
# Both numbers move together and both come from the same patch, exactly as
# Android's versionName/versionCode do. There are four occurrences of each — the
# app and PacketTunnel, Debug and Release — and all four have to match, because
# an extension whose version disagrees with its host fails to install on a
# device rather than failing to build.
#
#   scripts/set-ios-version.sh 1.0.265
#
set -euo pipefail

version="${1:-}"
project="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/iosApp/iosApp.xcodeproj/project.pbxproj"

if [ -z "$version" ]; then
    echo "usage: $(basename "$0") <marketing-version>   e.g. 1.0.265" >&2
    exit 2
fi

case "$version" in
    1.0.*) ;;
    *)
        echo "expected 1.0.<patch> to match the other platforms, got '$version'" >&2
        exit 2
        ;;
esac

patch="${version##*.}"
case "$patch" in
    ''|*[!0-9]*)
        echo "'$patch' is not a number; the build number is the patch, as on Android" >&2
        exit 2
        ;;
esac

[ -f "$project" ] || { echo "no project at $project" >&2; exit 1; }

# In place, and then read back: sed reports success for a pattern it never
# matched, so a rename in the pbxproj would silently leave the old version in a
# build that then reaches Apple.
sed -i.bak \
    -e "s/MARKETING_VERSION = [^;]*;/MARKETING_VERSION = ${version};/g" \
    -e "s/CURRENT_PROJECT_VERSION = [^;]*;/CURRENT_PROJECT_VERSION = ${patch};/g" \
    "$project"
rm -f "${project}.bak"

marketing="$(grep -c "MARKETING_VERSION = ${version};" "$project" || true)"
build_number="$(grep -c "CURRENT_PROJECT_VERSION = ${patch};" "$project" || true)"

if [ "$marketing" -ne 4 ] || [ "$build_number" -ne 4 ]; then
    echo "expected 4 of each, found ${marketing} marketing and ${build_number} build" >&2
    echo "the project layout changed — check both targets in Debug and Release" >&2
    exit 1
fi

echo "iOS version ${version} (${patch}) — ${marketing} targets stamped"
