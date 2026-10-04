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
import androidx.compose.ui.res.stringResource
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
                Text(stringResource(R.string.v25_001), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.v25_002),
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
                                Text(p.name.ifBlank { stringResource(R.string.profile_unnamed) }, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                                Text(
                                    if (p.mode == AppMode.DESKTOP_RELAY) stringResource(R.string.v25_003, p.relayUrl.ifBlank { stringResource(R.string.diag_n01) })
                                    else stringResource(R.string.v25_004, p.cloudUrl.ifBlank { stringResource(R.string.diag_n01) }),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            }
                            IconButton(onClick = { renameTarget = p }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.v25_005), modifier = Modifier.size(18.dp))
                            }
                            if (profiles.size > 1) {
                                IconButton(onClick = { onDelete(p.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.v25_006), modifier = Modifier.size(18.dp))
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
                        Text(stringResource(R.string.v25_007))
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.v25_008)) }
                }
            }
        }
    }
    if (showAdd) {
        ProfileEditDialog(
            title = stringResource(R.string.v25_009),
            onConfirm = { onAdd(it); showAdd = false },
            onDismiss = { showAdd = false }
        )
    }
    renameTarget?.let { target ->
        var name by remember { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.v25_005)) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.v25_010)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) onRename(target.id, name.trim())
                    renameTarget = null
                }) { Text(stringResource(R.string.v25_011)) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.v25_012)) } }
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
                    label = { Text(stringResource(R.string.v25_013)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = mode == AppMode.DESKTOP_RELAY,
                        onClick = { mode = AppMode.DESKTOP_RELAY }
                    )
                    Text(stringResource(R.string.v25_014), modifier = Modifier.clickable { mode = AppMode.DESKTOP_RELAY })
                    Spacer(modifier = Modifier.width(16.dp))
                    RadioButton(
                        selected = mode == AppMode.CLOUD_HOSTED,
                        onClick = { mode = AppMode.CLOUD_HOSTED }
                    )
                    Text(stringResource(R.string.v25_015), modifier = Modifier.clickable { mode = AppMode.CLOUD_HOSTED })
                }
                if (mode == AppMode.DESKTOP_RELAY) {
                    OutlinedTextField(
                        value = relayUrl, onValueChange = { relayUrl = it },
                        label = { Text(stringResource(R.string.v25_016)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = accountId, onValueChange = { accountId = it },
                        label = { Text(stringResource(R.string.v25_017)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = cloudUrl, onValueChange = { cloudUrl = it },
                        label = { Text(stringResource(R.string.v25_018)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cloudWorkspace, onValueChange = { cloudWorkspace = it },
                        label = { Text(stringResource(R.string.v25_019)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.v25_020),
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
                    ) { Text(stringResource(R.string.v25_021)) }
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.v25_012)) }
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
                Text(stringResource(R.string.v25_022), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.v25_023),
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
                    ) { Text(if (copied) stringResource(R.string.v25_024) else stringResource(R.string.v25_025)) }
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.v25_008)) }
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
                Text(stringResource(R.string.v25_026), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.v25_027),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it; error = false },
                    label = { Text(stringResource(R.string.v25_028)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 300.dp),
                    isError = error
                )
                if (error) {
                    Text(stringResource(R.string.v25_029), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (text.isBlank() || !onImport(text.trim())) error = true
                            else onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.v25_030)) }
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.v25_012)) }
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
                    Text(stringResource(R.string.v25_031), fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = { OpLog.clear(context); onDismiss() }) { Text(stringResource(R.string.v25_032)) }
                    }
                }
                Text(
                    stringResource(R.string.v25_033),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (entries.isEmpty()) {
                    Text(stringResource(R.string.v25_034), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
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
                                    Text(stringResource(e.type.labelRes), fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.v25_008)) }
            }
        }
    }
}
