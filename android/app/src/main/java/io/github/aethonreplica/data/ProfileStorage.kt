package io.github.aethonreplica.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ProfileStorage(private val context: Context) {

    private val prefs = context.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val gson = Gson()

    init {
        // 一次性迁移: 旧版本可能把全局模式存成 socks5。Android 无系统级
        // 代理支持,socks5 模式只会造成"已连接但浏览器打不开网页"。这里在
        // 构造时立即纠正,确保任何连接路径(自动连接/沿用/自愈)拿到的都是 vpn。
        if (prefs.getString("mode", "vpn") != "vpn") {
            prefs.edit().putString("mode", "vpn").apply()
        }
    }

    companion object {
        // 涓庣數鑴戠増 (app-state.json) 鍚屾鐨勫唴缃妭鐐癸紱棣栨鍚姩鑷姩鍐欏叆锛岀敤鎴峰彲闅忔椂缂栬緫/鏇挎崲銆?
        private val BUILTIN_PROFILES = listOf(
            AppConfig(
                id = "vless-0", name = "HK-1 (175.29.23.87)", protocol = "vless",
                server = "175.29.23.87", serverPort = 443,
                uuid = "c18b978e-1c4e-415c-8bea-07942b563a64",
                tls = "tls", sni = "shuma.ccwu.cc", fingerprint = "chrome", transport = "ws",
                wsPath = "/", wsHost = "shuma.ccwu.cc"
            ),
            AppConfig(
                id = "vless-1", name = "HK-2 (122.10.119.252)", protocol = "vless",
                server = "122.10.119.252", serverPort = 443,
                uuid = "c18b978e-1c4e-415c-8bea-07942b563a64",
                tls = "tls", sni = "shuma.ccwu.cc", fingerprint = "chrome", transport = "ws",
                wsPath = "/", wsHost = "shuma.ccwu.cc"
            ),
            AppConfig(
                id = "vless-2", name = "HK-3 (68.64.178.52)", protocol = "vless",
                server = "68.64.178.52", serverPort = 443,
                uuid = "c18b978e-1c4e-415c-8bea-07942b563a64",
                tls = "tls", sni = "shuma.ccwu.cc", fingerprint = "chrome", transport = "ws",
                wsPath = "/", wsHost = "shuma.ccwu.cc"
            ),
            AppConfig(
                id = "vless-3", name = "JP-1 (103.143.81.126)", protocol = "vless",
                server = "103.143.81.126", serverPort = 8443,
                uuid = "c18b978e-1c4e-415c-8bea-07942b563a64",
                tls = "tls", sni = "shuma.ccwu.cc", fingerprint = "chrome", transport = "ws",
                wsPath = "/", wsHost = "shuma.ccwu.cc"
            )
        )
        private const val DEFAULT_SELECTED_ID = "vless-1" // 涓庣數鑴戠増褰撳墠閫変腑涓€鑷?
    }

    fun getProfiles(): List<AppConfig> {
        ensureSeeded()
        val json = prefs.getString("profiles_list", null) ?: return emptyList()
        val type = object : TypeToken<List<AppConfig>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    /** 棣栨鍚姩鏃跺啓鍏ュ唴缃妭鐐癸紝淇濊瘉鈥滄墦寮€鍗冲彲杩炴帴鈥濄€傚凡淇濆瓨杩囬厤缃殑鐢ㄦ埛涓嶅彈褰卞搷銆?*/
    private fun ensureSeeded() {
        if (prefs.getBoolean("seeded", false)) return
        if (prefs.getString("profiles_list", null) == null) {
            saveProfiles(BUILTIN_PROFILES)
            if (getSelectedProfileId() == null) {
                setSelectedProfileId(DEFAULT_SELECTED_ID)
            }
        }
        prefs.edit().putBoolean("seeded", true).apply()
    }

    fun saveProfiles(profiles: List<AppConfig>) {
        prefs.edit().putString("profiles_list", gson.toJson(profiles)).apply()
    }

    fun addProfile(profile: AppConfig) {
        val profiles = getProfiles().toMutableList()
        profiles.add(profile)
        saveProfiles(profiles)
    }

    fun updateProfile(profile: AppConfig) {
        val profiles = getProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            profiles[index] = profile
            saveProfiles(profiles)
        }
    }

    fun deleteProfile(id: String) {
        val profiles = getProfiles().filter { it.id != id }
        saveProfiles(profiles)
    }

    fun getSelectedProfileId(): String? {
        return prefs.getString("selected_profile", null)
    }

    fun setSelectedProfileId(id: String) {
        prefs.edit().putString("selected_profile", id).apply()
    }

    // ------------------------------------------------------------------
    // 閫氱敤璁剧疆锛堟寔涔呭寲锛屼緵杩炴帴椤?璁剧疆椤靛叡浜級
    // ------------------------------------------------------------------

    // Android 上没有系统级 HTTP 代理支持,socks5 模式下浏览器流量根本不会
    // 进入本地代理端口,表现为"已连接但打不开网页"。历史上设置页允许切换,
    // 旧版本又曾把 socks5 写进存储,导致升级后一直连不上。这里做一次性迁移:
    // 只要存储的不是 vpn,一律纠正回 vpn 并落盘,杜绝该问题再次出现。
    fun getMode(): String {
        val stored = prefs.getString("mode", "vpn") ?: "vpn"
        if (stored != "vpn") {
            prefs.edit().putString("mode", "vpn").apply()
            return "vpn"
        }
        return stored
    }

    /** 模式固定为 vpn;保留此方法仅为兼容旧调用,写入非 vpn 值会被忽略。 */
    fun setMode(value: String) {
        if (value == "vpn") prefs.edit().putString("mode", "vpn").apply()
    }

    fun getScanMode(): String = prefs.getString("scan_mode", "off") ?: "off"
    fun setScanMode(value: String) = prefs.edit().putString("scan_mode", value).apply()

    /** 鍚姩鏃惰嚜鍔ㄨ繛鎺ワ紙榛樿寮€鍚?鈥?鈥滅洿鎺ユ墦寮€灏卞彲浠ヨ繛鎺ヤ娇鐢ㄢ€濓級 */
    fun getAutoConnect(): Boolean = prefs.getBoolean("auto_connect", true)
    fun setAutoConnect(value: Boolean) = prefs.edit().putBoolean("auto_connect", value).apply()
}

