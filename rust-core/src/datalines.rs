//! Data-line merge for mod installs (handling.cfg / vehicles.ide /
//! carcols.dat): paste lines from a mod readme, replace matching entries
//! in the target data file by their first token (the vehicle/entry id),
//! append new ids — unless the file is sectioned (contains an `end`
//! line), where appending into the wrong section would corrupt it, so
//! new ids are skipped instead.
//!
//! Port of the Kotlin logic that used to live in `DataLinesActivity`
//! (RUST100 migration, 2026-10-23). Behavior is pinned byte-for-byte by
//! golden fixtures captured from the Kotlin implementation
//! (`DataLinesGoldenTest`, run green against the old code before the
//! port); the tests below mirror those fixtures exactly, including the
//! Indonesian note strings, the column-count warning threshold (> 2),
//! and the note truncation at 40 chars.
//!
//! Wire format of [`plan_wire`] (consumed by the Kotlin facade):
//! ```text
//! OK\t<changed>\t<fileLineCount>\t<sectioned 0|1>\t<notesLineCount>\n
//! <notes: exactly notesLineCount lines, each \n-terminated>
//! <merged file text, verbatim to the end of the string>
//! ```

pub struct DataLinesPlan {
    pub changed: usize,
    pub file_line_count: usize,
    pub sectioned: bool,
    /// One note per line, each terminated by '\n' (Kotlin appendLine).
    pub notes: String,
    /// Merged file content: lines joined by '\n' plus a trailing '\n'
    /// (exactly what the old Kotlin writer wrote to disk).
    pub merged_text: String,
}

/// Splits like `java.io.BufferedReader.readLine` (Kotlin `File.readLines`):
/// terminators are \r\n, \r, or \n; a trailing terminator does NOT
/// produce a final empty line; empty text yields zero lines.
fn split_file_lines(text: &str) -> Vec<String> {
    split_lines(text, false)
}

/// Splits like Kotlin `String.lineSequence()`: same terminators, but a
/// trailing terminator DOES produce a final empty line.
fn split_pasted_lines(text: &str) -> Vec<String> {
    split_lines(text, true)
}

fn split_lines(text: &str, keep_trailing_empty: bool) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut chars = text.chars().peekable();
    let mut ended_on_terminator = false;
    while let Some(c) = chars.next() {
        match c {
            '\r' => {
                if chars.peek() == Some(&'\n') {
                    chars.next();
                }
                out.push(std::mem::take(&mut cur));
                ended_on_terminator = true;
            }
            '\n' => {
                out.push(std::mem::take(&mut cur));
                ended_on_terminator = true;
            }
            _ => {
                cur.push(c);
                ended_on_terminator = false;
            }
        }
    }
    if !cur.is_empty() || (keep_trailing_empty && (ended_on_terminator || text.is_empty())) {
        out.push(cur);
    }
    out
}

/// Kotlin `lineKey`: the entry id of a data line = its first token when
/// splitting on whitespace/commas; comment lines (`;` / `#`) and blank
/// lines have no key. A line starting with a delimiter yields an empty
/// first segment, hence no key (pinned by the golden fixtures).
fn line_key(line: &str) -> Option<String> {
    let t = line.trim();
    if t.is_empty() || t.starts_with(';') || t.starts_with('#') {
        return None;
    }
    let first = t
        .split(|c: char| c.is_whitespace() || c == ',')
        .next()
        .unwrap_or("");
    if first.is_empty() {
        None
    } else {
        Some(first.to_string())
    }
}

/// Kotlin `tokenCount`: non-empty tokens when splitting on
/// whitespace/commas.
fn token_count(line: &str) -> usize {
    line.trim()
        .split(|c: char| c.is_whitespace() || c == ',')
        .filter(|s| !s.is_empty())
        .count()
}

/// Kotlin `String.equals(other, ignoreCase = true)`.
fn key_eq(a: &str, b: &str) -> bool {
    a.to_lowercase() == b.to_lowercase()
}

/// Kotlin `String.take(40)`.
fn take40(s: &str) -> String {
    s.chars().take(40).collect()
}

