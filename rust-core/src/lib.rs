//! ImgTxd Studio native core (Rust).
//!
//! Full core rewrite decided by the user on 2026-10-23 ("user 0, bebas rombak"):
//! archive parsers/builders (IMG, TXD, texdb), texture codecs, mipmap/resize,
//! and the audio index fixer move here; UI and app framework stay in Kotlin and
//! call in through the thin JNI surface at the bottom of this file.

pub mod audio;
pub mod audit;
pub mod datalines;
pub mod dff;
pub mod codec;
pub mod ide;
pub mod img;
pub mod mipmap;
pub mod texdb;
pub mod txd;

use jni::objects::{JByteArray, JClass, JIntArray, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jint, jintArray, jobjectArray, jstring};
use jni::JNIEnv;

/// Hard cap on texture dimensions accepted from JNI callers.
/// 16384 × 16384 × 4 bytes = 1 GiB — comfortably above any realistic mobile
/// texture (≤ 4096 × 4096) while still preventing 2³¹ × 2³¹ allocation-DoS
/// panics from a malicious Kotlin caller passing `Int.MAX_VALUE`.
const MAX_TEXTURE_DIM: jint = 16_384;

fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|js| js.to_string_lossy().into_owned())
        .unwrap_or_default()
}

fn report_string(env: &mut JNIEnv, result: Result<String, String>) -> jstring {
    let text = match result {
        Ok(line) => format!("OK {line}"),
        Err(e) => format!("ERROR {e}"),
    };
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn int_array_to_vec(env: &mut JNIEnv, array: &JIntArray) -> Option<Vec<u32>> {
    let len = env.get_array_length(array).ok()?;
    // Defensive: get_array_length returns jint; reject negatives so a JVM
    // bug or proxy can never become a giant usize allocation.
    if len < 0 {
        return None;
    }
    let mut buf = vec![0i32; len as usize];
    env.get_int_array_region(array, 0, &mut buf).ok()?;
    Some(buf.iter().map(|&v| v as u32).collect())
}

fn vec_to_int_array(env: &mut JNIEnv, data: &[u32]) -> jintArray {
    let out = match env.new_int_array(data.len() as jint) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    let as_i32: Vec<jint> = data.iter().map(|&p| p as jint).collect();
    if env.set_int_array_region(&out, 0, &as_i32).is_err() {
        return std::ptr::null_mut();
    }
    out.into_raw()
}

fn version_string() -> String {
    format!("imgtxd-core {}", env!("CARGO_PKG_VERSION"))
}

#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    env.new_string(version_string())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeDecodeDxt1(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteArray,
    width: jint,
    height: jint,
) -> jintArray {
    let bytes = env.convert_byte_array(&data).unwrap_or_default();
    // Reject invalid dimensions before decode_dxt1 can `width * height`-
    // allocate on a malicious jint (up to 2_147_483_647). codec::dxt uses
    // usize arithmetic; on 32-bit this would wrap and on 64-bit it would
    // request a 2^63-byte allocation and abort (panic=abort).
    if width <= 0 || height <= 0 || width > MAX_TEXTURE_DIM || height > MAX_TEXTURE_DIM {
        return std::ptr::null_mut();
    }
    let pixels = codec::dxt::decode_dxt1(&bytes, width as usize, height as usize);
    vec_to_int_array(&mut env, &pixels)
}

/// One mipmap halving step. `filter`: 0 = NEAREST, 1 = BOX, 2 = BILINEAR.
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeMipmapHalf(
    mut env: JNIEnv,
    _class: JClass,
    pixels: JIntArray,
    width: jint,
    height: jint,
    filter: jint,
) -> jintArray {
    let Some(px) = int_array_to_vec(&mut env, &pixels) else {
        return std::ptr::null_mut();
    };
    // Validate dimensions and pixel count BEFORE handing them to
    // PixelImage::new, which `assert!`s on bad input. Because panic=abort,
    // an invalid combination (e.g. width=0, or px.len() != w*h) would
    // kill the whole app, not just this call.
    if width <= 0 || height <= 0 || width > MAX_TEXTURE_DIM || height > MAX_TEXTURE_DIM {
        return std::ptr::null_mut();
    }
    let expected = (width as usize).checked_mul(height as usize);
    match expected {
        Some(n) if n == px.len() => {}
        _ => return std::ptr::null_mut(),
    }
    let img = mipmap::PixelImage::new(px, width as usize, height as usize);
    let f = match filter {
        0 => mipmap::MipFilter::Nearest,
        2 => mipmap::MipFilter::Bilinear,
        _ => mipmap::MipFilter::Box,
    };
    let out = mipmap::downscale_half(&img, f);
    vec_to_int_array(&mut env, &out.pixels)
}

