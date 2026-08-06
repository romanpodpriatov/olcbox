#!/usr/bin/env bash
# The three versions Cores.xcframework is built from, in one place.
#
# They used to live in two: build-cores-ios.sh derived the cache key and the
# release tag from its own copy, while fetch-cores-ios.sh carried the tag as a
# literal — and the literal was never updated when olcRTC was re-pinned. The two
# then disagreed silently, and the failure mode was the worst kind: fetch looks
# in the destination first, so as long as a freshly built framework was sitting
# there the right one got used and nothing looked wrong. The moment Gradle swept
# `sharedUI/build` — which it does, that directory is fetchCoresIosXcframework's
# declared output — fetch would fall through to a cache entry named after the
# *previous* olcRTC revision and restore a framework with no UDP in it, while
# every pin in the tree said UDP was there.
#
# Sourced by build-cores-ios.sh and fetch-cores-ios.sh. Bump a version here and
# the cache key, the release tag and the lookup all move together.
#
# The workflow keeps its own copy in `env:` because a job-level cache key cannot
# be computed from a sourced shell file; it checks itself against this one.

SINGBOX_VERSION="${SINGBOX_VERSION:-1.13.14}"
LIBXRAY_VERSION="${LIBXRAY_VERSION:-v1.260711.0}"
# Branch `proofkit-udp-spike` — the only lineage carrying a UDP relay. The pin
# before this was upstream `42ae4e0c`, where internal/client/udp.go does not
# exist at all, so the SOCKS5 server could not answer UDP ASSOCIATE and every
# datagram died inside the extension, DNS first among them.
OLCRTC_VERSION="${OLCRTC_VERSION:-v0.0.0-20260717184831-c83717e7e900}"

# Bumped when the framework's *shape* changes while its pins do not — adding the
# macOS slice being the first case. The versions alone cannot express that: they
# are identical before and after, so the tag would be identical too, and every
# consumer keyed on it — the destination stamp, the local cache, the published
# release — would hand back an iOS-only framework as though it were the one this
# build asked for. That is the same failure the header above describes, arriving
# by a different door.
#
# 2 → 3: the simulator slice. Same three versions, a framework one platform
# wider, and every consumer keyed on the tag — the destination stamp, the local
# cache, the published release — would otherwise hand back the two-slice build
# for a project that now asks for three.
CORES_BUILD="${CORES_BUILD:-3}"

# The revision rather than the whole pseudo-version: the tag stays readable and
# still changes whenever olcRTC does.
#
# The `ios-` prefix is now a misnomer — the framework carries a macOS slice too —
# but it is an identifier, not a description. Renaming it would orphan every
# published release and cache entry to buy nothing.
CORES_TAG="ios-cores-sb${SINGBOX_VERSION}-lx${LIBXRAY_VERSION}-rtc${OLCRTC_VERSION##*-}-b${CORES_BUILD}"
