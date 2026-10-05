package com.opencode.android.ui.screens

import com.opencode.android.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.data.model.*
import com.opencode.android.network.CloudConnectionState
import com.opencode.android.network.RelayConnectionState
import com.opencode.android.ui.components.ToolApprovalDialog
import com.opencode.android.ui.components.MarkdownText
import com.opencode.android.ui.components.looksLikeMarkdown
import com.opencode.android.util.MarkdownExporter
import com.opencode.android.util.ErrorCodes
import com.opencode.android.util.TAG_ALL
import com.opencode.android.util.TAG_DEFAULT
import com.opencode.android.util.TAG_KEY_TO_RES
import kotlinx.coroutines.launch

@Composable
fun SessionsManagementModal(
    sessions: List<SessionItem>,
    currentSessionId: String,
    selectedTagFilter: String?,
    availableTags: List<String>,
    onDismiss: () -> Unit,
    onSelectSession: (String) -> Unit,
    onSelectTagFilter: (String?) -> Unit,
    onTogglePin: (String) -> Unit,
    onArchive: (String) -> Unit,
    onBatchArchive: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.chat_038), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                // 标签快速筛选栏
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(availableTags) { tag ->
                        val isSelected = (selectedTagFilter == null && tag == TAG_ALL) || (selectedTagFilter == tag)
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectTagFilter(if (tag == TAG_ALL) null else tag) },
                            label = { Text(tagDisplay(tag), fontSize = 11.sp) },
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                val filteredList = sessions.filter { s ->
                    val matchTag = selectedTagFilter == null || s.tag == selectedTagFilter
                    matchTag && !s.isArchived
                }

                if (filteredList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.chat_040), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredList) { session ->
                            val isCurrent = session.id == currentSessionId
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().clickable { onSelectSession(session.id) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { onTogglePin(session.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (session.isPinned) Icons.Default.PushPin else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = "Pin",
                                            tint = if (session.isPinned) MaterialTheme.colorScheme.primary else Color.Gray,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(session.title, fontSize = 13.sp, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                                        Text(stringResource(R.string.chat_041, tagDisplay(session.tag)), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(
                                        onClick = { onArchive(session.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Archive, contentDescription = "Archive", tint = Color.Gray, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onBatchArchive,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Inventory2, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.chat_042), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_043))
            }
        }
    )
}
