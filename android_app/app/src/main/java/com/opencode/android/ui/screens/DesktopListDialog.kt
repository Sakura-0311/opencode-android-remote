package com.opencode.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.opencode.android.network.DesktopInfo
import com.opencode.android.util.DesktopRoutingPolicy
import java.text.SimpleDateFormat
import java.util.*

/**
 * v3.1 多 desktop 定向路由：在线 desktop 列表，选择目标电脑。
 * 空 deviceId 表示「主 desktop（自动）」。
 */
@Composable
fun DesktopListDialog(
    desktops: List<DesktopInfo>,
    selectedDeviceId: String,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    LaunchedEffect(Unit) { onRefresh() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.75f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.desk_001), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.desk_002))
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.desk_003))
                        }
                    }
                }

                Text(
                    stringResource(R.string.desk_004),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    // 「主 desktop（自动）」选项
                    item {
                        DesktopRow(
                            name = stringResource(R.string.desk_005),
                            subId = "",
                            isPrimary = false,
                            lastActiveText = "",
                            selected = selectedDeviceId.isEmpty(),
                            onClick = { onSelect("") }
                        )
                        HorizontalDivider()
                    }
                    items(desktops, key = { it.deviceId }) { d ->
                        val lastActiveText = if (d.lastActive > 0)
                            stringResource(R.string.desk_006, dateFmt.format(Date(d.lastActive))) else ""
                        DesktopRow(
                            name = d.deviceName.ifBlank { "Desktop" },
                            subId = DesktopRoutingPolicy.shortId(d.deviceId),
                            isPrimary = d.isPrimary,
                            lastActiveText = lastActiveText,
                            selected = d.deviceId == selectedDeviceId,
                            onClick = { onSelect(d.deviceId) }
                        )
                        HorizontalDivider()
                    }
                }

                if (desktops.isEmpty()) {
                    Text(
                        stringResource(R.string.desk_007),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopRow(
    name: String,
    subId: String,
    isPrimary: Boolean,
    lastActiveText: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Computer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                if (isPrimary) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            stringResource(R.string.desk_008),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            val sub = listOf(
                subId.takeIf { it.isNotEmpty() }?.let { "ID $it" } ?: "",
                lastActiveText
            ).filter { it.isNotBlank() }.joinToString(stringResource(R.string.desk_009))
            if (sub.isNotEmpty()) {
                Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = stringResource(R.string.desk_010),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
