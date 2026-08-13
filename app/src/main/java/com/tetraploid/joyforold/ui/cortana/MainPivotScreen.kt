package com.tetraploid.joyforold.ui.cortana

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import com.tetraploid.joyforold.agent.RuntimePermissionKind
import com.tetraploid.joyforold.assist.protocol.AssistRole
import com.tetraploid.joyforold.collaboration.AssistSessionPhase
import com.tetraploid.joyforold.overlay.FloatingOverlayService
import com.tetraploid.joyforold.ui.DemoViewModel
import com.tetraploid.joyforold.ui.theme.CortanaColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MainPivotScreen(
    viewModel: DemoViewModel = koinViewModel(),
    darkTheme: Boolean = false,
    onDarkThemeChange: (Boolean) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { MainPivotTabs.size })
    var pendingVoiceAfterPermission by remember { mutableStateOf(false) }
    var pendingVoiceInputAfterPermission by remember { mutableStateOf(false) }
    var pendingEnableWakeWordAfterPermission by remember { mutableStateOf(false) }
    var pendingWakeWordVoiceAfterPermission by remember { mutableStateOf(false) }
    var permissionDialog by remember { mutableStateOf<PermissionDialogRequest?>(null) }
    var showAssistRemoteScreen by remember { mutableStateOf(false) }
    val overlayRunning = remember { mutableStateOf(FloatingOverlayService.isRunning()) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            viewModel.onRecordAudioPermissionResult(granted)
            if (granted) {
                when {
                    pendingEnableWakeWordAfterPermission -> viewModel.setWakeWordEnabled(true)
                    pendingWakeWordVoiceAfterPermission -> viewModel.resumeWakeWordVoiceSession()
                    pendingVoiceAfterPermission -> viewModel.startVoiceReplyToConfirm()
                    pendingVoiceInputAfterPermission -> viewModel.startVoiceInput()
                }
            } else if (
                pendingVoiceAfterPermission ||
                pendingVoiceInputAfterPermission ||
                pendingEnableWakeWordAfterPermission ||
                pendingWakeWordVoiceAfterPermission
            ) {
                viewModel.onVoicePermissionDenied()
            }
            pendingVoiceAfterPermission = false
            pendingVoiceInputAfterPermission = false
            pendingEnableWakeWordAfterPermission = false
            pendingWakeWordVoiceAfterPermission = false
        },
    )

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> viewModel.onReadContactsPermissionResult(granted) },
    )

    LaunchedEffect(uiState.permissionPrompt) {
        val prompt = uiState.permissionPrompt ?: return@LaunchedEffect
        permissionDialog = PermissionDialogRequest(
            kind = when (prompt.kind) {
                RuntimePermissionKind.RecordAudio -> PermissionDialogKind.RecordAudio
                RuntimePermissionKind.ReadContacts -> PermissionDialogKind.ReadContacts
                RuntimePermissionKind.Accessibility -> PermissionDialogKind.Accessibility
            },
            title = prompt.title,
            message = prompt.message,
        )
        pendingEnableWakeWordAfterPermission = prompt.resumeEnableWakeWord
        pendingWakeWordVoiceAfterPermission = prompt.resumeWakeWordVoice
        pendingVoiceInputAfterPermission = prompt.resumeVoiceInputAfterPermission
        viewModel.clearPermissionPrompt()
    }

    DisposableEffect(lifecycleOwner) {
        viewModel.refreshAccessibilityState()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshAccessibilityState()
                overlayRunning.value = FloatingOverlayService.isRunning()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopVoiceInput()
        }
    }

    fun showRecordAudioDialog(message: String, onConfirm: () -> Unit) {
        permissionDialog = PermissionDialogRequest(
            kind = PermissionDialogKind.RecordAudio,
            title = "需要麦克风权限",
            message = message,
        )
        onConfirm()
    }

    fun showAccessibilityDialog() {
        permissionDialog = PermissionDialogRequest(
            kind = PermissionDialogKind.Accessibility,
            title = "需要打开无障碍",
            message = "助手帮您点屏幕、发微信时，需要打开「JoyForOld」无障碍服务。\n\n若要操作微信，请再到「设置 → 组件」里打开「微信支持」。",
        )
    }

    fun handleMicClick() {
        if (uiState.isListening) {
            if (!uiState.accessibilityServiceConnected) {
                showAccessibilityDialog()
                return
            }
            viewModel.stopVoiceInputAndRunAgent()
            return
        }
        if (uiState.recordAudioGranted) {
            if (uiState.waitingForUserConfirm) {
                viewModel.startVoiceReplyToConfirm()
            } else {
                viewModel.startVoiceInput()
            }
        } else {
            showRecordAudioDialog(
                if (uiState.waitingForUserConfirm) {
                    "要用语音回答，需要允许使用麦克风。"
                } else {
                    "要用语音下指令，需要允许使用麦克风。"
                },
            ) {
                pendingVoiceInputAfterPermission = true
                if (uiState.waitingForUserConfirm) {
                    pendingVoiceAfterPermission = true
                }
            }
        }
    }

    fun handleSendClick() {
        if (uiState.isListening) {
            if (!uiState.accessibilityServiceConnected) {
                showAccessibilityDialog()
                return
            }
            viewModel.stopVoiceInputAndRunAgent()
        } else if (uiState.command.isNotBlank()) {
            if (!uiState.accessibilityServiceConnected) {
                showAccessibilityDialog()
                return
            }
            viewModel.runAgent()
        }
    }

    fun handleWakeWordToggle(enabled: Boolean) {
        if (enabled && !uiState.recordAudioGranted) {
            showRecordAudioDialog("要用语音唤醒，需要允许使用麦克风。") {
                pendingEnableWakeWordAfterPermission = true
            }
            return
        }
        viewModel.setWakeWordEnabled(enabled)
    }

    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 2) {
            viewModel.refreshAssistConfig()
        }
    }

    LaunchedEffect(uiState.assistRole, uiState.assistPhase) {
        when {
            uiState.assistRole == AssistRole.CAREGIVER &&
                uiState.assistPhase == AssistSessionPhase.ACTIVE -> showAssistRemoteScreen = true
            uiState.assistPhase != AssistSessionPhase.ACTIVE -> showAssistRemoteScreen = false
        }
    }

    LaunchedEffect(uiState.assistNavigateTick) {
        if (uiState.assistNavigateTick > 0L) {
            pagerState.animateScrollToPage(2)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            viewModel.refreshAccessibilityState()
        }
    }

    LaunchedEffect(uiState.isListening, uiState.isRunning, uiState.waitingForUserConfirm) {
        if (uiState.isListening || uiState.isRunning || uiState.waitingForUserConfirm) {
            pagerState.animateScrollToPage(0)
        }
    }

    PermissionPromptDialog(
        request = permissionDialog,
        onConfirm = { kind ->
            when (kind) {
                PermissionDialogKind.RecordAudio -> {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                PermissionDialogKind.ReadContacts -> {
                    contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                }
                PermissionDialogKind.Accessibility -> {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            permissionDialog = null
        },
        onDismiss = {
            pendingVoiceAfterPermission = false
            pendingVoiceInputAfterPermission = false
            pendingEnableWakeWordAfterPermission = false
            pendingWakeWordVoiceAfterPermission = false
            permissionDialog = null
        },
    )

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(CortanaColors.Background),
        containerColor = CortanaColors.Background,
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PivotHeader(
                tabs = MainPivotTabs,
                pagerState = pagerState,
                scope = scope,
                modifier = Modifier.statusBarsPadding(),
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { page ->
                when (page) {
                    0 -> CortanaHomePage(
                        uiState = uiState,
                        onSuggestionClick = { suggestion ->
                            viewModel.submitCommand(suggestion)
                        },
                        onCommandChange = viewModel::updateCommand,
                        onMicClick = { handleMicClick() },
                        onPauseAgent = viewModel::pauseAgent,
                        onResumeAgent = viewModel::resumeAgent,
                        onCancelAgent = viewModel::cancelAgent,
                        onClearConfirm = viewModel::clearPendingConfirmUI,
                        onBinaryConfirm = { viewModel.submitBinaryConfirm(true) },
                        onBinaryCancel = { viewModel.submitBinaryConfirm(false) },
                        onDisambiguationSelect = viewModel::selectDisambiguationOption,
                        onUndo = viewModel::undoLastLocalAction,
                        onDismissUndo = viewModel::dismissUndoOffer,
                        onSendClick = { handleSendClick() },
                        onCancelClick = {
                            if (uiState.isRunning) {
                                viewModel.cancelAgent()
                            } else {
                                viewModel.clearInteraction()
                            }
                        },
                    )
                    1 -> SettingsPage(
                        uiState = uiState,
                        darkTheme = darkTheme,
                        onDarkThemeChange = onDarkThemeChange,
                        overlayRunning = overlayRunning.value,
                        onRequestAudioPermission = {
                            showRecordAudioDialog("语音识别和语音唤醒需要麦克风。") {}
                        },
                        onRequestContactsPermission = {
                            permissionDialog = PermissionDialogRequest(
                                kind = PermissionDialogKind.ReadContacts,
                                title = "需要读取联系人",
                                message = "允许后，助手才能按姓名帮您打电话或发消息。",
                            )
                        },
                        onToggleOverlay = { running -> overlayRunning.value = running },
                        onUpdateWakeWordPhrase = viewModel::updateWakeWordPhrase,
                        onApplyWakeWordPreset = viewModel::applyWakeWordPreset,
                        onSetWakeWordEnabled = { enabled -> handleWakeWordToggle(enabled) },
                        onSaveWakeWordConfig = viewModel::saveWakeWordConfig,
                        onSetCloudContextConsent = { granted ->
                            viewModel.setCloudContextConsent(granted)
                        },
                        onSetVoiceBargeIn = { enabled ->
                            viewModel.setVoiceBargeInEnabled(enabled)
                        },
                        onTestWakeWord = viewModel::testWakeWord,
                        onStartCalibration = viewModel::startWakeWordCalibration,
                        onRecordCalibrationStep = viewModel::recordCalibrationStep,
                    )
                    2 -> CollaborationPage(
                        uiState = uiState,
                        onSetAssistRole = viewModel::setAssistRole,
                        onSetAssistDisplayName = viewModel::setAssistDisplayName,
                        onSetAssistServerHttpUrl = viewModel::setAssistServerHttpUrl,
                        onSetAssistServerWsUrl = viewModel::setAssistServerWsUrl,
                        onStartElderAssist = viewModel::startElderAssistSession,
                        onJoinAssist = viewModel::joinAssistSession,
                        onConnectBinding = viewModel::connectAssistBinding,
                        onDeleteBinding = viewModel::deleteAssistBinding,
                        onOpenRemoteScreen = { showAssistRemoteScreen = true },
                        onSendAssistAction = viewModel::sendAssistAction,
                        onSendAssistTypeText = viewModel::sendAssistTypeText,
                        onSendAssistCommand = viewModel::sendAssistCommand,
                        onEndAssist = viewModel::endAssistSession,
                        onUpdateDaughterPhone = viewModel::updateDaughterPhone,
                        onUpdateSonPhone = viewModel::updateSonPhone,
                        onUpdateEmergencyPhone = viewModel::updateEmergencyPhone,
                        onUpdateEmergencyMessage = viewModel::updateEmergencyMessage,
                        onUpdateHomeAddress = viewModel::updateHomeAddress,
                        onUpdatePresetPhraseGoHome = viewModel::updatePresetPhraseGoHome,
                        onSave = viewModel::saveCaregiverSettings,
                    )
                    3 -> AboutPage(uiState = uiState)
                }
            }
        }
    }

        if (showAssistRemoteScreen &&
            uiState.assistRole == AssistRole.CAREGIVER &&
            uiState.assistPhase == AssistSessionPhase.ACTIVE
        ) {
            AssistRemoteScreenPage(
                uiState = uiState,
                onBack = { showAssistRemoteScreen = false },
                onSendAssistTap = viewModel::sendAssistTap,
                onSendAssistSwipe = viewModel::sendAssistSwipe,
                onSendAssistAction = viewModel::sendAssistAction,
                onEndAssist = {
                    showAssistRemoteScreen = false
                    viewModel.endAssistSession()
                },
            )
        }
    }
}
