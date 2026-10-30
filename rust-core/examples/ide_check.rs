//! ide_check — verifikasi parser indeks IDE (rust-core/src/ide.rs) atas
//! pohon data/ dataset game asli: mencetak jumlah berkas/pasangan terindeks
//! dan hasil lookup nama-nama jangkar yang diberikan sebagai argumen.
//!
//! Pemakaian:
//!   ide_check <folder-data> <namaModel> [namaModel...]
//! Exit code: 0 bila semua nama ditemukan, 1 bila ada yang hilang.

use std::path::Path;
use std::process::ExitCode;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 3 {
        eprintln!("pemakaian: ide_check <folder-data> <namaModel> [namaModel...]");
        return ExitCode::from(2);
    }
    let index = match imgtxd_core::ide::scan_dir(Path::new(&args[1])) {
        Ok(i) => i,
        Err(e) => {
            eprintln!("ERROR {e}");
            return ExitCode::from(2);
        }
    };
    println!(
        "files={} models={} txdNames={}",
        index.files_read,
        index.models.len(),
        index.txd_ids.len()
    );
    let mut missing = 0;
    for name in &args[2..] {
        match index.model_id(name) {
            Some(id) => println!("{name} = {id}"),
            None => {
                println!("{name} = TIDAK DITEMUKAN");
                missing += 1;
            }
        }
    }
    if missing == 0 {
        ExitCode::SUCCESS
    } else {
        ExitCode::from(1)
    }
}
