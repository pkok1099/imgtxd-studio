//! DFF (RenderWare clump) inspection and texture renaming.
//!
//! The rename works on a parsed chunk tree: only the target STRING payload
//! and the size fields of its ancestor chunks change; every other byte is
//! preserved verbatim.

const CH_STRUCT: u32 = 0x01;
const CH_STRING: u32 = 0x02;
const CH_EXTENSION: u32 = 0x03;
const CH_TEXTURE: u32 = 0x06;
const CH_MATERIAL: u32 = 0x07;
const CH_MATERIAL_LIST: u32 = 0x08;
const CH_FRAME_LIST: u32 = 0x0E;
const CH_GEOMETRY: u32 = 0x0F;
const CH_CLUMP: u32 = 0x10;
const CH_ATOMIC: u32 = 0x14;
const CH_GEOMETRY_LIST: u32 = 0x1A;
const PLUGIN_SKIN: u32 = 0x0116;
const PLUGIN_HANIM: u32 = 0x011E;
const PLUGIN_2DFX: u32 = 0x0253_F2FE;
const PLUGIN_COLLISION: u32 = 0x0253_F253;

fn is_container(ty: u32) -> bool {
    matches!(
        ty,
        CH_EXTENSION
            | CH_TEXTURE
            | CH_MATERIAL
            | CH_MATERIAL_LIST
            | CH_FRAME_LIST
            | CH_GEOMETRY
            | CH_CLUMP
            | CH_ATOMIC
            | CH_GEOMETRY_LIST
    )
}

fn u32le(data: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(data[at..at + 4].try_into().unwrap())
}

/// Friendly RW version from a chunk library id (GTA-era values 3.1–3.7).
fn rw_version_name(lib: u32) -> String {
    if lib & 0xFFFF_0000 != 0 {
        let minor = ((lib >> 14) & 0x3FF00) >> 12;
        let build = (lib >> 16) & 0xFF;
        format!("3.{minor}.0.{build}")
    } else {
        format!("mentah 0x{lib:08x}")
    }
}

#[derive(Default)]
struct Stats {
    version_lib: u32,
    clumps: usize,
    frames: usize,
    geometries: usize,
    vertices: u64,
    triangles: u64,
    materials: usize,
    atomics: usize,
    textures: Vec<String>,
    skin: usize,
    hanim: usize,
    effects_2dfx: usize,
    collision: usize,
}

/// Maximum nesting depth for `walk` / `parse_node`. Real DFFs nest ≤ 6 levels
/// (CLUMP → FRAME_LIST/GEOMETRY → MATERIAL → TEXTURE → STRING). 32 is well
/// above any legitimate file while still preventing a 10 000-deep malicious
/// stack from blowing the native stack (the depth cap in texdb's
/// `dff_texture_names` is 12; we use 32 here to be conservative).
const MAX_DFF_DEPTH: usize = 32;

fn walk(data: &[u8], start: usize, end: usize, stats: &mut Stats) {
    walk_inner(data, start, end, stats, 0)
}

fn walk_inner(data: &[u8], start: usize, end: usize, stats: &mut Stats, depth: usize) {
    if depth > MAX_DFF_DEPTH {
        return;
    }
    let mut off = start;
    while off + 12 <= end {
        let ty = u32le(data, off);
        let size = u32le(data, off + 4) as usize;
        let lib = u32le(data, off + 8);
        if stats.version_lib == 0 {
            stats.version_lib = lib;
        }
        let body = off + 12;
        let chunk_end = (body + size).min(end);
        match ty {
            CH_CLUMP => stats.clumps += 1,
            CH_ATOMIC => stats.atomics += 1,
            CH_MATERIAL => stats.materials += 1,
            PLUGIN_SKIN => stats.skin += 1,
            PLUGIN_HANIM => stats.hanim += 1,
            PLUGIN_2DFX => stats.effects_2dfx += 1,
            PLUGIN_COLLISION => stats.collision += 1,
            CH_FRAME_LIST => {
                // First child is a STRUCT whose first u32 is the frame count.
                if body + 16 <= chunk_end && u32le(data, body) == CH_STRUCT {
                    stats.frames += u32le(data, body + 12) as usize;
                }
            }
            CH_GEOMETRY => {
                stats.geometries += 1;
                // STRUCT child: flags, triangleCount, vertexCount, morphs.
                if body + 28 <= chunk_end && u32le(data, body) == CH_STRUCT {
                    stats.triangles += u32le(data, body + 16) as u64;
                    stats.vertices += u32le(data, body + 20) as u64;
                }
            }
            CH_TEXTURE => {
                // First STRING child is the texture name.
                let mut inner = body;
                while inner + 12 <= chunk_end {
                    let ity = u32le(data, inner);
                    let isize = u32le(data, inner + 4) as usize;
                    if ity == CH_STRING {
                        let raw = &data[inner + 12..(inner + 12 + isize).min(chunk_end)];
                        let name = String::from_utf8_lossy(raw)
                            .trim_end_matches('\0')
                            .to_string();
                        if !name.is_empty() {
                            stats.textures.push(name);
                        }
                        break;
                    }
                    inner += 12 + isize;
                }
            }
            _ => {}
        }
        if is_container(ty) && size > 0 {
            walk_inner(data, body, chunk_end, stats, depth + 1);
        }
        if size == 0 && !is_container(ty) {
            break;
        }
        off = body + size;
    }
}