/// Imports images into a texdb cache (TXD Tool wizard semantics in Rust).
/// `format` / `conflict` follow texdb::ImportFormat / ConflictPolicy i32 maps.
/// Returns "OK replaced=..;added=..;skipped=..;siblings=.." or "ERROR ..".
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeTexdbImportImages(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
    names: JObjectArray,
    pngs: JObjectArray,
    format: jint,
    conflict: jint,
    sibling_model: JString,
    reduce_to: jint,
    camnorm: jboolean,
    hash_check: jboolean,
    correct_caps: jboolean,
    use_rle: jboolean,
    gen_mips: jboolean,
    mip_filter: jint,
    defrag: jboolean,
) -> jstring {
    let dir_path = jstring_to_string(&mut env, &dir);
    let cache_name = std::path::Path::new(&dir_path)
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let count = env.get_array_length(&names).unwrap_or(0);
    if count < 0 {
        return report_string(&mut env, Err("array nama kosong/tidak valid".to_string()));
    }
    let mut images: Vec<(String, Vec<u8>)> = Vec::new();
    for i in 0..count {
        // CRITICAL: free each iteration's local refs explicitly. Without
        // this, a batch import of >512 textures overflows the JVM local-ref
        // table and aborts the process. Pattern: get_object_array_element
        // returns a new local ref each call; we wrap it in an owned JString /
        // JByteArray, use it, then move it into delete_local_ref which deletes
        // the underlying local ref.
        let Ok(name_obj) = env.get_object_array_element(&names, i) else { continue };
        let js = JString::from(name_obj);
        let name = jstring_to_string(&mut env, &js);
        let _ = env.delete_local_ref(js);
        let Ok(png_obj) = env.get_object_array_element(&pngs, i) else { continue };
        let jba = JByteArray::from(png_obj);
        let bytes = env.convert_byte_array(&jba).unwrap_or_default();
        let _ = env.delete_local_ref(jba);
        if !bytes.is_empty() {
            images.push((name, bytes));
        }
    }
    let model = jstring_to_string(&mut env, &sibling_model);
    let opts = texdb::ImportOptions {
        format: texdb::ImportFormat::from_i32(format),
        conflict: texdb::ConflictPolicy::from_i32(conflict),
        sibling_model: if model.trim().is_empty() { None } else { Some(model) },
        reduce_to: reduce_to.max(0) as usize,
        camnorm: camnorm != 0,
        hash_check: hash_check != 0,
        correct_caps: correct_caps != 0,
        use_rle: use_rle != 0,
        generate_mipmaps: gen_mips != 0,
        mip_filter,
        defragment: defrag != 0,
    };
    let result = texdb::import_textures(std::path::Path::new(&dir_path), &cache_name, &images, &opts);
    report_string(&mut env, result)
}

/// Rebuilds a texdb cache's .dat/.toc in place. Returns "OK entries=..;bytes=.." or "ERROR ..".
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeTexdbRebuildCache(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
) -> jstring {
    let dir_path = jstring_to_string(&mut env, &dir);
    let cache_name = std::path::Path::new(&dir_path)
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let result = texdb::rebuild_cache(std::path::Path::new(&dir_path), &cache_name);
    report_string(&mut env, result)
}

/// Decodes one cache texture to [width, height, ARGB pixels...]; null on failure.
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeTexdbTexturePixels(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
    index: jint,
) -> jintArray {
    let dir_path = jstring_to_string(&mut env, &dir);
    let cache_name = std::path::Path::new(&dir_path)
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    // Negative index means "nothing selected" in the UI; reject instead of
    // silently falling back to texture 0 via `index.max(0)`.
    if index < 0 {
        return std::ptr::null_mut();
    }
    match texdb::decode_pixels_at(std::path::Path::new(&dir_path), &cache_name, index as usize) {
        Some((w, h, _enc, pixels)) => {
            let mut out = Vec::with_capacity(2 + pixels.len());
            out.push(w as u32);
            out.push(h as u32);
            out.extend_from_slice(&pixels);
            vec_to_int_array(&mut env, &out)
        }
        None => std::ptr::null_mut(),
    }
}

