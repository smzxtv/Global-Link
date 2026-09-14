import { useState } from "react";
import { useApp } from "../store";
import { importSubscription } from "../lib/core";
import type { Protocol, ServerProfile } from "../types";

// 注意：这里刻意不提供「自动」——后端把该字段直接当作 sing-box 的出站 type 使用，
// "auto" 不是合法出站类型，选中它只会让连接报 "unsupported protocol: auto"。
const PROTOCOLS: { value: Protocol; label: string }[] = [
  { value: "shadowsocks", label: "Shadowsocks" },
  { value: "vmess", label: "VMess" },
  { value: "vless", label: "VLESS" },
  { value: "trojan", label: "Trojan" },
  { value: "hysteria2", label: "Hysteria2" },
  { value: "tuic", label: "TUIC" },
];

export default function ConfigurationsPage() {
  const { state, dispatch } = useApp();
  const { profiles, conn } = state;
  const [subUrl, setSubUrl] = useState("");
  const [subBusy, setSubBusy] = useState(false);
  const [subMsg, setSubMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [protocol, setProtocol] = useState<Protocol>("shadowsocks");
  const [address, setAddress] = useState("");
  const [port, setPort] = useState(443);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [uuid, setUuid] = useState("");
  /** 编辑既有配置时保留其原始 params（uuid / sni / host / path / transport / security / fp 等），
   *  避免「编辑 → 保存修改」把订阅导入带回的参数清空，导致该节点再也连不上。 */
  const [baseParams, setBaseParams] = useState<Record<string, string>>({});
  const [formErr, setFormErr] = useState<string | null>(null);
  function resetForm() {
    setName("");
    setProtocol("shadowsocks");
    setAddress("");
    setPort(443);
    setUsername("");
    setPassword("");
    setUuid("");
    setBaseParams({});
    setFormErr(null);
    setEditingId(null);
    setShowForm(false);
  }

  function startEdit(id: string) {
    const p = profiles.find((x) => x.id === id);
    if (!p) return;
    setName(p.name);
    setProtocol(p.protocol);
    setAddress(p.address);
    setPort(p.port);
    setUsername(p.params.username ?? "");
    setPassword(p.params.password ?? "");
    setUuid(p.params.uuid ?? "");
    setBaseParams({ ...p.params });
    setEditingId(id);
    setShowForm(true);
  }

  function saveProfile() {
    if (!name.trim() || !address.trim() || port <= 0) return;
    // 以原始参数为底，只覆盖表单能表达的字段；其余参数一律保留。
    const params: Record<string, string> = { ...baseParams };
    if (username.trim()) params.username = username.trim();
    else delete params.username;
    if (password.trim()) params.password = password.trim();
    else delete params.password;
    if (uuid.trim()) params.uuid = uuid.trim();
    else delete params.uuid;
    // 后端对 VLESS / VMess / TUIC 强制要求 uuid，缺失时连接只会失败，这里提前拦住。
    if ((protocol === "vless" || protocol === "vmess" || protocol === "tuic") && !params.uuid) {
      setFormErr("该协议必须填写 UUID");
      return;
    }
    setFormErr(null);
    const profile = { id: editingId ?? `profile-${Date.now()}`, name: name.trim(), protocol, address: address.trim(), port, params };
    if (editingId) {
      dispatch({ type: "update-profile", profile });
    } else {
      dispatch({ type: "add-profile", profile });
    }
    resetForm();
  }
  async function runImport() {
    const url = subUrl.trim();
    if (!url || subBusy) return;
    setSubBusy(true);
    setSubMsg(null);
    try {
      const nodes = await importSubscription(url);
      const existing = new Set(profiles.map((p) => `${p.protocol}|${p.address}|${p.port}|${p.name}`));
      const fresh = nodes
        .filter((n) => !existing.has(`${n.protocol}|${n.address}|${n.port}|${n.name}`))
        .map((n) => ({
          id: `sub-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
          name: n.name || `${n.protocol}:${n.address}`,
          protocol: n.protocol as ServerProfile["protocol"],
          address: n.address,
          port: n.port,
          params: n.params,
        }) as ServerProfile);
      fresh.forEach((profile) => dispatch({ type: "add-profile", profile }));
      if (fresh.length > 0) {
        dispatch({ type: "select-profile", id: fresh[0].id });
        setSubMsg({ ok: true, text: `导入成功：新增 ${fresh.length} 个节点（跳过 ${nodes.length - fresh.length} 个重复）` });
      } else {
        setSubMsg({ ok: false, text: `订阅解析到 ${nodes.length} 个节点，但全部已存在` });
      }
    } catch (e) {
      setSubMsg({ ok: false, text: String(e) });
    } finally {
      setSubBusy(false);
    }
  }

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>配置</h1>
          <p className="muted">管理服务器配置文件和协议设置。</p>
        </div>
      </header>

      <section className="card">
        <div className="diag-head">
          <h2>订阅导入</h2>
        </div>
        <p className="hint">粘贴 V2Ray / Clash 订阅链接，自动拉取并解析全部节点（支持 vless / trojan / ss / vmess 分享链接，重复节点自动跳过）。</p>
        <div className="sub-row">
          <input
            className="sub-input"
            value={subUrl}
            onChange={(e) => setSubUrl(e.target.value)}
            placeholder="https://example.com/sub?token=..."
          />
          <button className="primary" onClick={runImport} disabled={subBusy || !subUrl.trim()}>
            {subBusy ? "导入中…" : "导入订阅"}
          </button>
        </div>
        {subMsg && (
          <div className={`notice ${subMsg.ok ? "ok" : "error"}`}>{subMsg.text}</div>
        )}
      </section>

      <section className="card">
        <div className="diag-head">
          <h2>服务器配置</h2>
          <button className="primary" onClick={() => setShowForm(!showForm)}>
            {showForm ? "取消" : "添加配置"}
          </button>
        </div>

        {showForm && (
          <div className="profile-form">
            <div className="form-row">
              <label className="field">
                <span>名称</span>
                <input value={name} onChange={(e) => setName(e.target.value)} placeholder="我的服务器" />
              </label>
              <label className="field">
                <span>协议</span>
                <select value={protocol} onChange={(e) => setProtocol(e.target.value as Protocol)}>
                  {PROTOCOLS.map((p) => (
                    <option key={p.value} value={p.value}>{p.label}</option>
                  ))}
                </select>
              </label>
            </div>
            <div className="form-row">
              <label className="field">
                <span>地址</span>
                <input value={address} onChange={(e) => setAddress(e.target.value)} placeholder="example.com" />
              </label>
              <label className="field">
                <span>端口</span>
                <input type="number" min={1} max={65535} value={port} onChange={(e) => setPort(Number(e.target.value))} />
              </label>
            </div>
            <div className="form-row">
              <label className="field">
                <span>用户名（可选）</span>
                <input value={username} onChange={(e) => setUsername(e.target.value)} />
              </label>
              <label className="field">
                <span>密码（可选）</span>
                <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
              </label>
            </div>
            {(protocol === "vless" || protocol === "vmess" || protocol === "tuic") && (
              <>
                <div className="form-row">
                  <label className="field">
                    <span>UUID（必填）</span>
                    <input value={uuid} onChange={(e) => setUuid(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" />
                  </label>
                </div>
                <p className="hint">
                  传输方式（WebSocket / gRPC）、SNI、Host、path 等参数由「订阅导入」写入，
                  编辑保存时会自动保留；如需修改这些参数，请重新导入订阅。
                </p>
              </>
            )}
            {formErr && <div className="notice error">{formErr}</div>}
            <div className="form-row">
              <button className="primary" onClick={saveProfile}>
                {editingId ? "保存修改" : "添加"}
              </button>
              <button className="ghost" onClick={resetForm}>取消</button>
            </div>
          </div>
        )}
        <ul className="profile-list">
          {profiles.map((p) => (
            <li key={p.id} className={p.id === conn.profileId ? "selected" : ""}>
              <button className="profile-item" onClick={() => dispatch({ type: "select-profile", id: p.id })}>
                <strong>{p.name}</strong>
                <span className="muted">{p.protocol} · {p.address}:{p.port}</span>
              </button>
              <div className="profile-actions">
                <button className="ghost small" onClick={() => startEdit(p.id)}>编辑</button>
                <button className="ghost small danger" onClick={() => dispatch({ type: "remove-profile", id: p.id })}>删除</button>
              </div>
            </li>
          ))}
          {!profiles.length && <li className="muted empty">暂无配置，点击"添加配置"开始。</li>}
        </ul>
      </section>
    </div>
  );
}

