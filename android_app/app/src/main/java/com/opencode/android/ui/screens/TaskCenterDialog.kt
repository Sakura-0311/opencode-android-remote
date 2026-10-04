package com.opencode.android.ui.screens

import com.opencode.android.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opencode.android.data.model.OpenCodeUiState
import com.opencode.android.data.model.SessionItem
import com.opencode.android.data.model.TaskStatus
import com.opencode.android.data.model.ToolApprovalRequest

/**
 * P2-13: 任务中心——集中显示运行中/等待审批/等待输入/已完成/失败任务，
 * 点击任务直接跳转到对应 Session。
 */
@Composable
fun TaskCenterDialog(
    uiState: OpenCodeUiState,
    onSelectSession: (String) -> Unit,
    onCancelTask: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit
) {
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
                        Icon(Icons.Default.TaskAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.task_001), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.task_002))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // ---- 当前任务 ----
                    item {
                        TaskSectionCard(
                            title = stringResource(R.string.task_003),
                            status = uiState.taskStatus,
                            detail = uiState.taskStatusDetail.ifBlank {
                                when (uiState.taskStatus) {
                                    TaskStatus.IDLE -> stringResource(R.string.task_004)
                                    TaskStatus.RUNNING -> stringResource(R.string.task_005)
                                    TaskStatus.WAITING_INPUT -> stringResource(R.string.task_006)
                                    TaskStatus.APPROVAL_REQUIRED -> stringResource(R.string.task_007)
                                    TaskStatus.FAILED -> stringResource(R.string.task_008)
                                    TaskStatus.COMPLETED -> stringResource(R.string.task_009)
                                    TaskStatus.DISCONNECTED -> stringResource(R.string.task_010)
                                }
                            },
                            sessionTitle = uiState.availableSessions
                                .find { it.id == uiState.currentSessionId }?.title,
                            elapsedMs = if (uiState.taskStartTimeMs > 0 &&
                                (uiState.taskStatus == TaskStatus.RUNNING ||
                                        uiState.taskStatus == TaskStatus.WAITING_INPUT ||
                                        uiState.taskStatus == TaskStatus.APPROVAL_REQUIRED)
                            ) {
                                System.currentTimeMillis() - uiState.taskStartTimeMs
                            } else null,
                            onJump = {
                                if (uiState.currentSessionId.isNotBlank()) {
                                    onSelectSession(uiState.currentSessionId)
                                    onDismiss()
                                }
                            },
                            onCancel = {
                                onCancelTask()
                            },
                            showCancel = uiState.taskStatus == TaskStatus.RUNNING ||
                                    uiState.taskStatus == TaskStatus.WAITING_INPUT
                        )
                    }

                    // ---- 等待审批 ----
                    uiState.pendingApproval?.let { approval ->
                        item {
                            ApprovalCard(
                                approval = approval,
                                onApprove = onApprove,
                                onReject = onReject
                            )
                        }
                    }

                    // ---- 会话任务列表 ----
                    item {
                        Text(
                            stringResource(R.string.task_011),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    val sessions = uiState.availableSessions.filter { !it.isArchived }
                    if (sessions.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.task_012),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    } else {
                        items(sessions) { session ->
                            SessionRow(
                                session = session,
                                isCurrent = session.id == uiState.currentSessionId,
                                isTaskSession = session.id == uiState.currentSessionId &&
                                        uiState.taskStatus != TaskStatus.IDLE,
                                taskStatus = if (session.id == uiState.currentSessionId) uiState.taskStatus else null,
                                onClick = {
                                    onSelectSession(session.id)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: TaskStatus): Color {
    return when (status) {
        TaskStatus.RUNNING -> Color(0xFF2196F3)
        TaskStatus.WAITING_INPUT, TaskStatus.APPROVAL_REQUIRED -> Color(0xFFFF9800)
        TaskStatus.FAILED -> MaterialTheme.colorScheme.error
        TaskStatus.COMPLETED -> Color(0xFF4CAF50)
        TaskStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
        TaskStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun TaskSectionCard(
    title: String,
    status: TaskStatus,
    detail: String,
    sessionTitle: String?,
    elapsedMs: Long?,
    onJump: () -> Unit,
    onCancel: () -> Unit,
    showCancel: Boolean
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = statusColor(status).copy(alpha = 0.15f)
                ) {
                    Text(
                        text = stringResource(status.labelRes),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor(status),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(detail, fontSize = 13.sp)
            if (sessionTitle != null) {
                Text(
                    stringResource(R.string.task_013, sessionTitle),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (elapsedMs != null) {
                Text(
                    stringResource(R.string.task_014, formatElapsed(elapsedMs)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (status != TaskStatus.IDLE) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onJump) { Text(stringResource(R.string.task_015)) }
                    if (showCancel) {
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.task_016), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    approval: ToolApprovalRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFFF9800).copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stringResource(R.string.task_017), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE68900))
            Spacer(modifier = Modifier.height(4.dp))
            Text(stringResource(R.string.task_018, approval.toolName), fontSize = 13.sp)
            val approvalSummary = approval.summary ?: approval.filePath
            if (!approvalSummary.isNullOrBlank()) {
                Text(
                    approvalSummary.take(200),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApprove) { Text(stringResource(R.string.task_019)) }
                TextButton(onClick = onReject) {
                    Text(stringResource(R.string.task_020), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionItem,
    isCurrent: Boolean,
    isTaskSession: Boolean,
    taskStatus: TaskStatus?,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.title.ifBlank { stringResource(R.string.task_021) },
                    fontSize = 14.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1
                )
                if (session.tag.isNotBlank()) {
                    Text(
                        session.tag,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isTaskSession && taskStatus != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor(taskStatus).copy(alpha = 0.15f)
                ) {
                    Text(
                        stringResource(taskStatus.labelRes),
                        fontSize = 10.sp,
                        color = statusColor(taskStatus),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            } else if (isCurrent) {
                Text(stringResource(R.string.task_022), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> stringResource(R.string.task_023, s)
        s < 3600 -> stringResource(R.string.task_024, s / 60, s % 60)
        else -> stringResource(R.string.task_025, s / 3600, (s % 3600) / 60)
    }
}
