package io.github.aethonreplica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import io.github.aethonreplica.core.SingBoxCore
import io.github.aethonreplica.data.ProfileStorage

/** Settings tab: connection mode, DNS scan mode, auto-connect, about & support links. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val core = remember { SingBoxCore.getInstance() }
    val storage = remember { ProfileStorage(context) }

    var mode by remember { mutableStateOf(storage.getMode()) }
    var scanMode by remember { mutableStateOf(storage.getScanMode()) }
    var autoConnect by remember { mutableStateOf(storage.getAutoConnect()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "设置",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "连接模式",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "VPN（全局代理）",
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "全局 VPN 模式：所有应用流量均走加密隧道。SOCKS5 仅代理端口模式在 Android 上无法让浏览器自动走代理，已移除。",
                    fontSize = 11.sp,
                    color = androidx.compose.ui.graphics.Color(0xFF8B949E)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "DNS 扫描模式",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { scanMode = "off"; storage.setScanMode("off") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("关闭")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { scanMode = "dns"; storage.setScanMode("dns") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("DNS")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { scanMode = "http"; storage.setScanMode("http") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("HTTP")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { scanMode = "route"; storage.setScanMode("route") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("路由")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "启动时自动连接",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "打开应用后自动连接当前节点（首次需授权 VPN）",
                        fontSize = 11.sp,
                        color = androidx.compose.ui.graphics.Color(0xFF8B949E)
                    )
                }
                Switch(
                    checked = autoConnect,
                    onCheckedChange = {
                        autoConnect = it
                        storage.setAutoConnect(it)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "核心状态: " + core.status,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "关于",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "环球通 v2.0.0 - 出品：数码解码 · 核心 sing-box 1.14.0",
                    fontSize = 12.sp,
                    color = androidx.compose.ui.graphics.Color(0xFF8B949E),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                SupportLinksFooter()
            }
        }
    }
}