/// Compares a DFF model's texture names against the cache manifest.
/// Returns "OK\n<missing names, one per line>" or "ERROR ..".
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeTexdbCheckDff(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
    dff_path: JString,
) -> jstring {
    let dir_path = jstring_to_string(&mut env, &dir);
    let dff = jstring_to_string(&mut env, &dff_path);
    let cache_name = std::path::Path::new(&dir_path)
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let result = (|| -> Result<String, String> {
        let text = std::fs::read_to_string(
            std::path::Path::new(&dir_path).join(format!("{cache_name}.txt")),
        )
        .map_err(|e| e.to_string())?;
        let present: Vec<String> = text
            .lines()
            .filter_map(|l| {
                let l = l.trim();
                l.strip_prefix('"')
                    .and_then(|rest| rest.find('"').map(|e| rest[..e].to_lowercase()))
            })
            .collect();
        let bytes = std::fs::read(&dff).map_err(|e| e.to_string())?;
        let needed = texdb::dff_texture_names(&bytes);
        let missing: Vec<String> = needed
            .into_iter()
            .filter(|n| !present.contains(&n.to_lowercase()))
            .collect();
        Ok(missing.join("\n"))
    })();
    report_string(&mut env, result)
}

/// Deletes textures by index from a texdb cache. Returns a report line.
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeTexdbDeleteTextures(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
    indices: JIntArray,
) -> jstring {
    let dir_path = jstring_to_string(&mut env, &dir);
    let cache_name = std::path::Path::new(&dir_path)
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let result = match int_array_to_vec(&mut env, &indices) {
        Some(values) => {
            let idx: Vec<usize> = values.iter().map(|&v| v as usize).collect();
            texdb::delete_textures(std::path::Path::new(&dir_path), &cache_name, &idx)
        }
        None => Err("array indeks tidak valid".to_string()),
    };
    report_string(&mut env, result)
}

/// Renames one texdb texture. Returns a report line.
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeTexdbRenameTexture(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
    index: jint,
    new_name: JString,
) -> jstring {
    let dir_path = jstring_to_string(&mut env, &dir);
    let name = jstring_to_string(&mut env, &new_name);
    let cache_name = std::path::Path::new(&dir_path)
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let result = texdb::rename_texture(
        std::path::Path::new(&dir_path),
        &cache_name,
        index as usize,
        &name,
    );
    report_string(&mut env, result)
}

/// Adds an affiliate (alias) entry to a texdb cache. Returns a report line.
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeTexdbAddAlias(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
    target_index: jint,
    alias_name: JString,
) -> jstring {
    let dir_path = jstring_to_string(&mut env, &dir);
    let alias = jstring_to_string(&mut env, &alias_name);
    let cache_name = std::path::Path::new(&dir_path)
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let result = texdb::add_alias(
        std::path::Path::new(&dir_path),
        &cache_name,
        target_index as usize,
        &alias,
    );
    report_string(&mut env, result)
}

/// Repacks a texdb cache into a target family (0 DXT, 1 ETC1, 2 raw).
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeTexdbRepackCache(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
    target: jint,
) -> jstring {
    let dir_path = jstring_to_string(&mut env, &dir);
    let cache_name = std::path::Path::new(&dir_path)
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let result = texdb::repack_cache(std::path::Path::new(&dir_path), &cache_name, target);
    report_string(&mut env, result)
}

/// DFF inspection report (multi-line, Indonesian). "OK\n<report>".
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeDffInspect(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let path_str = jstring_to_string(&mut env, &path);
    let result = std::fs::read(&path_str)
        .map(|data| dff::inspect_dff(&data))
        .map_err(|e| e.to_string());
    match result {
        Ok(report) => env
            .new_string(format!("OK\n{report}"))
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(e) => report_string(&mut env, Err(e)),
    }
}

/// Renames a texture reference inside a DFF file (with .dff.bak backup).
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeDffRenameTexture(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    old_name: JString,
    new_name: JString,
) -> jstring {
    let path_str = jstring_to_string(&mut env, &path);
    let old = jstring_to_string(&mut env, &old_name);
    let new = jstring_to_string(&mut env, &new_name);
    let result = dff::rename_texture_in_file(std::path::Path::new(&path_str), &old, &new);
    report_string(&mut env, result)
}