/// Builds a human-readable (Indonesian) inspection report for a DFF file.
pub fn inspect_dff(data: &[u8]) -> String {
    let mut stats = Stats::default();
    walk(data, 0, data.len(), &mut stats);
    let mut out = String::new();
    out.push_str(&format!("Ukuran berkas: {} byte\n", data.len()));
    out.push_str(&format!(
        "Versi RenderWare: {} (library 0x{:08x})\n",
        rw_version_name(stats.version_lib),
        stats.version_lib
    ));
    out.push_str(&format!("Clump: {}\n", stats.clumps));
    out.push_str(&format!("Frame: {}\n", stats.frames));
    out.push_str(&format!(
        "Geometri: {} ({} vertex, {} segitiga)\n",
        stats.geometries, stats.vertices, stats.triangles
    ));
    out.push_str(&format!("Material: {}\n", stats.materials));
    out.push_str(&format!("Atomic: {}\n", stats.atomics));
    out.push_str(&format!("Skin: {}\n", stats.skin));
    out.push_str(&format!("HAnim (tulang): {}\n", stats.hanim));
    out.push_str(&format!("Efek 2dfx: {}\n", stats.effects_2dfx));
    out.push_str(&format!("Collision tertanam: {}\n", stats.collision));
    out.push_str(&format!("Tekstur dirujuk: {}\n", stats.textures.len()));
    for name in &stats.textures {
        out.push_str(&format!("  - {name}\n"));
    }
    out
}

/// A parsed chunk: leaves keep their payload verbatim.
struct Node {
    ty: u32,
    lib: u32,
    payload: Vec<u8>,
    children: Vec<Node>,
}

fn parse_node(data: &[u8], off: usize) -> Option<(Node, usize)> {
    parse_node_inner(data, off, 0)
}

fn parse_node_inner(data: &[u8], off: usize, depth: usize) -> Option<(Node, usize)> {
    // Bail on deeply nested malicious DFFs — prevents stack overflow abort.
    // Real DFFs nest ≤ 6 levels; 32 is well above any legitimate file.
    if depth > MAX_DFF_DEPTH {
        return None;
    }
    if off + 12 > data.len() {
        return None;
    }
    let ty = u32le(data, off);
    let size = u32le(data, off + 4) as usize;
    let lib = u32le(data, off + 8);
    let body = off + 12;
    let end = (body + size).min(data.len());
    if is_container(ty) {
        let mut children = Vec::new();
        let mut inner = body;
        let mut ok = true;
        while inner < end {
            match parse_node_inner(data, inner, depth + 1) {
                Some((child, consumed)) => {
                    children.push(child);
                    inner += consumed;
                }
                None => {
                    ok = false;
                    break;
                }
            }
        }
        if ok && inner == end {
            return Some((
                Node {
                    ty,
                    lib,
                    payload: Vec::new(),
                    children,
                },
                12 + size,
            ));
        }
        // Fall through: treat as opaque leaf when children don't tile exactly.
    }
    Some((
        Node {
            ty,
            lib,
            payload: data[body..end].to_vec(),
            children: Vec::new(),
        },
        12 + size,
    ))
}

fn serialize(node: &Node, out: &mut Vec<u8>) {
    let mut body = Vec::new();
    body.extend_from_slice(&node.payload);
    for child in &node.children {
        serialize(child, &mut body);
    }
    out.extend_from_slice(&node.ty.to_le_bytes());
    out.extend_from_slice(&(body.len() as u32).to_le_bytes());
    out.extend_from_slice(&node.lib.to_le_bytes());
    out.extend_from_slice(&body);
}

fn string_text(payload: &[u8]) -> String {
    String::from_utf8_lossy(payload)
        .trim_end_matches('\0')
        .to_string()
}

/// Returns the names of all textures referenced by the DFF (sorted, deduped).
pub fn texture_names(data: &[u8]) -> Vec<String> {
    let mut stats = Stats::default();
    walk(data, 0, data.len(), &mut stats);
    stats.textures.sort();
    stats.textures.dedup();
    stats.textures
}

