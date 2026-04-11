package ai.gizmo.app.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.gizmo.app.R
import ai.gizmo.app.model.ChatViewModel
import ai.gizmo.app.placeholder.PlaceholderScreen
import ai.gizmo.app.settings.SettingsScreen
import ai.gizmo.app.model.Voice
import ai.gizmo.app.ui.components.BottomNav
import ai.gizmo.app.ui.components.ConnectionIndicator
import ai.gizmo.app.ui.theme.BgPrimary
import ai.gizmo.app.ui.theme.TextDim
import ai.gizmo.app.ui.theme.TextPrimary
import ai.gizmo.app.memory.MemoryManagerScreen
import ai.gizmo.app.mode.ModeEditorScreen
import ai.gizmo.app.voice.VoiceStudioScreen
import ai.gizmo.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onSwitchServer: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showVoiceStudio by remember { mutableStateOf(false) }
    var showModeEditor by remember { mutableStateOf(false) }
    var showMemoryManager by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var editText by remember { mutableStateOf("") }
    val voices = remember { mutableStateListOf<Voice>() }

    // Load persisted settings
    LaunchedEffect(Unit) {
        viewModel.loadSettings(context)
        voices.addAll(viewModel.api.getVoices())
    }

    // Unified snackbar: observe viewModel.snackbarMessage
    LaunchedEffect(viewModel.snackbarMessage.value) {
        viewModel.snackbarMessage.value?.let { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
            viewModel.clearSnackbar()
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.handleImagePick(it, context.contentResolver) } }

    val docPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.handleDocumentPick(it, context.contentResolver) } }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.handleVideoPick(it, context.contentResolver) } }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.handleAudioPick(it, context.contentResolver) } }

    val activeTitle = viewModel.conversations
        .find { it.id == viewModel.activeConversationId.value }?.title

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                conversations = viewModel.conversations,
                activeConversationId = viewModel.activeConversationId.value,
                searchResults = viewModel.searchResults,
                isSearching = viewModel.isSearching.value,
                onNewChat = {
                    viewModel.newChat()
                    scope.launch { drawerState.close() }
                },
                onConversationClick = { conv ->
                    viewModel.loadConversation(conv.id)
                    scope.launch { drawerState.close() }
                },
                onDeleteConversation = { conv ->
                    viewModel.softDeleteConversation(conv.id)
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = context.getString(R.string.conversation_deleted),
                            actionLabel = context.getString(R.string.undo),
                            duration = SnackbarDuration.Short
                        )
                        when (result) {
                            SnackbarResult.ActionPerformed -> viewModel.undoDeleteConversation(conv)
                            SnackbarResult.Dismissed -> viewModel.confirmDeleteConversation(conv.id)
                        }
                    }
                },
                onRenameConversation = { conv, title ->
                    viewModel.renameConversation(conv.id, title)
                },
                onExportConversation = { conv ->
                    viewModel.exportConversation(conv.id, context.contentResolver)
                },
                onSearch = { viewModel.searchConversations(it) },
                onSettingsClick = {
                    showSettings = true
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.app_name),
                                color = TextPrimary,
                                fontSize = 18.sp
                            )
                            if (activeTitle != null) {
                                Text(text = " \u2014 ", color = TextDim, fontSize = 18.sp)
                                Text(
                                    text = activeTitle,
                                    color = TextSecondary,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            ConnectionIndicator(state = viewModel.connectionState.value)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = TextPrimary)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title), tint = TextSecondary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = BgPrimary)
                )
            },
            bottomBar = { BottomNav(selectedTab = selectedTab, onTabSelected = { selectedTab = it }) },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = BgPrimary
        ) { padding ->
            when (selectedTab) {
                0 -> {
                    Column(modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .imePadding()) {

                        val hasMessages = viewModel.messages.isNotEmpty() || viewModel.generating.value

                        if (hasMessages) {
                            MessageList(
                                messages = viewModel.messages,
                                streamingContent = viewModel.streamingContent.value,
                                streamingThinking = viewModel.streamingThinking.value,
                                streamingToolCalls = viewModel.streamingToolCalls,
                                generating = viewModel.generating.value,
                                serverUrl = viewModel.serverUrl,
                                selectedIndex = viewModel.selectedMessageIndex.value,
                                editingIndex = viewModel.editingMessageIndex.value,
                                onSelectMessage = { viewModel.selectedMessageIndex.value = it },
                                onEditMessage = { idx, newText ->
                                    viewModel.editMessage(idx, newText)
                                },
                                onStartEdit = { idx ->
                                    editText = viewModel.messages[idx].content
                                    viewModel.editingMessageIndex.value = idx
                                    viewModel.selectedMessageIndex.value = null
                                },
                                onCancelEdit = { viewModel.editingMessageIndex.value = null },
                                onCopyMessage = { text -> copyToClipboard(context, text) },
                                onRegenerate = { viewModel.regenerateLastResponse() },
                                onVariantSwitch = { idx, dir -> viewModel.switchVariant(idx, dir) },
                                onDownload = { url -> viewModel.downloadMediaFile(url, context.contentResolver) },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            EmptyState(
                                onSuggestionClick = { inputText = it },
                                onPickAudio = { audioPickerLauncher.launch("audio/*") },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        ChatInput(
                            generating = viewModel.generating.value,
                            thinkingEnabled = viewModel.thinkingEnabled.value,
                            onThinkingToggle = { viewModel.thinkingEnabled.value = it },
                            selectedMode = viewModel.selectedMode.value,
                            modes = viewModel.modes,
                            onModeSelected = { viewModel.selectedMode.value = it },
                            text = inputText,
                            onTextChange = { inputText = it },
                            pendingImageUri = viewModel.pendingImageUri.value,
                            pendingDocumentName = viewModel.pendingDocumentName.value,
                            pendingVideoUri = viewModel.pendingVideoUri.value,
                            pendingAudioName = viewModel.pendingAudioName.value,
                            onClearAttachment = { viewModel.clearAttachment() },
                            onPickImage = { imagePickerLauncher.launch("image/*") },
                            onPickDocument = { docPickerLauncher.launch("*/*") },
                            onPickVideo = { videoPickerLauncher.launch("video/*") },
                            onPickAudio = { audioPickerLauncher.launch("audio/*") },
                            isRecording = viewModel.isRecording.value,
                            onMicTap = {
                                if (viewModel.isRecording.value) {
                                    viewModel.stopRecording(context.contentResolver)
                                } else {
                                    viewModel.startRecording(context)
                                }
                            },
                            onMicLongPress = { viewModel.cancelRecording() },
                            onSend = { text ->
                                viewModel.sendMessage(text)
                                inputText = ""
                            },
                            onStop = { viewModel.stopGeneration() }
                        )
                    }
                }
                else -> PlaceholderScreen(
                    tabName = when (selectedTab) { 1 -> "Tasks"; 2 -> "Code"; 3 -> "Stats"; else -> "" },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }

    if (showSettings) {
        SettingsScreen(
            thinkingEnabled = viewModel.thinkingEnabled.value,
            onThinkingChanged = { viewModel.thinkingEnabled.value = it; viewModel.saveSettings(context) },
            contextLength = viewModel.contextLength.value,
            onContextLengthChanged = { viewModel.contextLength.value = it },
            modes = viewModel.modes,
            selectedMode = viewModel.selectedMode.value,
            onModeSelected = { viewModel.selectedMode.value = it; viewModel.saveSettings(context) },
            serviceHealth = viewModel.serviceHealth,
            serverName = viewModel.serverName,
            serverUrl = viewModel.serverUrl,
            onRefreshHealth = { viewModel.loadServiceHealth() },
            onSwitchServer = onSwitchServer,
            onOpenVoiceStudio = { showSettings = false; showVoiceStudio = true },
            onOpenModeEditor = { showSettings = false; showModeEditor = true },
            onOpenMemoryManager = { showSettings = false; showMemoryManager = true },
            ttsEnabled = viewModel.ttsEnabled.value,
            onTtsChanged = { viewModel.ttsEnabled.value = it; viewModel.saveSettings(context) },
            ttsSpeed = viewModel.ttsSpeed.value,
            onTtsSpeedChanged = { viewModel.ttsSpeed.value = it; viewModel.saveSettings(context) },
            ttsLanguage = viewModel.ttsLanguage.value,
            onTtsLanguageChanged = { viewModel.ttsLanguage.value = it; viewModel.saveSettings(context) },
            voices = voices,
            selectedVoiceId = viewModel.ttsVoiceId.value,
            onVoiceSelected = { viewModel.ttsVoiceId.value = it; viewModel.saveSettings(context) },
            onDismiss = { showSettings = false }
        )
    }

    if (showVoiceStudio) {
        VoiceStudioScreen(
            api = viewModel.api,
            selectedVoiceId = viewModel.ttsVoiceId.value,
            onSelectVoice = { viewModel.ttsVoiceId.value = it; viewModel.saveSettings(context) },
            onDismiss = { showVoiceStudio = false; scope.launch { voices.clear(); voices.addAll(viewModel.api.getVoices()) } }
        )
    }

    if (showModeEditor) {
        ModeEditorScreen(
            api = viewModel.api,
            onModesChanged = { viewModel.loadModes() },
            onDismiss = { showModeEditor = false }
        )
    }

    if (showMemoryManager) {
        MemoryManagerScreen(
            api = viewModel.api,
            onDismiss = { showMemoryManager = false }
        )
    }
}