/// IMG structural health report (multi-line). "OK\n<report>".
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeImgVerify(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let path_str = jstring_to_string(&mut env, &path);
    let p = std::path::Path::new(&path_str);
    let dir_path = p.with_extension("dir");
    let dir_ref = if dir_path.exists() { Some(dir_path.as_path()) } else { None };
    let report = img::verify_archive(p, dir_ref);
    env.new_string(format!("OK\n{report}"))
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Whole-game texture audit over a GTA files/ folder. "OK\n<report>".
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeGameAudit(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let path_str = jstring_to_string(&mut env, &path);
    let result = audit::audit_game_folder(std::path::Path::new(&path_str));
    match result {
        Ok(report) => env
            .new_string(format!("OK\n{report}"))
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(e) => report_string(&mut env, Err(e)),
    }
}

// ---------------------------------------------------------------------------
// IMG archive engine (VER1 + VER2) — the app-facing JNI surface. All archive
// parsing/writing lives in `img.rs`; the Kotlin `ImgArchive` is a thin facade
// over these calls. Mutating ops open the archive, apply, and the engine
// rewrites the directory itself (same semantics as the old Kotlin engine).
// ---------------------------------------------------------------------------

fn opt_jstring_to_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s)
        .ok()
        .map(|js| js.to_string_lossy().into_owned())
}

fn img_open_archive(
    path: &str,
    dir: &Option<String>,
) -> Result<img::ImgArchive, String> {
    img::ImgArchive::open(
        std::path::Path::new(path),
        dir.as_deref().map(std::path::Path::new),
    )
    .map_err(|e| e.to_string())
}

fn img_op(
    path: &str,
    dir: &Option<String>,
    f: impl FnOnce(&mut img::ImgArchive) -> std::io::Result<String>,
) -> Result<String, String> {
    let mut archive = img_open_archive(path, dir)?;
    f(&mut archive).map_err(|e| e.to_string())
}

fn img_entry_at(
    archive: &img::ImgArchive,
    index: jint,
) -> Result<&img::ImgEntry, String> {
    if index < 0 {
        return Err("Indeks entri negatif".to_string());
    }
    archive
        .entries
        .get(index as usize)
        .ok_or_else(|| "Indeks entri di luar jangkauan".to_string())
}

/// The listing wire format shared by nativeImgList and nativeImgRebuild:
/// element 0 is "OK\t<VER1|VER2>\t<count>\t<totalDataSize>\t<resolvedDirPath|>"
/// and each following element is "<offsetSectors>\t<sizeSectors>\t<cleanName>".
fn img_listing_lines(archive: &img::ImgArchive) -> Vec<String> {
    let version = match archive.version {
        img::ImgVersion::Ver1 => "VER1",
        img::ImgVersion::Ver2 => "VER2",
    };
    let dir_path = archive
        .dir_file
        .as_ref()
        .map(|p| p.to_string_lossy().into_owned())
        .unwrap_or_default();
    let mut v = vec![format!(
        "OK\t{version}\t{}\t{}\t{dir_path}",
        archive.entries.len(),
        archive.total_data_size()
    )];
    for e in &archive.entries {
        v.push(format!(
            "{}\t{}\t{}",
            e.offset_sectors,
            e.size_sectors,
            e.clean_name()
        ));
    }
    v
}

