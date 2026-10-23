//! game_check — validator artefak ImgTxd Studio memakai aturan loader
//! ASLI GTA SA Android 2.00 (libGTASA.so), sesuai
//! ~/workspace/gta-re/GAME-LOADER-RULES.md (B1..B6, perilaku lunak (c),
//! dan daftar "tidak dicek" (d)). Sengaja BUKAN aturan TXD Tool:
//! toc[0] mismatch = fallback game (bukan gagal), kegagalan .tmb ditelan
//! game (WARN), png=/img= diabaikan, encoding tak dikenal = WARN.
//!
//! Pemakaian:
//!   game_check cache <folder-cache-texdb>
//!   game_check txd <file.txd>
//! Exit code: 0 bila verdict != FAIL, 1 bila FAIL.
//!
//! Decode versi chunk meniru persis _rwStreamReadChunkHeader game
//! (0x1e1f74, terverifikasi dari disassembly libGTASA.so 2.00):
//! stamp mentah 0x1803FFFF ter-decode menjadi 0x36003 (RW 3.6.0.3),
//! lalu dibandingkan dengan jendela keras B6: 0x34000..=0x36003.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

// ---------------------------------------------------------------------------
// Temuan
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug)]
enum Level {
    Pass,
    Warn,
    Fail,
}

impl Level {
    fn label(self) -> &'static str {
        match self {
            Level::Pass => "PASS",
            Level::Warn => "WARN",
            Level::Fail => "FAIL",
        }
    }
}

#[derive(Default)]
struct Findings {
    list: Vec<(Level, String)>,
}

impl Findings {
    fn add(&mut self, level: Level, msg: impl Into<String>) {
        self.list.push((level, msg.into()));
    }
    fn pass(&mut self, msg: impl Into<String>) {
        self.add(Level::Pass, msg);
    }
    fn warn(&mut self, msg: impl Into<String>) {
        self.add(Level::Warn, msg);
    }
    fn fail(&mut self, msg: impl Into<String>) {
        self.add(Level::Fail, msg);
    }
    fn verdict(&self) -> Level {
        self.list.iter().map(|(l, _)| *l).max().unwrap_or(Level::Pass)
    }
    /// Gabung temuan satu keluarga varian. Untuk keluarga yang bukan format
    /// aktif game, FAIL diturunkan menjadi WARN (game tidak pernah membuka
    /// keluarga itu pada konfigurasi perangkat ini).
    fn merge_family(&mut self, other: Findings, family: &str, active: bool) {
        for (level, msg) in other.list {
            let level = if !active && level == Level::Fail {
                Level::Warn
            } else {
                level
            };
            let suffix = if !active && level == Level::Warn {
                " [keluarga bukan format aktif game]"
            } else {
                ""
            };
            self.add(level, format!("[{family}] {msg}{suffix}"));
        }
    }
    fn print(&self, title: &str) {
        println!("== {title} ==");
        for (level, msg) in &self.list {
            println!("{} {msg}", level.label());
        }
        println!("VERDICT: {}", self.verdict().label());
    }
}

// ---------------------------------------------------------------------------
// Versi RenderWare (decode persis _rwStreamReadChunkHeader 0x1e1f74)
// ---------------------------------------------------------------------------

fn rw_decode_version(stamp: u32) -> u32 {
    if stamp >> 16 == 0 {
        // Jalur berkas lama di asm: version = stamp << 8, build = 0.
        stamp << 8
    } else {
        // Jalur terpaket: ((0x3FF00 & (stamp >> 14)) + 0x30000) | ((stamp >> 16) & 0x3F)
        ((0x3FF00 & (stamp >> 14)).wrapping_add(0x30000)) | ((stamp >> 16) & 0x3F)
    }
}

fn version_in_window(stamp: u32) -> bool {
    (0x34000..=0x36003).contains(&rw_decode_version(stamp))
}

// ---------------------------------------------------------------------------
// TXD (B6)
// ---------------------------------------------------------------------------

fn u32le(data: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(data[at..at + 4].try_into().unwrap())
}

fn chunk_header(data: &[u8], pos: usize) -> (u32, u32, u32) {
    (u32le(data, pos), u32le(data, pos + 4), u32le(data, pos + 8))
}

