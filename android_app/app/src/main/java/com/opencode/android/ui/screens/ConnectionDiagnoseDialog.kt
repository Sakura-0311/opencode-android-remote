package com.opencode.android.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.network.RelayConnectionState

/**
 * P2-15: 分层连接诊断——手机网络 → Relay → WebSocket → 鉴权 → Desktop Agent → OpenCode → Session，
 * 明确指出具体失败环节，而不是只显示"连接失败"。
 */

private enum class LayerStatus { OK, WARN, ERROR, UNKNOWN }

private data class DiagnoseLayer(
    val name: String,
    val status: LayerStatus,
    val detail: String
)

@Composable
fun ConnectionDiagnoseDialog(
    uiState: OpenCodeUiState,
    onRunDiagnose: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val phoneNetOk = remember { isPhoneNetworkAvailable(context) }

    val layers = buildLayers(uiState, phoneNetOk)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.8f),
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
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("连接诊断", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "按链路逐层检查，定位故障环节",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(layers.size) { idx ->
                        LayerRow(layer = layers[idx], isLast = idx == layers.lastIndex)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (uiState.diagnoseLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在检测 OpenCode 服务…", fontSize = 12.sp)
                    } else {
                        TextButton(onClick = onRunDiagnose) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("重新检测 OpenCode")
                        }
                    }
                }
            }
        }
    }
}

private fun buildLayers(uiState: OpenCodeUiState, phoneNetOk: Boolean): List<DiagnoseLayer> {
    val state = uiState.relayConnectionState
    return listOf(
        DiagnoseLayer(
            name = "手机网络",
            status = if (phoneNetOk) LayerStatus.OK else LayerStatus.ERROR,
            detail = if (phoneNetOk) "移动数据 / Wi-Fi 可用" else "手机无可用网络，请检查 Wi-Fi 或移动数据"
        ),
        DiagnoseLayer(
            name = "Relay 服务器",
            status = when (state) {
                RelayConnectionState.DISCONNECTED -> LayerStatus.ERROR
                else -> LayerStatus.OK
            },
            detail = "地址：${uiState.relayUrl.ifBlank { "未配置" }}"
        ),
        DiagnoseLayer(
            name = "WebSocket 连接",
            status = when (state) {
                RelayConnectionState.CONNECTED,
                RelayConnectionState.AUTHENTICATING,
                RelayConnectionState.AUTHENTICATED,
                RelayConnectionState.DESKTOP_ONLINE -> LayerStatus.OK
                RelayConnectionState.CONNECTING,
                RelayConnectionState.RECONNECTING -> LayerStatus.WARN
                else -> LayerStatus.ERROR
            },
            detail = when (state) {
                RelayConnectionState.DISCONNECTED -> "未连接"
                RelayConnectionState.CONNECTING -> "正在连接…"
                RelayConnectionState.RECONNECTING -> "断线重连中…"
                RelayConnectionState.CONNECTED -> "已连通，等待鉴权"
                RelayConnectionState.AUTHENTICATING -> "正在验证身份…"
                RelayConnectionState.AUTH_FAILED -> "连接曾建立，但鉴权失败"
                RelayConnectionState.AUTHENTICATED,
                RelayConnectionState.DESKTOP_ONLINE -> "连接正常"
            }
        ),
        DiagnoseLayer(
            name = "身份鉴权",
            status = when {
                uiState.isAuthenticated -> LayerStatus.OK
                state == RelayConnectionState.AUTH_FAILED -> LayerStatus.ERROR
                else -> LayerStatus.WARN
            },
            detail = when {
                uiState.isAuthenticated -> "Secret 验证通过（房间：${uiState.accountId.ifBlank { "?" }}）"
                state == RelayConnectionState.AUTH_FAILED -> "Secret 错误或已失效，请重新配对"
                else -> "尚未完成鉴权"
            }
        ),
        DiagnoseLayer(
            name = "Desktop Agent",
            status = when {
                uiState.isDesktopOnline -> LayerStatus.OK
                uiState.isAuthenticated -> LayerStatus.ERROR
                else -> LayerStatus.UNKNOWN
            },
            detail = when {
                uiState.isDesktopOnline -> "电脑端桥接在线"
                uiState.isAuthenticated -> "电脑端未连接：请在电脑上启动 desktop_agent（python agent.py）"
                else -> "需先完成鉴权才能判断"
            }
        ),
        DiagnoseLayer(
            name = "OpenCode 服务",
            status = when (uiState.diagnoseOpencodeOk) {
                true -> LayerStatus.OK
                false -> LayerStatus.ERROR
                null -> if (uiState.isDesktopOnline) LayerStatus.WARN else LayerStatus.UNKNOWN
            },
            detail = when (uiState.diagnoseOpencodeOk) {
                true -> "服务正常${uiState.diagnoseOpencodeVersion.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}"
                false -> uiState.diagnoseOpencodeError.ifBlank { "服务无响应" } +
                        "：请在电脑终端执行 opencode serve --port 4096"
                null -> if (uiState.isDesktopOnline) "尚未检测，点击下方按钮检测"
                else "需 Desktop Agent 在线才能检测"
            }
        ),
        DiagnoseLayer(
            name = "当前会话",
            status = if (uiState.currentSessionId.isNotBlank()) LayerStatus.OK else LayerStatus.WARN,
            detail = if (uiState.currentSessionId.isNotBlank()) {
                val title = uiState.availableSessions.find { it.id == uiState.currentSessionId }?.title
                "已选择：${title?.ifBlank { uiState.currentSessionId } ?: uiState.currentSessionId}"
            } else "未选择会话"
        )
    )
}

@Composable
private fun LayerRow(layer: DiagnoseLayer, isLast: Boolean) {
    val (icon, tint) = when (layer.status) {
        LayerStatus.OK -> Icons.Default.CheckCircle to Color(0xFF4CAF50)
        LayerStatus.WARN -> Icons.Default.Error to Color(0xFFFF9800)
        LayerStatus.ERROR -> Icons.Default.Error to MaterialTheme.colorScheme.error
        LayerStatus.UNKNOWN -> Icons.Default.HelpOutline to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon as ImageVector, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(layer.name, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    layer.detail,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun isPhoneNetworkAvailable(context: Context): Boolean {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) {
        false
    }
}
