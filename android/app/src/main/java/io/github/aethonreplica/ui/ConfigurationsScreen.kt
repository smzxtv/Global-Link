package io.github.aethonreplica.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aethonreplica.core.SessionStatus
import io.github.aethonreplica.core.SingBoxCore
import io.github.aethonreplica.data.AppConfig
import io.github.aethonreplica.data.ProfileStorage

/** Configurations tab: manage server profiles from share links. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationsScreen() {
    val context = LocalContext.current
    val storage = remember { ProfileStorage(context) }
    val core = remember { SingBoxCore.getInstance() }

    var profiles by remember { mutableStateOf(storage.getProfiles()) }
    var selectedId by remember { mutableStateOf(storage.getSelectedProfileId()) }
    var shareLink by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "服务器配置",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        TextField(
            value = shareLink,
            onValueChange = { shareLink = it },
            label = { Text("分享链接") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                val parsed = AppConfig.fromShareLink(shareLink)
                if (parsed != null) {
                    storage.addProfile(parsed)
                    profiles = storage.getProfiles()
                    selectedId = parsed.id
                    storage.setSelectedProfileId(parsed.id)
                    shareLink = ""
                    message = "已添加: ${parsed.protocol} ${parsed.server}"
                } else {
                    message = "无法解析分享链接，请检查格式"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("添加配置")
        }

        if (message.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = message, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (profiles.isEmpty()) {
            Text(
                text = "暂无配置\n在上方粘贴分享链接并点击「添加配置」",
                fontSize = 14.sp,
                color = androidx.compose.ui.graphics.Color(0xFF8B949E)
            )
        }

        profiles.forEach { profile ->
            val isSelected = (selectedId == profile.id)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = profile.name.ifEmpty { profile.server },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${profile.protocol} · ${profile.server}:${profile.serverPort}" + if (isSelected) " · 已选择" else "",
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                selectedId = profile.id
                                storage.setSelectedProfileId(profile.id)
                                // 隧道若在运行,立即用新节点重连,否则会继续跑旧节点
                                // (singbox_config.json 只在 connect() 里重写,不重连就不生效)
                                if (core.status == SessionStatus.CONNECTED) {
                                    core.reconnect(
                                        context,
                                        profile.copy(mode = storage.getMode()),
                                        object : SingBoxCore.CoreListener {
                                            override fun onStatusChanged(newStatus: SessionStatus) {}
                                            override fun onLog(message: String) {}
                                            override fun onError(error: String) {
                                                message = "[错误] $error"
                                            }
                                            override fun onVpnPermissionRequired(intent: android.content.Intent) {
                                                message = "需要 VPN 授权，请到「连接」页点击连接"
                                            }
                                        }
                                    )
                                    message = "已切换节点并热重载: ${profile.name}"
                                } else {
                                    message = "已选择: ${profile.name}"
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isSelected) "已选择" else "选择")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                storage.deleteProfile(profile.id)
                                profiles = storage.getProfiles()
                                if (isSelected) {
                                    selectedId = null
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("删除")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