/// Berjalan di level kamus: kumpulkan semua header chunk (pos, tipe,
/// panjang, stamp). Err bila satu chunk melewati batas kamus/file.
fn walk_chunks(
    data: &[u8],
    mut pos: usize,
    end: usize,
) -> Result<Vec<(usize, u32, u32, u32)>, String> {
    let mut out = Vec::new();
    while pos + 12 <= end {
        let (t, l, s) = chunk_header(data, pos);
        if pos + 12 + l as usize > end {
            return Err(format!(
                "chunk tipe 0x{t:02X} di offset {pos} menyatakan panjang {l} melewati akhir kamus"
            ));
        }
        out.push((pos, t, l, s));
        pos += 12 + l as usize;
    }
    if pos != end {
        return Err(format!(
            "sisa {} byte di akhir kamus tidak membentuk chunk utuh",
            end - pos
        ));
    }
    Ok(out)
}

/// Validasi isi satu chunk tekstur native: Struct dalamnya harus terparse
/// utuh (header 88 byte, palet, data level-0, seluruh level mip, dan ekor
/// ekstensi) persis di dalam batas chunk-nya — pendekatan "pembaca native
/// platform berhasil" dari B6 (aturan internal pembaca itu sendiri belum
/// dibedah, lihat pertanyaan terbuka f-5 di ruleset).
fn check_native(data: &[u8], pos: usize, len: u32) -> Result<String, String> {
    let start = pos + 12;
    let end = start + len as usize;
    if len < 12 {
        return Err("chunk tekstur lebih kecil dari header Struct dalamnya".into());
    }
    let (itype, ilen, _istamp) = chunk_header(data, start);
    if itype != 0x01 {
        return Err(format!(
            "chunk pertama di dalam tekstur native bukan Struct (0x01), melainkan 0x{itype:02X}"
        ));
    }
    let pstart = start + 12;
    let pend = pstart + ilen as usize;
    if pend > end {
        return Err("panjang Struct native melewati akhir chunk tekstur".into());
    }
    let p = &data[pstart..pend];
    if p.len() < 88 {
        return Err(format!("payload Struct native {} byte < 88 byte wajib", p.len()));
    }
    let name = {
        let raw = &p[8..40];
        let nul = raw.iter().position(|&b| b == 0).unwrap_or(raw.len());
        String::from_utf8_lossy(&raw[..nul]).into_owned()
    };
    let raster_format = u32le(p, 72);
    let depth = p[84];
    let mip_count = p[85] as usize;
    let mut cur = 88usize;
    if raster_format & 0x2000 != 0 || depth == 8 {
        cur += 1024;
        if cur > p.len() {
            return Err("palet 1024 byte melewati akhir Struct".into());
        }
    }
    if cur + 4 > p.len() {
        return Err("field ukuran data level-0 melewati akhir Struct".into());
    }
    let data_size = i32::from_le_bytes(p[cur..cur + 4].try_into().unwrap());
    if data_size < 0 {
        return Err("ukuran data level-0 negatif".into());
    }
    cur += 4 + data_size as usize;
    if cur > p.len() {
        return Err(format!(
            "data level-0 ({data_size} byte) melewati akhir Struct"
        ));
    }
    for level in 1..mip_count {
        if cur + 4 > p.len() {
            return Err(format!("ukuran level mip {level} melewati akhir Struct"));
        }
        let sz = i32::from_le_bytes(p[cur..cur + 4].try_into().unwrap());
        if sz < 0 {
            return Err(format!("ukuran level mip {level} negatif"));
        }
        cur += 4 + sz as usize;
        if cur > p.len() {
            return Err(format!("level mip {level} ({sz} byte) melewati akhir Struct"));
        }
    }
    // Ekor setelah Struct = chunk ekstensi; harus berupa chunk utuh.
    let mut epos = pend;
    while epos < end {
        if epos + 12 > end {
            return Err("sisa ekor tekstur < 12 byte, bukan chunk ekstensi utuh".into());
        }
        let (_et, el, _es) = chunk_header(data, epos);
        if epos + 12 + el as usize > end {
            return Err("chunk ekstensi tekstur melewati akhir chunk tekstur".into());
        }
        epos += 12 + el as usize;
    }
    Ok(name)
}

