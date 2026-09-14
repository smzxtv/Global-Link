package io.github.aethonreplica.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class AppConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val protocol: String = "vless",
    // 与 ProfileStorage.getMode() 的默认值("vpn")保持一致。否则任何按默认值
    // 构造出的配置都会是 socks-only(无 TUN),浏览器流量根本不走代理,
    // 表现为"谷歌打不开"却看不出原因。
    val mode: String = "vpn",
    val server: String = "",
    val serverPort: Int = 443,
    val uuid: String = "",
    val password: String = "",
    val method: String = "aes-128-gcm",
    val tls: String = "tls",
    val sni: String = "",
    val fingerprint: String = "",
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val transport: String = "tcp",
    val wsPath: String = "/",
    val wsHost: String = "",
    val flow: String = "",
    val listenAddress: String = "127.0.0.1",
    val listenPort: Int = 1819,
    val logLevel: String = "info",
    var congestion: String = "cubic",
    var mtu: Int = 1380
) {
    companion object {
        private val gson = Gson()
        
        fun fromShareLink(link: String): AppConfig? {
            return ShareLinkParser.parse(link)
        }
    }
}
