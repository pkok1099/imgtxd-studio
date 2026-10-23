//! Profiling bench for the IMG engine (measurement only; not production code).
//! Subcommands:
//!   open     <path>            time ImgArchive::open alone (VER2 open reads
//!                              the whole file via std::fs::read)
//!   rebuild  <path>            time open -> rebuild() -> open again, per phase
//!                              (mirrors the app chain: JNI rebuild op + facade
//!                              refresh list)
//!   copybase <src> <dst>       std::fs::copy baseline for the same byte count

use imgtxd_core::img::*;
use std::path::Path;
use std::time::Instant;

fn ms(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1000.0
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let mode = args.get(1).map(|s| s.as_str()).unwrap_or("");
    match mode {
        "open" => {
            let path = Path::new(&args[2]);
            let size = std::fs::metadata(path).unwrap().len();
            let t = Instant::now();
            let arc = ImgArchive::open(path, None).unwrap();
            println!(
                "open: {:.1} ms | file={} B | entries={} | version={:?}",
                ms(t),
                size,
                arc.entries.len(),
                arc.version
            );
        }
        "rebuild" => {
            let path = Path::new(&args[2]);
            let size = std::fs::metadata(path).unwrap().len();
            let t0 = Instant::now();
            let mut arc = ImgArchive::open(path, None).unwrap();
            let open_ms = ms(t0);
            let entries = arc.entries.len();
            let t1 = Instant::now();
            arc.rebuild().unwrap();
            let rebuild_ms = ms(t1);
            let t2 = Instant::now();
            let arc2 = ImgArchive::open(path, None).unwrap();
            let reopen_ms = ms(t2);
            let new_size = std::fs::metadata(path).unwrap().len();
            println!(
                "open1: {:.1} ms | rebuild(): {:.1} ms | open2(refresh): {:.1} ms | total: {:.1} ms | file={} B -> {} B | entries={} (reopen {})",
                open_ms,
                rebuild_ms,
                reopen_ms,
                open_ms + rebuild_ms + reopen_ms,
                size,
                new_size,
                entries,
                arc2.entries.len()
            );
        }
        "copybase" => {
            let (src, dst) = (Path::new(&args[2]), Path::new(&args[3]));
            let t = Instant::now();
            let n = std::fs::copy(src, dst).unwrap();
            println!("fs::copy: {:.1} ms | {} B", ms(t), n);
        }
        _ => panic!("usage: bench_img open|rebuild|copybase ..."),
    }
}
