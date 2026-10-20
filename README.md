# GTA Img Studio 🎮📦

Modern, high-performance, and open-source GTA `.img` archive manager built specifically for modern Android (Android 12 – 16, API 31–36) with Jetpack Compose Material 3 and Shizuku privileged access.

---

## ✨ Features

- **Direct `Android/data` Access via Shizuku:**
  - Modify game files directly in restricted storage folders on Android 11, 12, 13, 14, 15, and 16 without needing root or a PC.
  - High-throughput I/O using batch privileged processes (`stat -c` O(1) metadata retrieval).

- **Built-in RenderWare TXD Studio (Texture Dictionary):**
  - **FOSS RenderWare Parser & Serializer:** Open, view, and modify `.txd` files directly inside `.img` archives or as standalone files from device storage or `Android/data` via Shizuku.
  - **Wide Format Support:** Decodes and displays DXT1, DXT3, DXT5 (S3TC), RGBA32, RGB24, 16-bit (565, 1555, 4444), 8-bit paletted, and ETC1 textures with transparency checkerboard preview.
  - **In-Place Texture Replacement:** Replace any texture with user images (`.png` / `.jpg`). If opened from an `.img` archive, automatically updates the archive in-place without manual extraction or repack.
  - **Export & Batch Export:** Export individual textures or batch export all textures in a TXD dictionary to lossless `.png` images.

- **Full Format Support (VER1 & VER2):**
  - GTA San Andreas (`.img` VER2 with embedded directory table).
  - GTA III and GTA Vice City (`.img` VER1 with companion `.dir` directory files).

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
git clone https://github.com/pkok1099/gta-img-studio.git
cd gta-img-studio

# Build debug APK
./gradlew assembleDebug

# Build release APK (R8 Minified)
./gradlew assembleRelease
```

> **Note for Fork Developers:** Official release builds distributed by the author are signed with a private release keystore and verified via on-device cryptographic SHA-256 checks. When building from source for your own use, Gradle automatically falls back to debug signing, or you can supply your own keystore in `app/build.gradle.kts`.

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0** (GPLv3). See the [LICENSE](LICENSE) file for details.
