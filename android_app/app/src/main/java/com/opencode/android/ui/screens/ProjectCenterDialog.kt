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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.opencode.android.network.ProjectInfo

/**
 * v1.6 P1 项目管理中心
 * 设备 → 项目 → 会话三级结构；显示路径、Git 分支；支持收藏。
 */
@Composable
fun ProjectCenterDialog(
    projects: List<ProjectInfo>,
    favoriteIds: Set<String>,
    deviceName: String,
    // N-6: 拉取失败时的错误信息（非空则显示错误而非空白列表）
    projectsError: String? = null,
    onRefresh: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onOpenProject: (ProjectInfo) -> Unit,
    onDismiss: () -> Unit
) {
    LaunchedEffect(Unit) { onRefresh() }

    // 收藏优先排序
    val sorted = remember(projects, favoriteIds) {
        projects.sortedWith(
            compareByDescending<ProjectInfo> { favoriteIds.contains(it.id) }
                .thenByDescending { it.isCurrent }
                .thenBy { it.name }
        )
    }

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
                    Column {
                        Text(stringResource(R.string.proj_001), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            deviceName.ifBlank { stringResource(R.string.proj_002) },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Row {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.proj_003))
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.proj_004))
                        }
                    }
                }

                Text(
                    stringResource(R.string.proj_005, projects.size),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (projectsError != null) {
                    // N-6: 取不到时显示明确错误 + 重试，不显示空白列表
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(R.string.proj_006, projectsError),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = onRefresh) {
                                Text(stringResource(R.string.proj_007))
                            }
                        }
                    }
                } else if (projects.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.proj_008),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sorted) { project ->
                            ProjectRow(
                                project = project,
                                isFavorite = favoriteIds.contains(project.id),
                                onToggleFavorite = { onToggleFavorite(project.id) },
                                onOpen = { onOpenProject(project) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(
    project: ProjectInfo,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(project.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    if (project.isCurrent) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                stringResource(R.string.proj_009),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                if (project.path.isNotBlank()) {
                    Text(
                        project.path,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 1
                    )
                }
                if (project.branch.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AccountTree,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = Color(0xFF4CAF50)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            project.branch,
                            fontSize = 11.sp,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = stringResource(R.string.proj_010),
                    tint = if (isFavorite) Color(0xFFFFC107)
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.proj_011))
        }
    }
}
