import { useApp } from "../store";
import type { Page } from "../types";

const NAV: { id: Page; label: string }[] = [
  { id: "connect", label: "连接" },
  { id: "configurations", label: "配置" },
  { id: "settings", label: "设置" },
];

export default function Sidebar() {
  const { state, dispatch } = useApp();

  return (
    <aside className="sidebar">
      <div className="brand">
        <div className="brand-mark">GL</div>
        <div>
          <div className="brand-name">环球通 Global Link</div>
          <div className="brand-sub">Global Link · sing-box frontend</div>
        </div>
      </div>

      <nav className="nav">
        {NAV.map((item) => (
          <button
            key={item.id}
            className={`nav-item ${state.page === item.id ? "active" : ""}`}
            onClick={() => dispatch({ type: "set-page", page: item.id })}
          >
            {item.label}
          </button>
        ))}
      </nav>

      <div className="sidebar-foot">
        <div>环球通 v{state.appInfo ? state.appInfo.appVersion : "2.1.0"} · 出品：数码解码</div>
        <div>
          {state.appInfo
            ? `核心 ${state.appInfo.core.singBoxVersion}`
            : "正在连接后端…"}
        </div>
        <div className={`core-dot ${state.appInfo?.core.corePresent ? "ok" : ""}`}>
          {state.appInfo?.core.corePresent ? "核心就绪" : "核心缺失 — 请重新安装最新版"}
        </div>
        <div className="sidebar-links">
          <a href="https://t.me/+tVg48WK48tlkNGVl" target="_blank" rel="noreferrer">
            💬 Telegram 群组
          </a>
          <span className="ad-badge">数码解码 · 技术支持</span>
        </div>
      </div>
    </aside>
  );
}