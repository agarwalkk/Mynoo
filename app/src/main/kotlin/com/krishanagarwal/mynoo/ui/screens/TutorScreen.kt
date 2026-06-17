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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.clickable
import com.krishanagarwal.mynoo.ui.viewmodel.TutorUiState
import com.krishanagarwal.mynoo.ui.viewmodel.ChatMessage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
    onSessionActiveChange: (Boolean) -> Unit,
    vm: TutorViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(ui.phase) {
        onSessionActiveChange(ui.phase != SessionPhase.IDLE)
    }

    androidx.activity.compose.BackHandler(enabled = ui.phase != SessionPhase.IDLE) {
        vm.onUserAttemptEnd()
    }

    LaunchedEffect(childState.name) {
        vm.loadChildTutorData(childState.name)
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

        val backgroundBrush = Brush.verticalGradient(
            colors = listOf(
                Color(0xFFFFFDF9), // Very soft cream
                Color(0xFFFFF4E6)  // Gentle peach/warm orange
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush),
        ) {
            // Mascot header bar
            val mascotText = when (ui.phase) {
                SessionPhase.STARTING -> "🦉 Mynoo is waking up..."
                SessionPhase.BOT_SPEAKING -> "🦉 Mynoo is speaking..."
                SessionPhase.RECORDING -> "🎧 Listening to you..."
                SessionPhase.PROCESSING -> "💭 Mynoo is thinking..."
                SessionPhase.WAITING_CHILD -> "💬 Your turn to speak!"
                else -> ""
            }

            if (ui.phase != SessionPhase.IDLE && mascotText.isNotBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    border = BorderStroke(1.5.dp, Color(0xFF2C3E50))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = when (ui.phase) {
                                SessionPhase.RECORDING -> "🦉🎧"
                                SessionPhase.PROCESSING -> "🦉💭"
                                SessionPhase.BOT_SPEAKING -> "🦉🗣️"
                                else -> "🦉"
                            },
                            fontSize = 24.sp
                        )
                        Text(
                            text = mascotText,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF2C3E50)
                        )
                    }
                }
            }

            // ── Language selector ────────────────────────────────────────────────
            if (ui.phase == SessionPhase.IDLE) {
                IdleSessionView(
                    childState = childState,
                    ui = ui,
                    onNavigateToPlacementQuiz = onNavigateToPlacementQuiz,
                    onStart    = { vm.startSession(childState) },
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
                    ChatBubble(msg = msg)
                }

                // Status indicator
                val isThinking = (ui.phase == SessionPhase.BOT_SPEAKING && ui.messages.lastOrNull()?.role != "bot") || ui.phase == SessionPhase.STARTING
                if (isThinking) {
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
                    onClick = { vm.onUserAttemptEnd() },
                    shape   = RoundedCornerShape(12.dp),
                ) { Text("End") }

                // Mic FAB
                MicFab(
                    phase     = ui.phase,
                    onPress   = { vm.pressMic() },
                    onRelease = { vm.stopMicAndSend() },
                )

                // Cancel button shown during recording, otherwise spacer to balance
                if (ui.phase == SessionPhase.RECORDING) {
                    OutlinedButton(
                        onClick = { vm.cancelMic() },
                        shape   = RoundedCornerShape(12.dp),
                        colors  = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border  = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                    ) {
                        Text("Cancel")
                    }
                } else {
                    Spacer(Modifier.width(80.dp))
                }
            }
        }
    }
}

