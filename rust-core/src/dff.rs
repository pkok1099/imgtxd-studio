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
const PLUGIN_BINMESH: u32 = 0x050E;
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
pub fn rename_texture_in_file(
    path: &std::path::Path,
    old: &str,
    new: &str,
) -> Result<String, String> {
    let data = std::fs::read(path).map_err(|e| e.to_string())?;
    let out = rename_texture(&data, old, new)?;
    let bak = path.with_extension("dff.bak");
    if !bak.exists() {
        std::fs::write(&bak, &data).map_err(|e| e.to_string())?;
    }
    // Atomic write-back: the .bak preserves the original, and temp+rename
    // guarantees the target is either the old or the new file — never a
    // truncated DFF from a kill/power loss mid-write.
    crate::fsutil::write_atomic(path, &out).map_err(|e| e.to_string())?;
    Ok(format!("renamed={old}->{new};backup={}", bak.display()))
}

/// 3D Mesh payload extracted from DFF for OpenGL ES rendering.
#[derive(Default, Debug)]
pub struct DffMesh {
    /// Interleaved [x, y, z, nx, ny, nz, u, v] per vertex.
    pub vertex_buffer: Vec<f32>,
    /// Triangle indices (0-based) referencing vertices in vertex_buffer.
    pub index_buffer: Vec<u32>,
    /// Bounding box: [min_x, min_y, min_z, max_x, max_y, max_z]
    pub bounds: [f32; 6],
    /// Texture names referenced by materials
    pub textures: Vec<String>,
}

#[derive(Default, Clone, Copy, Debug)]
struct RwMatrix {
    right: [f32; 3],
    up: [f32; 3],
    at: [f32; 3],
    pos: [f32; 3],
}

impl RwMatrix {
    fn identity() -> Self {
        Self {
            right: [1.0, 0.0, 0.0],
            up: [0.0, 1.0, 0.0],
            at: [0.0, 0.0, 1.0],
            pos: [0.0, 0.0, 0.0],
        }
    }

    fn multiply(&self, parent: &RwMatrix) -> RwMatrix {
        RwMatrix {
            right: [
                self.right[0] * parent.right[0] + self.right[1] * parent.up[0] + self.right[2] * parent.at[0],
                self.right[0] * parent.right[1] + self.right[1] * parent.up[1] + self.right[2] * parent.at[1],
                self.right[0] * parent.right[2] + self.right[1] * parent.up[2] + self.right[2] * parent.at[2],
            ],
            up: [
                self.up[0] * parent.right[0] + self.up[1] * parent.up[0] + self.up[2] * parent.at[0],
                self.up[0] * parent.right[1] + self.up[1] * parent.up[1] + self.up[2] * parent.at[1],
                self.up[0] * parent.right[2] + self.up[1] * parent.up[2] + self.up[2] * parent.at[2],
            ],
            at: [
                self.at[0] * parent.right[0] + self.at[1] * parent.up[0] + self.at[2] * parent.at[0],
                self.at[0] * parent.right[1] + self.at[1] * parent.up[1] + self.at[2] * parent.at[1],
                self.at[0] * parent.right[2] + self.at[1] * parent.up[2] + self.at[2] * parent.at[2],
            ],
            pos: [
                self.pos[0] * parent.right[0] + self.pos[1] * parent.up[0] + self.pos[2] * parent.at[0] + parent.pos[0],
                self.pos[0] * parent.right[1] + self.pos[1] * parent.up[1] + self.pos[2] * parent.at[1] + parent.pos[1],
                self.pos[0] * parent.right[2] + self.pos[1] * parent.up[2] + self.pos[2] * parent.at[2] + parent.pos[2],
            ],
        }
    }

    fn transform_point(&self, p: [f32; 3]) -> [f32; 3] {
        [
            p[0] * self.right[0] + p[1] * self.up[0] + p[2] * self.at[0] + self.pos[0],
            p[0] * self.right[1] + p[1] * self.up[1] + p[2] * self.at[1] + self.pos[1],
            p[0] * self.right[2] + p[1] * self.up[2] + p[2] * self.at[2] + self.pos[2],
        ]
    }

