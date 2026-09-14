package io.github.aethonreplica.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.aethonreplica.R
import io.github.aethonreplica.ui.MainActivity
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.Notification as LibboxNotification
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File

/**
 * Android VpnService hosting the sing-box core through libbox (embedded,
 * in-process). The service implements libbox PlatformInterface itself so it
 * can open the VpnService.Builder tunnel (an inner class of VpnService) and
 * protect outbound sockets of the core.
 */
class AethonVpnService : VpnService(), CommandServerHandler, PlatformInterface {

    companion object {
        private const val TAG = "AethonVpnService"
        private const val NOTIFICATION_CHANNEL_ID = "aethon_vpn"
        private const val NOTIFICATION_ID = 1

        const val ACTION_CONNECT = "io.github.aethonreplica.CONNECT"
        const val ACTION_DISCONNECT = "io.github.aethonreplica.DISCONNECT"
        const val EXTRA_CONFIG_PATH = "config_path"

        /** 本次断开是切换节点引起(历史遗留, 現已不再使用, 保留避免第三方调用编译失败)。 */
        @Deprecated("切换改走 ACTION_CONNECT 热重载, 不再需要 switching 标记")
        const val EXTRA_SWITCHING = "switching"

        // 自愈: 若进程意外死亡(系统回收、崩溃等), START_STICKY 会以 null intent
        // 重启本服务, 用这里持久化的"最后一次配置"重新拉起隧道。
        private const val SERVICE_PREFS = "aethon_service"
        private const val KEY_LAST_CONFIG = "last_config_path"
        private const val KEY_AUTO_RESTART = "auto_restart"

        /** 串行执行重载/启停的单线程队列: 耗时工作全在这里做, 保证先进先出, 快速连续切换也不会交错拆建会话。 */
        private val vpnOpExecutor: java.util.concurrent.ExecutorService by lazy {
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "aethon-vpn-op").apply { isDaemon = true }
            }
        }

        /**
         * 切换节点: 把新配置记为"最后一次配置"并保留自愈标记。
         * 进程保持存活, 由调用方直接 startService(ACTION_CONNECT) 触发
         * 进程内热重载; 这里的持久化只用于进程意外死亡后的自愈。
         */
        fun saveRestartConfig(context: Context, configPath: String) {
            context.getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE).edit()
                .putString(KEY_LAST_CONFIG, configPath)
                .putBoolean(KEY_AUTO_RESTART, true)
                .apply()
        }

        /** 切换节点: 直接 startService(ACTION_CONNECT) 触发进程内热重载。 */

        @Volatile
        var isRunning = false
            private set
    }

    private var commandServer: CommandServer? = null
    private var platform: AethonPlatform? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var libboxSetupDone = false
    private var defaultInterfaceCallback: ConnectivityManager.NetworkCallback? = null
    private var underlyingNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /** 会话拆除中: 抑制 libbox 回调触发的重入 stopVpn()。 */
    @Volatile
    private var tearingDown = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
                if (configPath != null) {
                    // 记住最后一次使用的配置,供进程意外死亡后自愈重启时使用。
                    getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_LAST_CONFIG, configPath)
                        .putBoolean(KEY_AUTO_RESTART, true)
                        .apply()
                    // 耗时工作(拆旧会话/建新隧道)全在单线程队列里串行执行,
                    // onStartCommand 立即返回: 不卡 UI, 切换节点不"假死闪退",
                    // 快速连续切换也不会交错拆建。startForeground 必须在
                    // onStartCommand 同步调用(前台服务超时限制)。
                    startForeground(NOTIFICATION_ID, createNotification())
                    // 投递到单线程队列后立即返回, 不卡主线程。注意: 这里绝不能
                    // stopSelf/stopSelfResult —— 服务必须保持 started 状态,
                    // 否则系统会走 onDestroy → stopVpn() 把刚建好的隧道拆掉。
                    vpnOpExecutor.execute { startVpn(configPath) }
                }
            }
            ACTION_DISCONNECT -> {
                // 用户在连接页点"断开"才清除自愈标记; 切换节点不再走这条路,
                // 而是直接 ACTION_CONNECT 热重载, 标记始终保留。
                getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_AUTO_RESTART, false)
                    .apply()
                vpnOpExecutor.execute { stopVpn() }
            }
            // 自愈分支(START_STICKY null intent 拉起): 主线程同步建隧道。
            // 此时进程刚被系统新建, 队列线程还没预热且服务必须尽快进前台,
            // 而调用方是系统而非 UI, 不存在卡界面的问题。
            else -> {
                // START_STICKY 重启(进程被杀后系统以 null intent 拉起本服务):
                // 若之前隧道在运行,则用最后一次配置自动重连,实现断线自愈。
                val prefs = getSharedPreferences(SERVICE_PREFS, MODE_PRIVATE)
                val lastConfig = prefs.getString(KEY_LAST_CONFIG, null)
                if (prefs.getBoolean(KEY_AUTO_RESTART, false) &&
                    lastConfig != null && File(lastConfig).isFile
                ) {
                    Log.i(TAG, "service restarted by system: reconnecting with last config")
                    // 尽快进入前台,避免触发 ForegroundServiceDidNotStartInTime 异常
                    startForeground(NOTIFICATION_ID, createNotification())
                    startVpn(lastConfig)
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    // 注意: 本方法只在 vpnOpExecutor 单线程队列里调用(或自愈分支主线程
    // 拉起时)。
    private fun startVpn(configPath: String) {
        try {
            // 服务已在 onStartCommand 同步进前台, 这里无需重复调用。

            ensureLibboxSetup()

            // 先停旧 core: 期间 TUN 接口保持 UP, 旧出站连接按旧规则继续跑,
            // 不会有"接口消失→DNS/TCP 全断"的窗口。这里跑在后台线程,
            // sleep 不会卡 UI。
            teardownSession(keepTun = true)

            val configFile = File(configPath)
            if (!configFile.isFile) {
                Log.e(TAG, "Config file not found: " + configPath)
                stopVpn()
                return
            }

            // platform must exist before the core starts: sing-box calls
            // getInterfaces() during startup and would otherwise throw.
            val helper = AethonPlatform(this)
            platform = helper

            val server = CommandServer(this, this)
            server.start()
            server.startOrReloadService(configFile.readText(), OverrideOptions())

            commandServer = server
            isRunning = true

            // 通知 SingBoxCore 隧道已真正建立(连接页据此把状态置为"已连接")
            SingBoxCore.getInstance().onServiceStarted()

            Log.i(TAG, "VPN started (libbox embedded core)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stopVpn()
        }
    }

    /**
     * 复制一份 TUN 的 fd 交给 sing-box, 并把所有权(所有权随 detachFd 转移)
     * 交给调用方。这样 sing-box 关闭它时不会影响我们的 [vpnInterface],
     * 接口的建立/销毁始终由本服务掌控。
     */
    private fun dupFd(pfd: ParcelFileDescriptor): Int {
        val dup = ParcelFileDescriptor.dup(pfd.fileDescriptor)
        return dup.detachFd()
    }

    private fun ensureLibboxSetup() {
        if (libboxSetupDone) return
        synchronized(this) {
            if (libboxSetupDone) return

            val baseDir = filesDir
            baseDir.mkdirs()
            val workingDir = getExternalFilesDir(null) ?: baseDir
            workingDir.mkdirs()

            val setup = SetupOptions()
            setup.basePath = baseDir.path
            setup.workingPath = workingDir.path
            setup.tempPath = baseDir.path + "/cache"
            setup.fixAndroidStack = true
            setup.debug = true
            setup.logMaxLines = 3000
            setup.appVersion = "2.0.0"
            setup.appMarketingVersion = "2.0.0"
            Libbox.setup(setup)

            libboxSetupDone = true
        }
    }

    /**
     * 释放当前会话资源(PFD / 命令服务器 / 网络回调), 但不停止服务本身。
     * 切换节点时复用同一 tun 接口热重载: 先停旧 core、后起新 core,
     * 中间绝不关闭 PFD —— 系统里一直只有一个 UP 的 tun0, 不会产生
     * DOWN 僵尸接口, TUN 旧包也不会被内核丢弃后触发上层报错。
     * 只有 stopVpn()(用户点"断开")才会真正关闭 PFD、销毁接口。
     */
    // keepTun = true(切换节点): 只停旧 core、不碰 PFD, 接口全程 UP。
    // 旧 CommandServer 必须彻底关闭 —— 它在 filesDir 下监听 command.sock,
    // 不关就直接 new 会 bind 冲突, 新 core 起不来(上一次"切换即断网"的根因)。
    // keepTun = false(用户点"断开"): 连 PFD 一起关, 接口真正销毁。
    private fun teardownSession(keepTun: Boolean = false) {
        tearingDown = true
        try {
            val server = commandServer
            commandServer = null
            // 网络回调由 sing-box 持有引用, 旧 core 退出后不再回调;
            // 切换时注销再重注册反而会产生"默认路由短暂丢失"窗口, 所以保留。
            // 只有彻底断开才注销。
            if (!keepTun) unregisterNetworkCallbacks()
            try {
                server?.closeService()
            } catch (e: Exception) {
                Log.w(TAG, "close service failed", e)
            }
            try {
                server?.close()
            } catch (e: Exception) {
                Log.w(TAG, "close command server failed", e)
            }
            // 给旧 core 一点时间真正退出并释放 command.sock, 避免新实例
            // bind 失败。这是后台线程, sleep 不卡 UI。
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (!keepTun) {
                val pfd = vpnInterface
                vpnInterface = null
                try {
                    pfd?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "close vpn interface failed", e)
                }
            }
            platform = null
            isRunning = false
        } finally {
            tearingDown = false
        }
    }

    private fun stopVpn() {
        teardownSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        // 同步 SingBoxCore 的状态并通知 UI 观察者: 否则隧道被系统终止后
        // 界面仍显示"已连接",而 tun0 实际已消失,流量全部直连。
        try {
            SingBoxCore.getInstance().onServiceStopped()
        } catch (e: Exception) {
            Log.w(TAG, "sync core status failed", e)
        }
        Log.i(TAG, "VPN stopped")
    }

    // ---- libbox PlatformInterface ----

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        // 热重载时 sing-box 会再次调用 openTun。若这里再 establish() 一次,
        // 系统会分配一个新的 tunN, 旧接口则降级为 DOWN 僵尸。
        // 因此已有接口时直接复用, 返回一个 dup 出来的 fd 给 sing-box:
        // 我们的 PFD 始终保持有效, 接口生命周期完全由本服务掌控。
        val existing = vpnInterface
        if (existing != null && existing.fd >= 0) {
            try {
                Log.i(TAG, "reuse existing tun fd for reload")
                return dupFd(existing)
            } catch (e: Exception) {
                Log.w(TAG, "dup existing tun fd failed, will establish a new one", e)
            }
        }
        if (prepare(this) != null) {
            throw IllegalStateException("android: missing vpn permission")
        }
        val builder = Builder()
            .setSession("Aethon VPN")
            .setMtu(options.mtu)

        val inet4 = options.inet4Address
        while (inet4.hasNext()) {
            val prefix = inet4.next()
            builder.addAddress(prefix.address(), prefix.prefix())
        }
        val inet6 = options.inet6Address
        while (inet6.hasNext()) {
            val prefix = inet6.next()
            builder.addAddress(prefix.address(), prefix.prefix())
        }

        if (options.autoRoute) {
            val dnsMode = options.dnsMode
            if (dnsMode != null && dnsMode.value != Libbox.DNSModeDisabled) {
                val servers = options.dnsServerAddress
                while (servers.hasNext()) {
                    builder.addDnsServer(servers.next())
                }
            }
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            // exclude our own package to avoid a VPN loop
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w("AethonPlatform", "addDisallowedApplication failed", e)
            }
        }

        val pfd = builder.establish()
        if (pfd == null) {
            throw IllegalStateException("android: establish vpn interface failed")
        }
        vpnInterface = pfd
        // 交给 sing-box 的必须是 dup 出来的副本: 若直接把 pfd.fd 交出去,
        // core 关闭它时会连带把我们的 PFD 一起废掉, 导致接口无法被干净拆除。
        return dupFd(pfd)
    }

    override fun useProcFS(): Boolean = true

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        throw IllegalStateException("android: connection owner not supported on this version")
    }

    // Reports the real default network to libbox. Without this the
    // platformDefaultInterfaceMonitor never gets a default interface and
    // route.auto_detect_interface cannot bind outbound sockets to a NIC.
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        if (connectivity == null) {
            Log.e(TAG, "ConnectivityManager unavailable")
            return
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun report(network: Network) {
                try {
                    val caps = connectivity.getNetworkCapabilities(network) ?: return
                    // 必须忽略我们自己建立的 VPN 网络(tun0)。VPN 启动后它会成为
                    // 系统默认网络,若上报给 libbox,auto_detect_interface 就会把
                    // 出站 socket 绑到 tun0,流量回灌隧道 → 出站报
                    // "no available network interface" 且 DNS/连接全部失败。
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        Log.i(TAG, "ignore VPN transport network for default interface")
                        return
                    }
                    val link = connectivity.getLinkProperties(network) ?: return
                    val name = link.interfaceName ?: return
                    val index = java.net.NetworkInterface.getByName(name)?.index ?: return
                    val expensive = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    val constrained = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    Log.i(TAG, "default interface: " + name + " index=" + index)
                    listener.updateDefaultInterface(name, index, expensive, constrained)
                } catch (e: Exception) {
                    Log.w(TAG, "report default interface failed", e)
                }
            }

            override fun onAvailable(network: Network) {
                report(network)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                report(network)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                report(network)
            }

            override fun onLost(network: Network) {
                listener.updateDefaultInterface("", -1, false, false)
            }
        }

        defaultInterfaceCallback = callback
        try {
            connectivity.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            Log.e(TAG, "registerDefaultNetworkCallback failed", e)
            defaultInterfaceCallback = null
        }

        // 兜底: 显式监听"非 VPN 且可上网"的底层网络。
        // 场景: 切换节点重连时,新会话的 TUN 建立后系统默认网络随即变为 VPN,
        // 上面的默认网络回调只会报告 VPN 网络并被忽略,libbox 永远拿不到默认
        // 接口,所有出站报 "no available network interface"。
        // (冷启动时注册瞬间还没有 VPN,默认网络就是物理网卡,所以只有
        //  重连路径会踩中这个问题。)
        val underlyingRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val underlyingCallback = object : ConnectivityManager.NetworkCallback() {
            private fun reportUnderlying(network: Network) {
                try {
                    val caps = connectivity.getNetworkCapabilities(network) ?: return
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                    val link = connectivity.getLinkProperties(network) ?: return
                    val name = link.interfaceName ?: return
                    val index = java.net.NetworkInterface.getByName(name)?.index ?: return
                    val expensive = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    val constrained = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    Log.i(TAG, "underlying interface: " + name + " index=" + index)
                    listener.updateDefaultInterface(name, index, expensive, constrained)
                } catch (e: Exception) {
                    Log.w(TAG, "report underlying interface failed", e)
                }
            }

            override fun onAvailable(network: Network) {
                reportUnderlying(network)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                reportUnderlying(network)
            }
        }
        try {
            connectivity.registerNetworkCallback(underlyingRequest, underlyingCallback)
            underlyingNetworkCallback = underlyingCallback
        } catch (e: Exception) {
            Log.w(TAG, "registerNetworkCallback(underlying) failed", e)
        }
    }

    /** 注销全部网络回调。每次会话拆除都要调用,否则重连会不断叠加回调。 */
    private fun unregisterNetworkCallbacks() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        defaultInterfaceCallback?.let { cb ->
            defaultInterfaceCallback = null
            try {
                connectivity?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterNetworkCallback failed", e)
            }
        }
        underlyingNetworkCallback?.let { cb ->
            underlyingNetworkCallback = null
            try {
                connectivity?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                Log.w(TAG, "unregisterNetworkCallback(underlying) failed", e)
            }
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        unregisterNetworkCallbacks()
    }

    override fun getInterfaces() = platform?.getInterfaces()
        ?: throw IllegalStateException("service not started")

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() {
        platform?.clearDNSCache()
    }

    override fun readWIFIState(): WIFIState? = platform?.readWIFIState()

    override fun localDNSTransport(): LocalDNSTransport = AethonLocalDnsTransport()

    override fun startNeighborMonitor(listener: NeighborUpdateListener) {
    }

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) {
    }

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        throw IllegalStateException("android: platform shell is not supported")
    }

    override fun openShellSession(
        user: PlatformUser,
        command: String,
        environ: StringIterator,
        term: String,
        rows: Int,
        cols: Int,
    ): ShellSession {
        throw IllegalStateException("android: platform shell is not supported")
    }

    override fun readSystemSSHHostKey(): String {
        throw IllegalStateException("android: ssh host key is not supported")
    }

    override fun lookupSFTPServer(): String {
        throw IllegalStateException("android: sftp server is not supported")
    }

    override fun tailscaleHostname(): String = ""

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions): BridgeSession {
        throw IllegalStateException("android: platform bridge is not supported")
    }

    override fun lookupUser(username: String): PlatformUser {
        throw IllegalStateException("android: lookup user is not supported")
    }

    override fun registerMyInterface(name: String) {
    }

    override fun sendNotification(notification: LibboxNotification) {
    }

    override fun cancelNotification(identifier: String, typeID: Int) {
    }

    // ---- libbox CommandServerHandler ----

    override fun serviceStop() {
        // 拆除会话期间 core 可能回调本方法,直接返回避免递归 stopVpn()。
        if (tearingDown) return
        stopVpn()
    }

    override fun serviceReload() {
    }

    override fun getSystemProxyStatus(): SystemProxyStatus {
        val status = SystemProxyStatus()
        status.available = false
        status.enabled = false
        return status
    }

    override fun setSystemProxyEnabled(enabled: Boolean) {
    }

    override fun triggerNativeCrash() {
    }

    override fun writeDebugMessage(message: String) {
        Log.d("sing-box", message)
    }

    override fun connectSSHAgent(): Int = -1

    // ---- lifecycle / notification ----

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Aethon VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Aethon VPN")
            .setContentText("VPN is active")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

