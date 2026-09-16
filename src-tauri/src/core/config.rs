use std::collections::HashMap;

use serde_json::{json, Value};

/// Connection parameters coming from the frontend.
#[derive(Clone, Debug)]
pub struct ConnectParams {
    pub mode: String, // "vpn" | "socks5"
    pub protocol: String,
    pub address: String,
    pub port: u16,
    pub params: HashMap<String, String>,
    pub socks_port: u16,
    pub log_level: String,
}

/// Build a sing-box configuration representing one connect() session.
pub fn build_config(p: &ConnectParams) -> Result<Value, String> {
    let mut inbounds: Vec<Value> = Vec::new();
    match p.mode.as_str() {
        "socks5" => inbounds.push(json!({
            "type": "mixed",
            "tag": "socks-in",
            "listen": "127.0.0.1",
            "listen_port": p.socks_port,
        })),
        "vpn" => inbounds.push(json!({
            "type": "tun",
            "tag": "tun-in",
            "auto_route": true,
            // strict_route 在 Windows 上会把 sing-box 自己的出站连接一起拦掉，实测表现为
            // 「核心已启动，但连节点服务器也超时」，隧道等于不通，必须关闭。
            "strict_route": false,
            "stack": "mixed",
            "mtu": 1500,
            "address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"],
            "endpoint_independent_nat": true,
        })),
        other => return Err(format!("unknown mode: {other}")),
    }

    let outbound = build_outbound(&p.protocol, &p.address, p.port, &p.params)?;

    Ok(json!({
        "log": { "level": p.log_level, "timestamp": true },
        "inbounds": inbounds,
        "outbounds": [
            outbound,
            { "type": "direct", "tag": "direct" },
            { "type": "block", "tag": "block" }
        ],
        "dns": build_dns(p),
        "inbounds": inbounds,
        "outbounds": [
            outbound,
            { "type": "direct", "tag": "direct" },
            { "type": "block", "tag": "block" }
        ],
        "route": build_route(p)
    }))
}

/// DNS server tags referenced from `dns.final`, `dns.rules` and the resolvers.
const DNS_PROXY_TAG: &str = "dns-proxy";
const DNS_LOCAL_TAG: &str = "dns-local";

/// 国内常见域名：走本地 DNS 就近解析（CDN 不绕远路、延迟低），其余走隧道内加密 DNS。
const CN_DOMAIN_SUFFIXES: &[&str] = &[
    ".cn", ".com.cn", ".net.cn", ".org.cn", ".gov.cn", ".edu.cn",
    "baidu.com", "qq.com", "weixin.qq.com", "tencent.com", "taobao.com", "tmall.com",
    "alipay.com", "alicdn.com", "aliyun.com", "jd.com", "bilibili.com", "zhihu.com",
    "douban.com", "sina.com.cn", "weibo.com", "sohu.com", "163.com", "126.com",
    "iqiyi.com", "youku.com", "douyin.com", "bytedance.com", "toutiao.com",
    "meituan.com", "dianping.com", "pinduoduo.com", "kuaishou.com", "xiaomi.com",
    "huawei.com", "mi.com", "csdn.net", "gitee.com", "cnblogs.com", "51job.com",
];

/// 解析策略：默认 `ipv4_only`，可用 params 里的 `domain_strategy` 覆盖
/// （prefer_ipv4 / prefer_ipv6 / ipv4_only / ipv6_only）。
fn dns_strategy(p: &ConnectParams) -> String {
    p.params
        .get("domain_strategy")
        .filter(|s| !s.is_empty())
        .cloned()
        .unwrap_or_else(|| "ipv4_only".into())
}

