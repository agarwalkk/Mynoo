package com.krishanagarwal.mynoo.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.krishanagarwal.mynoo.ui.viewmodel.AnswerRecord
import com.krishanagarwal.mynoo.ui.viewmodel.PlacementQuizState
import com.krishanagarwal.mynoo.ui.viewmodel.PlacementViewModel
import com.krishanagarwal.mynoo.ui.viewmodel.QuizPhase
import com.krishanagarwal.mynoo.ui.viewmodel.VoiceStatus

@Composable
fun PlacementQuizScreen(
    childName: String,
    langOverride: String?,
    onExit: () -> Unit,
    onFinish: () -> Unit,
    vm: PlacementViewModel = hiltViewModel()
) {
    val state by vm.ui.collectAsState()

    // Request permissions launcher
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        vm.initialize(childName, langOverride)
    }

    // Trigger onFinish when phase transitions to DONE
    LaunchedEffect(state.phase) {
        if (state.phase == QuizPhase.DONE) {
            kotlinx.coroutines.delay(2000)
            onFinish()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFFFDF5)) // Sleek, modern warm-light background
    ) {
        when (state.phase) {
            QuizPhase.LOADING -> LoadingView()
            QuizPhase.START_PROMPT -> StartPromptView(childName, state, vm, onFinish)
            QuizPhase.RESUME_PROMPT -> ResumePromptView(state, vm, onFinish)
            QuizPhase.QUIZ -> QuizView(childName, state, vm, onExit)
            QuizPhase.RESULT -> ResultView(childName, state, vm)
            QuizPhase.SAVING -> SavingView()
            QuizPhase.DONE -> DoneView(childName, state)
        }
    }
}

// ── Phase 1: Loading View ────────────────────────────────────────────────────

@Composable
private fun LoadingView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = Color(0xFF27AE60))
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Loading quiz…",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF7F8C8D)
        )
    }
}

// ── Phase 2: Start Prompt View ───────────────────────────────────────────────

