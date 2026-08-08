# Release process (M10)

This document describes how a `loggi` release is cut, signed, notarized, and
published to GitHub Releases. The mechanical steps live in
`.github/workflows/release.yml`; this file is the human-facing guide.

## TL;DR

```bash
# 1. Make sure you're on a green main, version is bumped, and CHANGELOG is
#    up to date.
git checkout main
git pull --rebase
# (bump workspace.version in Cargo.toml + commit, if not done)

# 2. Tag with semver (short form — the workflow trims trailing zero
#    components, so v1.0 matches Cargo's 1.0.0).
git tag -a v1.0 -m "v1.0"
git push origin v1.0

# 3. The release workflow takes it from there: matrix build, package every
#    target format, sign/notarize where the secrets are wired, upload to
#    GitHub Releases.
```

## Trigger

`.github/workflows/release.yml` fires on any of:

1. **Tag push** — `git tag -a v1.0 -m "v1.0" && git push origin v1.0`.
   The CI-friendly path; the tag is the only input, the body is
   auto-generated.
2. **GitHub Release published** — `Releases > New release > Publish` in the
   UI (or `gh release create` from the CLI). The release body the user
   wrote in the UI is preserved; the workflow only attaches the installer
   assets to it. This is the recommended path when you want to ship
   hand-written release notes.
3. **Workflow dispatch** — `Run workflow` from the Actions tab. Useful for
   re-running a build (e.g. when the signing secrets rotated) without
   bumping the version.

In all three cases the tag (e.g. `v1.0`) drives the package version
(`-Ploggi.version=1.0`). The Cargo workspace version is full semver
(`1.0.0`); the workflow trims trailing zero components so tags and
installers use the short form `1.0`.

## What gets built

The matrix job runs on `ubuntu-latest`, `macos-latest`, `windows-latest`.
Each runner builds the release JNI cdylib (`-Ploggi.jni.profile=release`),
then assembles the native installers via `gradlew :desktopApp:packageDmg |
packageExe | packageDeb | packageRpm` (with `packagePkg` as best-effort).

| OS | Formats |
|---|---|
| Windows | `.exe` (NSIS) |
| macOS | `.dmg` + `.pkg` |
| Linux | `.deb` (Ubuntu/Debian) + `.rpm` (RHEL/Fedora/SUSE) |

The Rust cdylib is bundled inside the app image under `natives/<os>-<arch>/`
and extracted + `System.load`ed at runtime. A smoke step at the end of each
runner opens a 1 GiB generated log and runs a search to confirm the JNI
host loads, the index path works, and the search returns matches.

## Signing & notarization (best-effort, gated on secrets)

| OS | Tool | When wired |
|---|---|---|
| macOS | `codesign` (Developer ID) + `notarytool` | when `MACOS_SIGNING_IDENTITY`, `MACOS_SIGNING_KEYCHAIN`, `MACOS_NOTARIZATION_PROFILE` secrets are set on the runner; otherwise the build is unsigned and notarization is skipped (the build still runs and produces the `.dmg` so unsigned local installs are possible). |
| Windows | `signtool` (Authenticode) | when `WINDOWS_SIGNTOOL_PATH` + cert secrets are present; otherwise the `.exe` is unsigned. |
| Linux | (no signing required) | `.deb` / `.rpm` are signed only if the runner has GPG / RPM-signing keychain, which the default runner does not. |

The gradle DSL only enables signing when the matching env vars are set
(`desktopApp/build.gradle.kts`); an unsigned build is the default local
output. CI uploads both signed and unsigned artifacts (the unsigned
artifact is the same path with `.unsigned` suffix in the name) so the
release can be re-signed externally if needed.

## Uploading to GitHub Releases

Each runner uploads its artifacts via `softprops/action-gh-release@v2`.
The release body is auto-generated from the merged PR titles since the
last tag (`actions/github-script`); edit it before publishing if you want
to.

## SHA256SUMS

The matrix runs `sha256sum` (or `certutil -hashfile` on Windows) over every
uploaded artifact and the **release** job stitches the results into a single
`SHA256SUMS` file at the root of the release assets.

## Versioning

- workspace version lives in `Cargo.toml` `[workspace.package].version`.
- The release tag must match `v<version>` (the workflow enforces this).
- The desktop package version is driven by `-Ploggi.version=<version>`,
  which falls back to the Cargo workspace version when not set.

## Post-release checklist

- [ ] Verify the GitHub Release has all expected installers.
- [ ] Spot-check the `SHA256SUMS`.
- [ ] On macOS, verify the `.dmg` is signed + notarized (Gatekeeper accepts
      it on a clean install).
- [ ] On Windows, verify the `.exe` is signed (SmartScreen / Authenticode
      report).
- [ ] On Linux, install the `.deb` and `.rpm` and confirm Loggi launches.
- [ ] Run the full smoke pass on at least one OS (open a 10 GiB log,
      search, follow, close; RSS must plateau — `docs/perf.md`).

## Rollback

Tagging a previous version's commit again is the simplest rollback. If the
bad release is already in the wild, mark it as a draft on the GitHub
Release page so the assets are still discoverable for archival but not
promoted as "latest".