fn check_txd(path: &Path) -> Findings {
    let mut f = Findings::default();
    let data = match std::fs::read(path) {
        Ok(d) => d,
        Err(e) => {
            f.fail(format!("berkas tidak bisa dibaca: {e}"));
            return f;
        }
    };
    if data.len() < 12 {
        f.fail("B6: berkas lebih kecil dari satu header chunk (12 byte)");
        return f;
    }
    let (root_type, root_len, root_stamp) = chunk_header(&data, 0);
    if root_type != 0x16 {
        f.fail(format!(
            "bukan chunk TexDictionary (0x16); tipe chunk pertama 0x{root_type:02X}"
        ));
        return f;
    }
    if !version_in_window(root_stamp) {
        f.fail(format!(
            "B6: versi chunk TexDictionary 0x{root_stamp:08X} (ter-decode 0x{:05X}) di luar jendela 0x34000..=0x36003",
            rw_decode_version(root_stamp)
        ));
        return f;
    }
    f.pass(format!(
        "B6: versi TexDictionary ter-decode 0x{:05X} dalam jendela",
        rw_decode_version(root_stamp)
    ));
    let dict_end = 12 + root_len as usize;
    if dict_end > data.len() {
        f.fail(format!(
            "B6: panjang chunk TexDictionary ({root_len}) melewati EOF berkas ({}) — aliran terpotong",
            data.len()
        ));
        return f;
    }
    let chunks = match walk_chunks(&data, 12, dict_end) {
        Ok(c) => c,
        Err(e) => {
            f.fail(format!("B6: {e}"));
            return f;
        }
    };
    // Chunk pertama yang dicari game: Struct kamus (RwStreamFindChunk 0x01).
    let struct_idx = chunks.iter().position(|&(_, t, _, _)| t == 0x01);
    let Some(struct_idx) = struct_idx else {
        f.fail("B6: chunk Struct (0x01) kamus tidak ditemukan — RwTexDictionaryStreamRead mengembalikan NULL");
        return f;
    };
    let (spos, _st, slen, sstamp) = chunks[struct_idx];
    if !version_in_window(sstamp) {
        f.fail(format!(
            "B6: versi chunk Struct 0x{sstamp:08X} (ter-decode 0x{:05X}) di luar jendela",
            rw_decode_version(sstamp)
        ));
        return f;
    }
    if slen < 2 || spos + 12 + slen as usize > data.len() {
        f.fail("B6: payload Struct kamus terpotong; count tekstur tidak terbaca utuh");
        return f;
    }
    let count = u16::from_le_bytes(data[spos + 12..spos + 14].try_into().unwrap()) as usize;
    let natives: Vec<(usize, u32, u32)> = chunks[struct_idx + 1..]
        .iter()
        .filter(|&&(_, t, _, _)| t == 0x15)
        .map(|&(p, _, l, s)| (p, l, s))
        .collect();
    if natives.len() < count {
        f.fail(format!(
            "B6: Struct count={count} tetapi hanya {} chunk tekstur native (0x15) ditemukan — janji count adalah gerbang keras, TXD ditolak",
            natives.len()
        ));
        return f;
    }
    if natives.len() > count {
        f.fail(format!(
            "B6: Struct count={count} tetapi ada {} chunk tekstur native (0x15) — kelebihan chunk sama-sama menolak TXD",
            natives.len()
        ));
        return f;
    }
    for (i, &(npos, nlen, nstamp)) in natives.iter().enumerate() {
        if !version_in_window(nstamp) {
            f.fail(format!(
                "B6: versi chunk tekstur native #{} 0x{nstamp:08X} (ter-decode 0x{:05X}) di luar jendela",
                i + 1,
                rw_decode_version(nstamp)
            ));
            return f;
        }
        match check_native(&data, npos, nlen) {
            Ok(name) => {
                let _ = name;
            }
            Err(e) => {
                f.fail(format!(
                    "B6: tekstur native #{} tidak terparse utuh: {e}",
                    i + 1
                ));
                return f;
            }
        }
    }
    f.pass(format!(
        "B6: Struct count={count} == jumlah chunk tekstur native; semua tekstur terparse utuh"
    ));
    f
}

// ---------------------------------------------------------------------------
// texdb (B1..B5 + (c) + (d))
// ---------------------------------------------------------------------------

struct MEntry {
    name: String,
    affiliate: Option<String>,
}

