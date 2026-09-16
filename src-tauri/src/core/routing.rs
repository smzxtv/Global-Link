//! Windows routing helper for VPN (TUN) mode.
//!
//! sing-box creates the wintun adapter internally when `auto_route` is enabled,
//! but it cannot clean up after a crashed process or a stale adapter left behind
//! by a previous session. This module handles the lifecycle around that:
//!
//! 1. **Pre-flight** — confirm elevation and that `wintun.dll` is reachable.
//! 2. **Recovery** — remove orphaned wintun/sing-box adapters and phantom
//!    devices left behind by crashed sessions.
//! 3. **Cleanup** — flush the DNS cache and verify the default route is restored
//!    once sing-box exits.
//! 4. **Diagnostics** — snapshot the current adapter and route table for the UI.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use serde::Serialize;
use tauri::{AppHandle, Emitter};

use crate::core::elevate::is_elevated;
use crate::core::singbox;

/// Human-readable snapshot of the routing state, surfaced to the frontend.
#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct RoutingDiagnostics {
    pub elevated: bool,
    pub wintun_available: bool,
    pub wintun_path: Option<String>,
    pub active_tun_adapters: Vec<String>,
    pub default_routes: Vec<String>,
    pub dns_servers: Vec<String>,
}

/// Pre-flight result before starting a TUN session.
#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct PreflightReport {
    pub can_start: bool,
    pub elevated: bool,
    pub wintun_available: bool,
    pub stale_adapters: Vec<String>,
    pub messages: Vec<String>,
}

/// Locate the bundled `wintun.dll` across all known bundle layouts
/// (see `singbox::resource_candidates`).
fn wintun_dll_path(app: &AppHandle) -> Option<PathBuf> {
    let exe_dir = std::env::current_exe()
        .ok()
        .and_then(|e| e.parent().map(|p| p.to_path_buf()));
    singbox::resource_candidates(
        singbox::resource_root(app),
        exe_dir,
        Path::new(env!("CARGO_MANIFEST_DIR")),
        singbox::CORE_SUBDIR,
        "wintun.dll",
    )
    .into_iter()
    .find(|p| p.is_file())
}

/// Run a PowerShell command and return stdout, or an error string.
fn run_powershell(script: &str) -> Result<String, String> {
    let output = std::process::Command::new("powershell.exe")
        .args(["-NoProfile", "-NonInteractive", "-Command", script])
        .output()
        .map_err(|e| format!("powershell unavailable: {e}"))?;
    Ok(String::from_utf8_lossy(&output.stdout).into_owned())
}

/// Find network adapters whose description mentions wintun or sing-box.
fn find_tun_adapters() -> Vec<String> {
    let script = r#"Get-NetAdapter | Where-Object { $_.InterfaceDescription -match 'wintun|sing-box' } | Select-Object -ExpandProperty Name"#;
    match run_powershell(script) {
        Ok(out) => out
            .lines()
            .map(|l| l.trim().to_string())
            .filter(|l| !l.is_empty())
            .collect(),
        Err(_) => Vec::new(),
    }
}

/// Read the current default (0.0.0.0/0) routes.
fn find_default_routes() -> Vec<String> {
    let script = r#"Get-NetRoute -DestinationPrefix '0.0.0.0/0' | ForEach-Object { "$($_.InterfaceAlias) -> $($_.NextHop) (metric $($_.RouteMetric))" }"#;
    match run_powershell(script) {
        Ok(out) => out
            .lines()
            .map(|l| l.trim().to_string())
            .filter(|l| !l.is_empty())
            .collect(),
        Err(_) => Vec::new(),
    }
}

/// Read the system DNS servers.
fn find_dns_servers() -> Vec<String> {
    let script = r#"Get-DnsClientServerAddress | Where-Object { $_.AddressFamily -eq 2 } | ForEach-Object { $_.ServerAddresses -join ',' }"#;
    match run_powershell(script) {
        Ok(out) => out
            .lines()
            .map(|l| l.trim().to_string())
            .filter(|l| !l.is_empty())
            .collect(),
        Err(_) => Vec::new(),
    }
}

/// Pre-flight check before starting a TUN session.
pub fn preflight(app: &AppHandle) -> PreflightReport {
    let elevated = is_elevated();
    let wintun_path = wintun_dll_path(app);
    let wintun_available = wintun_path.is_some();
    let stale_adapters = find_tun_adapters();

    let mut messages: Vec<String> = Vec::new();
    let mut can_start = true;

    if !elevated {
        can_start = false;
        messages.push("VPN mode requires administrator privileges — the app will request elevation.".into());
    }
    if !wintun_available {
        can_start = false;
        messages.push("wintun.dll not found — TUN adapter cannot be created.".into());
    }
    if !stale_adapters.is_empty() {
        messages.push(format!(
            "found {} orphaned TUN adapter(s): {} — will attempt cleanup on disconnect.",
            stale_adapters.len(),
            stale_adapters.join(", ")
        ));
    }
    if can_start {
        messages.push("ready to start TUN session.".into());
    }

    PreflightReport {
        can_start,
        elevated,
        wintun_available,
        stale_adapters,
        messages,
    }
}

