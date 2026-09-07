#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT

xcrun swiftc \
  "$root/iosApp/PacketTunnel/PhysicalInterface.swift" \
  "$root/iosApp/Tests/PhysicalInterfaceSelectionTests.swift" \
  -o "$test_dir/interface-selection-tests"
"$test_dir/interface-selection-tests"