/// Tokenisasi sadar-kuotip ala LoadEntry game (B4: token dalam tanda
/// kutip adalah satu token utuh, spasi di dalamnya ikut). Nama tekstur
/// nyata memang bisa mengandung spasi ("crazy paving"), dan target
/// affiliate ditulis "affiliate=crazy paving" — memecah per whitespace
/// (seperti parser engine) memotong target itu dan menghasilkan FAIL
/// palsu; validator mengikuti tokenisasi game.
fn quote_tokens(rest: &str) -> Vec<String> {
    let b = rest.as_bytes();
    let mut out = Vec::new();
    let mut i = 0usize;
    while i < b.len() {
        while i < b.len() && (b[i] as char).is_whitespace() {
            i += 1;
        }
        if i >= b.len() {
            break;
        }
        if b[i] == b'"' {
            let start = i + 1;
            let mut j = start;
            while j < b.len() && b[j] != b'"' {
                j += 1;
            }
            out.push(rest[start..j].to_string());
            i = (j + 1).min(b.len());
        } else {
            let start = i;
            while i < b.len() && !(b[i] as char).is_whitespace() {
                i += 1;
            }
            out.push(rest[start..i].to_string());
        }
    }
    out
}

/// Parser manifes minimal untuk validator (parse_manifest di texdb.rs
/// tidak diekspor): entri = baris diawali '"'; affiliate dari token
/// affiliate= dengan tokenisasi game di atas.
fn parse_manifest(text: &str) -> Vec<MEntry> {
    let mut out = Vec::new();
    for raw in text.lines() {
        let line = raw.trim();
        if !line.starts_with('"') {
            continue;
        }
        let Some(end) = line[1..].find('"') else { continue };
        let name = line[1..1 + end].to_string();
        let rest = &line[1 + end + 1..];
        let mut affiliate = None;
        for token in quote_tokens(rest) {
            if let Some((k, v)) = token.split_once('=') {
                if k == "affiliate" {
                    affiliate = Some(v.to_string());
                }
            }
        }
        out.push(MEntry { name, affiliate });
    }
    out
}

/// Ikuti rantai csize .dat (B2): untuk tiap entri non-affiliate, header
/// 12 byte harus terbaca dan offset + csize + 12 tidak boleh melewati EOF.
/// Mengembalikan offset tiap entri (None untuk affiliate).
fn walk_chain(dat: &[u8], entries: &[MEntry], be: bool) -> Result<Vec<Option<usize>>, String> {
    let mut offsets: Vec<Option<usize>> = vec![None; entries.len()];
    let mut off = 0usize;
    for (i, e) in entries.iter().enumerate() {
        if e.affiliate.is_some() {
            continue;
        }
        if off + 12 > dat.len() {
            return Err(format!(
                "entri {i} ('{}'): header listing di offset {off} melewati EOF .dat ({})",
                e.name,
                dat.len()
            ));
        }
        let raw = if be {
            u32::from_be_bytes(dat[off + 8..off + 12].try_into().unwrap())
        } else {
            u32::from_le_bytes(dat[off + 8..off + 12].try_into().unwrap())
        };
        if raw < 4 {
            return Err(format!("entri {i} ('{}'): csize={raw} < 4", e.name));
        }
        let span = raw as usize + 12;
        if off + span > dat.len() {
            return Err(format!(
                "entri {i} ('{}'): span csize+12={span} di offset {off} melewati EOF .dat ({})",
                e.name,
                dat.len()
            ));
        }
        offsets[i] = Some(off);
        off += span;
    }
    Ok(offsets)
}

/// Pemeriksaan .tmb ala LoadDataFile (B3) — semua temuan WARN karena
/// LoadThumbs membuang nilai kembali LoadDataFile (kegagalan ditelan,
/// database tetap jalan; yang hilang hanya thumbnail).
fn check_tmb(bytes: &[u8], entries: &[MEntry], f: &mut Findings) {
    let mut off = 0usize;
    let mut k = 0usize;
    for _e in entries.iter().filter(|e| e.affiliate.is_none()) {
        if off >= bytes.len() {
            f.warn(format!(
                "B3: .tmb habis sebelum listing non-affiliate ke-{k} — LoadDataFile gagal, ditelan game (thumbnail hilang)"
            ));
            return;
        }
        if bytes.len() - off < 8 || off + 16 > bytes.len() {
            f.warn(format!(
                "B3: sisa .tmb di listing ke-{k} < 16 byte header — LoadDataFile gagal, ditelan game (thumbnail hilang)"
            ));
            return;
        }
        let csize = u32le(bytes, off + 8);
        if csize < 4 {
            f.warn(format!(
                "B3: csize .tmb listing ke-{k} = {csize} < 4 — rantai thumbnail desinkron, ditelan game"
            ));
            return;
        }
        if off + 16 + (csize as usize - 4) > bytes.len() {
            f.warn(format!(
                "B3: payload .tmb listing ke-{k} melewati EOF — LoadDataFile gagal, ditelan game (thumbnail hilang)"
            ));
            return;
        }
        off += csize as usize + 12;
        k += 1;
    }
    if off != bytes.len() {
        f.warn(format!(
            "B3: .tmb terurai {k} listing memakai {off} dari {} byte — sisa tak pernah dibaca game (konsumsi posisional berhenti di jumlah manifes)",
            bytes.len()
        ));
    } else {
        f.pass(format!(
            "B3: .tmb terurai penuh ({k} listing non-affiliate, span csize+12)"
        ));
    }
}

