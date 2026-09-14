package io.github.aethonreplica.core

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import io.github.aethonreplica.data.AppConfig
import io.github.aethonreplica.data.ProfileStorage
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Session lifecycle states. */
enum class SessionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * sing-box core manager for Android.
 * Manages the native sing-box process and TUN interface.
 */
class SingBoxCore private constructor() {

    companion object {
        private const val TAG = "SingBoxCore"

        /** 大陆直连域名(含后缀)。 */
        private val DIRECT_DOMAINS = listOf(
            "cn", "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
            "qq.com", "weixin.qq.com", "wechat.com", "qpic.cn", "qlogo.cn",
            "taobao.com", "tmall.com", "alicdn.com", "alipay.com", "aliyun.com",
            "baidu.com", "bdstatic.com", "bcebos.com",
            "jd.com", "jdcdn.com", "360buyimg.com",
            "163.com", "126.com", "netease.com",
            "sina.com.cn", "sinaimg.cn", "weibo.com",
            "bilibili.com", "hdslb.com",
            "xiaomi.com", "mi.com", "miui.com",
            "huawei.com", "harmonyos.com",
            "douyin.com", "bytedance.com", "toutiao.com", "ixigua.com",
            "kuaishou.com",
            "zhihu.com", "zhimg.com",
            "douban.com",
            "amap.com", "alibaba-inc.com", "dingtalk.com", "youku.com",
            "iqiyi.com", "qiyi.com",
            "tencent.com",
            "csdn.net", "cnblogs.com", "oschina.net", "gitee.com",
            "weather.com.cn"
        )

        /** 必走代理的域名(含后缀)。 */
        private val PROXY_DOMAINS = listOf(
            "google.com", "googleapis.com", "gstatic.com", "googlevideo.com",
            "youtube.com", "ytimg.com", "ggpht.com",
            "facebook.com", "fbcdn.net",
            "twitter.com", "twimg.com", "x.com",
            "instagram.com", "cdninstagram.com",
            "telegram.org", "t.me",
            "discord.com", "discord.gg", "discordapp.com",
            "openai.com", "chatgpt.com", "oaistatic.com", "oaiusercontent.com",
            "anthropic.com", "claude.ai",
            "github.com", "githubusercontent.com", "githubassets.com",
            "wikipedia.org", "wikimedia.org",
            "reddit.com", "redd.it",
            "netflix.com", "nflxvideo.net", "nflxext.com",
            "spotify.com", "scdn.co",
            "tiktok.com", "tiktokcdn.com", "tiktokv.com",
            "bing.com", "microsoft.com", "windowsupdate.com",
            "apple.com", "icloud.com",
            "amazon.com", "awsstatic.com",
            "cloudflare.com"
        )

        @Volatile
        private var instance: SingBoxCore? = null

        fun getInstance(): SingBoxCore {
            return instance ?: synchronized(this) {
                instance ?: SingBoxCore().also { instance = it }
            }
        }
    }

    private val _status = AtomicReference(SessionStatus.DISCONNECTED)
    val status: SessionStatus get() = _status.get()

    private var vpnInterface: ParcelFileDescriptor? = null
    private val gson = Gson()

    /** 主线程 Handler: 用于连接超时兜底(服务无响应时把状态拉回 DISCONNECTED)。 */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectTimeoutMs = 15_000L

    fun connect(context: Context, config: AppConfig, listener: CoreListener): Boolean =
        startSession(context, config, listener, adoptRunning = true)

