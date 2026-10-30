//! IDE definition index: model name -> streaming model id, plus the
//! txd name -> candidate ids map (several models can share one TXD).
//!
//! IDE files (`vehicles.ide`, `peds.ide`, `default.ide`, `data/maps/**`)
//! define every streaming model on lines shaped
//! `id, modelName, txdName, ...`. The Modloader sheet uses this index to
//! auto-fill the Model ID field from the picked entry's file name instead
//! of making the user hunt the number down by hand.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

/// Parsed IDE index. Both maps keep first-seen order; model names and txd
/// names are stored lowercase.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct IdeIndex {
    /// (model name, streaming id); a duplicate model name keeps the FIRST id.
    pub models: Vec<(String, u32)>,
    /// (txd name, candidate model ids, deduplicated, first-seen order).
    pub txd_ids: Vec<(String, Vec<u32>)>,
    /// How many .ide files were actually read to build this index.
    pub files_read: usize,
}

#[derive(Default)]
struct IndexBuilder {
    index: IdeIndex,
    model_seen: HashMap<String, ()>,
    txd_pos: HashMap<String, usize>,
}

impl IndexBuilder {
    fn add_text(&mut self, text: &str) {
        for line in text.lines() {
            self.add_line(line);
        }
    }

    fn add_line(&mut self, raw: &str) {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') {
            return;
        }
        let mut fields = line.split(',');
        let Some(id) = fields.next().and_then(|f| f.trim().parse::<u32>().ok()) else {
            // Section headers (objs/cars/peds/end/...), txdp rows and any
            // other non-definition line have no numeric first field.
            return;
        };
        let model = fields.next().unwrap_or("").trim().to_lowercase();
        // Definition rows always carry a real model name; rows from the
        // path/2dfx sections put numbers or coordinates in field two.
        if model.is_empty() || model.parse::<f64>().is_ok() {
            return;
        }
        if !self.model_seen.contains_key(&model) {
            self.model_seen.insert(model.clone(), ());
            self.index.models.push((model, id));
        }
        if let Some(txd_field) = fields.next() {
            let txd = txd_field.trim().to_lowercase();
            if !txd.is_empty() {
                let pos = *self.txd_pos.entry(txd.clone()).or_insert_with(|| {
                    self.index.txd_ids.push((txd, Vec::new()));
                    self.index.txd_ids.len() - 1
                });
                let ids = &mut self.index.txd_ids[pos].1;
                if !ids.contains(&id) {
                    ids.push(id);
                }
            }
        }
    }

    fn finish(mut self, files_read: usize) -> IdeIndex {
        self.index.files_read = files_read;
        self.index
    }
}

/// Builds an index from in-memory IDE texts, in the given order.
pub fn parse_texts(texts: &[&str]) -> IdeIndex {
    let mut builder = IndexBuilder::default();
    for text in texts {
        builder.add_text(text);
    }
    builder.finish(texts.len())
}

/// Reads and parses the given .ide files, in the given order.
pub fn parse_files(paths: &[PathBuf]) -> IdeIndex {
    let mut builder = IndexBuilder::default();
    let mut read = 0;
    for path in paths {
        if let Ok(bytes) = std::fs::read(path) {
            read += 1;
            builder.add_text(&String::from_utf8_lossy(&bytes));
        }
    }
    builder.finish(read)
}

/// All `*.ide` files under `root` (recursive), sorted by path so the
/// first-wins rule resolves duplicates deterministically.
pub fn collect_ide_files(root: &Path) -> Vec<PathBuf> {
    let mut out = Vec::new();
    let mut stack = vec![root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                stack.push(path);
            } else if path
                .extension()
                .map(|e| e.eq_ignore_ascii_case("ide"))
                .unwrap_or(false)
            {
                out.push(path);
            }
        }
    }
    out.sort();
    out
}

/// Recursively scans a folder (typically the game's `files/` or
/// `files/data/`) and builds the index from every .ide under it.
pub fn scan_dir(root: &Path) -> Result<IdeIndex, String> {
    if !root.is_dir() {
        return Err(format!("folder tidak ditemukan: {}", root.display()));
    }
    let files = collect_ide_files(root);
    Ok(parse_files(&files))
}

impl IdeIndex {
    /// Streaming id for a model name (case-insensitive), if indexed.
    pub fn model_id(&self, name: &str) -> Option<u32> {
        let key = name.to_lowercase();
        self.models
            .iter()
            .find(|(n, _)| n == &key)
            .map(|(_, id)| *id)
    }

    /// Candidate model ids sharing a txd name (case-insensitive).
    pub fn txd_candidates(&self, name: &str) -> &[u32] {
        let key = name.to_lowercase();
        self.txd_ids
            .iter()
            .find(|(n, _)| n == &key)
            .map(|(_, ids)| ids.as_slice())
            .unwrap_or(&[])
    }
}

