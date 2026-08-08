//! CLI golden tests against generated corpora.

use std::io::Write;
use std::path::Path;

use assert_cmd::Command;
use predicates::prelude::*;

fn write_corpus(dir: &Path) -> std::path::PathBuf {
    let p = dir.join("app.log");
    let mut f = std::fs::File::create(&p).unwrap();
    for i in 0..100u64 {
        writeln!(
            f,
            "2026-08-07 12:00:00.{} INFO  request {i:04} handled",
            i % 1000
        )
        .unwrap();
    }
    for i in 0..10u64 {
        writeln!(
            f,
            "2026-08-07 12:00:05.{} ERROR request {i:04} failed retrying",
            i % 1000
        )
        .unwrap();
    }
    writeln!(f, "2026-08-07 12:00:06.000 INFO  request failed analysis").unwrap();
    p
}

#[test]
fn info_output() {
    let dir = tempfile::tempdir().unwrap();
    let p = write_corpus(dir.path());
    Command::cargo_bin("loggi")
        .unwrap()
        .arg("info")
        .arg(&p)
        .assert()
        .success()
        .stdout(predicate::str::contains("lines:      111"))
        .stdout(predicate::str::contains("encoding:   UTF-8"))
        .stdout(predicate::str::contains("index time:"));
}

#[test]
fn info_json() {
    let dir = tempfile::tempdir().unwrap();
    let p = write_corpus(dir.path());
    let out = Command::cargo_bin("loggi")
        .unwrap()
        .args(["info", "--json"])
        .arg(&p)
        .assert()
        .success();
    let stdout = String::from_utf8(out.get_output().stdout.clone()).unwrap();
    let v: serde_json::Value = serde_json::from_str(&stdout).unwrap();
    assert_eq!(v["line_count"], 111);
    assert_eq!(v["encoding"], "UTF-8");
    assert!(v["size"].as_u64().unwrap() > 0);
}

#[test]
fn search_literal_and_count() {
    let dir = tempfile::tempdir().unwrap();
    let p = write_corpus(dir.path());
    Command::cargo_bin("loggi")
        .unwrap()
        .args(["search", "-F", "ERROR"])
        .arg(&p)
        .assert()
        .success()
        .stdout(predicate::str::contains(
            "ERROR request 0000 failed retrying",
        ))
        .stdout(predicate::str::contains(
            "ERROR request 0009 failed retrying",
        ));

    Command::cargo_bin("loggi")
        .unwrap()
        .args(["search", "-c", "-F", "ERROR"])
        .arg(&p)
        .assert()
        .success()
        .stdout("10\n");
}

#[test]
fn search_regex_default() {
    let dir = tempfile::tempdir().unwrap();
    let p = write_corpus(dir.path());
    // regex by default: "failed (retrying|analysis)"
    Command::cargo_bin("loggi")
        .unwrap()
        .args(["search", "-c", "failed (retrying|analysis)"])
        .arg(&p)
        .assert()
        .success()
        .stdout("11\n");
}

#[test]
fn search_ignore_case() {
    let dir = tempfile::tempdir().unwrap();
    let p = write_corpus(dir.path());
    Command::cargo_bin("loggi")
        .unwrap()
        .args(["search", "-c", "-i", "-F", "info  request 0001"])
        .arg(&p)
        .assert()
        .success()
        .stdout("1\n");
}

#[test]
fn search_context() {
    let dir = tempfile::tempdir().unwrap();
    let p = write_corpus(dir.path());
    Command::cargo_bin("loggi")
        .unwrap()
        .args(["search", "-F", "-B", "1", "-A", "1", "ERROR request 0000"])
        .arg(&p)
        .assert()
        .success()
        .stdout(predicate::str::contains("99-2026"))
        .stdout(predicate::str::contains("100:2026"))
        .stdout(predicate::str::contains("101-2026"));
}

#[test]
fn search_quiet_exit_codes() {
    let dir = tempfile::tempdir().unwrap();
    let p = write_corpus(dir.path());
    Command::cargo_bin("loggi")
        .unwrap()
        .args(["search", "-q", "-F", "ERROR"])
        .arg(&p)
        .assert()
        .code(0)
        .stdout("");
    Command::cargo_bin("loggi")
        .unwrap()
        .args(["search", "-q", "-F", "NOPE"])
        .arg(&p)
        .assert()
        .code(1)
        .stdout("");
}

#[test]
fn search_json_ndjson() {
    let dir = tempfile::tempdir().unwrap();
    let p = write_corpus(dir.path());
    let out = Command::cargo_bin("loggi")
        .unwrap()
        .args(["search", "--json", "-F", "ERROR request 0000"])
        .arg(&p)
        .assert()
        .success();
    let stdout = String::from_utf8(out.get_output().stdout.clone()).unwrap();
    let lines: Vec<&str> = stdout.lines().collect();
    assert_eq!(lines.len(), 1);
    let v: serde_json::Value = serde_json::from_str(lines[0]).unwrap();
    assert_eq!(v["type"], "match");
    assert_eq!(v["line_number"], 101); // 1-based
    assert!(!v["matches"].as_array().unwrap().is_empty());
}

#[test]
fn search_line_offset_and_limit() {
    let dir = tempfile::tempdir().unwrap();
    let p = write_corpus(dir.path());
    Command::cargo_bin("loggi")
        .unwrap()
        .args(["search", "-c", "-F", "--line-offset", "100", "ERROR"])
        .arg(&p)
        .assert()
        .success()
        .stdout("10\n");
    Command::cargo_bin("loggi")
        .unwrap()
        .args(["search", "-F", "--limit", "3", "ERROR"])
        .arg(&p)
        .assert()
        .success()
        .stdout(predicate::str::contains("100:2026").and(predicate::str::contains("102:2026")))
        .stdout(predicate::str::contains("103:2026").not());
}

#[test]
fn tail_prints_last_lines() {
    let dir = tempfile::tempdir().unwrap();
    let p = write_corpus(dir.path());
    Command::cargo_bin("loggi")
        .unwrap()
        .args(["tail", "--lines", "2"])
        .arg(&p)
        .assert()
        .success()
        .stdout(predicate::str::contains(
            "ERROR request 0009 failed retrying",
        ))
        .stdout(predicate::str::contains("failed analysis"));
}

#[test]
fn missing_file_error() {
    Command::cargo_bin("loggi")
        .unwrap()
        .arg("search")
        .arg("x")
        .arg("/nonexistent/nope.log")
        .assert()
        .code(2);
}