    fn transform_vector(&self, v: [f32; 3]) -> [f32; 3] {
        [
            v[0] * self.right[0] + v[1] * self.up[0] + v[2] * self.at[0],
            v[0] * self.right[1] + v[1] * self.up[1] + v[2] * self.at[1],
            v[0] * self.right[2] + v[1] * self.up[2] + v[2] * self.at[2],
        ]
    }
}

#[derive(Default, Debug)]
struct ParsedGeometry {
    positions: Vec<[f32; 3]>,
    normals: Vec<[f32; 3]>,
    uvs: Vec<[f32; 2]>,
    indices: Vec<u32>,
    textures: Vec<String>,
}

fn parse_binmesh_indices(payload: &[u8]) -> Option<Vec<u32>> {
    if payload.len() < 12 {
        return None;
    }
    let flags = u32le(payload, 0);
    let num_meshes = u32le(payload, 4) as usize;
    let _total_indices = u32le(payload, 8) as usize;
    let mut off = 12;
    let mut all_indices = Vec::new();

    for _ in 0..num_meshes {
        if off + 8 > payload.len() {
            break;
        }
        let num_idx = u32le(payload, off) as usize;
        let _mat_idx = u32le(payload, off + 4);
        off += 8;

        if off + num_idx * 4 > payload.len() {
            break;
        }
        let mut raw_indices = Vec::with_capacity(num_idx);
        for _ in 0..num_idx {
            raw_indices.push(u32le(payload, off));
            off += 4;
        }

        if flags == 1 {
            // Triangle Strip -> Triangle List conversion
            if raw_indices.len() >= 3 {
                for i in 0..raw_indices.len() - 2 {
                    let a = raw_indices[i];
                    let b = raw_indices[i + 1];
                    let c = raw_indices[i + 2];
                    if a == b || b == c || a == c {
                        continue; // skip degenerate triangles
                    }
                    if i % 2 == 0 {
                        all_indices.push(a);
                        all_indices.push(b);
                        all_indices.push(c);
                    } else {
                        all_indices.push(b);
                        all_indices.push(a);
                        all_indices.push(c);
                    }
                }
            }
        } else {
            // Triangle List
            all_indices.extend(raw_indices);
        }
    }

    if all_indices.is_empty() {
        None
    } else {
        Some(all_indices)
    }
}

