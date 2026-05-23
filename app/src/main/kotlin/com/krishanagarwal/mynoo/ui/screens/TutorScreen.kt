package com.krishanagarwal.mynoo.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.clickable
import com.krishanagarwal.mynoo.ui.viewmodel.TutorUiState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.krishanagarwal.mynoo.data.model.ChildState
import com.krishanagarwal.mynoo.ui.viewmodel.SessionPhase
import com.krishanagarwal.mynoo.ui.viewmodel.TutorViewModel
import kotlinx.coroutines.launch

@Composable
fun TutorScreen(
    childState:   ChildState,
    onChildReset: () -> Unit,
    onNavigateToPlacementQuiz: (String) -> Unit,
    vm: TutorViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(childState.name) {
        vm.checkAssessedStatus(childState.name)
    }

    // Auto-scroll to last message
    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) {
            listState.animateScrollToItem(ui.messages.size - 1)
        }
    }

    // Mic permission launcher
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.onMicPermResult(granted) }

    LaunchedEffect(Unit) {
        micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ── Language selector ────────────────────────────────────────────────
        if (ui.phase == SessionPhase.IDLE) {
            IdleSessionView(
                childState = childState,
                ui = ui,
                onNavigateToPlacementQuiz = onNavigateToPlacementQuiz,
                onStart    = { lang -> vm.startSession(childState, lang) },
                onReset    = onChildReset,
            )
        } else {
            // ── Active session ───────────────────────────────────────────────
            // Messages
            LazyColumn(
                state          = listState,
                modifier       = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ui.messages, key = { it.id }) { msg ->
                    ChatBubble(role = msg.role, text = msg.text)
                }

                // Status indicator
                if (ui.phase == SessionPhase.BOT_SPEAKING || ui.phase == SessionPhase.STARTING) {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            TypingIndicator()
                        }
                    }
                }
                if (ui.phase == SessionPhase.RECORDING || ui.phase == SessionPhase.PROCESSING) {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            ) {
                                Text(
                                    text = if (ui.phase == SessionPhase.RECORDING) "🎙 Listening…" else "⏳ Processing…",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            // ── Error snackbar ───────────────────────────────────────────────
            ui.error?.let {
                Text(
                    text     = it,
                    color    = MaterialTheme.colorScheme.error,
                    style    = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    textAlign = TextAlign.Center,
                )
            }

            // ── Quick replies ────────────────────────────────────────────────
            if (ui.quickReplies.isNotEmpty() && ui.phase == SessionPhase.WAITING_CHILD) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ui.quickReplies) { reply ->
                        SuggestionChip(
                            onClick = { vm.sendQuickReply(reply) },
                            label   = { Text(reply, style = MaterialTheme.typography.bodySmall) },
                        )
                    }
                }
            }

            // ── Bottom controls ──────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                // End session button
                OutlinedButton(
                    onClick = { vm.endSession(childState) },
                    shape   = RoundedCornerShape(12.dp),
                ) { Text("End") }

                // Mic FAB
                MicFab(
                    phase     = ui.phase,
                    onPress   = { vm.pressMic() },
                    onRelease = { vm.stopMicAndSend() },
                )

                // Spacer to balance layout
                Spacer(Modifier.width(80.dp))
            }
        }
    }
}

@Composable
private fun IdleSessionView(
    childState: ChildState,
    ui: TutorUiState,
    onNavigateToPlacementQuiz: (String) -> Unit,
    onStart:    (String) -> Unit,
    onReset:    () -> Unit,
) {
    Column(
        modifier            = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!ui.isAssessed) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
                    .clickable { onNavigateToPlacementQuiz(childState.name) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("📝", fontSize = 32.sp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Take Placement Quiz",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Find your starting levels for English, Hindi, and Punjabi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = "Start",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Text("🎓", fontSize = 72.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text  = "Hi, ${childState.name}!",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Choose a language to start your session",
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        listOf("en" to "🇬🇧  English", "hi" to "🇮🇳  हिंदी", "pa" to "🏳  ਪੰਜਾਬੀ").forEach { (code, label) ->
            Button(
                onClick  = { onStart(code) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape    = RoundedCornerShape(12.dp),
            ) { Text(label, style = MaterialTheme.typography.titleMedium) }
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onReset) { Text("Switch learner") }
    }
}

@Composable
private fun ChatBubble(role: String, text: String) {
    val isBot = role == "bot"
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isBot) Arrangement.Start else Arrangement.End,
    ) {
        if (isBot) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Text("M", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            Spacer(Modifier.width(8.dp))
        }
        Surface(
            shape = RoundedCornerShape(
                topStart = if (isBot) 4.dp else 16.dp,
                topEnd   = 16.dp,
                bottomStart = 16.dp,
                bottomEnd = if (isBot) 16.dp else 4.dp,
            ),
            color = if (isBot)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Text(
                text     = text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style    = MaterialTheme.typography.bodyMedium,
                color    = if (isBot)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val scale by infiniteTransition.animateFloat(
        initialValue   = 0.6f,
        targetValue    = 1.0f,
        animationSpec  = infiniteRepeatable(
            animation  = tween(500),
            repeatMode = RepeatMode.Reverse,
        ),
        label          = "pulse",
    )
    Row(
        Modifier.padding(start = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            Box(
                Modifier
                    .scale(if (i == 1) scale else 1f - (scale - 0.6f) * 0.5f)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
            )
        }
    }
}

@Composable
private fun MicFab(
    phase:     SessionPhase,
    onPress:   () -> Unit,
    onRelease: () -> Unit,
) {
    val isRecording = phase == SessionPhase.RECORDING
    val isIdle      = phase == SessionPhase.WAITING_CHILD
    val enabled     = isIdle || isRecording

    val infiniteTransition = rememberInfiniteTransition(label = "mic")
    val ringScale by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = 1.4f,
        animationSpec = infiniteRepeatable(
            animation  = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label         = "ring",
    )

    Box(contentAlignment = Alignment.Center) {
        if (isRecording) {
            Box(
                Modifier
                    .size(80.dp)
                    .scale(ringScale)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.2f))
            )
        }
        FloatingActionButton(
            onClick           = { if (isRecording) onRelease() else if (isIdle) onPress() },
            containerColor    = when {
                isRecording -> MaterialTheme.colorScheme.error
                isIdle      -> MaterialTheme.colorScheme.primary
                else        -> MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor      = Color.White,
            shape             = CircleShape,
            modifier          = Modifier.size(64.dp),
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isRecording) "Stop" else "Speak",
                modifier    = Modifier.size(28.dp),
            )
        }
    }
}

