//! Thread-count sweep isolator. Run: cargo run --release --example thr2
//!
//! For each kernel: wall time with 1/2/4/8 threads, speedup vs 1 thread, and
//! per-op inflation. The shape of the curve distinguishes scheduling
//! contention (super-linear inflation) from bandwidth/frequency limits
//! (linear, capped) from a healthy machine (speedup == thread count).

use imgtxd_core::codec;
use std::time::Instant;

fn sweep<T: Send>(name: &str, n: usize, f: impl Fn() -> T + Sync) {
    print!("{name:<28}");
    let mut base = None;
    for &t in &[1usize, 2, 4, 8] {
        let iters = (n / t).max(2);
        let start = Instant::now();
        std::thread::scope(|s| {
            for _ in 0..t {
                s.spawn(|| {
                    for _ in 0..iters {
                        std::hint::black_box(f());
                    }
                });
            }
        });
        let wall = start.elapsed().as_secs_f64();
        let total_ops = iters * t;
        let per_op = wall / total_ops as f64;
        let per_op_ms = per_op * 1e3;
        let sp = base.unwrap_or(per_op) / per_op;
        if t == 1 {
            base = Some(per_op);
            print!(" 1t: per_op={per_op_ms:.2}ms");
        } else {
            print!("  {t}t: sp={sp:.2}x per_op={per_op_ms:.2}ms");
        }
    }
    println!();
}

fn main() {
    let px = vec![0x3F7F_3F7Fu32; 512 * 512];

    sweep("encode_dxt5 512", 256, || {
        codec::dxt::encode_dxt5(&px, 512, 512)
    });
    sweep("vec![0u8; 1MB]", 1024, || vec![0u8; 1 << 20]);
    sweep("clone 1MB", 1024, || px.clone());
    let raw = vec![7u8; 1 << 20];
    sweep("crc32 1MB", 512, || imgtxd_core::texdb::crc32_ieee(&raw));

    // Pure-ALU scalar loop over 1MB, zero allocations (hand-rolled hash-ish
    // fold so nothing can be elided).
    sweep("pure ALU 1MB no-alloc", 512, || {
        let mut acc = 0u64;
        for chunk in px.chunks(64) {
            let mut x = u64::from(chunk[0]);
            x = x.wrapping_mul(6364136223846793005).wrapping_add(1);
            acc ^= x ^ (chunk.len() as u64);
        }
        acc
    });
}
