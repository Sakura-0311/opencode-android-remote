package com.opencode.android.ui.components

import com.opencode.android.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import com.opencode.android.data.model.ToolApprovalRequest

@Composable
fun ToolApprovalDialog(
    request: ToolApprovalRequest,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit
) {
    // v5.0.3 (B-3): 按 agent 下发的 expires_at 倒计时，过期后禁用按钮。
    // 此前 expiresAt 全工程无人读取：过期后按钮仍可点，用户点了批准，
    // agent 按「nonce 失效」丢弃，任务永远卡在等审批且毫无提示。
    var nowMs by remember(request.callId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(request.callId, request.expiresAt) {
        val expiresAt = request.expiresAt
        if (expiresAt == null || expiresAt <= 0L) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            if (nowMs >= expiresAt) break
            delay(1000L)
        }
    }
    val secondsLeft = request.expiresAt
        ?.takeIf { it > 0L }
        ?.let { ((it - nowMs) / 1000L).coerceAtLeast(0L) }
    val expired = secondsLeft != null && secondsLeft <= 0L

    AlertDialog(
        onDismissRequest = { /* 强制用户明确选择同意或拒绝，避免误触外部关闭 */ },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Gavel,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = stringResource(R.string.approval_001),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.approval_002, request.toolName),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
            ) {
                // 文件路径与摘要说明
                if (!request.filePath.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = request.filePath,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (!request.summary.isNullOrBlank()) {
                    Text(
                        text = request.summary,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                // v5.0.3 (B-3): 剩余时间 / 已过期提示
                if (expired) {
                    Text(
                        text = stringResource(R.string.approval_005),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                } else if (secondsLeft != null) {
                    Text(
                        text = stringResource(R.string.approval_006, secondsLeft),
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                // 专为移动端设计的精简代码 Diff 预览
                CompactDiffView(
                    diffLines = request.diffLines,
                    rawContent = request.rawContent
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onApprove(request.callId) },
                enabled = !expired,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(44.dp)
            ) {
                Text(stringResource(R.string.approval_003), fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { onReject(request.callId) },
                enabled = !expired,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.height(44.dp)
            ) {
                Text(stringResource(R.string.approval_004), fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
            }
        }
    )
}