/// Full routing snapshot for diagnostics.
pub fn diagnostics(app: &AppHandle) -> RoutingDiagnostics {
    let wintun_path = wintun_dll_path(app);
    RoutingDiagnostics {
        elevated: is_elevated(),
        wintun_available: wintun_path.is_some(),
        wintun_path: wintun_path.map(|p| p.display().to_string()),
        active_tun_adapters: find_tun_adapters(),
        default_routes: find_default_routes(),
        dns_servers: find_dns_servers(),
    }
}

/// Flush the DNS cache and emit a status event.
pub fn flush_dns(app: &AppHandle) -> Result<String, String> {
    let output = std::process::Command::new("ipconfig")
        .arg("/flushdns")
        .output()
        .map_err(|e| format!("ipconfig unavailable: {e}"))?;
    let msg = if output.status.success() {
        "DNS cache flushed".into()
    } else {
        format!("ipconfig exited with {}", output.status)
    };
    let _ = app.emit("core-log", format!("[routing] {msg}"));
    Ok(msg)
}

/// Verify the default route is present and report its interface.
pub fn verify_default_route(app: &AppHandle) -> Result<String, String> {
    let routes = find_default_routes();
    let msg = if routes.is_empty() {
        "no default route found — connectivity may be broken".into()
    } else {
        format!("default route via: {}", routes.join("; "))
    };
    let _ = app.emit("core-log", format!("[routing] {msg}"));
    Ok(msg)
}

/// Full post-session cleanup: flush DNS, verify route, report stale adapters.
pub fn cleanup(app: &AppHandle) -> Result<HashMap<String, String>, String> {
    let mut report: HashMap<String, String> = HashMap::new();
    report.insert("dns".into(), flush_dns(app)?);
    report.insert("route".into(), verify_default_route(app)?);
    let stale = find_tun_adapters();
    report.insert(
        "stale_adapters".into(),
        if stale.is_empty() {
            "none".into()
        } else {
            stale.join(", ")
        },
    );
    Ok(report)
}

/// Delete phantom wintun devices left behind by hard-killed/crashed sing-box
/// processes. sing-box normally destroys its wintun adapter on graceful exit,
/// but a crash or a hard kill (`child.kill()`) leaves a device in "Unknown"
/// state that makes the NEXT `create adapter` fail with "Cannot create a file
/// when that file already exists" — i.e. VPN mode stays broken until the phantom
/// is removed. `pnputil /remove-device` clears them immediately, no reboot.
/// Only "Unknown"-state devices are touched; a live adapter reports "OK".
/// Returns the friendly names of the devices that were removed.
fn remove_phantom_wintun_devices() -> Vec<String> {
    let script = r#"
$removed = @()
Get-PnpDevice -ErrorAction SilentlyContinue |
  Where-Object { $_.FriendlyName -match 'sing-tun|wintun' -and $_.Status -eq 'Unknown' } |
  ForEach-Object {
    $null = pnputil /remove-device "$($_.InstanceId)" 2>$null
    $removed += $_.FriendlyName
  }
$removed -join ', '
"#;
    match run_powershell(script) {
        Ok(out) => out
            .lines()
            .flat_map(|l| l.split(','))
            .map(|l| l.trim().to_string())
            .filter(|l| !l.is_empty())
            .collect(),
        Err(_) => Vec::new(),
    }
}

/// Remove orphaned TUN adapters left by a crashed session.
/// Returns the list of adapters that were found (removal may require a reboot).
pub fn recover_stale_adapters(app: &AppHandle) -> Result<Vec<String>, String> {
    let phantoms = remove_phantom_wintun_devices();
    if !phantoms.is_empty() {
        let _ = app.emit(
            "core-log",
            format!(
                "[routing] removed {} phantom wintun device(s) left by crashed sessions: {}",
                phantoms.len(),
                phantoms.join(", ")
            ),
        );
    }
    let stale = find_tun_adapters();
    if stale.is_empty() {
        let _ = app.emit("core-log", "[routing] no orphaned TUN adapters detected".to_string());
        return Ok(stale);
    }
    let _ = app.emit(
        "core-log",
        format!(
            "[routing] found {} orphaned adapter(s): {}",
            stale.len(),
            stale.join(", ")
        ),
    );
    Ok(stale)
}

/// Called before spawning a TUN session: pre-flight + recovery.
pub fn prepare_for_tun(app: &AppHandle) -> Result<PreflightReport, String> {
    let _ = recover_stale_adapters(app);
    let report = preflight(app);
    for msg in &report.messages {
        let _ = app.emit("core-log", format!("[routing] {msg}"));
    }
    Ok(report)
}

/// Called after a TUN session ends: cleanup + diagnostics.
pub fn teardown_after_tun(app: &AppHandle) -> Result<(), String> {
    let _ = cleanup(app);
    Ok(())
}
