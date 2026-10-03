package com.opencode.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 对外分发准备：应用内隐私说明（与仓库根目录 PRIVACY.md 保持一致）。
 */
@Composable
fun PrivacyDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.PrivacyTip,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("隐私说明", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PrivacySection(
                        title = "一句话版本",
                        body = "你的数据只保存在你自己的手机上，只发往你自己配置的服务器。我们不收集、不上传、不分享你的任何个人信息。"
                    )
                    PrivacySection(
                        title = "数据存储",
                        body = "配对密钥（Secret）、云端 API Key、账号 ID 保存在手机本地加密存储中，加密失败时拒绝写入，绝不降级为明文。会话列表、聊天记录、服务器地址、断线恢复序号保存在本地普通存储。以上数据可通过\"清除应用数据\"或卸载应用彻底删除。"
                    )
                    PrivacySection(
                        title = "数据去向",
                        body = "本应用只连接你自己填写的服务器（自建 Relay、电脑端 Agent、云端 OpenCode）。聊天内容、文件浏览结果仅在你的手机与你的服务器之间传输，不经过任何第三方。我们没有账号系统，没有统计 SDK，没有广告 SDK。"
                    )
                    PrivacySection(
                        title = "权限用途",
                        body = "相机：仅用于扫描配对二维码。通知：任务完成/失败/等待审批提醒。前台服务与唤醒锁：保持长连接（可关闭）。网络状态：判断断网。震动：通知提醒。"
                    )
                    PrivacySection(
                        title = "更新检查",
                        body = "\"检查更新\"会请求 GitHub 公开 API 获取最新版本信息，仅传输应用版本号，不含个人信息。"
                    )
                    PrivacySection(
                        title = "崩溃上报",
                        body = "如你开启崩溃上报，崩溃时会发送脱敏后的堆栈（不含聊天内容与密钥）用于修 bug。默认关闭。"
                    )
                    Text(
                        "更新日期：2026-10-04",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("知道了")
                }
            }
        }
    }
}

@Composable
private fun PrivacySection(title: String, body: String) {
    Column {
        Text(
            title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(body, fontSize = 13.sp, lineHeight = 19.sp)
    }
}
