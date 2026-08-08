//! loggi CLI: info / search / tail over the loggi engine (rg-inspired flags).

mod output;
mod tail;

use std::io::{IsTerminal, Write};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Instant;

use clap::{Parser, Subcommand};
use loggi_engine::index::{IndexOptions, SharedIndex};
use loggi_engine::search::{SearchEngine, SearchOptions};
use loggi_engine::{FileInfo, Progress};

#[derive(Parser)]
#[command(
    name = "loggi",
    version,
    about = "A log viewer for very large log files (engine CLI)."
)]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// File info + index stats
    Info {
        file: PathBuf,
        /// JSON output
        #[arg(long)]
        json: bool,
    },
    /// Search a file for a pattern (regex by default; -F for literals)
    Search(SearchArgs),
    /// Print the tail of a file (with optional follow)
    Tail {
        file: PathBuf,
        /// Number of lines to print
        #[arg(long, default_value_t = 10)]
        lines: u64,
        /// Keep watching and print appended lines
        #[arg(long)]
        follow: bool,
    },
}

#[derive(clap::Args)]
struct SearchArgs {
    pattern: String,
    file: PathBuf,
    /// Case-insensitive matching
    #[arg(short = 'i', long)]
    ignore_case: bool,
    /// Treat the pattern as a literal string (default is regex)
    #[arg(short = 'F', long)]
    fixed_strings: bool,
    /// Suppress line numbers
    #[arg(short = 'N', long)]
    no_line_numbers: bool,
    /// Show count of matching lines
    #[arg(short = 'c', long)]
    count: bool,
    /// Context lines after a match
    #[arg(short = 'A', long, default_value_t = 0)]
    after_context: u64,
    /// Context lines before a match
    #[arg(short = 'B', long, default_value_t = 0)]
    before_context: u64,
    /// Context lines around a match (same as -A and -B)
    #[arg(short = 'C', long)]
    context: Option<u64>,
    /// 0-based first line to search from
    #[arg(long)]
    line_offset: Option<u64>,
    /// Stop after this many matches
    #[arg(long)]
    limit: Option<u64>,
    /// Quiet: no output, exit 0 if any match
    #[arg(short = 'q', long)]
    quiet: bool,
    /// Watch the file and search appended data
    #[arg(long)]
    follow: bool,
    /// Colored output: auto/always/never
    #[arg(long, default_value = "auto")]
    color: String,
    /// NDJSON stream of match objects (for tooling)
    #[arg(long)]
    json: bool,
    /// Search worker threads (0 = auto)
    #[arg(long, default_value_t = 0)]
    threads: usize,
}

fn main() {
    let cli = Cli::parse();
    let result = match cli.cmd {
        Cmd::Info { file, json } => cmd_info(&file, json),
        Cmd::Search(args) => cmd_search(args),
        Cmd::Tail {
            file,
            lines,
            follow,
        } => tail::cmd_tail(&file, lines, follow),
    };
    if let Err(e) = result {
        eprintln!("loggi: {e}");
        std::process::exit(2);
    }
}

/// Indexing options shared by commands; shows progress on a TTY.
fn index_opts() -> IndexOptions {
    let mut opts = IndexOptions::default();
    let tty = std::io::stderr().is_terminal();
    if tty {
        opts.progress = Some(Arc::new(move |p: Progress| {
            if p.total > 0 && p.fraction() < 1.0 {
                eprint!(
                    "\rindexing... {:.1}% ({}/{})",
                    p.fraction() * 100.0,
                    fmt_bytes(p.done),
                    fmt_bytes(p.total)
                );
                let _ = std::io::stderr().flush();
            }
        }));
    }
    opts
}

fn cmd_info(file: &std::path::Path, json: bool) -> anyhow::Result<()> {
    let t = Instant::now();
    let index = SharedIndex::open(file, &index_opts())?;
    let idx = index.snapshot();
    let info = idx.to_info(t.elapsed());
    if json {
        println!("{}", output::info_json(&info)?);
    } else {
        print_info(&info);
    }
    Ok(())
}

fn print_info(info: &FileInfo) {
    let idx_bytes_per_line = if info.line_count > 0 {
        info.index_bytes as f64 / info.line_count as f64
    } else {
        0.0
    };
    println!("file:       {}", info.path.display());
    println!("size:       {} ({} bytes)", fmt_bytes(info.size), info.size);
    println!("lines:      {}", info.line_count);
    println!("max line:   {} bytes", info.max_line_len);
    println!("encoding:   {}", output::encoding_name(info.encoding));
    println!(
        "index:      {} ({:.2} bytes/line)",
        fmt_bytes(info.index_bytes),
        idx_bytes_per_line
    );
    println!(
        "index time: {:.3} s ({:.0} MiB/s)",
        info.index_time.as_secs_f64(),
        info.index_throughput_mibs
    );
}

