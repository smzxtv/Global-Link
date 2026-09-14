package io.github.aethonreplica.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.aethonreplica.core.SessionStatus
import io.github.aethonreplica.core.SingBoxCore
import kotlinx.coroutines.delay
import io.github.aethonreplica.data.AppConfig
import io.github.aethonreplica.data.ProfileStorage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen() {
    val context = LocalContext.current
    val storage = remember { ProfileStorage(context) }
    val core = remember { SingBoxCore.getInstance() }
    
    var profiles by remember { mutableStateOf(storage.getProfiles()) }
    var selectedProfile by remember { 
        mutableStateOf(storage.getSelectedProfileId()?.let { id -> profiles.find { it.id == id } })
    }
    var status by remember { mutableStateOf(core.status) }
    var logs by remember { mutableStateOf(listOf<String>()) }
    var mode by remember { mutableStateOf(storage.getMode()) }
    var scanMode by remember { mutableStateOf(storage.getScanMode()) }

    fun pushLog(line: String) { logs = (logs + line).takeLast(100) }

    /** 等待 VPN 权限授权期间暂存的配置，授权成功后自动继续连接 */
    var pendingProfile by remember { mutableStateOf<AppConfig?>(null) }
    var pendingVpnRetry by remember { mutableStateOf(false) }

    // ---- 断线自愈 / 状态同步相关状态 ----
    // userRequestedDisconnect: 用户主动断开时不自动重连
    // awaitingVpnPermission: 等待授权弹窗期间不自动重连(避免反复弹窗)
    // reconnectAttempts: 自愈重连次数上限,避免无网环境死循环
    var userRequestedDisconnect by remember { mutableStateOf(false) }
    var awaitingVpnPermission by remember { mutableStateOf(false) }
    var reconnectAttempts by remember { mutableStateOf(0) }
    var firstStatusSeen by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pushLog("[权限] VPN 授权成功，继续连接…")
            awaitingVpnPermission = false
            pendingVpnRetry = true
        } else {
            pushLog("[权限] 未授予 VPN 权限，无法建立全局连接")
            awaitingVpnPermission = false
            pendingProfile = null
        }
    }

    fun doConnect(profile: AppConfig) {
        core.connect(context, profile.copy(mode = mode), listener = object : SingBoxCore.CoreListener {
            override fun onStatusChanged(newStatus: SessionStatus) { status = newStatus }
            override fun onLog(message: String) { pushLog(message) }
            override fun onError(error: String) { pushLog("[错误] $error") }
            override fun onVpnPermissionRequired(intent: android.content.Intent) {
                // 先存档再弹授权框：授权回调里要用它自动重试连接。
                // 期间标记为等待授权,避免自愈逻辑在此期间重复发起连接。
                awaitingVpnPermission = true
                pendingProfile = profile
                permissionLauncher.launch(intent)
            }
        })
    }

    // 注册全局状态观察者: 无论连接由谁发起(连接页按钮、配置页切换节点、
    // 服务进程自愈重启),状态都能同步到本界面,避免显示与实际不符。
    // 放在其它 LaunchedEffect 之前,确保启动自动连接发起时观察者已就位。
    DisposableEffect(core) {
        core.externalStatusListener = object : SingBoxCore.CoreListener {
            override fun onStatusChanged(newStatus: SessionStatus) { status = newStatus }
            override fun onLog(message: String) { pushLog(message) }
            override fun onError(error: String) { pushLog("[错误] $error") }
            override fun onVpnPermissionRequired(intent: android.content.Intent) {}
        }
        onDispose { core.externalStatusListener = null }
    }

    LaunchedEffect(pendingVpnRetry) {
        if (pendingVpnRetry) {
            pendingVpnRetry = false
            pendingProfile?.let { doConnect(it) }
        }
    }

    LaunchedEffect(Unit) {
        profiles = storage.getProfiles()
        if (selectedProfile == null) {
            selectedProfile = storage.getSelectedProfileId()
                ?.let { id -> profiles.find { it.id == id } }
                ?: profiles.firstOrNull()
            selectedProfile?.let { storage.setSelectedProfileId(it.id) }
        }
        // 启动自动连接：权限已授予时直接连上；首次启动会自动弹出授权框
        if (storage.getAutoConnect() &&
            status == SessionStatus.DISCONNECTED &&
            selectedProfile != null
        ) {
            pushLog("[自动] 正在连接 ${selectedProfile?.name} …")
            doConnect(selectedProfile!!)
        }
    }

    // ---- 断线自愈: 非用户主动断开时自动重连(带次数上限,避免无网环境死循环) ----
    LaunchedEffect(status) {
        if (!firstStatusSeen) {
            // 首次组合时的初始状态不算"意外断线",避免与启动自动连接重复触发
            firstStatusSeen = true
            return@LaunchedEffect
        }
        when (status) {
            SessionStatus.CONNECTED -> {
                // 连接稳定保持 10 秒后才清零计数,作为新一轮自愈的起点;
                // 同时解除"用户主动断开"标记,让之后的掉线仍能自愈
                delay(10_000L)
                if (status == SessionStatus.CONNECTED) {
                    reconnectAttempts = 0
                    userRequestedDisconnect = false
                }
            }
            SessionStatus.DISCONNECTED -> {
                val profile = selectedProfile
                if (!userRequestedDisconnect &&
                    !awaitingVpnPermission &&
                    storage.getAutoConnect() &&
                    reconnectAttempts < 5 &&
                    profile != null
                ) {
                    reconnectAttempts++
                    delay(3_000L)
                    // 等待期间状态可能已被其它路径(如切换节点重连)改变,再确认一次
                    if (status != SessionStatus.DISCONNECTED) return@LaunchedEffect
                    pushLog("[自愈] 检测到断线，正在重连（第 $reconnectAttempts 次）…")
                    doConnect(profile)
                }
            }
            else -> {}
        }
    }
    
    val statusColor by animateColorAsState(
        when (status) {
            SessionStatus.CONNECTED -> Color(0xFF3FB950)
            SessionStatus.CONNECTING -> Color(0xFFD29922)
            SessionStatus.ERROR -> Color(0xFFF85149)
            else -> Color(0xFF8B949E)
        },
        label = "statusColor"
    )
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        selectedProfile?.let { profile ->
            Text(
                text = profile.name,
                fontSize = 14.sp,
                color = Color(0xFF8B949E)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.1f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = when (status) {
                        SessionStatus.CONNECTED -> Icons.Filled.CheckCircle
                        SessionStatus.CONNECTING -> Icons.Filled.Sync
                        SessionStatus.ERROR -> Icons.Filled.Error
                        else -> Icons.Filled.PowerOff
                    },
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = when (status) {
                        SessionStatus.CONNECTED -> "已连接"
                        SessionStatus.CONNECTING -> "连接中..."
                        SessionStatus.ERROR -> "错误"
                        else -> "已断开"
                    },
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = {
                if (status == SessionStatus.CONNECTED) {
                    userRequestedDisconnect = true
                    core.disconnect(context, object : SingBoxCore.CoreListener {
                        override fun onStatusChanged(newStatus: SessionStatus) { status = newStatus }
                        override fun onLog(message: String) { pushLog(message) }
                        override fun onError(error: String) { pushLog("[错误] $error") }
                        override fun onVpnPermissionRequired(intent: android.content.Intent) {}
                    })
                } else {
                    val profile = selectedProfile
                    if (profile == null) {
                        pushLog("[提示] 请先在「配置」页添加一个服务器配置")
                    } else {
                        doConnect(profile)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (status == SessionStatus.CONNECTED) Color(0xFFF85149) else Color(0xFF238636)
            )
        ) {
            Text(
                text = when (status) {
                    SessionStatus.CONNECTED -> "断开连接"
                    SessionStatus.CONNECTING -> "连接中…"
                    else -> "连接"
                },
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (logs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    logs.takeLast(8).forEach { line ->
                        Text(text = line, fontSize = 11.sp, color = Color(0xFF8B949E))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        SupportLinksFooter(modifier = Modifier.padding(vertical = 8.dp))
    }
}
