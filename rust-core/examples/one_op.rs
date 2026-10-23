use imgtxd_core::texdb::*;
use std::path::PathBuf;

fn opts(conflict: ConflictPolicy) -> ImportOptions {
    ImportOptions {
        format: ImportFormat::CompressedAlpha,
        conflict,
        sibling_model: None,
        reduce_to: 2048,
        camnorm: true,
        hash_check: true,
        correct_caps: false,
        use_rle: true,
        generate_mipmaps: true,
        mip_filter: 1,
    }
}

fn main() {
    let op = std::env::args().nth(1).expect("op");
    let dir = PathBuf::from(std::env::args().nth(2).expect("dir"));
    let png = std::env::args().nth(3).map(|p| std::fs::read(p).unwrap());
    let result = match op.as_str() {
        "import_add" => import_textures(&dir, "txd", &[("zz_new_texture".to_string(), png.unwrap())], &opts(ConflictPolicy::JustAdd)),
        "import_overwrite" => import_textures(&dir, "txd", &[("gunshell".to_string(), png.unwrap())], &opts(ConflictPolicy::Overwrite)),
        "delete" => delete_textures(&dir, "txd", &[0]),
        "rebuild" => rebuild_cache(&dir, "txd"),
        "repack_etc" => repack_cache(&dir, "txd", 1),
        _ => panic!("unknown op"),
    };
    println!("{op}: {}", result.unwrap_or_else(|e| format!("ERR {e}")));
}