/// Computes the merge plan of [pasted_text] over [file_text] with the
/// exact semantics (and note strings) of the old Kotlin `buildPlan`.
pub fn plan(file_text: &str, pasted_text: &str) -> DataLinesPlan {
    let file_lines = split_file_lines(file_text);
    let file_line_count = file_lines.len();
    let sectioned = file_lines.iter().any(|l| l.trim() == "end");

    // Kotlin: pasted.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }
    let new_lines: Vec<String> = split_pasted_lines(pasted_text)
        .iter()
        .map(|l| l.trim_end().to_string())
        .filter(|l| !l.trim().is_empty())
        .collect();

    let mut result: Vec<String> = file_lines;
    let mut notes = String::new();
    let mut changed = 0usize;

    for raw in &new_lines {
        let Some(key) = line_key(raw) else {
            notes.push_str(&format!("Lewati (komentar/kosong): {}\n", take40(raw)));
            continue;
        };
        let idx = result
            .iter()
            .position(|l| line_key(l).map(|k| key_eq(&k, &key)).unwrap_or(false));
        match idx {
            Some(i) => {
                let expected = token_count(&result[i]);
                let got = token_count(raw);
                if expected.abs_diff(got) > 2 {
                    notes.push_str(&format!(
                        "GANTI {key} — PERINGATAN jumlah kolom beda jauh (berkas {expected} vs tempelan {got})\n"
                    ));
                } else {
                    notes.push_str(&format!("GANTI {key}\n"));
                }
                result[i] = raw.clone();
                changed += 1;
            }
            None if sectioned => {
                notes.push_str(&format!(
                    "LEWATI {key} — berkas berseksi (mode ganti-saja); id baru tidak ditambahkan\n"
                ));
            }
            None => {
                notes.push_str(&format!("TAMBAH {key}\n"));
                result.push(raw.clone());
                changed += 1;
            }
        }
    }
    notes.push_str(&format!("Total perubahan: {changed}\n"));

    let merged_text = result.join("\n") + "\n";
    DataLinesPlan {
        changed,
        file_line_count,
        sectioned,
        notes,
        merged_text,
    }
}

