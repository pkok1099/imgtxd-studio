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

# Resolve NDK directory: check standard user workspace or home fallback
NDK_ROOT="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK_ROOT" ]; then
    if [ -d "$HOME/android-ndk-r27b" ]; then
        NDK_ROOT="$HOME/android-ndk-r27b"
    elif [ -d "$HOME/workspace/android-ndk" ]; then
        NDK_ROOT="$HOME/workspace/android-ndk"
    else
        echo "Error: Android NDK not found" >&2
        exit 1
    fi
fi

# Detect host prebuilt llvm tag (linux-x86_64 or linux-aarch64)
HOST_TAG="linux-x86_64"
if [ "$(uname -m)" = "aarch64" ]; then
    HOST_TAG="linux-aarch64"
fi
NDK="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG"
API=31
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK/bin/aarch64-linux-android${API}-clang"
export CC_aarch64_linux_android="$NDK/bin/aarch64-linux-android${API}-clang"
export AR_aarch64_linux_android="$NDK/bin/llvm-ar"

# Android 15+/16 memakai 16KB memory page pada sebagian device dan Google Play
# mewajibkan dukungan 16KB utk targetSdk 35+. Wrapper linker NDK tidak
# menyetel max-page-size secara default -> .so hanya ter-align 4KB dan GAGAL
# dlopen ("program alignment") di device 16KB. Paksa align 16KB.
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"

CRATE_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_JNILIBS="$(dirname "$CRATE_DIR")/app/src/main/jniLibs/arm64-v8a"

cd "$CRATE_DIR"
cargo build --release --target aarch64-linux-android
mkdir -p "$APP_JNILIBS"
cp target/aarch64-linux-android/release/libimgtxd_core.so "$APP_JNILIBS/"
echo "Installed: $APP_JNILIBS/libimgtxd_core.so"
