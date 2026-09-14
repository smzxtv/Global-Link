import { useEffect, useState } from "react";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { useApp } from "../store";
import type { LogLevel } from "../types";
import {
  checkForUpdates,
  downloadUpdate,
  type UpdateInfo,
  type UpdateProgress,
  type DownloadResult,
} from "../lib/core";

type UpdateStatus = "idle" | "checking" | "available" | "downloading" | "done" | "error";

export default function SettingsPage() {
  const { state, dispatch } = useApp();
  const { settings, appInfo } = state;
  const [updateStatus, setUpdateStatus] = useState<UpdateStatus>("idle");
  const [updateInfo, setUpdateInfo] = useState<UpdateInfo | null>(null);
  const [updateError, setUpdateError] = useState<string | null>(null);
  const [progress, setProgress] = useState<UpdateProgress | null>(null);
  const [lastCheck, setLastCheck] = useState<string>("从未");
  const [downloadResult, setDownloadResult] = useState<DownloadResult | null>(null);
  useEffect(() => {
    const unlisteners: UnlistenFn[] = [];
    listen<UpdateProgress>("update-progress", (e) => {
      setProgress(e.payload);
    }).then((fn) => unlisteners.push(fn));
    listen<DownloadResult>("update-downloaded", (e) => {
      setDownloadResult(e.payload);
      setUpdateStatus("done");
      setProgress(null);
    }).then((fn) => unlisteners.push(fn));
    return () => {
      for (const fn of unlisteners) fn();
    };
  }, []);

  useEffect(() => {
    if (!settings.autoUpdate) return;
    let cancelled = false;
    const doCheck = () => {
      if (cancelled) return;
      checkForUpdates()
        .then(() => setLastCheck(new Date().toLocaleString("zh-CN")))
        .catch(() => setLastCheck(`检查失败 ${new Date().toLocaleString("zh-CN")}`));
    };
    doCheck();
    const ms = settings.autoUpdateHours * 60 * 60 * 1000;
    const interval = setInterval(doCheck, ms);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, [settings.autoUpdate, settings.autoUpdateHours]);
  async function checkNow() {
    setUpdateStatus("checking");
    setUpdateError(null);
    setDownloadResult(null);
    try {
      const info = await checkForUpdates();
      if (info) {
        setUpdateInfo(info);
        setUpdateStatus("available");
        dispatch({ type: "push-log", line: `[更新] 发现新版本: ${info.latestVersion}` });
      } else {
        setUpdateStatus("idle");
        dispatch({ type: "push-log", line: "[更新] 已是最新版本" });
      }
      setLastCheck(new Date().toLocaleString("zh-CN"));
    } catch (err) {
      setUpdateStatus("error");
      setUpdateError(String(err));
      dispatch({ type: "push-log", line: `[更新] ${String(err)}` });
    }
  }

  async function startDownload() {
    if (!updateInfo) return;
    const asset =
      updateInfo.assets.find((a) => a.name.includes("Windows-x64-Installer.exe")) ??
      updateInfo.assets.find((a) => a.name.endsWith(".exe")) ??
      updateInfo.assets[0];
    if (!asset) return;
    setUpdateStatus("downloading");
    setProgress(null);
    dispatch({ type: "push-log", line: `[更新] 正在下载 ${asset.name} (${asset.size} 字节)…` });
    try {
      const result = await downloadUpdate(asset.name);
      dispatch({ type: "push-log", line: `[更新] 已保存到 ${result.savedPath} (校验=${result.verified}, 已启动=${result.launched})` });
    } catch (err) {
      setUpdateStatus("error");
      setUpdateError(String(err));
      dispatch({ type: "push-log", line: `[更新] ${String(err)}` });
    }
  }
  const currentVersion = appInfo?.appVersion ?? "…";

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>设置</h1>
          <p className="muted">路由、代理和更新选项。</p>
        </div>
      </header>

      <section className="card">
        <h2>本地代理</h2>
        <div className="form-row">
          <label className="field">
            <span>SOCKS5 监听端口</span>
            <input
              type="number"
              min={1024}
              max={65535}
              value={settings.socksPort}
              onChange={(e) =>
                dispatch({ type: "set-settings", patch: { socksPort: Number(e.target.value) } })
              }
            />
          </label>
          <label className="field">
            <span>日志级别</span>
            <select
              value={settings.logLevel}
              onChange={(e) =>
                dispatch({ type: "set-settings", patch: { logLevel: e.target.value as LogLevel } })
              }
            >
              {(["error", "warn", "info", "debug", "trace"] as LogLevel[]).map((l) => (
                <option key={l} value={l}>
                  {l}
                </option>
              ))}
            </select>
          </label>
        </div>
      </section>
      <section className="card">
        <h2>更新</h2>
        <div className="form-row">
          <label className="switch-row">
            <input
              type="checkbox"
              checked={settings.autoUpdate}
              onChange={(e) =>
                dispatch({ type: "set-settings", patch: { autoUpdate: e.target.checked } })
              }
            />
            <span>启动时检查，之后每 {settings.autoUpdateHours} 小时检查一次</span>
          </label>
          <label className="switch-row">
            <input
              type="checkbox"
              checked={settings.autoDownload}
              onChange={(e) =>
                dispatch({ type: "set-settings", patch: { autoDownload: e.target.checked } })
              }
            />
            <span>自动下载更新（SHA-256 校验）</span>
          </label>
        </div>

        <div className="form-row" style={{ marginTop: 14 }}>
          <button className="primary" onClick={checkNow} disabled={updateStatus === "checking"}>
            {updateStatus === "checking" ? "检查中…" : "立即检查"}
          </button>
          {updateInfo && updateStatus === "available" && (
            <button className="primary" onClick={startDownload}>
              下载 {updateInfo.latestVersion}
            </button>
          )}
          <span className="muted">上次检查: {lastCheck}</span>
        </div>
        {updateStatus === "downloading" && progress && (
          <div className="update-progress">
            <div className="progress-bar">
              <div
                className="progress-fill"
                style={{
                  width: `${progress.total > 0 ? (progress.received / progress.total) * 100 : 0}%`,
                }}
              />
            </div>
            <span className="muted">
              {(progress.received / 1024 / 1024).toFixed(1)} / {(progress.total / 1024 / 1024).toFixed(1)} MB
            </span>
          </div>
        )}

        {updateStatus === "available" && updateInfo && (
          <div className="update-available">
            <h3>
              {updateInfo.latestVersion} 可用
              {updateInfo.publishedAt && (
                <span className="muted"> · 发布于 {updateInfo.publishedAt}</span>
              )}
            </h3>
            <pre className="release-notes">{updateInfo.releaseNotes}</pre>
          </div>
        )}
        {updateStatus === "done" && downloadResult && (
          <div className="notice">
            更新已下载到 {downloadResult.savedPath}
            {downloadResult.verified && " · SHA-256 校验通过"}
            {downloadResult.launched && " · 安装程序已启动"}
          </div>
        )}

        {updateStatus === "error" && updateError && (
          <div className="notice error">{updateError}</div>
        )}
      </section>

      <section className="card">
        <h2>关于</h2>
        <dl className="about">
          <dt>应用</dt>
          <dd>{appInfo ? `${appInfo.appName} v${currentVersion}` : "环球通 Global Link"}</dd>
          <dt>核心</dt>
          <dd>{appInfo ? appInfo.core.singBoxVersion : "…"}</dd>
          <dt>核心路径</dt>
          <dd>{appInfo?.core.corePath ?? "未获取"}</dd>
          <dt>版本信息</dt>
          <dd>环球通 v2.1.0 - 出品：数码解码</dd>
        </dl>
        <div className="about-links">
          <a href="https://t.me/+tVg48WK48tlkNGVl" target="_blank" rel="noreferrer">
            💬 Telegram 群组
          </a>
          <span className="ad-badge">数码解码 · 技术支持</span>
        </div>
      </section>
    </div>
  );
}
