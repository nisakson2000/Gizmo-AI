package ai.gizmo.app.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import ai.gizmo.app.model.Conversation
import ai.gizmo.app.placeholder.PlaceholderScreen
import ai.gizmo.app.settings.SettingsScreen
import ai.gizmo.app.ui.components.BottomNav
import ai.gizmo.app.ui.components.ConnectionIndicator
import ai.gizmo.app.ui.theme.BgPrimary
import ai.gizmo.app.ui.theme.TextDim
import ai.gizmo.app.ui.theme.TextPrimary
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
    var inputText by remember { mutableStateOf("") }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.handleImagePick(it, context.contentResolver) } }

    val docPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.handleDocumentPick(it, context.contentResolver) } }

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
                                Text(
                                    text = " \u2014 ",
                                    color = TextDim,
                                    fontSize = 18.sp
                                )
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
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.settings_title),
                                tint = TextSecondary
                            )
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
                        .padding(padding)) {

                        val hasMessages = viewModel.messages.isNotEmpty() || viewModel.generating.value

                        if (hasMessages) {
                            MessageList(
                                messages = viewModel.messages,
                                streamingContent = viewModel.streamingContent.value,
                                streamingThinking = viewModel.streamingThinking.value,
                                streamingToolCalls = viewModel.streamingToolCalls,
                                generating = viewModel.generating.value,
                                serverUrl = viewModel.serverUrl,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            EmptyState(
                                onSuggestionClick = { inputText = it },
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
                            pendingImageUri = viewModel.pendingImageUri.value,
                            pendingDocumentName = viewModel.pendingDocumentName.value,
                            onClearAttachment = { viewModel.clearAttachment() },
                            onPickImage = { imagePickerLauncher.launch("image/*") },
                            onPickDocument = { docPickerLauncher.launch("*/*") },
                            onSend = { text ->
                                viewModel.sendMessage(text)
                                inputText = ""
                            },
                            onStop = { viewModel.stopGeneration() }
                        )
                    }
                }
                else -> PlaceholderScreen(
                    tabName = when (selectedTab) {
                        1 -> "Tasks"
                        2 -> "Code"
                        3 -> "Stats"
                        else -> ""
                    },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }

    if (showSettings) {
        SettingsScreen(
            thinkingEnabled = viewModel.thinkingEnabled.value,
            onThinkingChanged = { viewModel.thinkingEnabled.value = it },
            contextLength = viewModel.contextLength.value,
            onContextLengthChanged = { viewModel.contextLength.value = it },
            modes = viewModel.modes,
            selectedMode = viewModel.selectedMode.value,
            onModeSelected = { viewModel.selectedMode.value = it },
            serviceHealth = viewModel.serviceHealth,
            serverName = viewModel.serverName,
            serverUrl = viewModel.serverUrl,
            onRefreshHealth = { viewModel.loadServiceHealth() },
            onSwitchServer = onSwitchServer,
            onDismiss = { showSettings = false }
        )
    }
}
