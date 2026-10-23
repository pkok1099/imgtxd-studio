#!/bin/bash
# Builds libimgtxd_core.so for Android arm64 and installs it into the app's
# jniLibs. Toolchain lives OUTSIDE the repo (user workspace):
#   Rust:  ~/workspace/rust (rustup, RUSTUP_HOME/CARGO_HOME)
#   NDK:   ~/workspace/android-ndk (r27)
# Run from anywhere; paths below are the user's machine layout.
set -euo pipefail

export RUSTUP_HOME="${RUSTUP_HOME:-$HOME/workspace/rust/rustup}"
export CARGO_HOME="${CARGO_HOME:-$HOME/workspace/rust/cargo}"
export PATH="$CARGO_HOME/bin:$PATH"

NDK="${ANDROID_NDK_HOME:-$HOME/workspace/android-ndk}/toolchains/llvm/prebuilt/linux-x86_64"
API=31
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK/bin/aarch64-linux-android${API}-clang"
export CC_aarch64_linux_android="$NDK/bin/aarch64-linux-android${API}-clang"
export AR_aarch64_linux_android="$NDK/bin/llvm-ar"

CRATE_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_JNILIBS="$(dirname "$CRATE_DIR")/app/src/main/jniLibs/arm64-v8a"

cd "$CRATE_DIR"
cargo build --release --target aarch64-linux-android
mkdir -p "$APP_JNILIBS"
cp target/aarch64-linux-android/release/libimgtxd_core.so "$APP_JNILIBS/"
echo "Installed: $APP_JNILIBS/libimgtxd_core.so"