/// Whether the file starts with the VER2 magic.
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeImgIsVer2(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jboolean {
    let path_str = jstring_to_string(&mut env, &path);
    if img::is_ver2_file(std::path::Path::new(&path_str)) {
        1
    } else {
        0
    }
}

/// Lists an archive: element 0 is
/// "OK\t<VER1|VER2>\t<count>\t<totalDataSize>\t<resolvedDirPath|>" and each
/// following element is "<offsetSectors>\t<sizeSectors>\t<cleanName>".
/// On failure a single "ERROR\t<message>" element is returned.
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeImgList(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    dir: JString,
) -> jobjectArray {
    let path_str = jstring_to_string(&mut env, &path);
    let dir_opt = opt_jstring_to_string(&mut env, &dir);
    let items: Vec<String> = match img_open_archive(&path_str, &dir_opt) {
        Ok(archive) => img_listing_lines(&archive),
        Err(e) => vec![format!("ERROR\t{e}")],
    };
    string_array(&mut env, &items)
}

/// Creates a fresh empty archive (version 1 or 2) at the path.
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeImgCreate(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    version: jint,
    dir: JString,
) -> jstring {
    let path_str = jstring_to_string(&mut env, &path);
    let dir_opt = opt_jstring_to_string(&mut env, &dir);
    let v = if version == 1 {
        img::ImgVersion::Ver1
    } else {
        img::ImgVersion::Ver2
    };
    let result = img::ImgArchive::create_empty(
        std::path::Path::new(&path_str),
        v,
        dir_opt.as_deref().map(std::path::Path::new),
    )
    .map(|_| "arsip kosong dibuat".to_string())
    .map_err(|e| e.to_string());
    report_string(&mut env, result)
}

/// Extracts one entry (by index) to a destination file.
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeImgExtract(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    dir: JString,
    index: jint,
    dest: JString,
) -> jstring {
    let path_str = jstring_to_string(&mut env, &path);
    let dir_opt = opt_jstring_to_string(&mut env, &dir);
    let dest_str = jstring_to_string(&mut env, &dest);
    let result = (|| -> Result<String, String> {
        let archive = img_open_archive(&path_str, &dir_opt)?;
        let entry = img_entry_at(&archive, index)?.clone();
        let name = entry.clean_name().to_string();
        archive
            .extract(&entry, std::path::Path::new(&dest_str))
            .map_err(|e| e.to_string())?;
        Ok(format!("terekstrak {name}"))
    })();
    report_string(&mut env, result)
}

/// Adds a file to the archive. `entry_name` may be null (use the file name).
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeImgAdd(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    dir: JString,
    file_path: JString,
    entry_name: JString,
) -> jstring {
    let path_str = jstring_to_string(&mut env, &path);
    let dir_opt = opt_jstring_to_string(&mut env, &dir);
    let file_str = jstring_to_string(&mut env, &file_path);
    let name_opt = opt_jstring_to_string(&mut env, &entry_name);
    let result = img_op(&path_str, &dir_opt, |archive| {
        archive.add(std::path::Path::new(&file_str), name_opt.as_deref())?;
        Ok(format!("ditambahkan; total {} entri", archive.entries.len()))
    });
    report_string(&mut env, result)
}

/// Replaces one entry (by index) with a file's contents.
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeImgReplace(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    dir: JString,
    index: jint,
    file_path: JString,
) -> jstring {
    let path_str = jstring_to_string(&mut env, &path);
    let dir_opt = opt_jstring_to_string(&mut env, &dir);
    let file_str = jstring_to_string(&mut env, &file_path);
    let result = (|| -> Result<String, String> {
        let mut archive = img_open_archive(&path_str, &dir_opt)?;
        img_entry_at(&archive, index)?;
        archive
            .replace(index as usize, std::path::Path::new(&file_str))
            .map_err(|e| e.to_string())?;
        Ok("entri diganti".to_string())
    })();
    report_string(&mut env, result)
}

/// Deletes entries by index.
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeImgDelete(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    dir: JString,
    indices: JIntArray,
) -> jstring {
    let path_str = jstring_to_string(&mut env, &path);
    let dir_opt = opt_jstring_to_string(&mut env, &dir);
    let result = match int_array_to_vec(&mut env, &indices) {
        Some(values) => img_op(&path_str, &dir_opt, |archive| {
            let idx: Vec<usize> = values.iter().map(|&v| v as usize).collect();
            archive.delete(&idx)?;
            Ok(format!("terhapus; sisa {} entri", archive.entries.len()))
        }),
        None => Err("array indeks tidak valid".to_string()),
    };
    report_string(&mut env, result)
}

/// Renames one entry (by index).
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeImgRename(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    dir: JString,
    index: jint,
    new_name: JString,
) -> jstring {
    let path_str = jstring_to_string(&mut env, &path);
    let dir_opt = opt_jstring_to_string(&mut env, &dir);
    let name_str = jstring_to_string(&mut env, &new_name);
    let result = (|| -> Result<String, String> {
        let mut archive = img_open_archive(&path_str, &dir_opt)?;
        img_entry_at(&archive, index)?;
        archive
            .rename(index as usize, &name_str)
            .map_err(|e| e.to_string())?;
        Ok("entri diganti nama".to_string())
    })();
    report_string(&mut env, result)
}

/// Rebuilds (repacks) the archive in place and returns the post-rebuild
/// listing (same wire format as nativeImgList) so callers can refresh their
/// snapshot from this single open — no separate list call. On failure a
/// single "ERROR\t<message>" element is returned.
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeImgRebuild(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    dir: JString,
) -> jobjectArray {
    let path_str = jstring_to_string(&mut env, &path);
    let dir_opt = opt_jstring_to_string(&mut env, &dir);
    let items: Vec<String> = (|| -> Result<Vec<String>, String> {
        let mut archive = img_open_archive(&path_str, &dir_opt)?;
        archive.rebuild().map_err(|e| e.to_string())?;
        Ok(img_listing_lines(&archive))
    })()
    .unwrap_or_else(|e| vec![format!("ERROR\t{e}")]);
    string_array(&mut env, &items)
}

// ---------------------------------------------------------------------------
// TXD engine, texdb listing, and audio fixer (full-Rust migration). The
// Kotlin TxdParser/TxdBuilder/TexdbCache/OswIdxFixer are thin facades over
// these calls; all format logic lives in `txd.rs` / `texdb.rs` / `audio.rs`.
// ---------------------------------------------------------------------------

fn string_array(env: &mut JNIEnv, items: &[String]) -> jobjectArray {
    let string_class = match env.find_class("java/lang/String") {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };
    let array = match env.new_object_array(items.len() as jint, string_class, JObject::null()) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    for (i, item) in items.iter().enumerate() {
        let s = match env.new_string(item) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };
        if env.set_object_array_element(&array, i as jint, s).is_err() {
            return std::ptr::null_mut();
        }
    }
    array.into_raw()
}

