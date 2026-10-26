//! Nested-parallelism control for the hot kernels.
//!
//! The codecs and the mipmap generator split large work into row/block bands
//! processed on scoped worker threads. That is the right behavior for a
//! single big operation (one texture encode from JNI), but it is exactly
//! wrong when the caller is *already* one of N outer workers — as in batch
//! import or `sync_after_write`, where N images run concurrently and every
//! one of them would spawn its own band threads (N x N threads on N cores:
//! measured on an 8-core host as ~60x CPU-time inflation with wall time no
//! better than serial).
//!
//! The fix is a crate-level depth counter: while any outer worker holds an
//! [`OuterGuard`], [`inner_workers`] reports 1 and the kernels run inline.
//! Band and inline execution are byte-identical by construction (the band
//! function is the same code writing disjoint output slices), so switching
//! between them can never change results — pinned by a differential test.

use std::sync::atomic::{AtomicUsize, Ordering};

static OUTER_WORKERS: AtomicUsize = AtomicUsize::new(0);

/// Held for the duration of one outer-level concurrent worker (batch-import
/// prepare, variant re-encode, thumbnail decode). While at least one guard
/// is alive, the hot kernels run inline instead of spawning band threads.
pub struct OuterGuard;

impl OuterGuard {
    /// Registers this worker with the depth counter. Every spawned outer
    /// worker must hold one guard for its whole body.
    #[inline]
    pub fn acquire() -> Self {
        OUTER_WORKERS.fetch_add(1, Ordering::Relaxed);
        OuterGuard
    }
}

impl Drop for OuterGuard {
    #[inline]
    fn drop(&mut self) {
        OUTER_WORKERS.fetch_sub(1, Ordering::Relaxed);
    }
}

/// Band-worker count a kernel should use right now: the machine's
/// parallelism (capped, as before) — or 1 while an outer concurrent phase is
/// active, because the caller is already one of N balanced workers and
/// nested banding only oversubscribes the CPU.
///
/// `fallback` is the pre-existing default each kernel used when
/// `available_parallelism` failed, kept verbatim per call site.
#[inline]
pub(crate) fn inner_workers(fallback: usize) -> usize {
    if OUTER_WORKERS.load(Ordering::Relaxed) > 0 {
        1
    } else {
        std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(fallback)
            .min(16)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn guard_depth_gates_inner_workers() {
        let plain = inner_workers(4);
        assert!((1..=16).contains(&plain));
        let g = OuterGuard::acquire();
        assert_eq!(inner_workers(4), 1);
        let g2 = OuterGuard::acquire();
        assert_eq!(inner_workers(4), 1);
        drop(g2);
        assert_eq!(inner_workers(4), 1);
        drop(g);
        assert_eq!(inner_workers(4), plain);
    }
}