/// Build the `dns` object. 下面每条都是实测踩出来的：
///
/// - TUN 模式下浏览器把域名交给系统解析。以前没有 `dns` 段，sing-box 默认用本机（被污染的）
///   递归解析，YouTube 解析到假 IP，表现为「已连接但网页打不开」。
/// - 上游必须 `detour: "proxy"` 才走隧道；且 DoH 地址必须写 **域名**（写 IP 会因 SNI
///   不匹配握手失败：实测 `1.1.1.1` 超时、`cloudflare-dns.com` 正常），因此给它配
///   `domain_resolver: dns-local` 完成自举解析。
/// - `strategy: ipv4_only`：多数线路没有可用 IPv6 出口，返回 AAAA 会让浏览器先连 IPv6
///   再立刻失败（YouTube 曾因此完全打不开），Windows 上还会 AAAA 悬挂。
fn build_dns(p: &ConnectParams) -> Value {
    json!({
        "servers": [
            {
                "type": "https",
                "tag": DNS_PROXY_TAG,
                "server": "cloudflare-dns.com",
                "domain_resolver": DNS_LOCAL_TAG,
                "detour": "proxy"
            },
            { "type": "local", "tag": DNS_LOCAL_TAG }
        ],
        "rules": [
            { "domain_suffix": CN_DOMAIN_SUFFIXES, "server": DNS_LOCAL_TAG }
        ],
        "final": DNS_PROXY_TAG,
        "strategy": dns_strategy(p)
    })
}

/// Build the `route` object.
///
/// - `auto_detect_interface: true`：把 sing-box 自己的出站绑到物理网卡。缺了它，去节点
///   服务器的流量会被刚建好的 TUN 再抓一次，形成回环风暴（实测 2 万+ 连接、全网不通）。
/// - `hijack-dns`：把进入隧道的 DNS 查询交给上面的 DNS 模块，避免污染答案。
/// - `sniff`：从 TLS ClientHello 里取出域名，供规则匹配与远程解析使用。
fn build_route(p: &ConnectParams) -> Value {
    json!({
        "auto_detect_interface": true,
        "rules": [
            { "action": "sniff" },
            { "protocol": "dns", "action": "hijack-dns" }
        ],
        "default_domain_resolver": { "server": DNS_PROXY_TAG, "strategy": dns_strategy(p) },
        "final": "proxy"
    })
}

fn build_outbound(
    protocol: &str,
    address: &str,
    port: u16,
    params: &HashMap<String, String>,
) -> Result<Value, String> {
    let mut out = json!({
        "type": protocol,
        "tag": "proxy",
        "server": address,
        "server_port": port,
    });

    // 节点域名必须用「本地 DNS」解析：若用隧道内的 DNS，就得先连上代理才能解析代理地址，
    // 形成死锁。策略与全局一致（默认 ipv4_only），避免在只有 IPv4 出口的网络上先试 AAAA。
    out["domain_resolver"] = json!({
        "server": DNS_LOCAL_TAG,
        "strategy": params
            .get("domain_strategy")
            .filter(|s| !s.is_empty())
            .cloned()
            .unwrap_or_else(|| "ipv4_only".into())
    });

    match protocol {
        "shadowsocks" => {
            let method = params.get("method").cloned().unwrap_or_else(|| "aes-128-gcm".into());
            let password = params.get("password").ok_or("shadowsocks requires a 'password'")?;
            out["method"] = json!(method);
            out["password"] = json!(password);
        }
        "vmess" => {
            let uuid = params.get("uuid").ok_or("vmess requires a 'uuid'")?;
            out["uuid"] = json!(uuid);
            let security = params.get("security").cloned().unwrap_or_else(|| "auto".into());
            out["security"] = json!(security);
            if let Some(alter_id) = params.get("alter_id") {
                out["alter_id"] = json!(alter_id);
            }
        }
        "vless" => {
            let uuid = params.get("uuid").ok_or("vless requires a 'uuid'")?;
            out["uuid"] = json!(uuid);
            if let Some(flow) = params.get("flow") {
                out["flow"] = json!(flow);
            }
            // Apply TLS if security is "tls" or "reality".
            let security = params.get("security").cloned().unwrap_or_else(|| "tls".into());
            if !security.is_empty() && security != "none" {
                apply_tls(&mut out, params);
            }
            // Apply transport (ws/grpc/http).
            apply_transport(&mut out, params);
        }
        "trojan" => {
            let password = params.get("password").ok_or("trojan requires a 'password'")?;
            out["password"] = json!(password);
            apply_tls(&mut out, params);
            apply_transport(&mut out, params);
        }
        "http" => {
            // HTTP 代理出站：适用于 Cloudflare Worker / 通用 HTTP 代理。
            // 免费 worker 域名走 443 TLS；带 sni 会自动配 server_name。
            apply_tls(&mut out, params);
            if let Some(username) = params.get("username") {
                out["username"] = json!(username);
            }
            if let Some(password) = params.get("password") {
                out["password"] = json!(password);
            }
        }
        "hysteria2" => {
            let password = params.get("password").ok_or("hysteria2 requires a 'password'")?;
            out["password"] = json!(password);
            apply_tls(&mut out, params);
        }
        "tuic" => {
            let uuid = params.get("uuid").ok_or("tuic requires a 'uuid'")?;
            out["uuid"] = json!(uuid);
            if let Some(password) = params.get("password") {
                out["password"] = json!(password);
            }
            apply_tls(&mut out, params);
        }
        other => return Err(format!("unsupported protocol: {other}")),
    }

    Ok(out)
}