fn int_arrays_to_images(
    env: &mut JNIEnv,
    pixels_list: &JObjectArray,
    widths: &JIntArray,
    heights: &JIntArray,
) -> Option<Vec<(Vec<u32>, usize, usize)>> {
    let count = env.get_array_length(pixels_list).ok()?;
    if count < 0 {
        return None;
    }
    let w = int_array_to_vec(env, widths)?;
    let h = int_array_to_vec(env, heights)?;
    if w.len() != count as usize || h.len() != count as usize {
        return None;
    }
    let mut images = Vec::with_capacity(count as usize);
    for i in 0..count {
        // Free the per-iteration local ref so large batch imports don't
        // overflow the JVM local-ref table.
        let Ok(obj) = env.get_object_array_element(pixels_list, i) else { continue };
        let jia = JIntArray::from(obj);
        let px = int_array_to_vec(env, &jia)?;
        let _ = env.delete_local_ref(jia);
        images.push((px, w[i as usize] as usize, h[i as usize] as usize));
    }
    Some(images)
}

fn txd_import_options(gen_mips: jboolean, mip_filter: jint, max_dimension: jint) -> txd::ImportOptions {
    txd::ImportOptions {
        generate_mipmaps: gen_mips != 0,
        mip_filter: txd::MipFilter::from_ordinal(mip_filter),
        max_dimension: if max_dimension > 0 { Some(max_dimension as usize) } else { None },
    }
}

fn txd_as_dxt1(mode: jint) -> Option<bool> {
    match mode {
        1 => Some(true),
        2 => Some(false),
        _ => None,
    }
}

