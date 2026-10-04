package com.opencode.android.ui.screens

import com.opencode.android.R
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
import androidx.compose.ui.res.stringResource
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
                        Text(stringResource(R.string.diag_001), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.diag_002))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.diag_003),
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
                        Text(stringResource(R.string.diag_004), fontSize = 12.sp)
                    } else {
                        TextButton(onClick = onRunDiagnose) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.diag_005))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun buildLayers(uiState: OpenCodeUiState, phoneNetOk: Boolean): List<DiagnoseLayer> {
    val state = uiState.relayConnectionState
    return listOf(
        DiagnoseLayer(
            name = stringResource(R.string.diag_006),
            status = if (phoneNetOk) LayerStatus.OK else LayerStatus.ERROR,
            detail = if (phoneNetOk) stringResource(R.string.diag_007) else stringResource(R.string.diag_008)
        ),
        DiagnoseLayer(
            name = stringResource(R.string.diag_009),
            status = when (state) {
                RelayConnectionState.DISCONNECTED -> LayerStatus.ERROR
                else -> LayerStatus.OK
            },
            detail = stringResource(R.string.diag_010, uiState.relayUrl.ifBlank { stringResource(R.string.diag_n01) })
        ),
        DiagnoseLayer(
            name = stringResource(R.string.diag_011),
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
                RelayConnectionState.DISCONNECTED -> stringResource(R.string.diag_012)
                RelayConnectionState.CONNECTING -> stringResource(R.string.diag_013)
                RelayConnectionState.RECONNECTING -> stringResource(R.string.diag_014)
                RelayConnectionState.CONNECTED -> stringResource(R.string.diag_015)
                RelayConnectionState.AUTHENTICATING -> stringResource(R.string.diag_016)
                RelayConnectionState.AUTH_FAILED -> stringResource(R.string.diag_017)
                RelayConnectionState.AUTHENTICATED,
                RelayConnectionState.DESKTOP_ONLINE -> stringResource(R.string.diag_018)
            }
        ),
        DiagnoseLayer(
            name = stringResource(R.string.diag_019),
            status = when {
                uiState.isAuthenticated -> LayerStatus.OK
                state == RelayConnectionState.AUTH_FAILED -> LayerStatus.ERROR
                else -> LayerStatus.WARN
            },
            detail = when {
                uiState.isAuthenticated -> stringResource(R.string.diag_020, uiState.accountId.ifBlank { "?" })
                state == RelayConnectionState.AUTH_FAILED -> stringResource(R.string.diag_021)
                else -> stringResource(R.string.diag_022)
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
                uiState.isDesktopOnline -> stringResource(R.string.diag_023)
                uiState.isAuthenticated -> stringResource(R.string.diag_024)
                else -> stringResource(R.string.diag_025)
            }
        ),
        DiagnoseLayer(
            name = stringResource(R.string.diag_026),
            status = when (uiState.diagnoseOpencodeOk) {
                true -> LayerStatus.OK
                false -> LayerStatus.ERROR
                null -> if (uiState.isDesktopOnline) LayerStatus.WARN else LayerStatus.UNKNOWN
            },
            detail = when (uiState.diagnoseOpencodeOk) {
                true -> stringResource(R.string.diag_027, uiState.diagnoseOpencodeVersion.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "")
                false -> uiState.diagnoseOpencodeError.ifBlank { stringResource(R.string.diag_028) } +
                        stringResource(R.string.diag_029)
                null -> if (uiState.isDesktopOnline) stringResource(R.string.diag_030)
                else stringResource(R.string.diag_031)
            }
        ),
        DiagnoseLayer(
            name = stringResource(R.string.diag_032),
            status = if (uiState.currentSessionId.isNotBlank()) LayerStatus.OK else LayerStatus.WARN,
            detail = if (uiState.currentSessionId.isNotBlank()) {
                val title = uiState.availableSessions.find { it.id == uiState.currentSessionId }?.title
                stringResource(R.string.diag_033, title?.ifBlank { uiState.currentSessionId } ?: uiState.currentSessionId)
            } else stringResource(R.string.diag_034)
        ),
        // v3.2: 安全存储状态
        DiagnoseLayer(
            name = stringResource(R.string.diag_035),
            status = if (uiState.secureStorageOk) LayerStatus.OK else LayerStatus.WARN,
            detail = uiState.secureStorageInfo.ifBlank { stringResource(R.string.diag_036) }
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
