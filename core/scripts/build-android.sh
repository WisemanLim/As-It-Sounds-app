#!/usr/bin/env bash
# Build ais-ffi for Android (4 ABIs) and emit Kotlin bindings.
# Output goes to ../android/app/src/main/jniLibs/<abi>/ and ../android/app/src/main/java/com/asitsounds/core/

set -euo pipefail
cd "$(dirname "$0")/.."

export PATH="${HOME}/.cargo/bin:${PATH}"

: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must be set (e.g. ~/Library/Android/sdk/ndk/27.x.x)}"

PROFILE="${PROFILE:-release}"
LIBNAME="libais_ffi.so"
ANDROID_APP_DIR="../android/app"
JNILIBS_DIR="${ANDROID_APP_DIR}/src/main/jniLibs"
KOTLIN_OUT="${ANDROID_APP_DIR}/src/main/java"

TARGETS=(
    aarch64-linux-android
    armv7-linux-androideabi
    x86_64-linux-android
    i686-linux-android
)

echo "==> Ensuring cargo-ndk is installed"
if ! command -v cargo-ndk >/dev/null 2>&1; then
    cargo install --locked cargo-ndk
fi

echo "==> Ensuring Android Rust targets are installed"
for t in "${TARGETS[@]}"; do
    rustup target add "$t"
done

echo "==> Building ais-ffi for Android (4 ABIs)"
mkdir -p "${JNILIBS_DIR}"
cargo ndk \
    -t arm64-v8a \
    -t armeabi-v7a \
    -t x86_64 \
    -t x86 \
    -o "${JNILIBS_DIR}" \
    build --package ais-ffi --profile "$PROFILE"

echo "==> Generating Kotlin bindings"
cargo run --release --features cli --bin uniffi-bindgen -- \
    generate crates/ais-ffi/src/ais_ffi.udl \
    --language kotlin \
    --out-dir "${KOTLIN_OUT}" \
    --config crates/ais-ffi/uniffi.toml

echo
echo "✓ Native libs:    ${JNILIBS_DIR}/<abi>/${LIBNAME}"
echo "✓ Kotlin binding: ${KOTLIN_OUT}/com/asitsounds/core/*.kt"
