package io.github.aethonreplica.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 与电脑版一致的 Telegram 群组链接 */
const val TELEGRAM_GROUP_URL = "https://t.me/+tVg48WK48tlkNGVl"

/**
 * 底部支持链接（对应电脑版侧边栏底部 / 设置页“关于”）：
 * 💬 Telegram 群组  +  数码解码 · 技术支持
 */
@Composable
fun SupportLinksFooter(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        TextButton(onClick = { openUrl(context, TELEGRAM_GROUP_URL) }) {
            Text(
                text = "💬 Telegram 群组",
                fontSize = 13.sp,
                color = Color(0xFF58A6FF)
            )
        }
        Text(
            text = "数码解码 · 技术支持",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF58A6FF),
            modifier = Modifier
                .background(Color(0xFF1F6FEB).copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        // 无可处理的应用时静默忽略
    }
}