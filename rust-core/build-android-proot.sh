#!/bin/bash
# Build libimgtxd_core.so (arm64) di proot, pakai clang milik Termux sebagai linker.
set -euo pipefail

T="${TERMUX_PREFIX:-/data/data/com.termux/files/usr}"
export RUSTUP_HOME="${RUSTUP_HOME:-/root/.rustup}"
export CARGO_HOME="${CARGO_HOME:-/root/.cargo}"
export PATH="$CARGO_HOME/bin:$PATH"
unset LD_PRELOAD

# Cek clang Termux bisa jalan
"$T/bin/clang" --version >/dev/null 2>&1 || {
  echo "clang Termux tidak bisa dijalankan dari proot" >&2; exit 1; }

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$T/bin/clang"
export CC_aarch64_linux_android="$T/bin/clang"
export AR_aarch64_linux_android="$T/bin/llvm-ar"

# Align 16KB (Android 15+/Play Store)
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"

CRATE_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_JNILIBS="$(dirname "$CRATE_DIR")/app/src/main/jniLibs/arm64-v8a"
SO="$CRATE_DIR/target/aarch64-linux-android/release/libimgtxd_core.so"

cd "$CRATE_DIR"
cargo build --release --target aarch64-linux-android

mkdir -p "$APP_JNILIBS"
cp "$SO" "$APP_JNILIBS/"
echo "Disalin ke: $APP_JNILIBS/libimgtxd_core.so"

# Verifikasi
echo "--- NEEDED (idealnya hanya lib sistem Android) ---"
readelf -d "$APP_JNILIBS/libimgtxd_core.so" | grep NEEDED || true
echo "--- LOAD align (harus 0x4000) ---"
readelf -lW "$APP_JNILIBS/libimgtxd_core.so" | grep LOAD || true
