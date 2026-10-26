//! SIMD kernels for the hot pixel paths — x86_64 (SSE2 baseline, SSSE3/AVX2
//! runtime-detected) and aarch64 NEON (baseline on that target), each with a
//! portable scalar fallback. Every kernel is bit-identical to the scalar
//! reference it replaces; differential tests prove it over exhaustive or
//! randomized domains.
//!
//! `unsafe` contract summary (per-kernel contracts live at each block):
//! - loads use `loadu`/`vld1q` (no alignment requirement) and only touch
//!   bytes within the chunk the loop has already bounds-checked;
//! - stores write into a `Vec` pre-sized to exactly the output length;
//! - feature-gated code is only reached after `is_x86_feature_detected!`
//!   (x86) or unconditionally on aarch64 where NEON is baseline.

#[cfg(target_arch = "x86_64")]
use std::sync::atomic::{AtomicU8, Ordering};

#[cfg(any(target_arch = "x86_64", target_arch = "aarch64"))]
pub mod conv;
#[cfg(any(target_arch = "x86_64", target_arch = "aarch64"))]
pub mod dxt;
#[cfg(any(target_arch = "x86_64", target_arch = "aarch64"))]
pub mod etc1;

/// Feature tier for the runtime dispatch (x86_64). 1 = SSE2 (always present
/// on x86_64), 2 = SSSE3, 3 = AVX2. aarch64 always compiles its NEON path.
#[cfg(target_arch = "x86_64")]
pub fn tier() -> u8 {
    static TIER: AtomicU8 = AtomicU8::new(0);
    match TIER.load(Ordering::Relaxed) {
        0 => {
            let t = if std::arch::is_x86_feature_detected!("avx2") {
                3
            } else if std::arch::is_x86_feature_detected!("ssse3") {
                2
            } else {
                1
            };
            TIER.store(t, Ordering::Relaxed);
            t
        }
        t => t,
    }
}
