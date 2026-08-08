//! Shared utilities: cooperative cancellation, progress, cross-platform pread.

use std::fs::File;
use std::io;
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

/// Cooperative cancellation flag. Checked by long operations; once set it stays set
/// until reset.
#[derive(Debug, Clone, Default)]
pub struct AtomicFlag(Arc<AtomicBool>);

impl AtomicFlag {
    pub fn new() -> Self {
        Self::default()
    }

    /// Returns `true` if cancellation has been requested.
    #[inline]
    pub fn is_set(&self) -> bool {
        self.0.load(Ordering::Relaxed)
    }

    pub fn set(&self) {
        self.0.store(true, Ordering::Relaxed);
    }

    pub fn clear(&self) {
        self.0.store(false, Ordering::Relaxed);
    }
}

/// Progress of an indexing or search operation, reported at throttled intervals.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Progress {
    /// Bytes or lines processed so far.
    pub done: u64,
    /// Total bytes or lines; 0 when unknown (e.g. growing file).
    pub total: u64,
}

impl Progress {
    /// 0.0 ..= 1.0; 1.0 when the operation is complete.
    pub fn fraction(&self) -> f64 {
        if self.total == 0 {
            0.0
        } else {
            (self.done as f64 / self.total as f64).min(1.0)
        }
    }
}

/// Positioned read (no seek needed, thread-safe per file handle).
#[cfg(unix)]
pub fn pread(file: &File, buf: &mut [u8], offset: u64) -> io::Result<usize> {
    use std::os::unix::fs::FileExt;
    file.read_at(buf, offset)
}

#[cfg(windows)]
pub fn pread(file: &File, buf: &mut [u8], offset: u64) -> io::Result<usize> {
    use std::os::windows::fs::FileExt;
    file.seek_read(buf, offset)
}

/// Stat for change detection.
pub fn file_size(path: &Path) -> io::Result<u64> {
    Ok(path.metadata()?.len())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn flag_works() {
        let f = AtomicFlag::new();
        assert!(!f.is_set());
        f.set();
        assert!(f.is_set());
        f.clear();
        assert!(!f.is_set());
    }
}