fn apply_tls(out: &mut Value, params: &HashMap<String, String>) {
    let mut tls = json!({ "enabled": true });
    if let Some(sni) = params.get("sni") {
        tls["server_name"] = json!(sni);
    }
    if let Some(insecure) = params.get("insecure") {
        tls["insecure"] = json!(insecure == "true" || insecure == "1");
    }
    // uTLS 浏览器指纹：Cloudflare 一类的 CDN/面板会按 TLS 指纹过滤，Go 原生握手常被判定为
    // 异常，表现为「TCP/TLS 能通但隧道拿不到数据」。订阅里的 fp（如 chrome）必须带上。
    // 注意 sing-box 1.11+ 该字段名为 utls.fingerprint（不是早期的 utls.id）。
    if let Some(fp) = params.get("fp") {
        if !fp.is_empty() && fp != "none" {
            tls["utls"] = json!({ "enabled": true, "fingerprint": fp });
        }
    }
    out["tls"] = tls;
}

fn apply_transport(out: &mut Value, params: &HashMap<String, String>) {
    let transport_type = match params.get("transport") {
        Some(t) if !t.is_empty() => t.as_str(),
        _ => return, // no transport
    };
    let mut transport = json!({ "type": transport_type });
    if let Some(path) = params.get("path") {
        transport["path"] = json!(path);
    }
    if let Some(host) = params.get("host") {
        transport["headers"] = json!({ "host": host });
    }
    out["transport"] = transport;
}

#[cfg(test)]
mod tests {
    use super::*;

    fn params(extra: HashMap<String, String>) -> ConnectParams {
        let mut p = HashMap::new();
        p.insert("password".into(), "secret".into());
        p.extend(extra);
        ConnectParams {
            mode: "socks5".into(),
            protocol: "shadowsocks".into(),
            address: "example.com".into(),
            port: 8388,
            params: p,
            socks_port: 1819,
            log_level: "info".into(),
        }
    }

    #[test]
    fn socks5_inbound_and_listen_port() {
        let cfg = build_config(&params(HashMap::new())).unwrap();
        let inbound = &cfg["inbounds"][0];
        assert_eq!(inbound["type"], "mixed");
        assert_eq!(inbound["listen_port"], 1819);
        assert_eq!(cfg["route"]["final"], "proxy");
    }

    #[test]
    fn vpn_mode_uses_tun_inbound() {
        let mut p = params(HashMap::new());
        p.mode = "vpn".into();
        let cfg = build_config(&p).unwrap();
        assert_eq!(cfg["inbounds"][0]["type"], "tun");
        assert_eq!(cfg["inbounds"][0]["auto_route"], true);
        assert!(cfg["inbounds"][0]["address"].is_array());
    }

    #[test]
    fn shadowsocks_outbound_carries_credentials() {
        let cfg = build_config(&params(HashMap::new())).unwrap();
        let out = &cfg["outbounds"][0];
        assert_eq!(out["type"], "shadowsocks");
        assert_eq!(out["method"], "aes-128-gcm");
        assert_eq!(out["password"], "secret");
        assert_eq!(out["server"], "example.com");
        assert_eq!(out["server_port"], 8388);
    }

