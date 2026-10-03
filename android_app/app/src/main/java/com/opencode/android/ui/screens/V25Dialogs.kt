package com.opencode.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.opencode.android.data.model.AppMode
import com.opencode.android.data.model.ConnectionProfile
import com.opencode.android.util.OpLog

// ============ v2.5: 多连接 profiles 管理 ============

@Composable
fun ProfileManagerDialog(
    profiles: List<ConnectionProfile>,
    activeProfileId: String,
    onSwitch: (String) -> Unit,
    onAdd: (ConnectionProfile) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ConnectionProfile?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("连接配置", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "切换不同服务器/账号。密钥按配置单独保存，切换后需重新连接。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(profiles, key = { it.id }) { p ->
                        val active = p.id == activeProfileId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSwitch(p.id); onDismiss() }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (active) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(p.name, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                                Text(
                                    if (p.mode == AppMode.DESKTOP_RELAY) "电脑中继 · ${p.relayUrl.ifBlank { "未配置" }}"
                                    else "云端直连 · ${p.cloudUrl.ifBlank { "未配置" }}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            }
                            IconButton(onClick = { renameTarget = p }) {
                                Icon(Icons.Default.Edit, contentDescription = "重命名", modifier = Modifier.size(18.dp))
                            }
                            if (profiles.size > 1) {
                                IconButton(onClick = { onDelete(p.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        Divider()
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("新建配置")
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("关闭") }
                }
            }
        }
    }
    if (showAdd) {
        ProfileEditDialog(
            title = "新建连接配置",
            onConfirm = { onAdd(it); showAdd = false },
            onDismiss = { showAdd = false }
        )
    }
    renameTarget?.let { target ->
        var name by remember { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("配置名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) onRename(target.id, name.trim())
                    renameTarget = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun ProfileEditDialog(
    title: String,
    onConfirm: (ConnectionProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(AppMode.DESKTOP_RELAY) }
    var relayUrl by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf("") }
    var cloudUrl by remember { mutableStateOf("") }
    var cloudWorkspace by remember { mutableStateOf("/workspace") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("配置名称（如：家里电脑）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = mode == AppMode.DESKTOP_RELAY,
                        onClick = { mode = AppMode.DESKTOP_RELAY }
                    )
                    Text("电脑中继", modifier = Modifier.clickable { mode = AppMode.DESKTOP_RELAY })
                    Spacer(modifier = Modifier.width(16.dp))
                    RadioButton(
                        selected = mode == AppMode.CLOUD_HOSTED,
                        onClick = { mode = AppMode.CLOUD_HOSTED }
                    )
                    Text("云端直连", modifier = Modifier.clickable { mode = AppMode.CLOUD_HOSTED })
                }
                if (mode == AppMode.DESKTOP_RELAY) {
                    OutlinedTextField(
                        value = relayUrl, onValueChange = { relayUrl = it },
                        label = { Text("Relay 地址（ws://…）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = accountId, onValueChange = { accountId = it },
                        label = { Text("Account ID（配对后自动填）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = cloudUrl, onValueChange = { cloudUrl = it },
                        label = { Text("云端地址（https://…）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cloudWorkspace, onValueChange = { cloudWorkspace = it },
                        label = { Text("工作区路径") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "密钥不在此处填写：电脑中继用扫码配对，云端在连接时填写 API Key。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (name.isNotBlank()) onConfirm(
                                ConnectionProfile(
                                    name = name.trim(), mode = mode,
                                    relayUrl = relayUrl.trim(), accountId = accountId.trim(),
                                    cloudUrl = cloudUrl.trim(), cloudWorkspace = cloudWorkspace.trim()
                                )
                            )
                        },
                        modifier = Modifier.weight(1f),
                        enabled = name.isNotBlank()
                    ) { Text("保存") }
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                }
            }
        }
    }
}

// ============ v2.5: 配置导出 ============

@Composable
fun ConfigExportDialog(json: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("导出配置", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "不含任何密钥。导入到新设备后需重新配对。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                ) {
                    Text(
                        json,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState())
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("opencode-config", json))
                            copied = true
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (copied) "已复制" else "复制") }
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("关闭") }
                }
            }
        }
    }
}

// ============ v2.5: 配置导入 ============

@Composable
fun ConfigImportDialog(
    onImport: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("导入配置", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "粘贴导出的配置 JSON。导入后密钥为空，需重新配对。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = false },
                    label = { Text("配置 JSON") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 300.dp),
                    isError = error
                )
                if (error) {
                    Text("JSON 无效或版本不匹配，未导入。", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (text.isBlank() || !onImport(text.trim())) error = true
                            else onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("导入") }
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                }
            }
        }
    }
}

// ============ v2.5: 本地操作记录 ============

@Composable
fun OpLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val entries = remember { OpLog.read(context) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("本地操作记录", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = { OpLog.clear(context); onDismiss() }) { Text("清空") }
                    }
                }
                Text(
                    "仅本机保存：审批/中断/撤销/配对等，不含对话内容。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (entries.isEmpty()) {
                    Text("暂无记录", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(entries, key = { it.ts }) { e ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(
                                    e.timeLabel(), fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.width(76.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(e.type.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    if (e.detail.isNotBlank()) {
                                        Text(
                                            e.detail, fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                }
                            }
                            Divider()
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
            }
        }
    }
}
