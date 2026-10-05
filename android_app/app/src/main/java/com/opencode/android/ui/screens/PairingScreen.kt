package com.opencode.android.ui.screens

import com.opencode.android.BuildConfig

import com.opencode.android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.data.model.AppError
import com.opencode.android.data.model.AppMode
import com.opencode.android.data.model.DiagnosticsResult
import com.opencode.android.service.OpenCodeKeepAliveService

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
    diagnostics: DiagnosticsResult?,
    statusBanner: String?,
    onSwitchMode: (AppMode) -> Unit,
    onTestConnectivity: () -> Unit,
    onConnectDesktop: (accountId: String, secret: String, relayUrl: String) -> Unit,
    onConnectCloud: (cloudUrl: String, apiKey: String, workspace: String) -> Unit,
    // v1.6 P0 扫码配对
    onQrPairing: (relayUrl: String, accountId: String, pairingToken: String, desktopName: String) -> Unit = { _, _, _, _ -> }
) {
    val context = LocalContext.current
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

    // v1.6 P0 扫码配对状态
    var showScanner by remember { mutableStateOf(false) }
    var scannedQr by remember { mutableStateOf<PairingQrData?>(null) }
    var showPairConfirm by remember { mutableStateOf(false) }

    // v4.8.0/M1: 明文协议明示 —— ws:// 或 http:// 时显示警告；
    // release 包下连接前弹确认框
    val insecureScheme = remember(relayUrl) { isInsecureRelayUrl(relayUrl) }
    var showInsecureConfirm by remember { mutableStateOf(false) }

    // v1.6: 扫码器全屏覆盖
    if (showScanner) {
        QrScannerScreen(
            onQrScanned = { data ->
                scannedQr = data
                showScanner = false
                showPairConfirm = true
            },
            onCancel = { showScanner = false }
        )
        return
    }

    // v1.6: 扫码后确认对话框（显示设备名称、电脑名称、连接地址和权限摘要）
    if (showPairConfirm && scannedQr != null) {
        val qr = scannedQr!!
        AlertDialog(
            onDismissRequest = { showPairConfirm = false },
            title = { Text(stringResource(R.string.pair_001)) },
            text = {
                Column {
                    Text(stringResource(R.string.pair_002, qr.desktopName), fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(stringResource(R.string.pair_003, qr.accountId), fontSize = 13.sp)
                    Text(stringResource(R.string.pair_004, qr.relayUrl), fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.pair_005),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showPairConfirm = false
                    onQrPairing(qr.relayUrl, qr.accountId, qr.pairingToken, qr.desktopName)
                }) { Text(stringResource(R.string.pair_001)) }
            },
            dismissButton = {
                TextButton(onClick = { showPairConfirm = false }) { Text(stringResource(R.string.pair_006)) }
            }
        )
    }
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
            text = stringResource(R.string.pair_007),
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
                text = { Text(stringResource(R.string.pair_008), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = {
                    selectedTab = 1
                    onSwitchMode(AppMode.CLOUD_HOSTED)
                },
                text = { Text(stringResource(R.string.pair_009), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

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

        // 连通性与隧道排查诊断卡片
        if (diagnostics != null) {
            val isSuccess = diagnostics.isSuccess
            val cardBg = if (isSuccess) Color(0xFF0F3823) else MaterialTheme.colorScheme.errorContainer
            val contentColor = if (isSuccess) Color(0xFF4ADE80) else MaterialTheme.colorScheme.onErrorContainer

            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = diagnostics.statusTitle,
                            fontWeight = FontWeight.Bold,
                            color = contentColor,
                            fontSize = 13.sp
                        )
                    }
                    if (diagnostics.detailMessage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = diagnostics.detailMessage,
                            color = contentColor.copy(alpha = 0.9f),
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        )
                    }
                    if (!diagnostics.tunnelHint.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = Color.Black.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = diagnostics.tunnelHint,
                                color = contentColor,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }

        // 错误提示卡片
        if (appError != null && diagnostics == null) {
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
                label = { Text(stringResource(R.string.pair_010)) },
                placeholder = { Text(stringResource(R.string.pair_011)) },
                leadingIcon = {
                    Icon(Icons.Default.Badge, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("pair_account"),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text(stringResource(R.string.pair_012)) },
                placeholder = { Text(stringResource(R.string.pair_013)) },
                leadingIcon = {
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                        IconButton(onClick = {
                            clipboard.getText()?.text?.trim()?.let {
                                if (it.isNotEmpty()) secret = it
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = stringResource(R.string.pair_014),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = { isSecretVisible = !isSecretVisible }) {
                            Icon(
                                imageVector = if (isSecretVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle Secret"
                            )
                        }
                    }
                },
                visualTransformation = if (isSecretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("pair_secret"),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = relayUrl,
                onValueChange = { relayUrl = it },
                label = { Text(stringResource(R.string.pair_015)) },
                placeholder = { Text(stringResource(R.string.pair_016)) },
                leadingIcon = {
                    Icon(Icons.Default.Sensors, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("pair_relay_url"),
                shape = RoundedCornerShape(12.dp)
            )

            // v4.8.0/M1: 明文协议警告（ws:// / http://）
            if (insecureScheme) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().testTag("pair_insecure_warning")
                ) {
                    Icon(
                        Icons.Default.Warning, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.pair_034),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onTestConnectivity,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.pair_017), fontSize = 13.sp)
                }

                Button(
                    onClick = {
                        // v4.8.0/M1: release 包下明文协议连接前二次确认
                        if (insecureScheme && !BuildConfig.DEBUG) showInsecureConfirm = true
                        else onConnectDesktop(accountId, secret, relayUrl)
                    },
                    modifier = Modifier.weight(1.4f).height(48.dp).testTag("pair_connect"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Computer, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.pair_018), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // v1.6 P0 一键扫码配对
            OutlinedButton(
                onClick = { showScanner = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.pair_019), fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = stringResource(R.string.pair_020),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.pair_021) +
                                "1. `opencode serve --port 4096`\n" +
                                "2. `python desktop_agent/agent.py $accountId`\n" +
                                stringResource(R.string.pair_022),
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
                label = { Text(stringResource(R.string.pair_023)) },
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
                label = { Text(stringResource(R.string.pair_024)) },
                placeholder = { Text(stringResource(R.string.pair_025)) },
                leadingIcon = {
                    Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                        IconButton(onClick = {
                            clipboard.getText()?.text?.trim()?.let {
                                if (it.isNotEmpty()) cloudKey = it
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = stringResource(R.string.pair_026),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = { isCloudKeyVisible = !isCloudKeyVisible }) {
                            Icon(
                                imageVector = if (isCloudKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle Cloud Key"
                            )
                        }
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
                label = { Text(stringResource(R.string.pair_027)) },
                placeholder = { Text("/workspace") },
                leadingIcon = {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onTestConnectivity,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.pair_017), fontSize = 13.sp)
                }

                Button(
                    onClick = { onConnectCloud(cloudUrl, cloudKey, cloudWorkspace) },
                    modifier = Modifier.weight(1.4f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.CloudDone, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.pair_028), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = stringResource(R.string.pair_029),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.pair_030) +
                                stringResource(R.string.pair_031) +
                                stringResource(R.string.pair_032),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 电池优化忽略引导
        TextButton(
            onClick = { OpenCodeKeepAliveService.requestIgnoreBatteryOptimization(context) }
        ) {
            Icon(Icons.Default.BatteryAlert, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.pair_033), fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(28.dp))
    }

    // v4.8.0/M1: 明文协议连接确认框（release 包）
    if (showInsecureConfirm) {
        AlertDialog(
            onDismissRequest = { showInsecureConfirm = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.pair_035)) },
            text = { Text(stringResource(R.string.pair_036, relayUrl)) },
            confirmButton = {
                TextButton(onClick = {
                    showInsecureConfirm = false
                    onConnectDesktop(accountId, secret, relayUrl)
                }) { Text(stringResource(R.string.pair_037)) }
            },
            dismissButton = {
                TextButton(onClick = { showInsecureConfirm = false }) {
                    Text(stringResource(R.string.pair_038))
                }
            }
        )
    }
}

/** v4.8.0/M1: 是否为明文协议（ws:// 或 http://）。 */
fun isInsecureRelayUrl(url: String): Boolean {
    val u = url.trim().lowercase()
    return u.startsWith("ws://") || u.startsWith("http://")
}
