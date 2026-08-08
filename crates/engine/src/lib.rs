//! loggi-engine: UI-agnostic core for indexing, lazy reading and searching very
//! large log files.
//!
//! Design (see `docs/PLAN.md`): a file is never loaded into memory. Memory holds
//! only (1) a compressed line-offset index and (2) bitsets of matching line
//! numbers. Display reads are one contiguous `pread` per visible chunk.

pub mod encoding;
pub mod index;
pub mod reader;
pub mod search;
pub mod util;
pub mod watch;

pub use encoding::TextEncoding;
pub use index::{FileIndex, FileInfo, IndexOptions, SharedIndex};
pub use reader::{LazyReader, LineRead};
pub use search::{
    DEFAULT_CACHE_CAP_LINES, HighlightMatcher, SearchEngine, SearchOptions, SearchResults,
    SearchStatus,
};
pub use util::{AtomicFlag, Progress};
pub use watch::{ChangeKind, FileWatcher, WatchConfig};