    /**
     * 断开当前会话(若有)并立即用新配置重连。
     * 用于切换节点后让新节点立刻生效,而不是继续跑旧节点。
     * 必须真正重启隧道,所以不走"沿用正在运行隧道"的快捷路径。
     */
    fun reconnect(context: Context, config: AppConfig, listener: CoreListener): Boolean {
        // 切换节点: 进程内热重载, 不杀进程。
        // 旧方案"setExactAndAllowWhileIdle 闹钟 + 杀进程重启"已被彻底删除:
        // 杀进程会连 MainActivity 一起带走, 而闹钟拉回的 Activity 受后台启动
        // 限制无法抢占前台(实测 visible=false 停在后台) → 用户看到的就是闪退。
        // 现在直接 startService(ACTION_CONNECT): 服务侧 onStartCommand 把拆建
        // 会话的耗时工作丢后台线程串行执行, 调用方立即返回, 界面全程存活。
        if (AethonVpnService.isRunning) {
            setStatus(SessionStatus.CONNECTING, listener)
            listener.onLog("[切换] 正在用新节点重建隧道…")
            return try {
                // 同样用全局模式设置覆盖, 避免旧 profile 的 mode="socks5" 导致无 TUN。
                val effectiveConfig = config.copy(mode = ProfileStorage(context).getMode())
                val configJson = buildConfig(effectiveConfig)
                Log.d(TAG, "Generated config JSON:\n$configJson")
                val configFile = File(context.filesDir, "singbox_config.json")
                configFile.writeText(configJson)

                // 保存自愈配置: 若进程此后意外死亡(系统回收等), 服务仍能自愈重建。
                AethonVpnService.saveRestartConfig(context, configFile.absolutePath)

                // 触发服务内热重载: startService 本身是异步投递, 服务侧耗时工作
                // 在后台线程执行, 这里立即返回, 不卡 UI 线程。
                val intent = Intent(context, AethonVpnService::class.java).apply {
                    action = AethonVpnService.ACTION_CONNECT
                    putExtra(AethonVpnService.EXTRA_CONFIG_PATH, configFile.absolutePath)
                }
                context.startService(intent)
                // "已连接"仍由服务建好隧道后回调 onServiceStarted() 来置位,
                // 这里只给一个超时兜底: 15 秒还没回调就报超时, 避免界面永远转圈。
                scheduleConnectTimeout()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch node", e)
                setStatus(SessionStatus.ERROR, listener)
                listener.onError(e.message ?: "Unknown error")
                false
            }
        }
        return startSession(context, config, listener, adoptRunning = false)
    }

