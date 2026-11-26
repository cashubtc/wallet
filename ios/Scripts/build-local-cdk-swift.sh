#!/usr/bin/env bash
set -euo pipefail

# Build a local cdk-swift package (xcframework + generated Swift sources) from a
# CDK checkout, so the app can be tested against an unreleased CDK branch.
#
# Usage: ios/Scripts/build-local-cdk-swift.sh <cdk-checkout> [output-dir]
#
# The output directory is a complete Swift package (default:
# ios/LocalPackages/cdk-swift-pr2696). It is git-ignored and never committed.
# Slices: iOS device (arm64) and iOS simulator (arm64). macOS and x86_64 are
# intentionally skipped to keep local builds fast.

CDK_DIR="${1:?usage: $0 <cdk-checkout> [output-dir]}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${2:-${SCRIPT_DIR}/../LocalPackages/cdk-swift-pr2696}"
CDK_DIR="$(cd "$CDK_DIR" && pwd)"
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"

PROFILE=release-ffi
WORK="$CDK_DIR/build/local-swift"
rm -rf "$WORK"
mkdir -p "$WORK"

cd "$CDK_DIR"
export DEVELOPER_DIR="$(/usr/bin/xcrun xcode-select -p)"

for target in aarch64-apple-ios aarch64-apple-ios-sim; do
  echo "==> cargo build $target"
  env -u MACOSX_DEPLOYMENT_TARGET -u SDKROOT \
    cargo build --locked --profile "$PROFILE" -p cdk-ffi-swift --target "$target"
  strip -S "target/$target/$PROFILE/libcdk_ffi_swift.a"
done

echo "==> uniffi-bindgen-swift"
cargo build --locked --release -p cdk-ffi-swift --bin uniffi-bindgen-swift
BINDGEN="$CDK_DIR/target/release/uniffi-bindgen-swift"

VERSION="$(sed -n 's/^version = "\([0-9]*\.[0-9]*\.[0-9]*\).*/\1/p' Cargo.toml | head -1)"

make_framework() {
  local name="$1" target="$2" plist="$3"
  local fw="$WORK/$name/CashuDevKitFFI.framework"
  local lib="target/$target/$PROFILE/libcdk_ffi_swift.a"
  mkdir -p "$fw"
  cp "$lib" "$fw/CashuDevKitFFI"
  sed "s|__CDK_SWIFT_FRAMEWORK_VERSION__|${VERSION}|g" "bindings/swift/resources/$plist" > "$fw/Info.plist"
  "$BINDGEN" "$lib" "$fw/Headers" --headers
  "$BINDGEN" "$lib" "$fw/Modules" --xcframework --modulemap \
    --module-name CashuDevKitFFI --modulemap-filename module.modulemap
}
make_framework device aarch64-apple-ios Info-iOS.plist
make_framework simulator aarch64-apple-ios-sim Info-iOSSimulator.plist

SOURCES="$WORK/Sources/Cdk"
mkdir -p "$SOURCES"
"$BINDGEN" "target/aarch64-apple-ios-sim/$PROFILE/libcdk_ffi_swift.a" "$SOURCES" --swift-sources
sed -i '' 's/canImport(cdk_ffiFFI)/canImport(CashuDevKitFFI)/g; s/import cdk_ffiFFI/import CashuDevKitFFI/g' "$SOURCES"/*.swift

echo "==> xcframework"
rm -rf "$OUT_DIR/CashuDevKitFFI.xcframework" "$OUT_DIR/Sources"
xcodebuild -create-xcframework \
  -framework "$WORK/device/CashuDevKitFFI.framework" \
  -framework "$WORK/simulator/CashuDevKitFFI.framework" \
  -output "$OUT_DIR/CashuDevKitFFI.xcframework"
mkdir -p "$OUT_DIR/Sources"
cp -R "$SOURCES" "$OUT_DIR/Sources/Cdk"

cat > "$OUT_DIR/Package.swift" <<'SWIFT'
// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "cdk-swift",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "Cdk", targets: ["Cdk"])],
    targets: [
        .binaryTarget(name: "CashuDevKitFFI", path: "CashuDevKitFFI.xcframework"),
        .target(name: "Cdk", dependencies: ["CashuDevKitFFI"], path: "Sources/Cdk"),
    ]
)
SWIFT

echo "Built local cdk-swift package at $OUT_DIR (CDK $(git rev-parse --short HEAD))"