fn parse_geometry_node(node: &Node) -> Option<ParsedGeometry> {
    let mut struct_payload = None;
    let mut binmesh_indices = None;
    let mut textures = Vec::new();

    for child in &node.children {
        match child.ty {
            CH_STRUCT => {
                struct_payload = Some(&child.payload);
            }
            CH_MATERIAL_LIST => {
                for mat_child in &child.children {
                    if mat_child.ty == CH_MATERIAL {
                        for tex_child in &mat_child.children {
                            if tex_child.ty == CH_TEXTURE {
                                for str_child in &tex_child.children {
                                    if str_child.ty == CH_STRING {
                                        let name = string_text(&str_child.payload);
                                        if !name.is_empty() {
                                            textures.push(name);
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            CH_EXTENSION => {
                for ext_child in &child.children {
                    if ext_child.ty == PLUGIN_BINMESH {
                        if let Some(indices) = parse_binmesh_indices(&ext_child.payload) {
                            binmesh_indices = Some(indices);
                        }
                    }
                }
            }
            _ => {}
        }
    }

    let payload = struct_payload?;
    if payload.len() < 16 {
        return None;
    }

    let format_flags = u32le(payload, 0);
    let num_triangles = u32le(payload, 4) as usize;
    let num_vertices = u32le(payload, 8) as usize;
    let num_morph_targets = u32le(payload, 12) as usize;

    if num_vertices == 0 || num_morph_targets == 0 {
        return None;
    }

    let has_normals = (format_flags & 0x10) != 0;
    let has_prelit = (format_flags & 0x08) != 0;
    let has_uv1 = (format_flags & 0x04) != 0;
    let num_uvs = if (format_flags & 0x80) != 0 {
        2
    } else if (format_flags & 0x00FF_0000) != 0 {
        ((format_flags >> 16) & 0xFF) as usize
    } else if has_uv1 {
        1
    } else {
        0
    };

    let mut off = 16;
    if (format_flags & 0x0100_0000) != 0 {
        // Native geometry: format is hardware-specific, standard parsing cannot safely read
        return None;
    }

    if has_prelit {
        off += num_vertices * 4;
    }

    let mut uvs = Vec::with_capacity(num_vertices);
    if num_uvs > 0 && off + num_vertices * 8 <= payload.len() {
        for _ in 0..num_vertices {
            let u = f32::from_le_bytes(payload[off..off + 4].try_into().unwrap());
            let v = f32::from_le_bytes(payload[off + 4..off + 8].try_into().unwrap());
            uvs.push([u, v]);
            off += 8;
        }
        // Skip remaining UV channels if any
        if num_uvs > 1 {
            off += (num_uvs - 1) * num_vertices * 8;
        }
    } else {
        uvs.resize(num_vertices, [0.0, 0.0]);
    }

    let mut fallback_indices = Vec::with_capacity(num_triangles * 3);
    if off + num_triangles * 8 <= payload.len() {
        for _ in 0..num_triangles {
            let v2 = u16::from_le_bytes(payload[off..off + 2].try_into().unwrap()) as u32;
            let v1 = u16::from_le_bytes(payload[off + 2..off + 4].try_into().unwrap()) as u32;
            let _mat = u16::from_le_bytes(payload[off + 4..off + 6].try_into().unwrap());
            let v3 = u16::from_le_bytes(payload[off + 6..off + 8].try_into().unwrap()) as u32;
            fallback_indices.push(v1);
            fallback_indices.push(v2);
            fallback_indices.push(v3);
            off += 8;
        }
    }

    // Morph target 0: bounding sphere (16 bytes) + has_pos(4) + has_normals(4)
    if off + 24 > payload.len() {
        return None;
    }
    // Skip sphere (16) + has_pos(4) + has_normals(4)
    off += 24;

    let mut positions = Vec::with_capacity(num_vertices);
    if off + num_vertices * 12 <= payload.len() {
        for _ in 0..num_vertices {
            let x = f32::from_le_bytes(payload[off..off + 4].try_into().unwrap());
            let y = f32::from_le_bytes(payload[off + 4..off + 8].try_into().unwrap());
            let z = f32::from_le_bytes(payload[off + 8..off + 12].try_into().unwrap());
            positions.push([x, y, z]);
            off += 12;
        }
    } else {
        return None;
    }

    let mut normals = Vec::with_capacity(num_vertices);
    if has_normals && off + num_vertices * 12 <= payload.len() {
        for _ in 0..num_vertices {
            let nx = f32::from_le_bytes(payload[off..off + 4].try_into().unwrap());
            let ny = f32::from_le_bytes(payload[off + 4..off + 8].try_into().unwrap());
            let nz = f32::from_le_bytes(payload[off + 8..off + 12].try_into().unwrap());
            normals.push([nx, ny, nz]);
            off += 12;
        }
    } else {
        // Fallback normals if missing: default up (0, 0, 1)
        normals.resize(num_vertices, [0.0, 0.0, 1.0]);
    }

    let indices = binmesh_indices.unwrap_or(fallback_indices);

    Some(ParsedGeometry {
        positions,
        normals,
        uvs,
        indices,
        textures,
    })
}

/// Parses the full 3D model mesh from DFF bytes.
pub fn parse_dff_mesh(data: &[u8]) -> Option<DffMesh> {
    let mut nodes = Vec::new();
    let mut off = 0usize;
    while off + 12 <= data.len() {
        let ty = u32le(data, off);
        let size = u32le(data, off + 4);
        if ty == 0 && size == 0 {
            break;
        }
        let (node, consumed) = parse_node(data, off)?;
        if consumed == 0 {
            break;
        }
        nodes.push(node);
        off += consumed;
    }

    // 1. Find Clump node
    let clump = nodes.iter().find(|n| n.ty == CH_CLUMP)?;

    // 2. Parse Frame List to compute world transformation per frame
    let mut frames_matrices: Vec<RwMatrix> = Vec::new();
    let mut frames_parent: Vec<i32> = Vec::new();

    for child in &clump.children {
        if child.ty == CH_FRAME_LIST {
            for fl_child in &child.children {
                if fl_child.ty == CH_STRUCT {
                    let p = &fl_child.payload;
                    if p.len() >= 4 {
                        let count = u32le(p, 0) as usize;
                        let mut off = 4;
                        for _ in 0..count {
                            if off + 56 > p.len() {
                                break;
                            }
                            let r = [
                                f32::from_le_bytes(p[off..off + 4].try_into().unwrap()),
                                f32::from_le_bytes(p[off + 4..off + 8].try_into().unwrap()),
                                f32::from_le_bytes(p[off + 8..off + 12].try_into().unwrap()),
                            ];
                            let u = [
                                f32::from_le_bytes(p[off + 12..off + 16].try_into().unwrap()),
                                f32::from_le_bytes(p[off + 16..off + 20].try_into().unwrap()),
                                f32::from_le_bytes(p[off + 20..off + 24].try_into().unwrap()),
                            ];
                            let a = [
                                f32::from_le_bytes(p[off + 24..off + 28].try_into().unwrap()),
                                f32::from_le_bytes(p[off + 28..off + 32].try_into().unwrap()),
                                f32::from_le_bytes(p[off + 32..off + 36].try_into().unwrap()),
                            ];
                            let pos = [
                                f32::from_le_bytes(p[off + 36..off + 40].try_into().unwrap()),
                                f32::from_le_bytes(p[off + 40..off + 44].try_into().unwrap()),
                                f32::from_le_bytes(p[off + 44..off + 48].try_into().unwrap()),
                            ];
                            let parent = i32::from_le_bytes(p[off + 48..off + 52].try_into().unwrap());
                            // p[off + 52..off + 56] is flags
                            off += 56;

                            frames_matrices.push(RwMatrix { right: r, up: u, at: a, pos });
                            frames_parent.push(parent);
                        }
                    }
                }
            }
        }
    }

    // Resolve hierarchical world matrices for each frame
    let mut world_matrices = vec![RwMatrix::identity(); frames_matrices.len()];
    for i in 0..frames_matrices.len() {
        let mut curr = frames_matrices[i];
        let mut p_idx = frames_parent[i];
        let mut depth = 0;
        while p_idx >= 0 && (p_idx as usize) < frames_matrices.len() && depth < 32 {
            curr = curr.multiply(&frames_matrices[p_idx as usize]);
            p_idx = frames_parent[p_idx as usize];
            depth += 1;
        }
        world_matrices[i] = curr;
    }

    // 3. Parse Geometry List
    let mut geometries: Vec<ParsedGeometry> = Vec::new();
    for child in &clump.children {
        if child.ty == CH_GEOMETRY_LIST {
            for gl_child in &child.children {
                if gl_child.ty == CH_GEOMETRY {
                    if let Some(geo) = parse_geometry_node(gl_child) {
                        geometries.push(geo);
                    }
                }
            }
        } else if child.ty == CH_GEOMETRY {
            if let Some(geo) = parse_geometry_node(child) {
                geometries.push(geo);
            }
        }
    }

    // 4. Parse Atomics linking Frames to Geometries
    let mut atomics: Vec<(usize, usize)> = Vec::new(); // (frame_idx, geometry_idx)
    for child in &clump.children {
        if child.ty == CH_ATOMIC {
            for at_child in &child.children {
                if at_child.ty == CH_STRUCT && at_child.payload.len() >= 8 {
                    let frame_idx = u32le(&at_child.payload, 0) as usize;
                    let geo_idx = u32le(&at_child.payload, 4) as usize;
                    atomics.push((frame_idx, geo_idx));
                }
            }
        }
    }

    // If no explicit atomic list found, pair geometry 0 with frame 0
    if atomics.is_empty() && !geometries.is_empty() {
        for (g_idx, _) in geometries.iter().enumerate() {
            atomics.push((0, g_idx));
        }
    }

    let mut vertex_buffer = Vec::new();
    let mut index_buffer = Vec::new();
    let mut all_textures = Vec::new();
    let mut vertex_base: u32 = 0;

    let mut min_x = f32::MAX;
    let mut min_y = f32::MAX;
    let mut min_z = f32::MAX;
    let mut max_x = f32::MIN;
    let mut max_y = f32::MIN;
    let mut max_z = f32::MIN;

    for (frame_idx, geo_idx) in atomics {
        if geo_idx >= geometries.len() {
            continue;
        }
        let geo = &geometries[geo_idx];
        let mat = if frame_idx < world_matrices.len() {
            world_matrices[frame_idx]
        } else {
            RwMatrix::identity()
        };

        for tex in &geo.textures {
            if !all_textures.contains(tex) {
                all_textures.push(tex.clone());
            }
        }

        let num_verts = geo.positions.len();
        for i in 0..num_verts {
            let wp = mat.transform_point(geo.positions[i]);
            let wn = mat.transform_vector(geo.normals[i]);
            let uv = geo.uvs[i];

            min_x = min_x.min(wp[0]);
            min_y = min_y.min(wp[1]);
            min_z = min_z.min(wp[2]);
            max_x = max_x.max(wp[0]);
            max_y = max_y.max(wp[1]);
            max_z = max_z.max(wp[2]);

            vertex_buffer.extend_from_slice(&[
                wp[0], wp[1], wp[2],
                wn[0], wn[1], wn[2],
                uv[0], uv[1],
            ]);
        }

        for &idx in &geo.indices {
            index_buffer.push(vertex_base + idx);
        }

        vertex_base += num_verts as u32;
    }

    if vertex_buffer.is_empty() || index_buffer.is_empty() {
        return None;
    }

    Some(DffMesh {
        vertex_buffer,
        index_buffer,
        bounds: [min_x, min_y, min_z, max_x, max_y, max_z],
        textures: all_textures,
    })
}

/// Serializes DffMesh into a compact binary packet for JNI:
/// [Header: 4x u32 (num_vertices, num_indices, bounds[6] floats, textures_count)]
/// + [Float array: vertex_buffer (num_vertices * 8 floats)]
/// + [U32 array: index_buffer (num_indices * 4 bytes)]
/// + [Textures: string bytes]
pub fn mesh_to_bytes(mesh: &DffMesh) -> Vec<u8> {
    let mut out = Vec::new();
    let num_verts = (mesh.vertex_buffer.len() / 8) as u32;
    let num_indices = mesh.index_buffer.len() as u32;

    out.extend_from_slice(&num_verts.to_le_bytes());
    out.extend_from_slice(&num_indices.to_le_bytes());

    for b in mesh.bounds {
        out.extend_from_slice(&b.to_le_bytes());
    }

    out.extend_from_slice(&(mesh.textures.len() as u32).to_le_bytes());
    for tex in &mesh.textures {
        let b = tex.as_bytes();
        out.extend_from_slice(&(b.len() as u32).to_le_bytes());
        out.extend_from_slice(b);
    }

    for f in &mesh.vertex_buffer {
        out.extend_from_slice(&f.to_le_bytes());
    }

    for idx in &mesh.index_buffer {
        out.extend_from_slice(&idx.to_le_bytes());
    }

    out
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
        let geometry_body = [
            chunk(CH_STRUCT, &gstruct),
            chunk(CH_MATERIAL_LIST, &chunk(CH_STRUCT, &[1, 0, 0, 0])),
            material,
        ]
        .concat();
        let geometry = chunk(CH_GEOMETRY, &geometry_body);
        let clump_body = [
            chunk(CH_STRUCT, &[1, 0, 0, 0]),
            chunk(CH_GEOMETRY_LIST, &geometry),
        ]
        .concat();
        chunk(CH_CLUMP, &clump_body)
    }

    #[test]
    fn inspect_counts_synthetic() {
        let dff = synthetic_dff();
        let report = inspect_dff(&dff);
        assert!(report.contains("3.6.0.3"), "{report}");
        assert!(
            report.contains("Geometri: 1 (4 vertex, 3 segitiga)"),
            "{report}"
        );
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

    #[test]
    fn parse_mesh_test_cube() {
        if let Ok(data) = std::fs::read("/tmp/test_cube.dff") {
            let mesh = parse_dff_mesh(&data).expect("Should parse test_cube.dff");
            assert_eq!(mesh.vertex_buffer.len(), 4 * 8); // 4 vertices * 8 floats
            assert_eq!(mesh.index_buffer.len(), 2 * 3); // 2 triangles * 3 indices
            assert_eq!(mesh.textures, vec!["test_tex".to_string()]);
            let serialized = mesh_to_bytes(&mesh);
            assert!(!serialized.is_empty());
        }
    }
}