/// Serializes [`plan`] into the wire block described in the module docs.
pub fn plan_wire(file_text: &str, pasted_text: &str) -> String {
    let p = plan(file_text, pasted_text);
    let notes_lines = p.notes.matches('\n').count();
    format!(
        "OK\t{}\t{}\t{}\t{}\n{}{}",
        p.changed,
        p.file_line_count,
        if p.sectioned { 1 } else { 0 },
        notes_lines,
        p.notes,
        p.merged_text
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    // --- helper pins (mirror DataLinesGoldenTest.lineKeyPins/tokenCountPins) ---

    #[test]
    fn line_key_pins() {
        assert_eq!(line_key("; comment"), None);
        assert_eq!(line_key("# comment"), None);
        assert_eq!(line_key("   "), None);
        assert_eq!(line_key(",starts-with-comma"), None);
        assert_eq!(line_key("ABC, def ghi"), Some("ABC".to_string()));
        assert_eq!(line_key("  ABC   def"), Some("ABC".to_string()));
        assert_eq!(
            line_key("1100, landstal, landstal"),
            Some("1100".to_string())
        );
    }

    #[test]
    fn token_count_pins() {
        assert_eq!(token_count(""), 0);
        assert_eq!(token_count("  a  "), 1);
        assert_eq!(token_count("a,b, c"), 3);
        assert_eq!(token_count("LANDSTALKER 1700.0 2.0 5.0"), 4);
        assert_eq!(token_count("1100, landstal, landstal, CHER, 1, 0"), 6);
    }

    // --- plan fixtures (the Kotlin goldens, byte-for-byte) ---

    const HANDLING: &str =
        "; handling comment\nLANDSTALKER 1700.0 2.0 5.0\nBANSHEE 1400.0 1.5 4.0\n";

    #[test]
    fn replace_identical_and_case_insensitive() {
        let p = plan(HANDLING, "BANSHEE 1400.0 1.5 4.0\nbanshee 1500.0 9.9");
        assert_eq!(
            p.notes,
            "GANTI BANSHEE\nGANTI banshee\nTotal perubahan: 2\n"
        );
        assert_eq!(p.changed, 2);
        assert_eq!(
            p.merged_text,
            "; handling comment\nLANDSTALKER 1700.0 2.0 5.0\nbanshee 1500.0 9.9\n"
        );
        assert_eq!(p.file_line_count, 3);
        assert!(!p.sectioned);
    }

    #[test]
    fn column_warning_thresholds() {
        let p = plan(HANDLING, "LANDSTALKER 1 2 3 4 5");
        assert_eq!(p.notes, "GANTI LANDSTALKER\nTotal perubahan: 1\n");
        let p = plan(HANDLING, "LANDSTALKER 1 2 3 4 5 6");
        assert_eq!(
            p.notes,
            "GANTI LANDSTALKER — PERINGATAN jumlah kolom beda jauh (berkas 4 vs tempelan 7)\nTotal perubahan: 1\n"
        );
        let p = plan(HANDLING, "LANDSTALKER 1 2 3 4 5 6 7");
        assert_eq!(
            p.notes,
            "GANTI LANDSTALKER — PERINGATAN jumlah kolom beda jauh (berkas 4 vs tempelan 8)\nTotal perubahan: 1\n"
        );
    }

    #[test]
    fn append_new_id_unsectioned() {
        let p = plan(HANDLING, "NEWCAR 1 2 3");
        assert_eq!(p.notes, "TAMBAH NEWCAR\nTotal perubahan: 1\n");
        assert_eq!(p.changed, 1);
        assert_eq!(
            p.merged_text,
            "; handling comment\nLANDSTALKER 1700.0 2.0 5.0\nBANSHEE 1400.0 1.5 4.0\nNEWCAR 1 2 3\n"
        );
    }

    #[test]
    fn comments_skipped_and_truncated() {
        let long_comment = format!("; {}", "x".repeat(60));
        let pasted = format!("; just a comment\n# hash note\n{long_comment}\n\n   ");
        let p = plan(HANDLING, &pasted);
        let expected = format!(
            "Lewati (komentar/kosong): ; just a comment\nLewati (komentar/kosong): # hash note\nLewati (komentar/kosong): {}\nTotal perubahan: 0\n",
            long_comment.chars().take(40).collect::<String>()
        );
        assert_eq!(p.notes, expected);
        assert_eq!(p.changed, 0);
    }

    #[test]
    fn comma_first_line_has_no_key() {
        let p = plan(HANDLING, ",weird line");
        assert_eq!(
            p.notes,
            "Lewati (komentar/kosong): ,weird line\nTotal perubahan: 0\n"
        );
        assert_eq!(p.changed, 0);
    }

    #[test]
    fn sectioned_file_is_replace_only() {
        let ide = "objs\n1100, landstal, landstal, CHER, 1, 0\nend\n";
        let p = plan(
            ide,
            "1100, newtex, newtex, CHER, 1, 0\n2200, foo, foo, CHER, 1, 0",
        );
        assert_eq!(
            p.notes,
            "GANTI 1100\nLEWATI 2200 — berkas berseksi (mode ganti-saja); id baru tidak ditambahkan\nTotal perubahan: 1\n"
        );
        assert_eq!(p.changed, 1);
        assert!(p.sectioned);
        assert_eq!(
            p.merged_text,
            "objs\n1100, newtex, newtex, CHER, 1, 0\nend\n"
        );
    }

    #[test]
    fn sequential_pastes_see_appended_lines() {
        let p = plan("A 1\nB 2\n", "C 3\nC 4 4");
        assert_eq!(p.notes, "TAMBAH C\nGANTI C\nTotal perubahan: 2\n");
        assert_eq!(p.merged_text, "A 1\nB 2\nC 4 4\n");
    }

    #[test]
    fn empty_paste_changes_nothing() {
        let p = plan(HANDLING, "");
        assert_eq!(p.notes, "Total perubahan: 0\n");
        assert_eq!(p.changed, 0);
        assert_eq!(p.merged_text, HANDLING);
    }

    #[test]
    fn pasted_lines_are_trim_end_but_not_trim_start() {
        let p = plan(HANDLING, "BANSHEE 1.0 2.0 3.0   \n  LANDSTALKER 9 9 9");
        assert_eq!(
            p.notes,
            "GANTI BANSHEE\nGANTI LANDSTALKER\nTotal perubahan: 2\n"
        );
        assert_eq!(
            p.merged_text,
            "; handling comment\n  LANDSTALKER 9 9 9\nBANSHEE 1.0 2.0 3.0\n"
        );
    }

    // --- file-text splitting (java readLines semantics; Rust-only side) ---

    #[test]
    fn file_splitting_matches_read_lines() {
        assert_eq!(split_file_lines(""), Vec::<String>::new());
        assert_eq!(split_file_lines("A 1"), vec!["A 1"]);
        assert_eq!(split_file_lines("A 1\n"), vec!["A 1"]);
        assert_eq!(split_file_lines("A 1\r\nB 2\r\n"), vec!["A 1", "B 2"]);
        assert_eq!(split_file_lines("A 1\rB 2"), vec!["A 1", "B 2"]);
        assert_eq!(split_file_lines("A 1\n\n"), vec!["A 1", ""]);
    }

    #[test]
    fn crlf_file_merges_to_lf_output() {
        let p = plan("A 1\r\nB 2\r\n", "B 9");
        assert_eq!(p.file_line_count, 2);
        assert_eq!(p.merged_text, "A 1\nB 9\n");
    }

    #[test]
    fn wire_block_layout() {
        let w = plan_wire("A 1\nB 2\n", "C 3");
        assert_eq!(
            w,
            "OK\t1\t2\t0\t2\nTAMBAH C\nTotal perubahan: 1\nA 1\nB 2\nC 3\n"
        );
    }
}
