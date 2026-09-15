# 内置节点说明

本文档记录 环球通 Global Link 内置/测试过的节点来源。

## 当前内置节点（2026-09-15 复测）

验证方式：使用与应用内完全相同的 sing-box 核心与**同一份由 Rust `build_config()` 生成的配置**，
在本机起 SOCKS5/mixed 入站后真实出流量。**判据必须是「国内被墙的站点」**（下节有说明），
本次用 `curl -4 --max-time 12 --socks5-hostname 127.0.0.1:<inbound> https://www.google.com/generate_204`
必须拿到 `204`，再用 `github.com` / `www.wikipedia.org` 双站确认 200，并读 `cloudflare.com/cdn-cgi/trace`
核对真实落地国家。

| 内置节点 | 地址 | google 204 | github / wikipedia | 真实落地 | 结论 |
|---|---|---|---|---|---|
| HK-4（默认首选） | `45.152.64.16:443` | ✅ 0.50s | 200 / 200 | SG | ✅ 可用 |
| HK-2（旧快照兜底） | `122.10.119.252:443` | ✅ 0.58s | 200 / 200 | SG | ✅ 可用 |
| TW-2 | `140.235.38.47:443` | ✅ 1.96s | 200 / 200（较慢：github 10.3s、youtube 15.9s） | TW | ️ 可用但慢 |
| TW-1 | `43.213.230.249:443` | ❌ 5s 超时 | — | — | ❌ **TCP 层就不通**（已下架） |
| JP-2 | `138.3.212.160:8443` | ❌ 5s 超时 | — | — |  **TCP 层就不通**（已下架） |
| CA-1 | `137.220.52.250:443` | ❌ 5s 超时 | — | — | ❌ **TCP 层就不通**（已下架） |

复测时对三个失败节点单独做过 TCP 连通性对照（8s 预算，同机同网络）：
`45.152.64.16:443` / `122.10.119.252:443` / `140.235.38.47:443` 均 `TCP OPEN`，
而 `43.213.230.249:443` / `138.3.212.160:8443` / `137.220.52.250:443` 全部 8s 无响应——
**说明是这三个 IP 本身已失效/不可达，不是客户端或配置问题**。面板会轮换 IP 池，内置节点只是首启兜底。

首位选 HK 而非延迟最低的 TW：本机到 HK 的路由在国内出口通常更稳，二者仅差 0.25s。
`ConnectPage` 与 `hydrate` 在缺少有效选中项时会回落到列表第一项，**第一项必须是实测可用节点**，
否则干净安装首启就会「有节点但连不上」。

## Windows「有节点但连不上」的三个真实成因

1. **IPv6 有地址但无可用路由**：域名节点解析到 AAAA 后握手长时间挂起。已修复——出站默认写入
   `domain_strategy: "prefer_ipv4"`（可在节点参数里用 `domain_strategy` 覆盖）。
2. **缺少 uTLS 指纹**：Cloudflare 一类前置会按 TLS 指纹过滤，Go 原生握手被判异常，表现为
   「TCP/TLS 都通、隧道拿不到数据」。已修复——订阅里的 `fp=chrome` 现在会映射为
   `tls.utls = { enabled: true, fingerprint: "chrome" }`（sing-box 1.11+ 字段名是 `fingerprint`，
   不是早期的 `id`）。
3. **系统代理争抢**：本机若装着 v2rayN 等工具（`ProxyEnable=1` → `127.0.0.1:10808`），
   会与本应用的 `127.0.0.1:1819` 抢系统代理。同一时间只保留一个。
   注意：这个残留代理还会**污染排查结论**——通过它抓订阅会得到「订阅返回 0 字节」的假象。

> 节点连不上时先在「连接」页确认选中的节点，并使用「本地 SOCKS5」模式。
> 应用内「诊断」面板的「测试连接」只做 **TCP 可达性**测试：TCP 通 ≠ 节点可用。

### ⚠️ 测节点的两个致命陷阱（2026-09-15 踩坑记录）

