#!/usr/bin/env bash
# Quick host-only bindings generation (no cross compile) for smoke testing.

set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="${HOME}/.cargo/bin:${PATH}"

cargo build --release --package ais-ffi

UDL="crates/ais-ffi/src/ais_ffi.udl"

cargo run --release --features cli --bin uniffi-bindgen -- \
    generate "$UDL" \
    --language swift \
    --out-dir bindings/swift \
    --config crates/ais-ffi/uniffi.toml

cargo run --release --features cli --bin uniffi-bindgen -- \
    generate "$UDL" \
    --language kotlin \
    --out-dir bindings/kotlin \
    --config crates/ais-ffi/uniffi.toml

echo "✓ Swift bindings : bindings/swift/"
echo "✓ Kotlin bindings: bindings/kotlin/"
ls -la bindings/swift bindings/kotlin
