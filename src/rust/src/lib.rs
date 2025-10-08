use memmap2::Mmap;
use serde::{Deserialize, Serialize};
use std::ffi::{CStr, CString};
use std::fs::File;
use std::io::{BufRead, BufReader};
use std::os::raw::c_char;
use std::sync::Mutex;

lazy_static::lazy_static! {
    static ref MMAP_CACHE: Mutex<std::collections::HashMap<String, std::sync::Arc<Mmap>>> = Mutex::new(std::collections::HashMap::new());
}

#[derive(Serialize, Deserialize)]
struct LogLinesResponse {
    lines: Vec<String>,
    total_lines: usize,
}

#[derive(Serialize, Deserialize)]
struct SearchResult {
    line_number: usize,
    content: String,
}

#[derive(Serialize, Deserialize)]
struct SearchResponse {
    matches: Vec<SearchResult>,
}

/// Reads a specified range of lines from a log file.
/// 
/// # Arguments
/// * `file_path` - A C string pointer to the path of the file to read
/// * `start_line` - The 1-indexed line number to start reading from
/// * `line_count` - The number of lines to read
/// 
/// # Returns
/// A C string pointer containing the JSON response with the requested lines and total line count,
/// or null pointer if an error occurs
#[no_mangle]
pub extern "C" fn read_log_lines(file_path: *const c_char, start_line: usize, line_count: usize) -> *mut c_char {
    if file_path.is_null() {
        return std::ptr::null_mut();
    }

    let path = unsafe {
        match CStr::from_ptr(file_path).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        }
    };

    // Try to get from cache first
    let mut cache = MMAP_CACHE.lock().unwrap();
    let mmap = if let Some(existing) = cache.get(path) {
        std::sync::Arc::clone(existing)
    } else {
        // Create new mapping
        let file = match File::open(path) {
            Ok(f) => f,
            Err(_) => return std::ptr::null_mut(),
        };
        
        let mmap = match unsafe { Mmap::map(&file) } {
            Ok(m) => m,
            Err(_) => return std::ptr::null_mut(),
        };
        
        let mmap_arc = std::sync::Arc::new(mmap);
        cache.insert(path.to_string(), std::sync::Arc::clone(&mmap_arc));
        mmap_arc
    };
    drop(cache); // Release the lock

    // Count total lines
    let mmap_slice = &mmap[..];
    let total_lines = mmap_slice
        .split(|&b| b == b'\n')
        .count();

    // Extract requested lines
    let mut lines = Vec::new();
    let mut current_line = 0;
    let mut start_pos = 0;

    // Skip to start_line (0-indexed, but start_line is 1-indexed in function)
    for (i, &byte) in mmap_slice.iter().enumerate() {
        if byte == b'\n' {
            current_line += 1;
            
            if current_line == start_line {
                start_pos = i + 1;
                break;
            }
        }
    }

    // If we haven't reached start_line, start from the beginning
    if current_line < start_line {
        start_pos = mmap_slice.len();
    }

    // Collect requested number of lines
    let mut collected = 0;
    let mut line_start = start_pos;
    
    for i in start_pos..mmap_slice.len() {
        if mmap_slice[i] == b'\n' {
            if collected >= line_count {
                break;
            }
            
            let line = &mmap_slice[line_start..i];
            if let Ok(s) = std::str::from_utf8(line) {
                lines.push(s.to_string());
            }
            collected += 1;
            line_start = i + 1;
        }
    }
    
    // Handle last line if it doesn't end with newline
    if collected < line_count && line_start < mmap_slice.len() {
        let line = &mmap_slice[line_start..];
        if let Ok(s) = std::str::from_utf8(line) {
            lines.push(s.to_string());
        }
    }

    let response = LogLinesResponse {
        lines,
        total_lines,
    };

    let json_str = match serde_json::to_string(&response) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };

    match CString::new(json_str) {
        Ok(c_str) => c_str.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Searches for a text pattern in a log file.
/// 
/// # Arguments
/// * `file_path` - A C string pointer to the path of the file to search
/// * `query` - A C string pointer to the text pattern to search for
/// * `case_sensitive` - Whether the search should be case sensitive
/// 
/// # Returns
/// A C string pointer containing the JSON response with the matches found,
/// or null pointer if an error occurs
#[no_mangle]
pub extern "C" fn search_log_lines(file_path: *const c_char, query: *const c_char, case_sensitive: bool) -> *mut c_char {
    if file_path.is_null() || query.is_null() {
        return std::ptr::null_mut();
    }

    let path = unsafe {
        match CStr::from_ptr(file_path).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        }
    };

    let search_query = unsafe {
        match CStr::from_ptr(query).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        }
    };

    let file = match File::open(path) {
        Ok(f) => f,
        Err(_) => return std::ptr::null_mut(),
    };

    let reader = BufReader::new(file);
    let mut matches = Vec::new();

    for (line_number, result) in reader.lines().enumerate() {
        if let Ok(line) = result {
            let contains_query = if case_sensitive {
                line.contains(search_query)
            } else {
                line.to_lowercase().contains(&search_query.to_lowercase())
            };

            if contains_query {
                matches.push(SearchResult {
                    line_number: line_number + 1, // 1-indexed
                    content: line.clone(),
                });
            }
        }
    }

    let response = SearchResponse {
        matches,
    };

    let json_str = match serde_json::to_string(&response) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };

    match CString::new(json_str) {
        Ok(c_str) => c_str.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Performs a regex search for a pattern in a log file.
/// 
/// # Arguments
/// * `file_path` - A C string pointer to the path of the file to search
/// * `pattern` - A C string pointer to the regex pattern to search for
/// * `case_sensitive` - Whether the search should be case sensitive
/// 
/// # Returns
/// A C string pointer containing the JSON response with the matches found,
/// or null pointer if an error occurs
#[no_mangle]
pub extern "C" fn regex_search_log_lines(file_path: *const c_char, pattern: *const c_char, case_sensitive: bool) -> *mut c_char {
    if file_path.is_null() || pattern.is_null() {
        return std::ptr::null_mut();
    }

    let path = unsafe {
        match CStr::from_ptr(file_path).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        }
    };

    let pattern_str = unsafe {
        match CStr::from_ptr(pattern).to_str() {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        }
    };

    // Build regex pattern with appropriate flags
    let regex_pattern = if case_sensitive {
        pattern_str.to_string()
    } else {
        format!("(?i){}", pattern_str)
    };

    let regex = match regex::Regex::new(&regex_pattern) {
        Ok(r) => r,
        Err(_) => return std::ptr::null_mut(), // Invalid regex pattern
    };

    // Use the same approach as search_log_lines for consistency
    let file = match File::open(&path) {
        Ok(f) => f,
        Err(_) => return std::ptr::null_mut(),
    };

    let reader = BufReader::new(file);
    let mut matches = Vec::new();

    for (idx, result) in reader.lines().enumerate() {
        if let Ok(line) = result {
            if regex.is_match(&line) {
                matches.push(SearchResult {
                    line_number: idx + 1, // 1-indexed
                    content: line.clone(),
                });
            }
        }
    }
    
    let response = SearchResponse {
        matches,
    };

    let json_str = match serde_json::to_string(&response) {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };

    match CString::new(json_str) {
        Ok(c_str) => c_str.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Gets the total number of lines in a log file.
/// 
/// # Arguments
/// * `file_path` - A C string pointer to the path of the file to count lines for
/// 
/// # Returns
/// The total number of lines in the file, or 0 if an error occurs
#[no_mangle]
pub extern "C" fn get_total_line_count(file_path: *const c_char) -> usize {
    if file_path.is_null() {
        return 0;
    }

    let path = unsafe {
        match CStr::from_ptr(file_path).to_str() {
            Ok(s) => s,
            Err(_) => return 0,
        }
    };

    let file = match File::open(path) {
        Ok(f) => f,
        Err(_) => return 0,
    };

    let reader = BufReader::new(file);
    reader.lines().count()
}

/// Frees a C string previously allocated by this library.
/// 
/// # Arguments
/// * `ptr` - A pointer to the C string to be freed
#[no_mangle]
pub extern "C" fn free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe {
            let _ = CString::from_raw(ptr);
        }
    }
}

// Add lazy_static dependency
extern crate lazy_static;