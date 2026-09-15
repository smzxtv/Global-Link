use std::path::{Path, PathBuf};
use std::process::Command;

use tauri::path::BaseDirectory;
use tauri::{AppHandle, Manager};

pub const CORE_SUBDIR: &str = "sing-box";

pub fn exe_name() -> &'static str {
    if cfg!(windows) {
        "sing-box.exe"
    } else {
        "sing-box"
    }
}

/// Resource base directory for the installed bundle.
pub fn resource_root(app: &AppHandle) -> PathBuf {
    app.path()
        .resource_dir()
        .or_else(|_| app.path().resolve("", BaseDirectory::Resource))
        .unwrap_or_default()
}

/// All plausible locations for a bundled file, covering every layout seen in
/// the wild:
///
/// 1. `<resources>/<subdir>/<file>` — canonical layout (`resources/sing-box/…`)
/// 2. `<resources>/<file>`          — flattened layout some NSIS/MSI bundlers
///    produce when the `resources` map copies file-by-file (this is what bit
///    end users: the core WAS shipped, just one directory level up)
/// 3. `<exe_dir>/<subdir>/<file>` and `<exe_dir>/<file>` — portable builds
/// 4. `<manifest>/resources/…` — both layouts when developing from `src-tauri`
pub fn resource_candidates(
    resource_root: PathBuf,
    exe_dir: Option<PathBuf>,
    manifest_dir: &Path,
    subdir: &str,
    file_name: &str,
) -> Vec<PathBuf> {
    let mut out: Vec<PathBuf> = Vec::new();
    let mut push = |p: PathBuf| {
        if !out.contains(&p) {
            out.push(p);
        }
    };

    if !resource_root.as_os_str().is_empty() {
        push(resource_root.join(subdir).join(file_name));
        push(resource_root.join(file_name));
    }
    if let Some(dir) = exe_dir {
        push(dir.join(subdir).join(file_name));
        push(dir.join(file_name));
    }
    push(manifest_dir.join("resources").join(subdir).join(file_name));
    push(manifest_dir.join("resources").join(file_name));
    out
}

/// Resolve the sing-box executable across all known bundle layouts.
pub fn resolve_core_path(app: &AppHandle) -> Option<PathBuf> {
    let exe_dir = std::env::current_exe()
        .ok()
        .and_then(|e| e.parent().map(|p| p.to_path_buf()));
    resource_candidates(
        resource_root(app),
        exe_dir,
        Path::new(env!("CARGO_MANIFEST_DIR")),
        CORE_SUBDIR,
        exe_name(),
    )
    .into_iter()
    .find(|p| p.is_file())
}

/// Human-readable list of the directories searched for bundled resources,
/// included in error messages so end users can attach it to bug reports.
pub fn describe_search(app: &AppHandle) -> String {
    let exe_dir = std::env::current_exe()
        .ok()
        .and_then(|e| e.parent().map(|p| p.to_path_buf()));
    let mut dirs: Vec<String> = Vec::new();
    for c in resource_candidates(
        resource_root(app),
        exe_dir,
        Path::new(env!("CARGO_MANIFEST_DIR")),
        CORE_SUBDIR,
        exe_name(),
    ) {
        if let Some(d) = c.parent() {
            let s = d.display().to_string();
            if !dirs.contains(&s) {
                dirs.push(s);
            }
        }
    }
    dirs.join("；")
}

/// Read the first line of `sing-box version` output.
pub fn core_version(exe: &Path) -> String {
    match Command::new(exe).arg("version").output() {
        Ok(out) if out.status.success() => {
            let text = String::from_utf8_lossy(&out.stdout);
            let first = text.lines().next().unwrap_or("unknown");
            first.trim().to_string()
        }
        _ => "unknown".to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn candidates_cover_flattened_and_subdir_layouts() {
        let root = PathBuf::from("C:\\app");
        let exe = Some(PathBuf::from("C:\\app\\bin"));
        let manifest = Path::new("D:\\src\\src-tauri");
        let c = resource_candidates(root, exe, manifest, CORE_SUBDIR, "sing-box.exe");

        // canonical subdir layout in the resource dir
        assert!(c.contains(&PathBuf::from("C:\\app\\sing-box\\sing-box.exe")));
        // flattened layout (the one that shipped broken to end users)
        assert!(c.contains(&PathBuf::from("C:\\app\\sing-box.exe")));
        // next to the executable, both layouts
        assert!(c.contains(&PathBuf::from("C:\\app\\bin\\sing-box\\sing-box.exe")));
        assert!(c.contains(&PathBuf::from("C:\\app\\bin\\sing-box.exe")));
        // dev tree, both layouts
        assert!(c.contains(&PathBuf::from(
            "D:\\src\\src-tauri\\resources\\sing-box\\sing-box.exe"
        )));
        assert!(c.contains(&PathBuf::from("D:\\src\\src-tauri\\resources\\sing-box.exe")));
        // no duplicates
        let unique: std::collections::HashSet<_> = c.iter().collect();
        assert_eq!(unique.len(), c.len());
    }

    #[test]
    fn candidates_work_without_exe_dir() {
        let root = PathBuf::from("C:\\app");
        let c = resource_candidates(root, None, Path::new("D:\\src\\src-tauri"), CORE_SUBDIR, "wintun.dll");
        assert!(c.contains(&PathBuf::from("C:\\app\\sing-box\\wintun.dll")));
        assert!(c.contains(&PathBuf::from("C:\\app\\wintun.dll")));
    }
}