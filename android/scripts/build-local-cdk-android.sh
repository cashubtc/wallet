#!/usr/bin/env bash
set -euo pipefail

# Build org.cashudevkit:cdk-android from a local CDK checkout and publish it to
# ~/.m2 (mavenLocal), so the app can be tested against an unreleased CDK branch.
#
# Usage: android/scripts/build-local-cdk-android.sh <cdk-checkout> [version]
#
# Requires cargo-ndk (`cargo install cargo-ndk --locked`), the Rust Android
# targets, an Android NDK, and JDK 17. ABIs: arm64-v8a and x86_64 (device and
# emulator). The default version is a pre-release marker so it can never be
# mistaken for the published 0.18.x artifacts.

CDK_DIR="${1:?usage: $0 <cdk-checkout> [version]}"
VERSION="${2:-0.18.0-pr2696}"
CDK_DIR="$(cd "$CDK_DIR" && pwd)"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)"
  export ANDROID_NDK_HOME
fi
[ -d "${ANDROID_NDK_HOME:-}" ] || { echo "Android NDK not found; set ANDROID_NDK_HOME" >&2; exit 1; }
command -v cargo-ndk >/dev/null || { echo "cargo-ndk missing: cargo install cargo-ndk --locked" >&2; exit 1; }

KOTLIN="$CDK_DIR/bindings/kotlin"
JNI="$KOTLIN/cdk-android/src/main/jniLibs"
cd "$CDK_DIR"

echo "==> cargo ndk (NDK: $ANDROID_NDK_HOME)"
rm -rf "$JNI"
cargo ndk -t arm64-v8a -t x86_64 --platform 24 -o "$JNI" \
  build --locked --profile release-ffi -p cdk-ffi-kotlin

# uniffi reads the interface from the library's symbol table, so generate the
# bindings before stripping.
echo "==> uniffi-bindgen-kotlin"
GENERATED="$KOTLIN/cdk-jvm/src/main/kotlin"
rm -rf "$GENERATED"
mkdir -p "$GENERATED"
cargo run --locked --release -p cdk-ffi-kotlin --bin uniffi-bindgen-kotlin -- generate \
  --library "$CDK_DIR/target/aarch64-linux-android/release-ffi/libcdk_ffi_kotlin.so" \
  --language kotlin \
  --config "$KOTLIN/rust/uniffi.toml" \
  --out-dir "$GENERATED" \
  --no-format

# cargo-ndk also copies the plain cdk-ffi cdylib; only the Kotlin one is shipped.
find "$JNI" -name '*.so' ! -name 'libcdk_ffi_kotlin.so' -delete

STRIP="$(ls "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-strip | head -1)"
for so in "$JNI"/*/libcdk_ffi_kotlin.so; do "$STRIP" --strip-all "$so"; done

echo "==> gradle publishToMavenLocal ($VERSION)"
# CDK's own bindings/kotlin Gradle project pins an AGP that no longer runs on
# current toolchains, so package with the app's Gradle/AGP instead.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AAR_PROJECT="$SCRIPT_DIR/local-cdk-aar"
rm -rf "$AAR_PROJECT/src"
mkdir -p "$AAR_PROJECT/src/main"
cp -R "$GENERATED" "$AAR_PROJECT/src/main/kotlin"
cp -R "$JNI" "$AAR_PROJECT/src/main/jniLibs"
"$SCRIPT_DIR/../gradlew" -p "$AAR_PROJECT" --no-daemon publishToMavenLocal -PaarVersion="$VERSION"

echo "Published org.cashudevkit:cdk-android:$VERSION to ~/.m2 (CDK $(git -C "$CDK_DIR" rev-parse --short HEAD))"
