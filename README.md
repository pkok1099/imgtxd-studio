# ImgTxd Studio 🎮📦

Modern, high-performance, and open-source `.img` archive and `.txd` texture dictionary manager built specifically for modern Android (Android 12 – 16, API 31–36) with Jetpack Compose Material 3 and Shizuku privileged access.

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
