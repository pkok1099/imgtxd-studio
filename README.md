# ImgTxd Studio 🎮📦

[![GitHub release](https://img.shields.io/github/v/release/pkok1099/imgtxd-studio?color=blue&logo=github&style=flat-square)](https://github.com/pkok1099/imgtxd-studio/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android%2012%20--%2016-green?logo=android&style=flat-square)](https://github.com/pkok1099/imgtxd-studio/releases)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-orange.svg?style=flat-square)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-purple?logo=kotlin&style=flat-square)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack-Compose%20M3-brightgreen?logo=jetpackcompose&style=flat-square)](https://developer.android.com/jetpack/compose)

> **Modern, high-performance, and open-source `.img` archive & `.txd` texture dictionary editor for Android (Android 12 – 16, API 31–36).**  
> Direct alternative to legacy PC IMG Tool and TXD Tool on mobile, powered by **Jetpack Compose Material 3** and **Shizuku privileged storage access**.

[⬇️ **Download Latest APK (v3.3.0)**](https://github.com/pkok1099/imgtxd-studio/releases/tag/v3.3.0) • [📦 All Releases](https://github.com/pkok1099/imgtxd-studio/releases) • [✨ Features](#-features) • [🎯 Supported Games](#-supported-games) • [🛠️ Build](#️-building-from-source)

## ⬇️ Download

| Channel | Version | Direct APK download | Release notes |
|---|---|---|---|
| Stable | v3.3.0 | [GTA_Img_Studio.apk](https://github.com/pkok1099/imgtxd-studio/releases/download/v3.3.0/GTA_Img_Studio.apk) (4.5 MB) | [v3.3.0](https://github.com/pkok1099/imgtxd-studio/releases/tag/v3.3.0) |

---

## 🔍 Overview

Modding RenderWare games like **GTA San Andreas** on newer Android versions (Android 12, 13, 14, 15, and 16) has become difficult due to Scoped Storage restrictions preventing apps from writing to `Android/data/`.

**ImgTxd Studio** solves this completely:
- 🚀 **Full `.img` Archive Tool:** Add, replace, extract, rename, delete, and rebuild `.img` archives (VER1 & VER2).
- 🎨 **RenderWare `.txd` Texture Studio:** View, replace, and export textures directly inside `.img` files or standalone `.txd` files (DXT1/3/5, ETC1, 16/24/32-bit).
- ⚡ **Seamless `Android/data` Access via Shizuku:** No PC or root required to edit game textures or files directly.
- 🪟 **Multi-Window Support:** Open `.img` and `.txd` simultaneously side-by-side or in floating windows.

---

## 🎯 Supported games

ImgTxd Studio supports games and engines utilizing RenderWare binary formats and `.img` sector-based archives:

- Grand Theft Auto: San Andreas (`.img` VER2 with embedded directory table, PC & Mobile texture dictionaries).
- Grand Theft Auto: Vice City (`.img` VER1 paired with `.dir` directory table, PC & Mobile `.txd`).
- Grand Theft Auto III (`.img` VER1 paired with `.dir` directory table, PC & Mobile `.txd`).
- Bully: Anniversary Edition (Sector-based `.img` archives and companion texture dictionaries).

---

## ✨ Features

- **Multi-Window Mode ("Mode Jendela"):**
  - **PER_FEATURE (Default):** Dedicated windows for IMG archives and TXD texture dictionaries (at most 1 IMG window and 1 TXD window in Recent Apps). Opening a new file reuses the window cleanly with unsaved-change protection.
  - **PER_FILE:** Document-task mode where each opened file runs in its own window card in Recent Apps, with automatic duplicate task deduplication.
  - **SINGLE_WINDOW:** Everything stays in a single window/task; TXDs inside IMG archives open as in-app dialogs.
  - Configurable at any time via Settings (stored persistently in Jetpack DataStore Preferences).

- **Cross-Window Mutex & Repository Synchronization:**
  - TXD files extracted from inside an IMG archive and edited in a separate window are synchronized back to the parent archive with path-specific `Mutex` concurrency protection and reactive `SharedFlow` broadcast.

- **Direct `Android/data` Access via Shizuku:**
  - Modify game files directly in restricted storage folders on Android 11, 12, 13, 14, 15, and 16 without needing root or a PC.
  - High-throughput I/O using batch privileged processes (`stat -c` O(1) metadata retrieval).

- **RenderWare TXD Studio (Texture Dictionary):**
  - **FOSS RenderWare Parser & Serializer:** Open, view, and modify `.txd` files directly inside `.img` archives or as standalone files.
  - **Wide Format Support:** Decodes and displays DXT1, DXT3, DXT5 (S3TC), RGBA32, RGB24, 16-bit (565, 1555, 4444), 8-bit paletted, and ETC1 textures with transparency checkerboard preview.
  - **Auto Replace:** Automatically match and batch-replace textures by scanning mod folders or selecting multiple image files simultaneously.
  - **In-Place Replacement:** Replace any texture with user images (`.png` / `.jpg`). Updates parent `.img` archives in-place without manual repacks.
  - **Export & Batch Export:** Export individual textures or batch export all textures in a TXD dictionary to lossless `.png` images.

- **Full Format Support (VER1 & VER2):**
  - San Andreas format (`.img` VER2 with embedded directory table).
  - Classic format (`.img` VER1 with companion `.dir` directory files).

- **Ultra High-Performance Engine:**
  - **Virtual Lazy Pagination:** Easily handles massive archives like `gta3.img` with **16,000+ entries** without UI stutter or memory bloat (renders in 100-item incremental chunks).
  - **Recomposition Isolation:** Independent row state providers preserve 60–120 FPS buttery-smooth scrolling.
  - **Debounced Search:** Off-thread regex and name filtering with 120ms debouncing.
  - **Zero-Allocation String Caching:** File names and sector offsets are pre-cached at instantiation.

- **Complete Archive Operations:**
  - **Add File:** Append new models (`.dff`), textures (`.txd`), collision (`.col`), and placement (`.ipl`) files.
  - **Replace File:** Smart in-place replacement when file size fits existing sectors, or append-to-end with header adjustment.
  - **Extract / Batch Extract:** Extract individual files or select multiple entries to export.
  - **Rename & Delete:** Edit file headers or batch-remove entries.
  - **Rebuild & Compact:** Repack active entries into a contiguous gap-free layout to reclaim dead storage space.

- **Modern Android Architecture:**
  - 100% Kotlin with Jetpack Compose & Material 3.
  - True Edge-to-Edge display with full WindowInsets handling.
  - Frame-driven drawer animation (`withFrameNanos`) that remains smooth even when system animator duration scale is set to 0x.
  - Multi-layer anti-tampering and cryptographic signature verification.

---

## 🛠️ Building from Source

### Prerequisites
- JDK 21
- Android SDK (compileSdk 36, minSdk 31)

### Build Commands
```bash
# Clone the repository
git clone https://github.com/pkok1099/imgtxd-studio.git
cd imgtxd-studio

# Build debug APK
./gradlew assembleDebug

# Build release APK (R8 Minified)
./gradlew assembleRelease
```

> **Note for Fork Developers:** Official release builds distributed by the author are signed with a private release keystore and verified via on-device cryptographic SHA-256 checks. When building from source for your own use, Gradle automatically falls back to debug signing, or you can supply your own keystore in `app/build.gradle.kts`.

---

## ⚖️ Legal Disclaimer

ImgTxd Studio is an independent, free, and open-source file modification tool. All trademarks, game titles, and brand names (including Grand Theft Auto, GTA, Bully, and Rockstar Games) are the property of their respective owners. ImgTxd Studio is not affiliated with, endorsed by, or sponsored by Rockstar Games or Take-Two Interactive.

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0** (GPLv3). See the [LICENSE](LICENSE) file for details.