/// Encoding yang dipetakan eksplisit oleh GetRQFormat/GetRLESegSize
/// game (d-3), ditambah 0x1401 (RGBA32 mentah — jalur baku .tmb/unc).
/// Di luar ini game mengembalikan format RQ 0 tanpa menolak.
const KNOWN_ENCODINGS: [u16; 15] = [
    0x83F0, 0x83F1, 0x83F2, 0x83F3, // DXT1/3/5 (+ satu varian)
    0x8C00, 0x8C01, 0x8C02, 0x8C03, // PVRTC
    0x8033, 0x8034, 0x8363, // RGB565(t)/RGBA4444/RGB565
    0x1909, 0x190A, // luminance / luminance-alpha
    0x8D64, // ETC1
    0x1401, // RGBA32 mentah
];

fn check_family(dir: &Path, name: &str, variant: &str, entries: &[MEntry]) -> Findings {
    let mut f = Findings::default();
    let dat_path = dir.join(format!("{name}.{variant}.dat"));
    let toc_path = dir.join(format!("{name}.{variant}.toc"));
    let tmb_path = dir.join(format!("{name}.{variant}.tmb"));
    let dat = match std::fs::read(&dat_path) {
        Ok(d) => d,
        Err(_) => {
            // Keluarga khusus .tmb saja (mis. unc): hanya cek thumbnail.
            if !toc_path.exists() && tmb_path.exists() {
                let tmb = std::fs::read(&tmb_path).unwrap_or_default();
                check_tmb(&tmb, entries, &mut f);
                return f;
            }
            f.fail(format!(
                "B1: {}.{variant}.dat tidak bisa dibuka — OS_FileOpen gagal membuat LoadDataOffsets return 0",
                name
            ));
            return f;
        }
    };

    // --- B2: rantai csize (gerbang keras jalur fallback + konsistensi span)
    let chain = match walk_chain(&dat, entries, false) {
        Ok(o) => {
            f.pass(format!(
                "B2: rantai csize .dat utuh sampai entri terakhir ({} tekstur non-affiliate)",
                o.iter().filter(|x| x.is_some()).count()
            ));
            Some((o, false))
        }
        Err(le_err) => match walk_chain(&dat, entries, true) {
            Ok(o) => {
                f.pass(format!(
                    "B2: rantai csize utuh dengan csize big-endian (mode byteswap flag==3 di LoadDataOffsets); rantai little-endian putus: {le_err}"
                ));
                Some((o, true))
            }
            Err(_) => {
                f.fail(format!("B2: rantai csize putus — {le_err}"));
                None
            }
        },
    };
    let read_csize = |at: usize, be: bool| -> u32 {
        if be {
            u32::from_be_bytes(dat[at..at + 4].try_into().unwrap())
        } else {
            u32le(&dat, at)
        }
    };

    // --- B1: pasangan .toc/.dat
    let toc = std::fs::read(&toc_path).ok();
    match &toc {
        None => {
            f.pass("B1: .toc tidak ada — game memakai jalur fallback pindai sekuensial .dat (B2)");
        }
        Some(t) if t.len() < 4 => {
            f.warn(format!(
                "B1: .toc hanya {} byte (< 4) — game tidak dapat membaca ukuran pembanding; perilaku tak terdefinisi rapi di ruleset",
                t.len()
            ));
        }
        Some(t) => {
            let stated = u32le(t, 0) as usize;
            if stated == dat.len() {
                f.pass(format!(
                    "B1: toc[0] == ukuran .dat ({}) — jalur normal",
                    dat.len()
                ));
                let want = 4 + 4 * entries.len();
                if t.len() < want {
                    f.warn(format!(
                        "B1/d-5: .toc memuat {} byte, kurang dari 4+4*{} offset yang dibaca game; sisa hasil baca tidak diperiksa game",
                        t.len(),
                        entries.len()
                    ));
                }
                // Offset dipakai verbatim (d-5), tetapi span yang ditunjuk
                // harus konsisten: LoadFullTexture membaca header di sana.
                let be = chain.as_ref().map(|(_, b)| *b).unwrap_or(false);
                for (i, e) in entries.iter().enumerate() {
                    if e.affiliate.is_some() {
                        continue; // slot affiliate tak pernah dipakai (GetEntry resolve ke target)
                    }
                    let slot = 4 + i * 4;
                    if slot + 4 > t.len() {
                        break;
                    }
                    let off = i32::from_le_bytes(t[slot..slot + 4].try_into().unwrap());
                    if off < 0 {
                        f.fail(format!(
                            "B1: offset .toc negatif ({off}) untuk tekstur non-affiliate '{e}' (entri {i})",
                            e = e.name
                        ));
                        continue;
                    }
                    let off = off as usize;
                    if off + 12 > dat.len() {
                        f.fail(format!(
                            "B5: offset .toc {off} untuk '{}' melewati EOF .dat — header listing tak terbaca saat streaming",
                            e.name
                        ));
                        continue;
                    }
                    let csize = read_csize(off + 8, be);
                    if csize < 4 || off + csize as usize + 12 > dat.len() {
                        f.fail(format!(
                            "B5: span '{}' dari offset .toc {off} (csize {csize}) melewati EOF .dat",
                            e.name
                        ));
                    }
                }
            } else {
                f.pass(format!(
                    "B1: toc[0]={stated} != ukuran .dat {} — BUKAN gagal: game menutup .toc dan beralih ke pindai sekuensial .dat (fallback B2)",
                    dat.len()
                ));
            }
        }
    }

    // --- d-3: encoding tak dikenal (WARN, game memetakan ke RQ 0)
    let scan_offsets: Vec<usize> = match &chain {
        Some((o, _)) => o.iter().filter_map(|x| *x).collect(),
        None => Vec::new(),
    };
    if !scan_offsets.is_empty() {
        // Keluarga big-endian (rantai menang via byteswap) menyimpan field
        // header listing big-endian juga — baca encoding searah keluarga.
        let chain_be = chain.as_ref().map(|(_, b)| *b).unwrap_or(false);
        let mut unknown: HashMap<u16, usize> = HashMap::new();
        for &off in &scan_offsets {
            let enc = if chain_be {
                u16::from_be_bytes(dat[off + 2..off + 4].try_into().unwrap())
            } else {
                u16::from_le_bytes(dat[off + 2..off + 4].try_into().unwrap())
            };
            if !KNOWN_ENCODINGS.contains(&enc) {
                *unknown.entry(enc).or_default() += 1;
            }
        }
        if !unknown.is_empty() {
            let mut parts: Vec<String> = unknown
                .iter()
                .map(|(e, n)| format!("0x{e:04X} x{n}"))
                .collect();
            parts.sort();
            f.warn(format!(
                "d-3: encoding tak dikenal GetRQFormat: {} — game memetakan ke RQ format 0 dan tetap jalan (bukan penolakan)",
                parts.join(", ")
            ));
        }
    }

    // --- B3: .tmb (selalu lunak)
    match std::fs::read(&tmb_path) {
        Ok(tmb) => check_tmb(&tmb, entries, &mut f),
        Err(_) => {
            f.warn(format!(
                "B3: {name}.{variant}.tmb tidak ada — LoadThumbs gagal ditelan game; database tetap jalan, thumbnail hilang"
            ));
        }
    }
    f
}