fn cmd_search(args: SearchArgs) -> anyhow::Result<()> {
    if !args.file.is_file() {
        anyhow::bail!("no such file: {}", args.file.display());
    }
    let context = args.context.unwrap_or_default();
    let (before, after) = (args.before_context, args.after_context);
    let color = match args.color.as_str() {
        "always" => true,
        "never" => false,
        _ => std::io::stdout().is_terminal(),
    };

    let shared = SharedIndex::open(&args.file, &index_opts())?;
    let engine = SearchEngine::with_config(
        shared.snapshot(),
        args.threads,
        loggi_engine::DEFAULT_CACHE_CAP_LINES,
    );
    let reader = loggi_engine::LazyReader::new(engine.index().clone());

    let search_opts = SearchOptions {
        patterns: vec![args.pattern.clone()],
        ignore_case: args.ignore_case,
        use_regex: !args.fixed_strings,
        start_line: args.line_offset,
        end_line: None,
        max_results: args.limit,
    };

    if args.follow {
        return follow_search(&shared, &engine, &search_opts, &args);
    }

    let mut matches: Vec<u64> = Vec::new();
    let results = engine.search_with(&search_opts, |_status, lines| {
        matches.extend_from_slice(lines);
        true
    })?;
    if let Some(limit) = args.limit {
        // Batches may overshoot the limit; the engine trims its own result
        // set, and the printed list is trimmed here too (sorted by line).
        matches.truncate(limit as usize);
    }

    if args.quiet {
        std::process::exit(if !results.matches.is_empty() { 0 } else { 1 });
    }
    if args.count {
        println!("{}", results.matches.len());
        return Ok(());
    }
    if args.json {
        output::print_json_matches(
            &engine,
            &reader,
            &search_opts,
            &matches,
            &args.file.to_string_lossy(),
        )?;
        return Ok(());
    }
    let before = if context > 0 && before == 0 {
        context
    } else {
        before
    };
    let after = if context > 0 && after == 0 {
        context
    } else {
        after
    };
    let po = output::PrintOptions {
        engine: &engine,
        reader: &reader,
        opts: &search_opts,
        no_line_numbers: args.no_line_numbers,
        color,
    };
    if before == 0 && after == 0 {
        output::print_lines(&po, &matches)?;
    } else {
        output::print_context(&po, &matches, before, after)?;
    }
    Ok(())
}

/// Follow: reindex on change and search only the appended lines.
fn follow_search(
    shared: &SharedIndex,
    engine: &SearchEngine,
    opts: &SearchOptions,
    args: &SearchArgs,
) -> anyhow::Result<()> {
    let watcher =
        loggi_engine::FileWatcher::new(shared.path(), &loggi_engine::WatchConfig::default())?;
    let reader = loggi_engine::LazyReader::new(engine.index().clone());
    let mut from_line = engine.index().line_count().saturating_sub(1);

    loop {
        let mut added = 0u64;
        let mut truncated = false;
        loop {
            match watcher.poll() {
                loggi_engine::ChangeKind::Unchanged => break,
                loggi_engine::ChangeKind::DataAdded => added += 1,
                loggi_engine::ChangeKind::Truncated => {
                    truncated = true;
                    break;
                }
            }
        }
        if truncated || added > 0 {
            if truncated {
                return follow_search_reset(shared, engine, args);
            }
            shared.refresh(&index_opts())?;
            engine.invalidate_cache();
            let new_count = engine.index().line_count();
            if new_count <= from_line {
                continue;
            }
            // Resume from one line before the last processed one, then drop
            // that line's match (overlap dedup).
            let resume = from_line.saturating_sub(1);
            let mut new_opts = opts.clone();
            new_opts.start_line = Some(resume);
            new_opts.end_line = Some(new_count);
            let mut matches: Vec<u64> = Vec::new();
            let results = engine.search_with(&new_opts, |_s, lines| {
                matches.extend_from_slice(lines);
                true
            })?;
            let _ = results;
            let matches: Vec<u64> = matches.into_iter().filter(|&l| l != resume).collect();
            if !args.count && !args.quiet && !args.json {
                let po = output::PrintOptions {
                    engine,
                    reader: &reader,
                    opts,
                    no_line_numbers: args.no_line_numbers,
                    color: false,
                };
                output::print_lines(&po, &matches)?;
            }
            from_line = new_count;
        }
        std::thread::sleep(std::time::Duration::from_millis(200));
    }
}

fn follow_search_reset(
    shared: &SharedIndex,
    engine: &SearchEngine,
    args: &SearchArgs,
) -> anyhow::Result<()> {
    // Re-open: the file was rewritten.
    let _ = shared;
    let _ = engine;
    let _ = args;
    anyhow::bail!("file truncated; restart to re-index");
}

pub fn fmt_bytes(n: u64) -> String {
    const UNITS: [&str; 5] = ["B", "KiB", "MiB", "GiB", "TiB"];
    let mut v = n as f64;
    let mut u = 0;
    while v >= 1024.0 && u < UNITS.len() - 1 {
        v /= 1024.0;
        u += 1;
    }
    if u == 0 {
        format!("{n} B")
    } else {
        format!("{v:.1} {}", UNITS[u])
    }
}