/// Lists a TXD's textures: header "OK\t<rwVersion>\t<count>", then per
/// texture the wire line from `txd::list_lines`.
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeTxdList(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jobjectArray {
    let path_str = jstring_to_string(&mut env, &path);
    let items = match txd::parse_file(std::path::Path::new(&path_str)) {
        Ok(txd_file) => txd::list_lines(&txd_file),
        Err(e) => vec![format!("ERROR\t{e}")],
    };
    string_array(&mut env, &items)
}

/// Decoded level-0 ARGB pixels of one TXD texture. Uses the targeted
/// single-texture scan (chunk headers + only the requested entry's payload)
/// and falls back to the full parse for anything it cannot handle.
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeTxdTexturePixels(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    index: jint,
) -> jintArray {
    let path_str = jstring_to_string(&mut env, &path);
    let pixels = (|| -> Option<Vec<u32>> {
        if index < 0 {
            return None;
        }
        let path = std::path::Path::new(&path_str);
        if let Some(px) = txd::decode_entry_pixels_at(path, index as usize) {
            return Some(px);
        }
        let txd_file = txd::parse_file(path).ok()?;
        let entry = txd_file.textures.get(index as usize)?;
        txd::decode_pixels(entry)
    })();
    match pixels {
        Some(px) => vec_to_int_array(&mut env, &px),
        None => std::ptr::null_mut(),
    }
}

/// Batch-replaces TXD textures by index. asDxt1Mode: 0 preserve, 1 force
/// DXT1, 2 force raw. maxDimension 0 = no reduce.
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeTxdReplaceTextures(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    indices: JIntArray,
    pixels_list: JObjectArray,
    widths: JIntArray,
    heights: JIntArray,
    as_dxt1_mode: jint,
    gen_mips: jboolean,
    mip_filter: jint,
    max_dimension: jint,
) -> jstring {
    let path_str = jstring_to_string(&mut env, &path);
    let result = (|| -> Result<String, String> {
        let idx = int_array_to_vec(&mut env, &indices)
            .ok_or_else(|| "array indeks tidak valid".to_string())?;
        let idx: Vec<usize> = idx.iter().map(|&v| v as usize).collect();
        let images = int_arrays_to_images(&mut env, &pixels_list, &widths, &heights)
            .ok_or_else(|| "array gambar tidak valid".to_string())?;
        txd::replace_in_file(
            std::path::Path::new(&path_str),
            &idx,
            &images,
            txd_as_dxt1(as_dxt1_mode),
            &txd_import_options(gen_mips, mip_filter, max_dimension),
        )
    })();
    report_string(&mut env, result)
}

/// Batch-adds textures to a TXD. policy: 0 = JustAdd, 1 = Overwrite.
/// Returns "OK added=A overwritten=B total=T".
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeTxdAddTextures(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    names: JObjectArray,
    pixels_list: JObjectArray,
    widths: JIntArray,
    heights: JIntArray,
    policy: jint,
    gen_mips: jboolean,
    mip_filter: jint,
    max_dimension: jint,
) -> jstring {
    let path_str = jstring_to_string(&mut env, &path);
    let result = (|| -> Result<String, String> {
        let count = env
            .get_array_length(&names)
            .map_err(|_| "array nama tidak valid".to_string())?;
        if count < 0 {
            return Err("array nama tidak valid".to_string());
        }
        let mut name_vec: Vec<String> = Vec::with_capacity(count as usize);
        for i in 0..count {
            let obj = env
                .get_object_array_element(&names, i)
                .map_err(|_| "array nama tidak valid".to_string())?;
            // Delete the local ref each iteration; without this, a >512-texture
            // batch import overflows the JVM local-ref table.
            let js = JString::from(obj);
            name_vec.push(jstring_to_string(&mut env, &js));
            let _ = env.delete_local_ref(js);
        }
        let images = int_arrays_to_images(&mut env, &pixels_list, &widths, &heights)
            .ok_or_else(|| "array gambar tidak valid".to_string())?;
        if images.len() != name_vec.len() {
            return Err("Jumlah nama dan gambar tidak cocok".to_string());
        }
        let pol = if policy == 1 {
            txd::ConflictPolicy::Overwrite
        } else {
            txd::ConflictPolicy::JustAdd
        };
        let (added, overwritten) = txd::add_in_file(
            std::path::Path::new(&path_str),
            &name_vec,
            &images,
            pol,
            &txd_import_options(gen_mips, mip_filter, max_dimension),
        )?;
        let total = txd::parse_file(std::path::Path::new(&path_str))
            .map(|t| t.textures.len())
            .unwrap_or(0);
        Ok(format!("added={added} overwritten={overwritten} total={total}"))
    })();
    report_string(&mut env, result)
}

/// DXT1-encodes ARGB pixels (benchmark + tooling).
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeEncodeDxt1(
    mut env: JNIEnv,
    _class: JClass,
    pixels: JIntArray,
    width: jint,
    height: jint,
) -> jni::sys::jbyteArray {
    let px = match int_array_to_vec(&mut env, &pixels) {
        Some(v) => v,
        None => return std::ptr::null_mut(),
    };
    // Reject invalid dimensions before encode_dxt1 can allocate (mirrors
    // nativeDecodeDxt1 / nativeMipmapHalf). Without this a malicious jint
    // width/height can abort the process via OOM.
    if width <= 0 || height <= 0 || width > MAX_TEXTURE_DIM || height > MAX_TEXTURE_DIM {
        return std::ptr::null_mut();
    }
    let data = codec::dxt::encode_dxt1(&px, width as usize, height as usize);
    let out = match env.new_byte_array(data.len() as jint) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    let as_i8: Vec<jni::sys::jbyte> = data.iter().map(|&b| b as jni::sys::jbyte).collect();
    if env.set_byte_array_region(&out, 0, &as_i8).is_err() {
        return std::ptr::null_mut();
    }
    out.into_raw()
}

/// Full texdb listing for the browser: header, DEFAULTS line, then one
/// line per texture (see `texdb::describe_cache`).
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeTexdbList(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
) -> jobjectArray {
    let dir_str = jstring_to_string(&mut env, &dir);
    let items = match texdb::describe_cache(std::path::Path::new(&dir_str)) {
        Ok(desc) => {
            let mut v = vec![format!(
                "OK\t{}\t{}\t{}\t{}",
                desc.name,
                desc.variant,
                desc.variants.join(","),
                desc.dat_file_name
            )];
            let defaults = desc
                .defaults
                .iter()
                .map(|(k, val)| format!("{k}={val}"))
                .collect::<Vec<_>>()
                .join(";");
            v.push(format!("DEFAULTS\t{defaults}"));
            for t in &desc.textures {
                let flags = t
                    .flags
                    .iter()
                    .map(|(k, val)| format!("{k}={val}"))
                    .collect::<Vec<_>>()
                    .join(";");
                v.push(format!(
                    "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
                    t.name,
                    t.width,
                    t.height,
                    t.offset,
                    t.size,
                    t.hash as i32,
                    if t.has_mipmaps { 1 } else { 0 },
                    t.png_crc.as_deref().unwrap_or(""),
                    t.img_crc.as_deref().unwrap_or(""),
                    t.category,
                    flags
                ));
            }
            v
        }
        Err(e) => vec![format!("ERROR\t{e}")],
    };
    string_array(&mut env, &items)
}

/// Writes one texdb entry's raw .dat span (affiliate-aware) to a file.
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeTexdbExtractRaw(
    mut env: JNIEnv,
    _class: JClass,
    dir: JString,
    index: jint,
    dest: JString,
) -> jstring {
    let dir_str = jstring_to_string(&mut env, &dir);
    let dest_str = jstring_to_string(&mut env, &dest);
    // Negative index means "nothing selected"; reject instead of silently
    // extracting texture 0.
    if index < 0 {
        return report_string(&mut env, Err("indeks tidak valid".to_string()));
    }
    let result = texdb::extract_raw_span(
        std::path::Path::new(&dir_str),
        index as usize,
        std::path::Path::new(&dest_str),
    );
    report_string(&mut env, result)
}

/// Regenerates the `.idx` next to a GTA SA `.osw` audio archive.
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeAudioFixOsw(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let path_str = jstring_to_string(&mut env, &path);
    let result = audio::fix_file(std::path::Path::new(&path_str))
        .map(|(count, idx)| format!("{count} entri; indeks ditulis ke {}", idx.display()));
    report_string(&mut env, result)
}

/// Index entries of a `.osw` without writing: header + "name\toffset\tsize".
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeAudioComputeOsw(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jobjectArray {
    let path_str = jstring_to_string(&mut env, &path);
    let items = match std::fs::read(std::path::Path::new(&path_str))
        .map_err(|e| e.to_string())
        .and_then(|bytes| audio::compute_index(&bytes))
    {
        Ok(entries) => {
            let mut v = vec![format!("OK\t{}", entries.len())];
            for e in entries {
                v.push(format!("{}\t{}\t{}", e.name, e.data_offset, e.compressed_size));
            }
            v
        }
        Err(e) => vec![format!("ERROR\t{e}")],
    };
    string_array(&mut env, &items)
}

/// IDE definition index scan of one folder (recursive): wire lines of
/// `ide::wire_lines` (header + `M`/`T` lines), or a single
/// `ERROR\t<message>` element.
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeIdeScanDir(
    mut env: JNIEnv,
    _class: JClass,
    root: JString,
) -> jobjectArray {
    let root_str = jstring_to_string(&mut env, &root);
    let items = match ide::scan_dir(std::path::Path::new(&root_str)) {
        Ok(index) => ide::wire_lines(&index),
        Err(e) => vec![format!("ERROR\t{e}")],
    };
    string_array(&mut env, &items)
}

/// IDE definition index over an explicit list of .ide files, parsed in the
/// given order (duplicate model names keep the first id seen).
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeIdeScanPaths(
    mut env: JNIEnv,
    _class: JClass,
    paths: JObjectArray,
) -> jobjectArray {
    let count = env.get_array_length(&paths).unwrap_or(0);
    if count < 0 {
        return string_array(&mut env, &["ERROR\tarray path tidak valid".to_string()]);
    }
    let mut files: Vec<std::path::PathBuf> = Vec::with_capacity(count as usize);
    for i in 0..count {
        let Ok(obj) = env.get_object_array_element(&paths, i) else {
            continue;
        };
        // Delete the local ref each iteration; prevents JVM local-ref table
        // overflow on large .ide scan lists.
        let js = JString::from(obj);
        files.push(std::path::PathBuf::from(jstring_to_string(&mut env, &js)));
        let _ = env.delete_local_ref(js);
    }
    let index = ide::parse_files(&files);
    string_array(&mut env, &ide::wire_lines(&index))
}

/// Data-line merge plan for mod data files (handling.cfg / vehicles.ide /
/// carcols.dat). Returns the wire block of `datalines::plan_wire`
/// ("OK\t<changed>\t<fileLineCount>\t<sectioned>\t<notesLineCount>\n
/// <notes><merged text>"); the merge itself is total (no error case).
#[no_mangle]
pub extern "system" fn Java_com_onlasdan_studio_core_NativeCore_nativeDataLinesPlan(
    mut env: JNIEnv,
    _class: JClass,
    file_text: JString,
    pasted_text: JString,
) -> jstring {
    let file = jstring_to_string(&mut env, &file_text);
    let pasted = jstring_to_string(&mut env, &pasted_text);
    let out = datalines::plan_wire(&file, &pasted);
    env.new_string(out)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