fn check_cache(dir: &Path) -> Findings {
    let mut f = Findings::default();
    let name = match imgtxd_core::texdb::resolve_cache_name(dir) {
        Ok(n) => n,
        Err(e) => {
            f.fail(format!("manifes tidak ter(resolve: {e}"));
            return f;
        }
    };
    let text = match std::fs::read_to_string(dir.join(format!("{name}.txt"))) {
        Ok(t) => t,
        Err(e) => {
            f.fail(format!("B4: {name}.txt tidak bisa dibuka game (LoadEntries gagal): {e}"));
            return f;
        }
    };
    let entries = parse_manifest(&text);
    if entries.is_empty() {
        f.warn("B4: manifes tidak memuat entri tekstur sama sekali");
    }

    // --- B4: affiliate harus resolvable by-name (GetEntry: hash + cari nama)
    let by_name: HashMap<&str, usize> = entries
        .iter()
        .enumerate()
        .map(|(i, e)| (e.name.as_str(), i))
        .collect();
    let lower: HashMap<String, &str> = entries
        .iter()
        .map(|e| (e.name.to_lowercase(), e.name.as_str()))
        .collect();
    for e in &entries {
        let Some(target) = &e.affiliate else { continue };
        // Ikuti rantai affiliate (maks beberapa hop, anti siklus).
        let mut cur = target.as_str();
        let mut hops = 0;
        loop {
            hops += 1;
            if hops > entries.len() + 1 {
                f.fail(format!(
                    "B4: rantai affiliate '{}' siklik/tak berujung di '{cur}'",
                    e.name
                ));
                break;
            }
            match by_name.get(cur) {
                Some(&j) => match &entries[j].affiliate {
                    Some(next) => cur = next.as_str(),
                    None => break, // target nyata ditemukan
                },
                None => {
                    if let Some(actual) = lower.get(&cur.to_lowercase()) {
                        f.fail(format!(
                            "B4: affiliate '{}' menunjuk '{cur}' — hanya cocok beda kapital dengan '{actual}'; hash nama game case-sensitive, GetEntry tidak menemukannya",
                            e.name
                        ));
                    } else {
                        f.fail(format!(
                            "B4: affiliate '{}' menunjuk '{cur}' yang tidak ada di manifes — GetEntry gagal resolve",
                            e.name
                        ));
                    }
                    break;
                }
            }
        }
    }

    // --- Keluarga varian
    let mut families: Vec<String> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(dir) {
        for entry in rd.flatten() {
            let fname = entry.file_name().to_string_lossy().into_owned();
            if let Some(rest) = fname.strip_prefix(&format!("{name}.")) {
                for ext in [".toc", ".dat", ".tmb"] {
                    if let Some(v) = rest.strip_suffix(ext) {
                        if !families.iter().any(|x| x == v) {
                            families.push(v.to_string());
                        }
                    }
                }
            }
        }
    }
    families.sort();
    if families.is_empty() {
        f.fail("B1: tidak ada berkas varian (.dat/.toc/.tmb) sama sekali");
        return f;
    }
    // Format aktif: urutan preferensi keluarga berkas-data di Android
    // (etc untuk GPU Mali dataset ini); keluarga lain tetap diperiksa
    // tetapi FAIL-nya diturunkan (game tak membukanya pada konfigurasi ini).
    let preference = ["etc", "dxt", "pvr", "360", "ps3", "unc"];
    let active = preference
        .iter()
        .find(|v| {
            families.iter().any(|x| x == *v) && dir.join(format!("{name}.{v}.dat")).exists()
        })
        .map(|v| v.to_string())
        .or_else(|| {
            families
                .iter()
                .find(|v| dir.join(format!("{name}.{v}.dat")).exists())
                .cloned()
        });
    match &active {
        Some(a) => f.pass(format!(
            "keluarga format aktif game: {a} (dari keluarga yang ada: {})",
            families.join(", ")
        )),
        None => {
            f.fail("B1: tidak ada keluarga varian dengan berkas .dat");
            return f;
        }
    }
    for v in &families {
        let fam = check_family(dir, &name, v, &entries);
        f.merge_family(fam, v, active.as_deref() == Some(v.as_str()));
    }
    f
}

// ---------------------------------------------------------------------------

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 3 {
        eprintln!("pemakaian: game_check <cache|txd> <path>");
        return ExitCode::from(2);
    }
    let path = PathBuf::from(&args[2]);
    let findings = match args[1].as_str() {
        "cache" => check_cache(&path),
        "txd" => check_txd(&path),
        _ => {
            eprintln!("mode tidak dikenal: {} (pakai cache|txd)", args[1]);
            return ExitCode::from(2);
        }
    };
    findings.print(&format!("{} {}", args[1], path.display()));
    if findings.verdict() == Level::Fail {
        ExitCode::from(1)
    } else {
        ExitCode::SUCCESS
    }
}