    #[test]
    fn vless_with_ws_transport() {
        let mut p = params(HashMap::new());
        p.protocol = "vless".into();
        p.address = "example.com".into();
        p.port = 443;
        p.params.insert("uuid".into(), "abc-123".into());
        p.params.insert("security".into(), "tls".into());
        p.params.insert("sni".into(), "example.com".into());
        p.params.insert("transport".into(), "ws".into());
        p.params.insert("path".into(), "/ws".into());
        p.params.insert("host".into(), "example.com".into());
        let cfg = build_config(&p).unwrap();
        let out = &cfg["outbounds"][0];
        assert_eq!(out["type"], "vless");
        assert_eq!(out["uuid"], "abc-123");
        assert_eq!(out["tls"]["enabled"], true);
        assert_eq!(out["tls"]["server_name"], "example.com");
        assert_eq!(out["transport"]["type"], "ws");
        assert_eq!(out["transport"]["path"], "/ws");
        assert_eq!(out["transport"]["headers"]["host"], "example.com");
    }

    #[test]
    fn unknown_mode_or_protocol_is_rejected() {
        let mut p = params(HashMap::new());
        p.mode = "bogus".into();
        assert!(build_config(&p).is_err());

        let mut p2 = params(HashMap::new());
        p2.protocol = "nope".into();
        assert!(build_config(&p2).is_err());
    }

    #[test]
    fn tls_protocols_get_tls_enabled() {
        let mut p = params(HashMap::new());
        p.protocol = "trojan".into();
        p.params.insert("sni".into(), "cdn.example.com".into());
        let cfg = build_config(&p).unwrap();
        let out = &cfg["outbounds"][0];
        assert_eq!(out["tls"]["enabled"], true);
        assert_eq!(out["tls"]["server_name"], "cdn.example.com");
    }

    #[test]
    fn resolvers_prefer_ipv4_so_broken_ipv6_networks_still_connect() {
        let cfg = build_config(&params(HashMap::new())).unwrap();
        // 节点域名用本地 DNS 解析，避免「先有代理才能解析代理」的死锁。
        assert_eq!(cfg["outbounds"][0]["domain_resolver"]["server"], "dns-local");
        assert_eq!(cfg["outbounds"][0]["domain_resolver"]["strategy"], "ipv4_only");
        // 全局 DNS 与默认解析器保持同一策略（境外网络常见「有 AAAA 但没有 IPv6 出口」）。
        assert_eq!(cfg["dns"]["strategy"], "ipv4_only");
        assert_eq!(cfg["route"]["default_domain_resolver"]["strategy"], "ipv4_only");

        // params 显式指定时以用户配置为准。
        let mut p = params(HashMap::new());
        p.params.insert("domain_strategy".into(), "prefer_ipv4".into());
        let cfg = build_config(&p).unwrap();
        assert_eq!(cfg["outbounds"][0]["domain_resolver"]["strategy"], "prefer_ipv4");
        assert_eq!(cfg["dns"]["strategy"], "prefer_ipv4");
    }

    #[test]
    fn tun_keeps_node_reachable_and_avoids_loopback() {
        let mut p = params(HashMap::new());
        p.mode = "vpn".into();
        let cfg = build_config(&p).unwrap();
        let tun = &cfg["inbounds"][0];
        assert_eq!(tun["type"], "tun");
        assert_eq!(tun["auto_route"], true);
        // strict_route 在 Windows 上会连自己的出站一起拦掉 → 连节点都超时，隧道等于不通。
        assert_eq!(tun["strict_route"], false);
        // 不绑定物理网卡时，去节点的流量会被 TUN 抓回来形成回环风暴。
        assert_eq!(cfg["route"]["auto_detect_interface"], true);
    }

