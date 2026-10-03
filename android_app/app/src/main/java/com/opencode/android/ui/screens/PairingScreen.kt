package com.opencode.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.data.model.AppError
import com.opencode.android.data.model.AppMode

@Composable
fun PairingScreen(
    currentMode: AppMode,
    initialAccountId: String,
    initialSecret: String,
    initialRelayUrl: String,
    initialCloudUrl: String,
    initialCloudKey: String,
    initialCloudWorkspace: String,
    appError: AppError?,
    statusBanner: String?,
    onSwitchMode: (AppMode) -> Unit,
    onConnectDesktop: (accountId: String, secret: String, relayUrl: String) -> Unit,
    onConnectCloud: (cloudUrl: String, apiKey: String, workspace: String) -> Unit
) {
    var selectedTab by remember(currentMode) {
        mutableStateOf(if (currentMode == AppMode.DESKTOP_RELAY) 0 else 1)
    }

    // 模式 1 状态
    var accountId by remember(initialAccountId) { mutableStateOf(initialAccountId) }
    var secret by remember(initialSecret) { mutableStateOf(initialSecret) }
    var relayUrl by remember(initialRelayUrl) { mutableStateOf(initialRelayUrl) }
    var isSecretVisible by remember { mutableStateOf(false) }

    // 模式 2 状态
    var cloudUrl by remember(initialCloudUrl) { mutableStateOf(initialCloudUrl) }
    var cloudKey by remember(initialCloudKey) { mutableStateOf(initialCloudKey) }
    var cloudWorkspace by remember(initialCloudWorkspace) { mutableStateOf(initialCloudWorkspace) }
    var isCloudKeyVisible by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.height(28.dp))

        // App Logo & Header
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = "OpenCode Logo",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "OpenCode Remote & Cloud",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = "双模支持 · 电脑远程控制 / 云端免机运行",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 模式切换 Tab
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = {
                    selectedTab = 0
                    onSwitchMode(AppMode.DESKTOP_RELAY)
                },
                text = { Text("💻 电脑中继模式", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = {
                    selectedTab = 1
                    onSwitchMode(AppMode.CLOUD_HOSTED)
                },
                text = { Text("☁️ 云端工作区", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 状态提示
        if (!statusBanner.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    text = statusBanner,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        // 错误提示卡片
        if (appError != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "[${appError.code}] ${appError.message}",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        if (selectedTab == 0) {
            // ==================== 模式 1: 电脑中继模式输入 ====================
            OutlinedTextField(
                value = accountId,
                onValueChange = { accountId = it },
                label = { Text("账号或房间名 (Account ID)") },
                placeholder = { Text("如: user_dev_001") },
                leadingIcon = {
                    Icon(Icons.Default.Badge, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text("配对安全密钥 (Secret)") },
                placeholder = { Text("电脑端启动时显示的 32 字节密钥") },
                leadingIcon = {
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingIcon = {
                    IconButton(onClick = { isSecretVisible = !isSecretVisible }) {
                        Icon(
                            imageVector = if (isSecretVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle Secret"
                        )
                    }
                },
                visualTransformation = if (isSecretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = relayUrl,
                onValueChange = { relayUrl = it },
                label = { Text("中继服务地址 (Relay Server URL)") },
                placeholder = { Text("wss://relay.yourdomain.com 或 ws://10.0.2.2:8765") },
                leadingIcon = {
                    Icon(Icons.Default.Sensors, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { onConnectDesktop(accountId, secret, relayUrl) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.Computer, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "连接到电脑端 OpenCode",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "💡 电脑中继使用指引：",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "在电脑端依次启动：\n" +
                                "1. `opencode serve --port 4096`\n" +
                                "2. `python desktop_agent/agent.py $accountId`\n" +
                                "将终端打印的 Secret 填入上方即可连线。",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }

        } else {
            // ==================== 模式 2: 云端工作区直连输入 ====================
            OutlinedTextField(
                value = cloudUrl,
                onValueChange = { cloudUrl = it },
                label = { Text("云端 OpenCode 实例地址") },
                placeholder = { Text("https://opencode.yourdomain.com:4096") },
                leadingIcon = {
                    Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = cloudKey,
                onValueChange = { cloudKey = it },
                label = { Text("云端访问 Token / API Key (可选)") },
                placeholder = { Text("部署时设置的鉴权 Token") },
                leadingIcon = {
                    Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingIcon = {
                    IconButton(onClick = { isCloudKeyVisible = !isCloudKeyVisible }) {
                        Icon(
                            imageVector = if (isCloudKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle Cloud Key"
                        )
                    }
                },
                visualTransformation = if (isCloudKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = cloudWorkspace,
                onValueChange = { cloudWorkspace = it },
                label = { Text("云端工作区挂载目录") },
                placeholder = { Text("/workspace") },
                leadingIcon = {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = { onConnectCloud(cloudUrl, cloudKey, cloudWorkspace) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.CloudDone, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "连入云端 OpenCode 工作区",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "☁️ 什么是云端工作区模式？",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "该模式完全不依赖本地电脑开机！\n" +
                                "OpenCode 7x24 小时运行在您的云服务器（VPS/Docker）上。\n" +
                                "手机随时随地通过 HTTPS/SSE 直连云端写代码、跑测试。",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(36.dp))
    }
}
