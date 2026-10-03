package com.opencode.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.opencode.android.network.DeviceInfo
import java.text.SimpleDateFormat
import java.util.*

/**
 * v1.6 P0 多设备管理：设备列表 / 重命名 / 撤销
 */
@Composable
fun DeviceManagementDialog(
    devices: List<DeviceInfo>,
    onRefresh: () -> Unit,
    onRevoke: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var revokeTarget by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<DeviceInfo?>(null) }
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { onRefresh() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("设备管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                }

                Text(
                    "${devices.size} 台已配对设备",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (devices.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无配对设备", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(devices) { device ->
                            DeviceRow(
                                device = device,
                                onRevoke = { revokeTarget = device.deviceName },
                                onRename = {
                                    renameTarget = device
                                    newName = device.deviceName
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // 撤销确认
    revokeTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("撤销设备") },
            text = { Text("确定撤销「$name」的配对授权吗？撤销后该设备将无法连接。") },
            confirmButton = {
                Button(
                    onClick = {
                        onRevoke(name)
                        revokeTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("撤销") }
            },
            dismissButton = {
                TextButton(onClick = { revokeTarget = null }) { Text("取消") }
            }
        )
    }

    // 重命名
    renameTarget?.let { device ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名设备") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(32) },
                    label = { Text("设备名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newName.isNotBlank() && newName != device.deviceName) {
                        onRename(device.deviceName, newName.trim())
                    }
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DeviceRow(
    device: DeviceInfo,
    onRevoke: () -> Unit,
    onRename: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Smartphone,
                contentDescription = null,
                tint = if (device.isOnline) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.deviceName, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    if (device.isOnline) {
                        Surface(
                            color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                "在线",
                                fontSize = 11.sp,
                                color = Color(0xFF4CAF50),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                val lastActive = if (device.lastActive > 0)
                    "最近活动 ${dateFmt.format(Date(device.lastActive))}" else ""
                val created = if (device.createdAt > 0)
                    "配对于 ${dateFmt.format(Date(device.createdAt))}" else ""
                Text(
                    listOf(lastActive, created).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Default.Edit, contentDescription = "重命名", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onRevoke) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "撤销",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