@Composable
private fun StartPromptView(
    childName: String,
    state: PlacementQuizState,
    vm: PlacementViewModel,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("📝", fontSize = 72.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Language Assessment",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFF1A2834),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "This quick adaptive quiz helps calibrate lessons to your personal language level.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF7F8C8D),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(32.dp))

        // Quiz items estimation card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                listOf(
                    Triple("🇬🇧", "English", "5–10 mins"),
                    Triple("🇮🇳", "Hindi", "5–10 mins"),
                    Triple("☬", "Punjabi", "5–10 mins")
                ).forEachIndexed { index, (emoji, name, time) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$emoji  $name",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color(0xFF2C3E50)
                        )
                        Text(
                            text = time,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF7F8C8D)
                        )
                    }
                    if (index < 2) {
                        HorizontalDivider(color = Color(0xFFF1F1F1))
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = { vm.startQuiz() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27AE60))
        ) {
            Text("Start Assessment", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(Modifier.height(12.dp))

        TextButton(onClick = onCancel) {
            Text("Not now — skip to dashboard ›", color = Color(0xFF27AE60), fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Phase 3: Resume Prompt View ──────────────────────────────────────────────

@Composable
private fun ResumePromptView(
    state: PlacementQuizState,
    vm: PlacementViewModel,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("💾", fontSize = 72.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "You have a saved quiz",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFF1A2834),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Continue learning right where you left off or start fresh.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF7F8C8D),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Completed", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF7F8C8D))
                    val completed = state.finalLevels.keys.joinToString(", ") { it.uppercase() }
                    Text(
                        text = if (completed.isEmpty()) "None" else "$completed ✓",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFF2C3E50)
                    )
                }
                HorizontalDivider(color = Color(0xFFF1F1F1))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("In Progress", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF7F8C8D))
                    val nextLang = when (state.langIndex) {
                        0 -> "English"
                        1 -> "Hindi"
                        2 -> "Punjabi"
                        else -> "English"
                    }
                    Text(
                        text = nextLang,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFF2C3E50)
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = { vm.resumeDraft() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27AE60))
        ) {
            Text("Resume Quiz", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = { vm.startFresh() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF7F8C8D)),
            border = BorderStroke(1.5.dp, Color(0xFFBDC3C7))
        ) {
            Text("Start Fresh", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(16.dp))

        TextButton(onClick = onCancel) {
            Text("Not now — skip to learning ›", color = Color(0xFF27AE60), fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Phase 4: Active Quiz View ────────────────────────────────────────────────

@Composable
private fun QuizView(
    childName: String,
    state: PlacementQuizState,
    vm: PlacementViewModel,
    onExit: () -> Unit
) {
    val currentLang = vm.LANGUAGES.getOrNull(state.langIndex) ?: vm.LANGUAGES[0]
    val currentLangColor = Color(android.graphics.Color.parseColor(currentLang.colour))
    val isFeedbackActive = state.showFeedback != null

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Top row with Exit
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${currentLang.emoji}  ${currentLang.label} Quiz",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = currentLangColor
                    )
                    IconButton(onClick = { vm.saveDraftAndExit(onComplete = onExit) }) {
                        Icon(Icons.Default.Close, contentDescription = "Exit", tint = Color(0xFF7F8C8D))
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Custom stepper tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    vm.LANGUAGES.forEachIndexed { idx, lang ->
                        val isTabActive = idx == state.langIndex
                        val isDone = state.finalLevels[lang.key] != null
                        val tabColor = Color(android.graphics.Color.parseColor(lang.colour))

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .clickable(enabled = !isFeedbackActive) { vm.switchLanguage(idx) },
                            color = when {
                                isTabActive -> tabColor
                                isDone -> tabColor.copy(alpha = 0.15f)
                                else -> Color(0xFFF1F1F1)
                            },
                            border = if (isDone && !isTabActive) BorderStroke(1.dp, tabColor) else null
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    if (isDone) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = tabColor,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = lang.label,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = when {
                                            isTabActive -> Color.White
                                            isDone -> tabColor
                                            else -> Color(0xFF7F8C8D)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Progress Line
                val answeredCount = state.history.size
                val animatedProgress by animateFloatAsState(
                    targetValue = answeredCount.toFloat() / 20f, // 20 target questions
                    animationSpec = tween(500),
                    label = "progress"
                )
                LinearProgressIndicator(
                    progress = { animatedProgress.coerceAtMost(1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = currentLangColor,
                    trackColor = currentLangColor.copy(alpha = 0.15f)
                )

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Progress: $answeredCount answered",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF7F8C8D)
                    )
                    Text(
                        text = "min 20, max 50",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF7F8C8D)
                    )
                }
            }
        },
        containerColor = Color(0xFFFFFDF5)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

            when {
                state.loadingQuestion -> {
                    Spacer(Modifier.height(80.dp))
                    CircularProgressIndicator(color = currentLangColor)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Preparing next question…",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = currentLangColor
                    )
                }
                state.questionError != null -> {
                    Spacer(Modifier.height(80.dp))
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFE74C3C), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Oops, something went wrong!",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF1A2834)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.questionError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF7F8C8D),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { vm.initialize(childName, currentLang.key) },
                        colors = ButtonDefaults.buttonColors(containerColor = currentLangColor),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Retry Connection", color = Color.White)
                    }
                }
                state.currentQuestion != null -> {
                    val q = state.currentQuestion

                    // Question Card
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = Color.White)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            // Badge metadata
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = currentLangColor.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "Level ${q.difficulty}",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = currentLangColor
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Q${state.history.size + 1}",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color(0xFF7F8C8D)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    IconButton(
                                        onClick = { vm.speakQuestion() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.VolumeUp, contentDescription = "Speak", tint = currentLangColor)
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            Text(
                                text = q.displayPrompt,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, lineHeight = 26.sp),
                                color = Color(0xFF1C2833)
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // Options Grid/List
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        q.options.forEachIndexed { idx, opt ->
                            val isSelected = state.selectedOption == idx
                            val isCorrect = idx == q.correctIndex

                            val cardColor = when {
                                isFeedbackActive && isCorrect -> Color(0xFFE8F8F5) // Right answer green
                                isFeedbackActive && isSelected && !isCorrect -> Color(0xFFFCE4D6) // Wrong answer red
                                isSelected -> currentLangColor.copy(alpha = 0.1f)
                                else -> Color.White
                            }

                            val borderStroke = when {
                                isFeedbackActive && isCorrect -> BorderStroke(2.dp, Color(0xFF27AE60))
                                isFeedbackActive && isSelected && !isCorrect -> BorderStroke(2.dp, Color(0xFFE67E22))
                                isSelected -> BorderStroke(2.dp, currentLangColor)
                                else -> BorderStroke(1.dp, Color(0xFFE8E8E8))
                            }

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable(enabled = !isFeedbackActive) { vm.submitAnswer(idx) },
                                color = cardColor,
                                border = borderStroke,
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val optionLabels = listOf("A", "B", "C", "D")
                                    Surface(
                                        shape = CircleShape,
                                        color = when {
                                            isFeedbackActive && isCorrect -> Color(0xFF27AE60)
                                            isFeedbackActive && isSelected && !isCorrect -> Color(0xFFE67E22)
                                            isSelected -> currentLangColor
                                            else -> Color(0xFFF5F7F8)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = optionLabels.getOrNull(idx) ?: "",
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected || (isFeedbackActive && isCorrect)) Color.White else Color(0xFF7F8C8D),
                                                fontSize = 14.sp
                                            )
                                        }
                                    }

                                    Spacer(Modifier.width(12.dp))

                                    Text(
                                        text = opt,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = Color(0xFF2C3E50),
                                        modifier = Modifier.weight(1f)
                                    )

                                    if (isFeedbackActive) {
                                        if (isCorrect) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF27AE60))
                                        } else if (isSelected) {
                                            Icon(Icons.Default.Cancel, contentDescription = null, tint = Color(0xFFE67E22))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    // Voice Answer FAB
                    if (q.questionType == "voice-answer" || q.questionType == "listen-prompt") {
                        VoiceFAB(state, vm, currentLangColor)
                        Spacer(Modifier.height(16.dp))
                    }

                    // Skip "I don't know" button
                    if (!isFeedbackActive) {
                        TextButton(
                            onClick = { vm.submitSkip() },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("🤷 Don't know / Skip question", color = currentLangColor, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        // Feedback explanation banner
                        Surface(
                            color = if (state.isFeedbackCorrect) Color(0xFFEAFaf1) else Color(0xFFFDF2E9),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = state.showFeedback ?: "",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (state.isFeedbackCorrect) Color(0xFF27AE60) else Color(0xFFE67E22),
                                modifier = Modifier.padding(16.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun VoiceFAB(state: PlacementQuizState, vm: PlacementViewModel, tint: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "voice")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            if (state.voiceStatus == VoiceStatus.RECORDING) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(scale)
                        .clip(CircleShape)
                        .background(Color(0xFFE74C3C).copy(alpha = 0.2f))
                )
            }
            FloatingActionButton(
                onClick = {
                    if (state.voiceStatus == VoiceStatus.RECORDING) {
                        vm.stopVoiceAnswer()
                    } else if (state.voiceStatus == VoiceStatus.IDLE || state.voiceStatus == VoiceStatus.ERROR) {
                        vm.startVoiceAnswer()
                    }
                },
                shape = CircleShape,
                containerColor = when (state.voiceStatus) {
                    VoiceStatus.RECORDING -> Color(0xFFE74C3C)
                    VoiceStatus.PROCESSING -> Color(0xFFBDC3C7)
                    else -> tint
                },
                contentColor = Color.White,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    imageVector = if (state.voiceStatus == VoiceStatus.RECORDING) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = "Voice input",
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = when (state.voiceStatus) {
                VoiceStatus.RECORDING -> "🔴 Recording... Tap to Send"
                VoiceStatus.PROCESSING -> "⏳ Analyzing speech..."
                VoiceStatus.ERROR -> state.voiceError ?: "STT Error"
                else -> "Hold to speak answer"
            },
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = if (state.voiceStatus == VoiceStatus.ERROR) Color(0xFFE74C3C) else Color(0xFF7F8C8D)
            )
        )
    }
}

// ── Phase 5: Result Card & Slider View ───────────────────────────────────────

@Composable
private fun ResultView(
    childName: String,
    state: PlacementQuizState,
    vm: PlacementViewModel
) {
    val currentLang = vm.LANGUAGES.getOrNull(state.langIndex) ?: vm.LANGUAGES[0]
    val currentLangColor = Color(android.graphics.Color.parseColor(currentLang.colour))
    val result = state.assessmentResult ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(currentLang.emoji, fontSize = 72.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "${currentLang.label} Level result",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFF1A2834),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        // Result Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Computed Level",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7F8C8D)
                )
                Text(
                    text = "${state.adjustedLevel}",
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Black,
                    color = currentLangColor
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = result.displaySummary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
                    color = Color(0xFF2C3E50),
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Premium visual level slider
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Adjust level manually if needed:",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF2C3E50)
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = { vm.adjustLevel(-5) },
                        modifier = Modifier
                            .size(40.dp)
                            .border(1.5.dp, Color(0xFFBDC3C7), CircleShape)
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = Color(0xFF7F8C8D))
                    }

                    Slider(
                        value = state.adjustedLevel.toFloat(),
                        onValueChange = { vm.setAdjustedLevelDirect(it.toInt()) },
                        valueRange = 1f..100f,
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = currentLangColor,
                            activeTrackColor = currentLangColor,
                            inactiveTrackColor = currentLangColor.copy(alpha = 0.15f)
                        )
                    )

                    IconButton(
                        onClick = { vm.adjustLevel(5) },
                        modifier = Modifier
                            .size(40.dp)
                            .border(1.5.dp, Color(0xFFBDC3C7), CircleShape)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase", tint = Color(0xFF7F8C8D))
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = { vm.confirmResult() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27AE60))
        ) {
            Text("Confirm & Continue", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

// ── Phase 6: Saving View ─────────────────────────────────────────────────────

@Composable
private fun SavingView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = Color(0xFF27AE60))
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Saving levels to Firestore...",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color(0xFF7F8C8D)
        )
    }
}