@Composable
private fun IdleSessionView(
    childState: ChildState,
    ui: TutorUiState,
    onNavigateToPlacementQuiz: (String) -> Unit,
    onStart:    () -> Unit,
    onReset:    () -> Unit,
) {
    Column(
        modifier            = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Streak Badge
        val streakText = if (ui.streak > 0) "${ui.streak} Day Streak 🔥" else "Start a Streak! 🔥"
        val streakColor = if (ui.streak > 0) Color(0xFFFF6B35) else Color(0xFF7F8C8D)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = streakColor.copy(alpha = 0.12f),
            border = BorderStroke(1.5.dp, streakColor),
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text(
                text = streakText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = streakColor
            )
        }

        if (!ui.isAssessed) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .clickable { onNavigateToPlacementQuiz(childState.name) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                border = BorderStroke(1.5.dp, Color(0xFF2C3E50))
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
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Start",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .size(100.dp)
                .background(Color(0xFFFFE66D), CircleShape)
                .border(2.dp, Color(0xFF2C3E50), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("🦉", fontSize = 56.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text  = "Hi, ${childState.name}!",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFFFF6B35),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Let's practice English and learn together!",
            style     = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color     = Color(0xFF2C3E50),
            textAlign = TextAlign.Center,
        )

        // Daily Goal Progress Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFAEE)),
            border = BorderStroke(1.5.dp, Color(0xFF2C3E50))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Daily Goal: 3-4 Sessions",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF2C3E50)
                )
                Spacer(Modifier.height(8.dp))
                // Row of 4 star indicators
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(4) { idx ->
                        val active = idx < ui.sessionsToday
                        Text(
                            text = if (active) "⭐" else "☆",
                            fontSize = 28.sp,
                            color = if (active) Color(0xFFFFD700) else Color(0xFFBDC3C7)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                // Custom greeting text based on count/skipped days
                val motivationalText = when {
                    ui.skippedDays > 0 -> "I missed you so much! You missed a day, but let's talk and get back on track! 💪"
                    ui.sessionsToday == 0 -> "Start your first session today! Mynoo is waiting for you! 🦉"
                    ui.sessionsToday == 1 -> "1 session done! Let's do another one to reach your goal! 🌟"
                    ui.sessionsToday == 2 -> "2 sessions done! You are doing great! Let's keep it up! 🚀"
                    ui.sessionsToday == 3 -> "Just 1 more session to complete your daily goal! You can do it! 🏆"
                    else -> "Woohoo! Daily goal completed! You are a superstar! 🎉"
                }
                Text(
                    text = motivationalText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = Color(0xFF2C3E50),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }

        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val scale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_scale"
        )

        Button(
            onClick  = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .scale(scale)
                .padding(vertical = 4.dp),
            shape    = RoundedCornerShape(24.dp),
            colors   = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFF6B35)
            ),
            border = BorderStroke(2.dp, Color(0xFF2C3E50)),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(vertical = 6.dp)
            ) {
                Text("💬 Let's Talk!", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White))
            }
        }

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onReset) { Text("Switch learner", color = Color(0xFF4ECDC4), fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage) {
    val isBot = msg.role == "bot"
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = if (isBot) Alignment.Start else Alignment.End
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isBot) Arrangement.Start else Arrangement.End,
            verticalAlignment     = Alignment.Bottom
        ) {
            if (isBot) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE3F2FD))
                        .border(1.5.dp, Color(0xFF2C3E50), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("🦉", fontSize = 18.sp) }
                Spacer(Modifier.width(8.dp))
            }
            Surface(
                shape = RoundedCornerShape(
                    topStart = if (isBot) 4.dp else 20.dp,
                    topEnd   = if (isBot) 20.dp else 4.dp,
                    bottomStart = 20.dp,
                    bottomEnd = 20.dp,
                ),
                color = if (isBot) Color(0xFFE3F2FD) else Color(0xFFFFFDE7),
                border = BorderStroke(2.dp, Color(0xFF2C3E50)),
                shadowElevation = 3.dp,
                modifier = Modifier.widthIn(max = 280.dp),
            ) {
                Text(
                    text     = msg.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style    = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        lineHeight = 20.sp
                    ),
                    color    = Color(0xFF2C3E50),
                )
            }
            if (!isBot) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFFDE7))
                        .border(1.5.dp, Color(0xFF2C3E50), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("👶", fontSize = 18.sp) }
            }
        }

        if (isBot && !msg.propType.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier
                    .padding(start = 44.dp, end = 16.dp)
                    .widthIn(max = 280.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(2.dp, Color(0xFF2C3E50)),
                colors = CardDefaults.cardColors(
                    containerColor = when (msg.propType) {
                        "fact" -> Color(0xFFE8F5E9)
                        "current_affairs" -> Color(0xFFFFF3E0)
                        else -> Color(0xFFF3E5F5)
                    }
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = msg.propTitle.orEmpty(),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF2C3E50)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = msg.propText.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF2C3E50).copy(alpha = 0.9f)
                    )
                }
            }
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