1. **绝对不要用 `--noproxy '*'` 去测 SOCKS 代理。** curl 的 `--noproxy` 会把**显式指定的**
   `--socks5-hostname` 一起关掉，请求会静默变成**直连**。直连时恰好是
   「`gstatic.com/generate_204` 返回 204（国内可直连）+ `google/github/wikipedia` 失败（被墙）」，
   于是很容易得出「节点通过」的完全错误结论。**本文档 2026-09-14 版的表格与「52/80 可用」
   就是这么测出来的，数据已作废，请以 2026-09-15 复测为准。**
   （`--noproxy '*'` 只在抓订阅、测直连时使用；测代理时**只**给 `--socks5-hostname` 即可。）
2. **判据必须选国内被墙的站点。** `gstatic.com/generate_204`、`cloudflare.com`、`apple.com`、
   `bing.com`、`amazon.com` 在国内**直连就通**，用它们判节点死活等于没测。要判「流量是否真的
   从节点出去」，只有两条可靠路径：
   - 站点被墙：`https://www.google.com/generate_204` 必须回 `204`、`https://github.com` 必须回 `200`；
   - 看出口 IP：`https://www.cloudflare.com/cdn-cgi/trace` 的 `ip=` / `loc=` 必须**不是本机**的国内 IP。

   自检方法（先直连跑一次做对照，两者的出口 IP/结果必须不同）：
   ```powershell
   # 直连（国内 IP）
   curl.exe -4 -s --noproxy '*' https://www.cloudflare.com/cdn-cgi/trace
   # 走节点（应显示落地国家的 IP）
   curl.exe -4 -s --socks5-hostname 127.0.0.1:1819 https://www.cloudflare.com/cdn-cgi/trace
   ```

## 发布版 v2.1.0 为什么必然连不上（根因）

公开仓库上 `0c89cfd`（v2.1.0）里的内置默认配置是**一个占位符**：

```
id: "default-profile", name: "示例服务器（Shadowsocks）",
protocol: shadowsocks, address: 127.0.0.1, port: 8388, password: "***"
```

同时该版本的初始选择项写死为 `mode: "vpn"`。因此从 Release 装 2.1.0 的用户，打开应用看到的
「节点」就是这台机器自己的 `127.0.0.1:8388`，点连接一定失败——这就是「节点显示得出却连不上」
最直接的一条原因（`vlessNode(default-*)` 那批真实 IP 是之后才加的，且当时并未推送）。

v2.1.1 的处理：

1. `default-*` 前缀的内置节点由程序版本托管，启动时与随包发布的列表对账（`reconcileProfiles`），
   占位符 `default-profile` 会被清除；用户自己导入的节点（`vless-*` / `profile-*` / `sub-*`）原样保留。
2. 初始选中项不再写死 id，改为跟随内置列表首位。
3. 升级后仍持久化 `mode: "vpn"`（2.1.0 的默认值），而新装默认是 `socks5`。VPN 模式在 Windows
   需要管理员权限与 wintun，若升级用户反馈连不上，先看这里——目前**不**自动改写用户选过的模式。

真机验证（同机装有 2.1.0，状态文件里只有那个占位符）：安装并启动 v2.1.1 后，
`app-state.json` 自动变为 6 个 `default-*` 实测节点、选中项回落到 `default-hk4`；
把含 4 个旧导入节点的状态文件放回去再启动，则得到 6 + 4 = 10 条、原选中项保留。



## 订阅来源

### 来源 1：Cloudflare Pages 订阅
- **订阅链接**：`https://111-6jh.pages.dev/c4f4450f-704a-4c22-afba-5aecffd73289/sub`
- **状态**：订阅本身仍可访问（能取到 5 条 vless 链接），但 **5 个节点全部不回数据**（origin 已停），2026-09-14 实测均超时。
- **协议**：VLESS + WebSocket + TLS
- **UUID**：`c4f4450f-704a-4c22-afba-5aecffd73289`
- **公共参数**：`sni=111-6jh.pages.dev`、`host=111-6jh.pages.dev`、`path=/c4f4450f-704a-4c22-afba-5aecffd73289?ed=2048`、`fp=chrome`
- **节点列表**：

