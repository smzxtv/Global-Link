//! GitHub Releases-based update checking, verification and download.
//!
//! The update source is `owner/repo`, configured at build time through the
//! `AETHON_REPLICA_UPDATE_REPO` environment variable (e.g. `me/aethon-replica`).
//! When unset, update checks report a clear "not configured" error instead of
//! silently doing nothing.

use std::fs::File;
use std::io::{self, Read, Write};
use std::path::Path;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tauri::{Manager, Emitter};

/// The update source `owner/repo`. Configured at build time through the
/// `AETHON_REPLICA_UPDATE_REPO` environment variable (e.g. `me/aethon-replica`).
/// When unset, update checks report a clear "not configured" error.
pub fn update_repo() -> &'static str {
    option_env!("AETHON_REPLICA_UPDATE_REPO").unwrap_or("")
}

const USER_AGENT: &str = "global-link-updater/2.0";

#[derive(Debug, Deserialize)]
struct GhRelease {
    tag_name: String,
    body: String,
    published_at: Option<String>,
    assets: Vec<GhAsset>,
}

#[derive(Debug, Deserialize)]
struct GhAsset {
    name: String,
    browser_download_url: String,
    size: u64,
    digest: Option<String>,
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct UpdateAsset {
    pub name: String,
    pub url: String,
    pub sha256: Option<String>,
    pub size: u64,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    pub update_repo: String,
    pub latest_version: String,
    pub published_at: Option<String>,
    pub release_notes: String,
    pub update_available: bool,
    pub assets: Vec<UpdateAsset>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DownloadResult {
    pub asset: String,
    pub saved_path: String,
    pub verified: bool,
    pub launched: bool,
}
fn repo_configured() -> Result<(), String> {
    let repo = update_repo();
    if repo.is_empty() || !repo.contains('/') {
        return Err(
            "update source not configured (set AETHON_REPLICA_UPDATE_REPO=\"owner/repo\")"
                .to_string(),
        );
    }
    Ok(())
}

fn fetch_latest() -> Result<GhRelease, String> {
    let url = format!("https://api.github.com/repos/{}/releases/latest", update_repo());
    let body = ureq::get(&url)
        .set("User-Agent", USER_AGENT)
        .set("Accept", "application/vnd.github+json")
        .call()
        .map_err(|e| format!("update check failed: {e}"))?
        .into_string()
        .map_err(|e| format!("cannot read update response: {e}"))?;
    serde_json::from_str(&body).map_err(|e| format!("cannot parse release info: {e}"))
}

/// Naive semver compare: parses leading numeric segments.
pub fn version_gt(latest: &str, current: &str) -> bool {
    let parse = |s: &str| -> Vec<u64> {
        s.split(|c: char| !c.is_ascii_digit())
            .filter_map(|p| p.parse().ok())
            .collect()
    };
    let a = parse(latest);
    let b = parse(current);
    for (x, y) in a.iter().zip(b.iter()) {
        if x != y {
            return x > y;
        }
    }
    a.len() > b.len()
}

pub fn check(current_version: &str) -> Result<UpdateInfo, String> {
    repo_configured()?;
    let rel = fetch_latest()?;
    let latest = rel.tag_name.trim_start_matches('v').to_string();
    let update_available = version_gt(&latest, current_version);
    let assets = rel
        .assets
        .into_iter()
        .map(|a| UpdateAsset {
            sha256: a
                .digest
                .as_deref()
                .map(|d| d.strip_prefix("sha256:").unwrap_or(d).to_lowercase()),
            name: a.name,
            url: a.browser_download_url,
            size: a.size,
        })
        .collect();
    Ok(UpdateInfo {
        update_repo: update_repo().to_string(),
        latest_version: latest,
        published_at: rel.published_at,
        release_notes: rel.body,
        update_available,
        assets,
    })
}

/// Compute the SHA-256 hex digest of a file.
pub fn sha256_digest(path: &Path) -> Result<String, String> {
    let mut f = File::open(path).map_err(|e| format!("cannot open {}: {e}", path.display()))?;
    let mut hasher = Sha256::new();
    io::copy(&mut f, &mut hasher).map_err(|e| format!("cannot hash {}: {e}", path.display()))?;
    Ok(hex::encode(hasher.finalize()))
}

/// Download `asset_name` from the latest release into the app cache dir,
/// verifying its SHA-256 against the GitHub-issued digest when present.
///
/// Progress is reported over the `update-progress` event:
/// `{ asset, received, total }`.
pub fn download_and_install(
    app: &tauri::AppHandle,
    asset_name: &str,
) -> Result<DownloadResult, String> {
    repo_configured()?;
    let rel = fetch_latest()?;
    let asset = rel
        .assets
        .into_iter()
        .find(|a| a.name == asset_name)
        .ok_or_else(|| format!("asset not found in latest release: {asset_name}"))?;

    let cache_dir = app
        .path()
        .app_cache_dir()
        .map_err(|e| format!("cannot resolve cache dir: {e}"))?;
    std::fs::create_dir_all(&cache_dir)
        .map_err(|e| format!("cannot create cache dir: {e}"))?;
    let dest = cache_dir.join(&asset.name);

    let response = ureq::get(&asset.browser_download_url)
        .set("User-Agent", USER_AGENT)
        .call()
        .map_err(|e| format!("download failed: {e}"))?;
    let mut reader = response.into_reader();
    let mut file = File::create(&dest).map_err(|e| format!("cannot create file: {e}"))?;

    let mut buf = [0u8; 65536];
    let mut received: u64 = 0;
    loop {
        let n = reader
            .read(&mut buf)
            .map_err(|e| format!("download interrupted: {e}"))?;
        if n == 0 {
            break;
        }
        file.write_all(&buf[..n])
            .map_err(|e| format!("write failed: {e}"))?;
        received += n as u64;
        let _ = app.emit(
            "update-progress",
            serde_json::json!({ "asset": asset.name, "received": received, "total": asset.size }),
        );
    }
    file.flush().ok();

    let actual = sha256_digest(&dest)?;
    let verify_expected = asset
        .digest
        .as_deref()
        .map(|d| d.trim().trim_start_matches("sha256:").to_lowercase());
    let verified = match &verify_expected {
        Some(expected) => {
            if expected != &actual {
                let _ = std::fs::remove_file(&dest);
                return Err(format!(
                    "SHA-256 mismatch for {} (expected {expected}, got {actual})",
                    asset.name
                ));
            }
            true
        }
        None => false,
    };

    let launched = launch_installer(&dest);
    Ok(DownloadResult {
        asset: asset.name,
        saved_path: dest.display().to_string(),
        verified,
        launched,
    })
}

#[cfg(windows)]
fn launch_installer(path: &Path) -> bool {
    // NSIS-style silent install; the installer's own manifest triggers UAC if needed.
    match std::process::Command::new(path).arg("/S").spawn() {
        Ok(_) => true,
        Err(_) => false,
    }
}

#[cfg(not(windows))]
fn launch_installer(_path: &Path) -> bool {
    false
}