/// Wire lines for the JNI surface (see `NativeCore.ideScanDir`): header
/// `OK\t<modelCount>\t<txdNameCount>\t<filesRead>`, then `M\t<name>\t<id>`
/// per model and `T\t<txdName>\t<id,id,...>` per txd name.
pub fn wire_lines(index: &IdeIndex) -> Vec<String> {
    let mut out = Vec::with_capacity(1 + index.models.len() + index.txd_ids.len());
    out.push(format!(
        "OK\t{}\t{}\t{}",
        index.models.len(),
        index.txd_ids.len(),
        index.files_read
    ));
    for (name, id) in &index.models {
        out.push(format!("M\t{name}\t{id}"));
    }
    for (name, ids) in &index.txd_ids {
        let joined = ids
            .iter()
            .map(|i| i.to_string())
            .collect::<Vec<_>>()
            .join(",");
        out.push(format!("T\t{name}\t{joined}"));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const VEHICLES_SNIPPET: &str = "\
# vehicles.ide snippet
cars
400, landstal, landstal, car, LANDSTAL, LANDSTAL, null, worker, 10, 0, 0, -1, 0.7, 0.7, -1
411,\tinfernus,\tinfernus,\tcar,\t\tINFERNUS, \tINFERNU, \tnull,\texecutive, \t5, \t0,\t0,\t\t-1, 0.7, 0.7,\t\t0
end
";

    #[test]
    fn vehicles_line_maps_model_and_txd() {
        let index = parse_texts(&[VEHICLES_SNIPPET]);
        assert_eq!(index.model_id("infernus"), Some(411));
        assert_eq!(index.model_id("INFERNUS"), Some(411));
        assert_eq!(index.model_id("landstal"), Some(400));
        assert_eq!(index.txd_candidates("infernus"), &[411]);
        assert_eq!(index.files_read, 1);
    }

    #[test]
    fn sections_comments_and_garbage_are_skipped() {
        let text = "\
# a comment

objs
5390, laeskateparkLA, glenpark7_lae, 150, 0
end
peds
7, male01, male01, CIVMALE, STAT_SENSIBLE_GUY
end
txdp
infernus, generic
some garbage line without commas
12345
, noname, txd, 1
999, 12.5, 30.0, 2dfx-ish row
";
        let index = parse_texts(&[text]);
        assert_eq!(index.model_id("laeskateparkla"), Some(5390));
        assert_eq!(index.model_id("male01"), Some(7));
        assert_eq!(index.models.len(), 2, "models: {:?}", index.models);
        // txdp row must not register its first field as a model.
        assert_eq!(index.model_id("infernus"), None);
    }

    #[test]
    fn crlf_and_messy_whitespace_are_tolerated() {
        let text = "cars\r\n  411 ,  infernus , infernus , car\r\nend\r\n";
        let index = parse_texts(&[text]);
        assert_eq!(index.model_id("infernus"), Some(411));
        assert_eq!(index.txd_candidates("infernus"), &[411]);
    }

    #[test]
    fn duplicate_model_first_wins_and_txd_collects_ids() {
        let text = "\
100, shared, sharedtxd, car
200, other, sharedtxd, car
300, shared, sharedtxd, car
";
        let index = parse_texts(&[text]);
        assert_eq!(index.model_id("shared"), Some(100));
        assert_eq!(index.txd_candidates("sharedtxd"), &[100, 200, 300]);
    }

    #[test]
    fn scan_dir_walks_recursively_and_sorts() {
        let base = std::env::temp_dir().join(format!("ide_scan_test_{}", std::process::id()));
        let nested = base.join("maps").join("LA");
        std::fs::create_dir_all(&nested).expect("mkdir");
        std::fs::write(base.join("vehicles.ide"), VEHICLES_SNIPPET).expect("write");
        std::fs::write(
            nested.join("LAe.ide"),
            "objs\n5390, laeskateparkLA, glenpark7_lae, 150, 0\nend\n",
        )
        .expect("write");
        std::fs::write(nested.join("ignore.txt"), "411, infernus, infernus").expect("write");

        let index = scan_dir(&base).expect("scan");
        assert_eq!(index.files_read, 2);
        assert_eq!(index.model_id("infernus"), Some(411));
        assert_eq!(index.model_id("laeskateparkla"), Some(5390));

        std::fs::remove_dir_all(&base).ok();
    }

    #[test]
    fn scan_dir_missing_folder_is_error() {
        let missing = std::env::temp_dir().join("ide_scan_missing_dir_xyz");
        assert!(scan_dir(&missing).is_err());
    }

    #[test]
    fn wire_lines_shape() {
        let index = parse_texts(&[VEHICLES_SNIPPET]);
        let lines = wire_lines(&index);
        assert_eq!(lines[0], "OK\t2\t2\t1");
        assert!(lines.contains(&"M\tinfernus\t411".to_string()));
        assert!(lines.contains(&"T\tinfernus\t411".to_string()));
    }
}