// ── Phase 7: Celebration Done View ───────────────────────────────────────────

@Composable
private fun DoneView(childName: String, state: PlacementQuizState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🎉", fontSize = 84.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Assessment Complete!",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFF1A2834),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Outstanding job, $childName! Here are your placement levels:",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF7F8C8D),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        // Summary Card of all levels
        state.finalLevels.forEach { (lang, level) ->
            val emoji = when (lang) {
                "en" -> "🇬🇧"
                "hi" -> "🇮🇳"
                "pa" -> "☬"
                else -> "🌐"
            }
            val label = when (lang) {
                "en" -> "English"
                "hi" -> "Hindi"
                "pa" -> "Punjabi"
                else -> "Language"
            }
            val colour = when (lang) {
                "en" -> Color(0xFF27AE60)
                "hi" -> Color(0xFFE67E22)
                "pa" -> Color(0xFF8E44AD)
                else -> Color(0xFF27AE60)
            }

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$emoji  $label",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF2C3E50)
                        )
                        Text(
                            text = "Level $level",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                            color = colour
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    LinearProgressIndicator(
                        progress = { level.toFloat() / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = colour,
                        trackColor = colour.copy(alpha = 0.15f)
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))

        CircularProgressIndicator(color = Color(0xFF27AE60), modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Loading your first lesson with Aarav…",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF7F8C8D),
            textAlign = TextAlign.Center
        )
    }
}
