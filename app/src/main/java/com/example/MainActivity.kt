package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.CompilerScreen
import com.example.ui.CompilerViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val viewModel: CompilerViewModel = viewModel()
                val session by viewModel.currentSession.collectAsState()
                val isAuthLoading by viewModel.isAuthLoading.collectAsState()
                val records by viewModel.allBuildRecords.collectAsState()
                val selectedRecordId by viewModel.selectedRecordId.collectAsState()
                val activeTab by viewModel.activeTab.collectAsState()
                val githubToken by viewModel.githubToken.collectAsState()
                val supabaseUrl by viewModel.supabaseUrl.collectAsState()
                val supabaseKey by viewModel.supabaseKey.collectAsState()
                val showTokenSheet by viewModel.showTokenSheet.collectAsState()
                val toastMessage by viewModel.statusToastMessage.collectAsState()

                val selectedRecord = records.firstOrNull { it.id == selectedRecordId }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ -> }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                // Selecionador de arquivo ZIP do sistema
                val pickZipLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        viewModel.onZipUriSelected(uri)
                    }
                }

                // Criador de documento para salvar o APK Release compilado no dispositivo
                val createApkDocumentLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
                ) { destinationUri ->
                    if (destinationUri != null) {
                        viewModel.exportPendingApkToUri(destinationUri)
                    }
                }

                BackHandler(enabled = session != null && (selectedRecord != null || activeTab != 0)) {
                    if (selectedRecord != null) {
                        viewModel.closeBuildLogs()
                    } else {
                        viewModel.selectTab(0)
                    }
                }

                CompilerScreen(
                    session = session,
                    isAuthLoading = isAuthLoading,
                    records = records,
                    selectedRecord = selectedRecord,
                    activeTab = activeTab,
                    githubToken = githubToken,
                    supabaseUrl = supabaseUrl,
                    supabaseKey = supabaseKey,
                    hasValidToken = viewModel.hasValidGitHubToken,
                    showTokenSheet = showTokenSheet,
                    toastMessage = toastMessage,
                    onSignIn = { email, password ->
                        viewModel.signIn(email, password)
                    },
                    onSignUp = { name, email, password ->
                        viewModel.signUp(name, email, password)
                    },
                    onRecoverPassword = { email, newPassword ->
                        viewModel.recoverPassword(email, newPassword)
                    },
                    onSignOut = {
                        viewModel.signOut()
                    },
                    onSelectTab = { tab ->
                        viewModel.selectTab(tab)
                    },
                    onPickZipClick = {
                        pickZipLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    onOpenLogsClick = { recordId ->
                        viewModel.openBuildLogs(recordId)
                    },
                    onCloseLogsClick = {
                        viewModel.closeBuildLogs()
                    },
                    onRecompileClick = { record ->
                        viewModel.recompileExistingProject(record)
                    },
                    onDownloadApkClick = { record ->
                        viewModel.prepareApkExport(record) { suggestedName ->
                            createApkDocumentLauncher.launch(suggestedName)
                        }
                    },
                    onShareApkClick = { record ->
                        viewModel.shareOrInstallApk(this@MainActivity, record)
                    },
                    onDeleteRecordClick = { record ->
                        viewModel.deleteBuildRecord(record)
                    },
                    onToggleTokenSheet = { show ->
                        viewModel.toggleTokenSheet(show)
                    },
                    onSaveCredentials = { token, sbUrl, sbKey ->
                        viewModel.saveCredentialsConfig(token, sbUrl, sbKey)
                    },
                    onClearToast = {
                        viewModel.clearStatusToast()
                    }
                )
            }
        }
    }
}