    private fun startSession(
        context: Context,
        config: AppConfig,
        listener: CoreListener,
        adoptRunning: Boolean
    ): Boolean {
        // 隧道已在运行(例如服务自愈重启后用户才打开 App): 先校验运行中的
        // 配置与期望模式一致再沿用。旧版本遗留的 socks-only 配置会在服务
        // 自愈时被原样恢复,若盲目沿用就会出现 UI 显示"已连接"但没有 TUN、
        // 浏览器完全不通的假连接。
        if (adoptRunning && AethonVpnService.isRunning) {
            val configFile = File(context.filesDir, "singbox_config.json")
            val runningConfig = if (configFile.isFile) configFile.readText() else ""
            val wantsTun = ProfileStorage(context).getMode() == "vpn"
            val hasTun = runningConfig.contains("\"tun\"")
            if (wantsTun == hasTun) {
                setStatus(SessionStatus.CONNECTED, listener)
                listener.onLog("[沿用] 隧道已在运行，直接接管状态")
                return true
            }
            listener.onLog("[修复] 运行中的隧道缺少 VPN 接口，正在重建…")
            // 不沿用,继续走下面的完整连接流程(重新生成配置并重启服务)。
            // 先把残留会话拆掉,否则下方"已有会话"检查会误报。
            // 状态当前应为 CONNECTED(服务在跑),手动复位以便重建。
            _status.set(SessionStatus.DISCONNECTED)
        }

        val current = _status.get()
        if (current == SessionStatus.CONNECTED || current == SessionStatus.CONNECTING) {
            listener.onError("A session is already active; disconnect first")
            return false
        }

        setStatus(SessionStatus.CONNECTING, listener)

        try {
            // 用全局模式设置覆盖 profile 中可能过时的 mode 字段。
            // 旧版本序列化的 profile 可能带有 mode="socks5", 导致不生成 TUN。
            val effectiveConfig = config.copy(mode = ProfileStorage(context).getMode())
            val configJson = buildConfig(effectiveConfig)
            Log.d(TAG, "Generated config JSON:\n$configJson")
            val configFile = File(context.filesDir, "singbox_config.json")
            configFile.writeText(configJson)

            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                listener.onVpnPermissionRequired(prepareIntent)
                setStatus(SessionStatus.DISCONNECTED, listener)
                return false
            }

            val intent = Intent(context, AethonVpnService::class.java).apply {
                action = AethonVpnService.ACTION_CONNECT
                putExtra(AethonVpnService.EXTRA_CONFIG_PATH, configFile.absolutePath)
            }
            context.startService(intent)

            // 不再立即置为 CONNECTED: 由 AethonVpnService 在隧道真正建立后
            // 回调 onServiceStarted()。这样"已连接"代表 tun0 真实存在,
            // 而不是服务尚未确认时的乐观假设。
            scheduleConnectTimeout()
            listener.onLog("[连接] 正在启动 sing-box…")

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            setStatus(SessionStatus.ERROR, listener)
            listener.onError(e.message ?: "Unknown error")
            return false
        }
    }

    fun disconnect(context: Context, listener: CoreListener) {
        try {
            val intent = Intent(context, AethonVpnService::class.java).apply {
                action = AethonVpnService.ACTION_DISCONNECT
            }
            context.startService(intent)

            setStatus(SessionStatus.DISCONNECTED, listener)
            listener.onLog("session stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect", e)
            listener.onError(e.message ?: "Disconnect failed")
        }
    }

    // ------------------------------------------------------------------
    // 隧道状态同步与重连
    // ------------------------------------------------------------------

    /**
     * 统一的状态变更入口: 同时通知"本次调用的 listener"和"全局观察者"。
     * 这样即使连接由配置页/服务侧发起,连接页也能正确同步状态,
     * 避免界面状态与真实隧道状态脱节。
     */
    private fun setStatus(newStatus: SessionStatus, listener: CoreListener?) {
        _status.set(newStatus)
        listener?.onStatusChanged(newStatus)
        externalStatusListener?.onStatusChanged(newStatus)
    }

    /**
     * 隧道状态的全局观察者。由 UI 注册,这样无论连接由谁发起
     * (连接页按钮、配置页切换节点重连、服务自愈重启),
     * 状态变化都能反映到界面,避免界面显示"已连接"而隧道实际已断。
     */
    @Volatile
    var externalStatusListener: CoreListener? = null

    /** 连接超时兜底: 服务迟迟不回报结果时把状态拉回 DISCONNECTED,让自愈重连有机会介入。 */
    private fun scheduleConnectTimeout() {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (_status.get() == SessionStatus.CONNECTING) {
                setStatus(SessionStatus.DISCONNECTED, null)
                externalStatusListener?.onError("[超时] 服务未响应连接请求，请重试")
            }
        }, connectTimeoutMs)
    }

    /** 由 AethonVpnService 在隧道真正建立后调用(此时 tun0 才真实存在)。 */
    fun onServiceStarted() {
        mainHandler.removeCallbacksAndMessages(null)
        setStatus(SessionStatus.CONNECTED, null)
    }

    /**
     * 由 AethonVpnService 在隧道被终止时调用(崩溃、系统回收、权限被吊销等)。
     * 同步内部状态并通知观察者,否则 UI 会一直显示"已连接",
     * 而 tun0 实际已消失、流量全部直连。
     */
    fun onServiceStopped() {
        mainHandler.removeCallbacksAndMessages(null)
        val prev = _status.get()
        if (prev == SessionStatus.CONNECTED || prev == SessionStatus.CONNECTING) {
            setStatus(SessionStatus.DISCONNECTED, null)
            externalStatusListener?.onLog("[服务] 隧道被终止，状态已同步为「已断开」")
        }
    }

    private fun buildConfig(config: AppConfig): String {
        val root = JsonObject()

        val log = JsonObject()
        log.addProperty("level", config.logLevel.ifEmpty { "info" })
        root.add("log", log)

        val inbounds = JsonArray()

        val socksInbound = JsonObject()
        socksInbound.addProperty("type", "socks")
        socksInbound.addProperty("tag", "socks-in")
        socksInbound.addProperty("listen", config.listenAddress.ifEmpty { "127.0.0.1" })
        socksInbound.addProperty("listen_port", config.listenPort)
        inbounds.add(socksInbound)

        if (config.mode == "vpn") {
            val tunInbound = JsonObject()
            tunInbound.addProperty("type", "tun")
            tunInbound.addProperty("tag", "tun-in")
            tunInbound.addProperty("mtu", config.mtu)
            tunInbound.addProperty("address", "172.19.0.1/30")
            tunInbound.addProperty("auto_route", true)
            tunInbound.addProperty("strict_route", false)
            // Android 上 TUN 的 TCP 必须走用户态协议栈: "system" 的内核 TCP
            // 在 VpnService fd 上无法完成握手(TUN 内 UDP/DNS 正常但 TCP 全部
            // 卡死/超时), 经实测证实。必须使用纯用户态的 gvisor 栈。
            tunInbound.addProperty("stack", "gvisor")
            // 1.14: inbound.sniff / sniff_override_destination 已被移除,
            // 协议嗅探改用 route.rules 里的 {"action":"sniff"}(见下方)。
            // TUN 只看到目标 IP,若不嗅探则所有 domain_suffix 规则都无法命中。
            inbounds.add(tunInbound)
        }

        root.add("inbounds", inbounds)

        val outbounds = JsonArray()
        outbounds.add(buildOutbound(config))

        val directOutbound = JsonObject()
        directOutbound.addProperty("type", "direct")
        directOutbound.addProperty("tag", "direct")
        outbounds.add(directOutbound)
        
        root.add("outbounds", outbounds)
        
        val route = JsonObject()
        route.addProperty("auto_detect_interface", true)
        // 注意: route 段没有 network_interface / default_network_interface 这两个字段,
        // 写入未知字段会让 sing-box 直接报 "decode config" 并拒绝启动。
        // Android 上默认接口由 VpnService.startDefaultInterfaceMonitor 回调提供。
        route.addProperty("override_android_vpn", true)
        // final 必须是 proxy 而不是 direct:
        // TUN 上 TLS SNI 嗅探不一定每一条连接都成功(实测 443 连接常以 1ms
        // 快速落到 final),若 final=direct,HTTPS 流量会被当作裸直连发出,
        // 在国外 IP 被防火墙阻隔的网络里就表现为"打不开 Google"。
        // 标准做法: 私有 CIDR 和大陆域名走 direct 白名单,其余一律走代理。
        route.addProperty("final", "proxy")
        val rules = JsonArray()

        // 1.14: 协议嗅探必须作为路由前的 non-final action 执行,
        // 否则 TUN 只有目标 IP,domain_suffix 规则全部失效,流量落到 final=direct。
        // 已通过 SOCKS 入站验证: sniff 规则本身工作正常(经 SOCKS 走同一条 route 规则
        // 请求 baidu 返回 200),因此 TCP 卡死与 sniff 无关,恢复该规则以支持域名分流。
        val sniffRule = JsonObject()
        sniffRule.addProperty("action", "sniff")
        rules.add(sniffRule)

        // 1.14: TUN 内的系统 DNS 查询必须由 sing-box 自己接管(hijack-dns),
        // 否则会被当作普通 UDP 转发到直连,在此环境直连 UDP 不通 → 解析全部失败。
        // 放在 sniff 之后,此时协议已被识别为 dns。
        // 关键: 必须限定 protocol=dns,否则该规则会匹配所有连接,
        // 把普通 HTTP/HTTPS 也当成 DNS 劫持,导致全部连接被吞掉。
        val hijackDnsRule = JsonObject()
        hijackDnsRule.addProperty("protocol", "dns")
        hijackDnsRule.addProperty("action", "hijack-dns")
        rules.add(hijackDnsRule)

        // 私有地址直连,不进代理。
        val privateRule = JsonObject()
        val privateIpDst = JsonArray()
        listOf("0.0.0.0/8", "10.0.0.0/8", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4", "::1/128", "fc00::/7", "fe80::/10").forEach { privateIpDst.add(it) }
        privateRule.add("ip_cidr", privateIpDst)
        privateRule.addProperty("action", "route")
        privateRule.addProperty("outbound", "direct")
        rules.add(privateRule)

        // 大陆域名直连,其余走代理。
        val directRule = JsonObject()
        val directDomains = JsonArray()
        for (domain in DIRECT_DOMAINS) {
            directDomains.add(domain)
        }
        directRule.add("domain_suffix", directDomains)
        directRule.addProperty("action", "route")
        directRule.addProperty("outbound", "direct")
        rules.add(directRule)

        val proxyRule = JsonObject()
        val proxyDomains = JsonArray()
        for (domain in PROXY_DOMAINS) {
            proxyDomains.add(domain)
        }
        proxyRule.add("domain_suffix", proxyDomains)
        proxyRule.addProperty("action", "route")
        proxyRule.addProperty("outbound", "proxy")
        rules.add(proxyRule)

        route.add("rules", rules)
        root.add("route", route)

        // DNS 配置说明:
        // - direct DNS follows the default route
        // - "direct" is not a valid detour target here
        // - proxy outbound targets an IP, so detour=proxy has no circular dependency
        // - 1.14 已移除 legacy address/detour 格式
        val dns = JsonObject()
        val servers = JsonArray()
        
        // 国内直连 DNS - 用于解析国内域名
        val directDns = JsonObject()
        directDns.addProperty("type", "https")
        directDns.addProperty("tag", "direct")
        directDns.addProperty("server", "223.5.5.5")
        directDns.addProperty("path", "/dns-query")
        // 注意: 不设置 detour,让 sing-box 自动路由
        servers.add(directDns)
        
        // 国外远程 DNS - 用于解析国外域名,防止 DNS 污染。
        // 历史问题1: 原配置 server="1.1.1.1" 在本机网络出口被黑洞
        // (Windows Test-NetConnection 1.1.1.1:443=False; 设备直连 curl 5s 超时),
        // 连接建立但无 DoH 应答,google.com 永远解析失败; 与节点无关,换节点无效。
        // 历史问题2: 改用 cloudflare-dns.com 后,实测该节点对 Cloudflare 流量极慢
        // (cloudflare.com 经节点 13.4s 才返回 200),DoH 在 5s 内拿不到应答,
        // google 解析再次卡死(日志: 反复 outbound connection to 104.16.248.249:443)。
        // 现方案: 直接用 Google DoH 的 IP(8.8.8.8, 其证书 SAN 含该 IP,可直接 HTTPS):
        // - IP 形式无需 bootstrap 解析,去掉 domain_resolver,少一个失败环节;
        // - detour=proxy 让 DoH 连接固定走节点出口(实测 detour 生效,可绕过
        //   route.final=direct),而节点到 Google 极快(generate_204 仅 0.48s)。
        val remoteDns = JsonObject()
        remoteDns.addProperty("type", "https")
        remoteDns.addProperty("tag", "remote")
        remoteDns.addProperty("server", "8.8.8.8")
        remoteDns.addProperty("path", "/dns-query")
        remoteDns.addProperty("detour", "proxy")
        servers.add(remoteDns)
        
        dns.add("servers", servers)
        
        val dnsRules = JsonArray()
        
        // 国外域名使用 remote DNS
        val proxyDnsRule = JsonObject()
        val proxyDnsDomains = JsonArray()
        for (domain in PROXY_DOMAINS) {
            proxyDnsDomains.add(domain)
        }
        proxyDnsRule.add("domain_suffix", proxyDnsDomains)
        proxyDnsRule.addProperty("action", "route")
        proxyDnsRule.addProperty("server", "remote")
        dnsRules.add(proxyDnsRule)
        
        dns.add("rules", dnsRules)
        dns.addProperty("final", "direct")  // 默认使用国内 DNS
        dns.addProperty("strategy", "prefer_ipv4")
        root.add("dns", dns)

        return gson.toJson(root)
    }

    private fun buildOutbound(config: AppConfig): JsonObject {
        val outbound = JsonObject()
        
        when (config.protocol) {
            "vless" -> {
                outbound.addProperty("type", "vless")
                outbound.addProperty("tag", "proxy")
                outbound.addProperty("server", config.server)
                outbound.addProperty("server_port", config.serverPort)
                outbound.addProperty("uuid", config.uuid)
                
                if (config.flow.isNotEmpty()) {
                    outbound.addProperty("flow", config.flow)
                }
                
                if (config.tls == "tls" || config.tls == "reality") {
                    val tls = JsonObject()
                    tls.addProperty("enabled", true)
                    tls.addProperty("server_name", config.sni.ifEmpty { config.server })

                    if (config.fingerprint.isNotEmpty()) {
                        val utls = JsonObject()
                        utls.addProperty("enabled", true)
                        utls.addProperty("fingerprint", config.fingerprint)
                        tls.add("utls", utls)
                    }

                    if (config.tls == "reality") {
                        val reality = JsonObject()
                        reality.addProperty("enabled", true)
                        reality.addProperty("public_key", config.realityPublicKey)
                        reality.addProperty("short_id", config.realityShortId)
                        tls.add("reality", reality)
                    }
                    outbound.add("tls", tls)
                }
                
                if (config.transport == "ws") {
                    val transport = JsonObject()
                    transport.addProperty("type", "ws")
                    transport.addProperty("path", config.wsPath.ifEmpty { "/" })
                    val headers = JsonObject()
                    headers.addProperty("Host", config.wsHost.ifEmpty { config.server })
                    transport.add("headers", headers)
                    outbound.add("transport", transport)
                }
            }
            
            "trojan" -> {
                outbound.addProperty("type", "trojan")
                outbound.addProperty("tag", "proxy")
                outbound.addProperty("server", config.server)
                outbound.addProperty("server_port", config.serverPort)
                outbound.addProperty("password", config.password)
                
                if (config.tls == "tls") {
                    val tls = JsonObject()
                    tls.addProperty("enabled", true)
                    tls.addProperty("server_name", config.sni.ifEmpty { config.server })
                    outbound.add("tls", tls)
                }
            }
            
            "shadowsocks" -> {
                outbound.addProperty("type", "shadowsocks")
                outbound.addProperty("tag", "proxy")
                outbound.addProperty("server", config.server)
                outbound.addProperty("server_port", config.serverPort)
                outbound.addProperty("method", config.method.ifEmpty { "aes-128-gcm" })
                outbound.addProperty("password", config.password)
            }
            
            "vmess" -> {
                outbound.addProperty("type", "vmess")
                outbound.addProperty("tag", "proxy")
                outbound.addProperty("server", config.server)
                outbound.addProperty("server_port", config.serverPort)
                outbound.addProperty("uuid", config.uuid)
                outbound.addProperty("security", config.method.ifEmpty { "auto" })
            }
            
            "hysteria2" -> {
                outbound.addProperty("type", "hysteria2")
                outbound.addProperty("tag", "proxy")
                outbound.addProperty("server", config.server)
                outbound.addProperty("server_port", config.serverPort)
                outbound.addProperty("password", config.password)
                
                if (config.tls == "tls") {
                    val tls = JsonObject()
                    tls.addProperty("enabled", true)
                    tls.addProperty("server_name", config.sni.ifEmpty { config.server })
                    outbound.add("tls", tls)
                }
            }
            
            "tuic" -> {
                outbound.addProperty("type", "tuic")
                outbound.addProperty("tag", "proxy")
                outbound.addProperty("server", config.server)
                outbound.addProperty("server_port", config.serverPort)
                outbound.addProperty("uuid", config.uuid)
                outbound.addProperty("password", config.password)
                outbound.addProperty("congestion_control", "cubic")
                
                if (config.tls == "tls") {
                    val tls = JsonObject()
                    tls.addProperty("enabled", true)
                    tls.addProperty("server_name", config.sni.ifEmpty { config.server })
                    outbound.add("tls", tls)
                }
            }
            
            else -> {
                outbound.addProperty("type", "direct")
                outbound.addProperty("tag", "proxy")
            }
        }
        
        return outbound
    }
    
    interface CoreListener {
        fun onStatusChanged(status: SessionStatus)
        fun onLog(message: String)
        fun onError(error: String)
        fun onVpnPermissionRequired(intent: Intent)
    }
}