| 名称 | 地址 | 端口 |
|---|---|---|
| 订阅节点-1 | 111-6jh.pages.dev | 443 |
| 订阅节点-2 | cf.090227.xyz | 443 |
| 订阅节点-3 | bestcf.top | 443 |
| 订阅节点-4 | cloudflare.182682.xyz | 443 |
| 订阅节点-5 | cf.zhetengsha.eu.org | 443 |

### 来源 2：主订阅（面板，当前 80 节点）
- **订阅链接**：`https://shuma.ccwu.cc/sub?token=<已脱敏，见本地备忘>`
- **状态**：✅ **存活**，返回 base64 编码的 80 条 vless 链接（台/日/港/加各 20 条）。
  **2026-09-15 用正确判据复测：67 / 80 个节点能真正代理被墙流量**
  （判据：`google.com/generate_204` 回 204 ∧ `github.com` 回 200 ∧ `wikipedia.org` 回 200，
  出口 IP 经 `cloudflare.com/cdn-cgi/trace` 核对为境外）。落地 IP 呈 Cloudflare 池
  （HK 段 → 103.253.145.105/SG，JP 段 → 104.28.157.x/JP，TW 段 → 104.28.15x/TW，CA 段 → 104.28.15x/CA）。
  失败的 13 个基本都在 TCP 层就无响应（IP 已下架）。
  > 修正记录 1：2026-09-14 早先曾误判为「已失效（返回 0 字节）」，实际是被本机残留的
  > 系统代理 `127.0.0.1:10808` 干扰所致（抓订阅这种普通 HTTP 请求用 `curl -4 --noproxy '*'` 是对的）。
  > 修正记录 2：2026-09-14 表格里那些延迟数字与「52 个可用」是**用错了方法**测的
  > （见上文「测节点的两个致命陷阱」），**已作废**，勿再引用。
- **协议**：VLESS + WebSocket + TLS，公共参数 `sni=host=shuma.ccwu.cc`、`path=/`、`fp=chrome`
- **维护方式**：面板会更换 IP 池（上一版内置的 4 个 IP 中 3 个已下架），因此内置节点只是
  首启兜底，日常应在「配置」页重新导入订阅。

> ⚠️ **凭据警告**：订阅链接带 token，任何拿到链接的人都能使用你的节点流量。
> 因此**不要把完整链接写进本仓库或任何公开渠道**（本文档此前写过完整链接，已脱敏，
> 建议在面板侧轮换一次 token）。日常使用请在应用内「配置 → 订阅导入」粘贴，
> 节点只会存到本地 `%APPDATA%\com.shuma.globallink\app-state.json`，不会上传。


## 如何在应用中导入

1. 打开 环球通 Global Link
2. 左侧「配置」→ 在「订阅导入」处粘贴订阅链接 → 点「导入订阅」
   （支持 vless / trojan / ss / vmess 分享链接，重复节点自动跳过）
3. 或点「添加配置」手工填写：协议 VLESS，地址/端口/UUID 见上表，传输方式 WebSocket，
   TLS 开启，SNI 与 Host 均填 `111-6jh.pages.dev`，path 填 `/c4f4450f-…?ed=2048`
4. 保存 → 回「连接」页选中该节点 → 连接
5. 连不上时展开「诊断」面板点「测试连接」，它只对选中节点做 **TCP 可达性**测试。
   注意：TCP 通 ≠ 节点可用（HK-1 就是 TCP/TLS 都正常但服务端不回数据），
   最终要以「连接」后真实网页能否打开为准。

## 节点与配置的存储位置

导入的节点保存在本地（不会上传）：
```
%APPDATA%\com.shuma.globallink\app-state.json
```

每次点「连接」时，后端会把真正交给 sing-box 的配置写在这里，排查连不上时优先看它：
```
%APPDATA%\com.shuma.globallink\session.json
```

> 该目录下**没有 `session.json`** 就说明连接在生成配置之前就失败了（例如核心缺失、
> 权限不足），而不是节点本身不通。
>
> v2.0.0 及更早版本使用的是 `%APPDATA%\io.github.aethonreplica.desktop\`，
> 升级到 v2.1.0（identifier 改为 `com.shuma.globallink`）后旧目录不再被读取。

