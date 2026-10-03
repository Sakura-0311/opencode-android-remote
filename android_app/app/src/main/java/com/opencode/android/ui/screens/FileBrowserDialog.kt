package com.opencode.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opencode.android.network.FileEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P2-12: 文件浏览器对话框——浏览电脑端文件树、预览文本文件。
 */
@Composable
fun FileBrowserDialog(
    currentPath: String,
    entries: List<FileEntry>,
    loading: Boolean,
    previewPath: String,
    previewContent: String,
    previewTruncated: Boolean,
    onNavigate: (String) -> Unit,
    onPreview: (String) -> Unit,
    onClosePreview: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
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
                // 标题栏
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (previewPath.isNotEmpty()) "文件预览" else "文件浏览器",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row {
                        if (previewPath.isEmpty()) {
                            IconButton(onClick = onRefresh) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新")
                            }
                        }
                        IconButton(onClick = { if (previewPath.isNotEmpty()) onClosePreview() else onDismiss() }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                }

                if (previewPath.isNotEmpty()) {
                    // ---- 文件预览 ----
                    Text(
                        text = previewPath,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    )
                    if (previewTruncated) {
                        Text(
                            text = "文件过大，仅显示前 200KB",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    } else {
                        Text(
                            text = previewContent.ifBlank { "(空文件)" },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                        )
                    }
                    TextButton(onClick = onClosePreview, modifier = Modifier.align(Alignment.End)) {
                        Text("返回目录")
                    }
                } else {
                    // ---- 目录列表 ----
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = currentPath.ifBlank { "…" },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    } else if (entries.isEmpty()) {
                        Text(
                            text = "空目录",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            // 上级目录
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val parent = parentOf(currentPath)
                                            if (parent != null) onNavigate(parent)
                                        }
                                        .padding(vertical = 10.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ArrowUpward,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("..（上级目录）", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            items(entries) { entry ->
                                val fullPath = joinPath(currentPath, entry.name)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (entry.isDir) onNavigate(fullPath) else onPreview(fullPath)
                                        }
                                        .padding(vertical = 10.dp)
                                ) {
                                    Icon(
                                        imageVector = if (entry.isDir) Icons.Default.Folder else Icons.Default.Description,
                                        contentDescription = null,
                                        tint = if (entry.isDir) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.name, fontSize = 14.sp, maxLines = 1)
                                        if (!entry.isDir) {
                                            Text(
                                                text = "${formatSize(entry.size)} · ${formatTime(entry.mtime)}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parentOf(path: String): String? {
    val p = path.trimEnd('/', '\\')
    val idx = maxOf(p.lastIndexOf('/'), p.lastIndexOf('\\'))
    if (idx <= 0) return null
    return p.substring(0, idx).ifBlank { "/" }
}

private fun joinPath(dir: String, name: String): String {
    val d = dir.trimEnd('/', '\\')
    return "$d/$name"
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    }
}

private fun formatTime(mtime: Long): String {
    if (mtime <= 0) return ""
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(mtime * 1000))
}
