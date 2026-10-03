package com.opencode.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.opencode.android.ui.screens.ChatScreen
import com.opencode.android.ui.screens.PairingScreen
import com.opencode.android.ui.theme.OpenCodeTheme
import com.opencode.android.viewmodel.OpenCodeViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: OpenCodeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // P1-5: 运行时申请通知权限 (Android 13+ / API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        setContent {
            OpenCodeTheme {
                val uiState by viewModel.uiState.collectAsState()

                if (!uiState.isPaired) {
                    PairingScreen(
                        currentMode = uiState.appMode,
                        initialAccountId = uiState.accountId,
                        initialSecret = uiState.secret,
                        initialRelayUrl = uiState.relayUrl,
                        initialCloudUrl = uiState.cloudServerUrl,
                        initialCloudKey = uiState.cloudApiKey,
                        initialCloudWorkspace = uiState.cloudWorkspacePath,
                        appError = uiState.appError,
                        diagnostics = uiState.diagnostics,
                        statusBanner = uiState.statusBanner,
                        onSwitchMode = { mode ->
                            viewModel.switchMode(mode)
                        },
                        onTestConnectivity = {
                            viewModel.testConnectivity()
                        },
                        onConnectDesktop = { accountId, secret, relayUrl ->
                            viewModel.pairDesktop(accountId, secret, relayUrl)
                        },
                        onConnectCloud = { cloudUrl, apiKey, workspace ->
                            viewModel.pairCloud(cloudUrl, apiKey, workspace)
                        }
                    )
                } else {
                    ChatScreen(
                        uiState = uiState,
                        onSendMessage = { prompt ->
                            viewModel.sendMessage(prompt)
                        },
                        onCancel = {
                            viewModel.cancelExecution()
                        },
                        onClearChat = {
                            viewModel.clearChat()
                        },
                        onDisconnect = {
                            viewModel.unpair()
                        },
                        onDismissError = {
                            viewModel.dismissError()
                        },
                        onSelectTagFilter = { tag ->
                            viewModel.setTagFilter(tag)
                        },
                        onSwitchSession = { id ->
                            viewModel.switchSession(id)
                        },
                        onTogglePinSession = { id ->
                            viewModel.togglePinSession(id)
                        },
                        onArchiveSession = { id ->
                            viewModel.archiveSession(id)
                        },
                        onBatchArchive = {
                            viewModel.batchArchiveOldSessions()
                        },
                        onApproveTool = { callId ->
                            viewModel.approveTool(callId)
                        },
                        onRejectTool = { callId ->
                            viewModel.rejectTool(callId)
                        },
                        onTriggerTestApproval = {
                            viewModel.triggerMockToolApprovalForTest()
                        },
                        onSearchLog = { query ->
                            viewModel.setLogSearchQuery(query)
                        },
                        onSetAutoScrollPaused = { isPaused ->
                            viewModel.setAutoScrollPaused(isPaused)
                        },
                        onExportMarkdown = {
                            viewModel.exportCurrentSession()
                        }
                    )
                }
            }
        }
    }
}
