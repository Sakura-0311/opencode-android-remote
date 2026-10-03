package com.opencode.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.opencode.android.network.AgentInfo
import com.opencode.android.network.ModelInfo

/**
 * v1.6 P1 Model/Agent 管理：选择对话框
 * Model 列表从 OpenCode 实际配置动态获取，不硬编码。
 */
@Composable
fun ModelAgentDialog(
    agents: List<AgentInfo>,
    models: List<ModelInfo>,
    selectedAgent: AgentInfo?,
    selectedModel: ModelInfo?,
    // N-6: 拉取失败时的错误信息（非空则显示错误而非空白列表）
    configError: String? = null,
    onRefresh: () -> Unit,
    onSelectAgent: (AgentInfo?) -> Unit,
    onSelectModel: (ModelInfo?) -> Unit,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableStateOf(0) }

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
                    Text("Model / Agent", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                }

                // 当前配置摘要
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "当前：${selectedAgent?.name ?: "默认 Agent"} · ${selectedModel?.displayName ?: "默认模型"}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (selectedModel != null) {
                            Text(
                                "${selectedModel.providerId} / ${selectedModel.modelId}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Agent (${agents.size})") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Model (${models.size})") })
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (tab == 0) {
                    // 默认选项
                    ListItem(
                        headlineContent = { Text("默认 Agent") },
                        supportingContent = { Text("使用会话默认配置", fontSize = 12.sp) },
                        leadingContent = {
                            RadioButton(
                                selected = selectedAgent == null,
                                onClick = { onSelectAgent(null) }
                            )
                        },
                        modifier = Modifier.clickable { onSelectAgent(null) }
                    )
                    LazyColumn {
                        items(agents) { agent ->
                            ListItem(
                                headlineContent = { Text(agent.name, fontWeight = FontWeight.Medium) },
                                supportingContent = {
                                    if (agent.description.isNotBlank())
                                        Text(agent.description, fontSize = 12.sp, maxLines = 2)
                                },
                                leadingContent = {
                                    RadioButton(
                                        selected = selectedAgent?.id == agent.id,
                                        onClick = { onSelectAgent(agent) }
                                    )
                                },
                                modifier = Modifier.clickable { onSelectAgent(agent) }
                            )
                        }
                    }
                } else {
                    // 默认选项
                    ListItem(
                        headlineContent = { Text("默认模型") },
                        supportingContent = { Text("使用会话默认配置", fontSize = 12.sp) },
                        leadingContent = {
                            RadioButton(
                                selected = selectedModel == null,
                                onClick = { onSelectModel(null) }
                            )
                        },
                        modifier = Modifier.clickable { onSelectModel(null) }
                    )
                    LazyColumn {
                        items(models) { model ->
                            ListItem(
                                headlineContent = { Text(model.displayName, fontWeight = FontWeight.Medium) },
                                supportingContent = {
                                    Text(
                                        "${model.providerId} / ${model.modelId}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                },
                                leadingContent = {
                                    RadioButton(
                                        selected = selectedModel?.providerId == model.providerId
                                                && selectedModel?.modelId == model.modelId,
                                        onClick = { onSelectModel(model) }
                                    )
                                },
                                modifier = Modifier.clickable { onSelectModel(model) }
                            )
                        }
                    }
                }

                if (configError != null) {
                    // N-6: 取不到时显示明确错误 + 重试，不显示空白列表
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "拉取失败：$configError",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = onRefresh) {
                                Text("重试")
                            }
                        }
                    }
                } else if ((tab == 0 && agents.isEmpty()) || (tab == 1 && models.isEmpty())) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "暂无数据，点击右上角刷新",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