/// Renames one texture reference inside the DFF. Returns the new file bytes.
/// Only the STRING payload and ancestor chunk sizes change.
pub fn rename_texture(data: &[u8], old: &str, new: &str) -> Result<Vec<u8>, String> {
    let new = new.trim();
    if new.is_empty() || new.len() > 64 || new.contains('\0') {
        return Err("nama tekstur baru tidak valid".to_string());
    }
    // Parse top-level chunks. Real-world DFFs may carry trailing padding
    // (rumpo.dff is padded to a round size); unparseable tail bytes are
    // preserved verbatim at the end of the output.
    let mut nodes = Vec::new();
    let mut off = 0usize;
    while off + 12 <= data.len() {
        let ty = u32le(data, off);
        let size = u32le(data, off + 4);
        if ty == 0 && size == 0 {
            break; // padding
        }
        let (node, consumed) = parse_node(data, off).ok_or("struktur DFF tidak valid")?;
        if consumed == 0 {
            return Err("struktur DFF tidak valid".to_string());
        }
        nodes.push(node);
        off += consumed;
    }
    let tail_start = off;
    let mut renamed = 0usize;
    fn rename_in(node: &mut Node, old: &str, new: &str, renamed: &mut usize) {
        if node.ty == CH_TEXTURE {
            for child in node.children.iter_mut() {
                if child.ty == CH_STRING {
                    if string_text(&child.payload) == old {
                        // Real DFF string chunks are strlen+1 (verified on
                        // game files); ancestor sizes are recomputed anyway.
                        let mut payload = new.as_bytes().to_vec();
                        payload.push(0);
                        child.payload = payload;
                        *renamed += 1;
                    }
                    break; // only the first STRING is the texture name
                }
            }
        }
        for child in node.children.iter_mut() {
            rename_in(child, old, new, renamed);
        }
    }
    for node in nodes.iter_mut() {
        rename_in(node, old, new, &mut renamed);
    }
    if renamed == 0 {
        return Err(format!("tekstur '{old}' tidak ditemukan di DFF"));
    }
    let mut out = Vec::with_capacity(data.len());
    for node in &nodes {
        serialize(node, &mut out);
    }
    out.extend_from_slice(&data[tail_start..]);
    Ok(out)
}

/// File-level rename: keeps a pristine `<file>.bak` next to the original on
/// first use, then writes the renamed bytes back. Returns a report line.
pub fn rename_texture_in_file(path: &std::path::Path, old: &str, new: &str) -> Result<String, String> {
    let data = std::fs::read(path).map_err(|e| e.to_string())?;
    let out = rename_texture(&data, old, new)?;
    let bak = path.with_extension("dff.bak");
    if !bak.exists() {
        std::fs::write(&bak, &data).map_err(|e| e.to_string())?;
    }
    std::fs::write(path, &out).map_err(|e| e.to_string())?;
    Ok(format!("renamed={old}->{new};backup={}", bak.display()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn chunk(ty: u32, body: &[u8]) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&ty.to_le_bytes());
        v.extend_from_slice(&(body.len() as u32).to_le_bytes());
        v.extend_from_slice(&0x1803_FFFFu32.to_le_bytes());
        v.extend_from_slice(body);
        v
    }

    fn synthetic_dff() -> Vec<u8> {
        let name = chunk(CH_STRING, b"mytex\0");
        let mask = chunk(CH_STRING, b"\0");
        let struct_leaf = chunk(CH_STRUCT, &[0, 0, 0, 0, 0, 0, 0, 0]);
        let texture_body = [struct_leaf, name, mask].concat();
        let texture = chunk(CH_TEXTURE, &texture_body);
        let material_body = [chunk(CH_STRUCT, &[0; 16]), texture].concat();
        let material = chunk(CH_MATERIAL, &material_body);
        // geometry struct: flags, tris=3, verts=4, morphs=1
        let mut gstruct = Vec::new();
        for v in [0u32, 3, 4, 1] {
            gstruct.extend_from_slice(&v.to_le_bytes());
        }
        let geometry_body = [chunk(CH_STRUCT, &gstruct), chunk(CH_MATERIAL_LIST, &chunk(CH_STRUCT, &[1, 0, 0, 0])), material]
            .concat();
        let geometry = chunk(CH_GEOMETRY, &geometry_body);
        let clump_body = [chunk(CH_STRUCT, &[1, 0, 0, 0]), chunk(CH_GEOMETRY_LIST, &geometry)].concat();
        chunk(CH_CLUMP, &clump_body)
    }

    #[test]
    fn inspect_counts_synthetic() {
        let dff = synthetic_dff();
        let report = inspect_dff(&dff);
        assert!(report.contains("3.6.0.3"), "{report}");
        assert!(report.contains("Geometri: 1 (4 vertex, 3 segitiga)"), "{report}");
        assert!(report.contains("Tekstur dirujuk: 1"), "{report}");
        assert!(report.contains("mytex"), "{report}");
    }

    #[test]
    fn rename_changes_only_sizes_and_string() {
        let dff = synthetic_dff();
        let out = rename_texture(&dff, "mytex", "yourtex").unwrap();
        // New name is longer by 2: total file grows by exactly 2.
        assert_eq!(out.len(), dff.len() + 2);
        assert!(texture_names(&out) == vec!["yourtex".to_string()]);
        // Renaming back to a shorter name restores the original bytes.
        let back = rename_texture(&out, "yourtex", "mytex").unwrap();
        assert_eq!(back, dff);
        assert!(rename_texture(&dff, "nope", "x").is_err());
    }
}