    #[test]
    fn dns_is_hijacked_and_foreign_domains_resolve_through_the_tunnel() {
        let cfg = build_config(&params(HashMap::new())).unwrap();
        let dns = &cfg["dns"];
        // 境外域名走隧道内的加密 DNS（写域名 + detour proxy；写成 IP 会因 SNI 不匹配失败）。
        assert_eq!(dns["servers"][0]["type"], "https");
        assert_eq!(dns["servers"][0]["server"], "cloudflare-dns.com");
        assert_eq!(dns["servers"][0]["detour"], "proxy");
        assert_eq!(dns["servers"][0]["domain_resolver"], "dns-local");
        assert_eq!(dns["final"], "dns-proxy");
        // 国内域名走本地 DNS。
        assert_eq!(dns["rules"][0]["server"], "dns-local");
        let suffixes = dns["rules"][0]["domain_suffix"].as_array().unwrap();
        assert!(suffixes.iter().any(|s| s.as_str() == Some(".cn")));
        // TUN 里必须劫持 DNS，否则浏览器拿到的是被污染的答案。
        let rules = cfg["route"]["rules"].as_array().unwrap();
        assert!(rules.iter().any(|r| r["action"] == "hijack-dns"));
        assert!(rules.iter().any(|r| r["action"] == "sniff"));
        assert_eq!(rules[1]["protocol"], "dns");
    }

    #[test]
    fn tls_applies_utls_fingerprint_from_fp_param() {
        // Cloudflare 前置节点会按 TLS 指纹过滤，fp 必须映射到 sing-box 的 utls.fingerprint。
        let mut p = params(HashMap::new());
        p.protocol = "vless".into();
        p.params.insert("uuid".into(), "abc-123".into());
        p.params.insert("security".into(), "tls".into());
        p.params.insert("sni".into(), "example.com".into());
        p.params.insert("fp".into(), "chrome".into());
        let cfg = build_config(&p).unwrap();
        let out = &cfg["outbounds"][0];
        assert_eq!(out["tls"]["utls"]["enabled"], true);
        assert_eq!(out["tls"]["utls"]["fingerprint"], "chrome");

        // 没有 fp（或 fp=none）时不应写出 utls 字段。
        let mut p2 = params(HashMap::new());
        p2.protocol = "vless".into();
        p2.params.insert("uuid".into(), "abc-123".into());
        let cfg2 = build_config(&p2).unwrap();
        assert!(cfg2["outbounds"][0]["tls"]["utls"].is_null());
    }

    #[test]
    fn generated_config_is_accepted_by_the_bundled_core() {
        // 用真实核心做架构校验：字段写错（例如漏掉 route.default_domain_resolver、
        // DoH 上游写成 IP）时 sing-box 会直接拒绝启动，这个测试能在打包前拦住这类问题。
        // 设置环境变量 GL_TEST_CONFIG_OUT 可把生成的配置导出到指定路径，便于人工实测。
        let core = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("resources")
            .join("sing-box")
            .join(core_exe_name());
        if !core.is_file() {
            eprintln!("bundled core not found; skipping schema check");
            return;
        }

        let mut p = params(HashMap::new());
        p.mode = "vpn".into();
        p.protocol = "vless".into();
        p.address = "122.10.119.252".into();
        p.port = 443;
        p.params
            .insert("uuid".into(), "c18b978e-1c4e-415c-8bea-07942b563a64".into());
        p.params.insert("security".into(), "tls".into());
        p.params.insert("sni".into(), "example.com".into());
        p.params.insert("transport".into(), "ws".into());
        p.params.insert("path".into(), "/".into());
        p.params.insert("host".into(), "example.com".into());
        p.params.insert("fp".into(), "chrome".into());
        let cfg = build_config(&p).unwrap();

        let keep = std::env::var("GL_TEST_CONFIG_OUT").unwrap_or_default();
        let file = if keep.is_empty() {
            let mut f = std::env::temp_dir();
            f.push(format!("gl-config-check-{}.json", std::process::id()));
            f
        } else {
            std::path::PathBuf::from(keep)
        };
        std::fs::write(&file, serde_json::to_string_pretty(&cfg).unwrap()).unwrap();

        let out = std::process::Command::new(&core)
            .args(["check", "-c"])
            .arg(&file)
            .output()
            .expect("failed to run the bundled core");
        let stderr = String::from_utf8_lossy(&out.stderr).to_string();
        if std::env::var("GL_TEST_CONFIG_OUT").is_err() {
            let _ = std::fs::remove_file(&file);
        }
        assert!(
            out.status.success(),
            "sing-box rejected the generated config: {stderr}"
        );
    }

    /// Executable name of the bundled core (mirrors `core::singbox::exe_name`).
    fn core_exe_name() -> &'static str {
        if cfg!(windows) {
            "sing-box.exe"
        } else {
            "sing-box"
        }
    }
}