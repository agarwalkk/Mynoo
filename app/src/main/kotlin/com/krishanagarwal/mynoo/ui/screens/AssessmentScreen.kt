package com.krishanagarwal.mynoo.ui.screens

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.krishanagarwal.mynoo.data.repository.AssessmentQuestion
import com.krishanagarwal.mynoo.ui.viewmodel.AssessmentViewModel
import com.krishanagarwal.mynoo.ui.viewmodel.currentQuestion
import com.krishanagarwal.mynoo.ui.viewmodel.score
import com.krishanagarwal.mynoo.ui.viewmodel.earnedMarks
import com.krishanagarwal.mynoo.ui.viewmodel.totalMarks
import java.io.ByteArrayOutputStream
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssessmentScreen(
    assessmentId: String,
    childName:    String,
    onFinish:     () -> Unit,
    vm: AssessmentViewModel = hiltViewModel(),
) {
    val quiz by vm.quiz.collectAsState()

    LaunchedEffect(assessmentId, childName) {
        if (quiz.assessment == null) vm.loadAssessment(childName, assessmentId)
    }

    // Handle toast/dialog error display for unanswered questions
    val context = LocalContext.current
    LaunchedEffect(quiz.error) {
        quiz.error?.let { err ->
            android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
            vm.clearError()
        }
    }

    when {
        quiz.generating || quiz.assessment == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Loading assessment.")
            }
        }
        quiz.finished -> {
            val savedScore = quiz.assessment?.score?.toInt()
            val scoreLabel = if (savedScore != null) {
                "Overall Score: $savedScore%"
            } else {
                "Overall Score: ${quiz.score}%  ·  ${quiz.earnedMarks} / ${quiz.totalMarks} marks"
            }
            SummaryView(quiz.summary, scoreLabel, onFinish)
        }
        else -> {
            val q   = quiz.currentQuestion ?: return
            val idx = quiz.currentIndex
            val total = quiz.assessment?.questions?.size ?: 1
            
            val themeColor = when (quiz.assessment?.subject?.lowercase()?.trim()) {
                "hindi" -> Color(0xFFE67E22)
                "english" -> Color(0xFF27AE60)
                "punjabi" -> Color(0xFF8E44AD)
                else -> Color(0xFF2980B9)
            }

            Scaffold(
                topBar = {
                    Surface(color = themeColor, contentColor = Color.White) {
                        Row(
                            modifier = Modifier
                                .height(56.dp)
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { onFinish() }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Quit",
                                    tint = Color.White
                                )
                            }
                             Column(Modifier.weight(1f)) {
                                 val assessmentTitle = quiz.assessment?.title.orEmpty().ifBlank { quiz.assessment?.subject ?: "Assessment" }
                                 Text(
                                     text = assessmentTitle,
                                     fontWeight = FontWeight.Bold,
                                     style = MaterialTheme.typography.titleMedium,
                                     color = Color.White,
                                     maxLines = 1,
                                     overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                 )
                                 val subtitle = if (quiz.assessment?.title.orEmpty().isNotBlank()) {
                                     "${quiz.assessment?.subject} · $childName"
                                 } else {
                                     childName
                                 }
                                 Text(
                                     text = subtitle,
                                     style = MaterialTheme.typography.bodySmall,
                                     color = Color.White.copy(alpha = 0.8f)
                                 )
                             }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.2f),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(
                                    text = q.difficulty.uppercase(),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .background(Color(0xFFFFFDF9)) // Warm cream background
                ) {
                    QuestionView(
                        question         = q,
                        index            = idx,
                        total            = total,
                        answered         = quiz.answers.getOrNull(idx),
                        revealed         = idx in quiz.revealed,
                        validating       = quiz.validating,
                        validationResult = quiz.validationResult,
                        retryUsed        = quiz.retryUsed.contains(idx),
                        mcqSelectedIndex = quiz.mcqSelectedIndex,
                        mcqFirstWrongIndex = quiz.mcqFirstWrongIndex,
                        mcqPhase         = quiz.mcqPhase,
                        onMCQSelect      = { vm.selectMCQOption(it) },
                        onCheckText      = { vm.validateCurrentAnswer(it) },
                        onCheckHandwritten = { b64, key -> vm.validateCurrentHandwrittenAnswer(b64, key) },
                        onRetryText      = { vm.retryCurrentQuestion() },
                        onPrev           = { vm.prev() },
                        onNext           = { text -> vm.next(text) }
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionView(
    question:           AssessmentQuestion,
    index:              Int,
    total:              Int,
    answered:           Map<String, Any>?,
    revealed:           Boolean,
    validating:         Boolean,
    validationResult:   Map<String, Any>?,
    retryUsed:          Boolean,
    mcqSelectedIndex:   Int?,
    mcqFirstWrongIndex: Int?,
    mcqPhase:           String,
    onMCQSelect:        (Int) -> Unit,
    onCheckText:        (String) -> Unit,
    onCheckHandwritten: (String, String) -> Unit,
    onRetryText:        () -> Unit,
    onPrev:             () -> Unit,
    onNext:             (String) -> Unit
) {
    val isMCQ = question.type == "mcq"
    val isAnswered = answered != null || mcqPhase == "done" || validationResult != null

    // Determine marks earned
    val marks = question.marks
    var earnedMarks: Double? = null
    if (answered != null) {
        val type = answered["type"] as? String ?: ""
        if (type == "mcq") {
            val correct = answered["correct"] as? Boolean ?: false
            val attempts = (answered["attempts"] as? Number)?.toInt() ?: 1
            if (correct) {
                earnedMarks = if (attempts == 2) marks / 2.0 else marks
            } else {
                earnedMarks = 0.0
            }
        } else {
            earnedMarks = (answered["aiEarnedMarks"] as? Number)?.toDouble() ?: 0.0
        }
    } else if (isAnswered) {
        if (isMCQ) {
            val correct = mcqSelectedIndex == question.correctIndex
            val attempts = if (mcqFirstWrongIndex != null) 2 else 1
            if (correct) {
                earnedMarks = if (attempts == 2) marks / 2.0 else marks
            } else {
                earnedMarks = 0.0
            }
        } else if (validationResult != null) {
            earnedMarks = (validationResult["earnedMarks"] as? Number)?.toDouble() ?: 0.0
        }
    }

    val marksColor = when {
        earnedMarks == null -> Color(0xFF856404)
        earnedMarks >= marks -> Color(0xFF1A7A4A)
        earnedMarks > 0.0 -> Color(0xFFE67E22)
        else -> Color(0xFFC0392B)
    }

    val marksBg = when {
        earnedMarks == null -> Color(0xFFFFF9E6)
        earnedMarks >= marks -> Color(0xFFD4EFDF)
        earnedMarks > 0.0 -> Color(0xFFFEF0E0)
        else -> Color(0xFFFADBD8)
    }

    // Text field state
    val restoredText = answered?.get("textAnswer") as? String ?: ""
    var textValue by remember(index) { mutableStateOf(TextFieldValue(restoredText)) }

    // descriptive answer modes: Type, Canvas, Camera
    var answerMode by remember(index) { mutableStateOf("type") }

    // Canvas dialog visibility
    var showCanvasDialog by remember { mutableStateOf(false) }

    // Camera launcher & state
    val context = LocalContext.current
    var cameraPhotoBitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    var cameraPhotoBase64 by remember(index) { mutableStateOf("") }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val byteArr = stream.toByteArray()
            val b64 = Base64.encodeToString(byteArr, Base64.NO_WRAP)
            cameraPhotoBitmap = bitmap
            cameraPhotoBase64 = b64
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    val resized = if (bitmap.width > 1200 || bitmap.height > 1200) {
                        val scale = 1200f / Math.max(bitmap.width, bitmap.height)
                        Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                    } else bitmap
                    
                    val stream = ByteArrayOutputStream()
                    resized.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                    val byteArr = stream.toByteArray()
                    val b64 = Base64.encodeToString(byteArr, Base64.NO_WRAP)
                    cameraPhotoBitmap = resized
                    cameraPhotoBase64 = b64
                }
            } catch (e: Exception) {
                Log.e("AssessmentScreen", "Error loading gallery image", e)
            }
        }
    }

    // Collapsible passage state
    var showPassage by remember(index) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Progress Bar
            LinearProgressIndicator(
                progress = { (index + 1f) / total },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Question ${index + 1} of $total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = marksBg
                ) {
                    Text(
                        text = if (earnedMarks != null) "${if (earnedMarks % 1.0 == 0.0) earnedMarks.toInt() else earnedMarks} / ${marks.toInt()}m" else "${marks.toInt()}m",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = marksColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Type Badge
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isMCQ) Color(0xFFFFF0EA) else Color(0xFFEEF4FF)
            ) {
                Text(
                    text = question.type.replace('_', ' ').uppercase(),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (isMCQ) Color(0xFFE67E22) else Color(0xFF2980B9)
                    )
                )
            }

            // Collapsible Passage View
            if (question.passage.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFD0E0FF)),
                    color = Color(0xFFF4F7FF)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showPassage = !showPassage },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📖 Reading Passage",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF2980B9)
                            )
                            Text(
                                text = if (showPassage) "Hide ▲" else "Read ▼",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF2980B9)
                            )
                        }
                        if (showPassage) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = Color(0xFFD0E0FF))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = question.passage,
                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                                color = Color(0xFF2C3E50)
                            )
                        }
                    }
                }
            }

            // Input Sentence (if present, e.g. translation, transformation, error correction)
            if (question.inputSentence.isNotBlank()) {
                val boxBg = if (question.type == "error_correction") Color(0xFFFFF0F0) else Color(0xFFF2F4F7)
                val boxBorder = if (question.type == "error_correction") Color(0xFFFADBD8) else Color(0xFFE2E8F0)
                val label = when (question.type) {
                    "error_correction" -> "⚠️ Spot the Error:"
                    "translation" -> "🌐 Translate:"
                    "transformation" -> "↻ transform:"
                    else -> "Given Sentence:"
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = boxBg,
                    border = BorderStroke(1.dp, boxBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF7F8C8D)
                        )
                        Text(
                            text = question.inputSentence,
                            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                            color = Color(0xFF2C3E50)
                        )
                    }
                }
            }

            // Question Text
            Text(
                text = renderMarkdown(question.question),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal, lineHeight = 24.sp),
                color = Color(0xFF2C3E50)
            )

            // Hint Text (if present)
            if (question.hint.isNotBlank()) {
                Text(
                    text = "💡 Hint: ${question.hint}",
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Blanks Word Bank
            if (question.blanks.isNotEmpty()) {
                Text(
                    text = "Word bank: ${question.blanks.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(4.dp))

            // MCQ Options / Descriptive Input
            if (isMCQ) {
                val labels = listOf("A", "B", "C", "D")
                val activeSelected = answered?.get("selectedIndex") as? Int ?: mcqSelectedIndex
                val firstWrong = answered?.get("firstWrongIndex") as? Int ?: mcqFirstWrongIndex
                val isMcqDone = answered != null || mcqPhase == "done"
                val isRetryState = mcqPhase == "first_wrong"

                question.options.forEachIndexed { i, opt ->
                    val isSelected = activeSelected == i
                    val isCorrect  = question.correctIndex == i
                    val isWrong    = isSelected && !isCorrect
                    val isFirstWrong = firstWrong == i

                    val borderThickness = if (isSelected || (isMcqDone && isCorrect)) 2.dp else 1.dp
                    val borderColor = when {
                        isMcqDone && isCorrect -> Color(0xFF27AE60)
                        isMcqDone && isWrong -> Color(0xFFE74C3C)
                        isRetryState && isFirstWrong -> Color(0xFFE74C3C)
                        isSelected -> Color(0xFF2980B9)
                        else -> Color(0xFFE2E8F0)
                    }

                    val containerColor = when {
                        isMcqDone && isCorrect -> Color(0xFFF0FBF5)
                        isMcqDone && isWrong -> Color(0xFFFDF0F0)
                        isRetryState && isFirstWrong -> Color(0xFFFDF0F0)
                        isSelected -> Color(0xFFEEF4FF)
                        else -> Color.White
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick  = { if (!isMcqDone && (!isRetryState || !isFirstWrong)) onMCQSelect(i) },
                        colors   = CardDefaults.cardColors(containerColor = containerColor),
                        border   = BorderStroke(borderThickness, borderColor),
                        shape    = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isMcqDone && isCorrect) Color(0xFF27AE60) else Color(0xFFF2F4F7),
                                    contentColor = if (isMcqDone && isCorrect) Color.White else Color(0xFF7F8C8D),
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = labels.getOrElse(i) { "$i" },
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                }
                                Text(
                                    text = opt,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF2C3E50),
                                    modifier = Modifier.weight(1f)
                                )
                                if (isMcqDone && isCorrect) {
                                    Text("✓", color = Color(0xFF27AE60), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                }
                                if ((isMcqDone || isRetryState) && (isSelected || isFirstWrong) && !isCorrect) {
                                    Text("✗", color = Color(0xFFE74C3C), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                }
                            }
                            
                            // Distractor explanation if incorrect and checked
                            if (isRetryState && isFirstWrong && question.optionExplanations.getOrNull(i)?.isNotBlank() == true) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = question.optionExplanations[i],
                                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                                    color = Color(0xFFC0392B),
                                    modifier = Modifier.padding(start = 40.dp)
                                )
                            }
                        }
                    }
                }

                if (isRetryState) {
                    Surface(
                        color = Color(0xFFFFF3CD),
                        border = BorderStroke(1.dp, Color(0xFFFFE0B2)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "❌ Incorrect — try again! Half marks if correct on retry.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF856404)
                        )
                    }
                }

                // Show Explanation Box once checked
                if (isMcqDone) {
                    val correctedOnRetry = firstWrong != null && activeSelected == question.correctIndex
                    val explanationTitle = when {
                        correctedOnRetry -> "✅ Correct on retry! (½ marks)"
                        activeSelected == question.correctIndex -> "🎉 Correct!"
                        else -> "❌ Not quite"
                    }
                    val explanationBg = if (activeSelected == question.correctIndex) Color(0xFFE8F8F0) else Color(0xFFFDEDEC)
                    val explanationBorder = if (activeSelected == question.correctIndex) Color(0xFFD4EFDF) else Color(0xFFFADBD8)
                    val explanationColor = if (activeSelected == question.correctIndex) Color(0xFF1A7A4A) else Color(0xFFC0392B)

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = explanationBg,
                        border = BorderStroke(1.dp, explanationBorder)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = explanationTitle,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = explanationColor
                            )
                            val specificExpl = question.optionExplanations.getOrNull(activeSelected ?: -1)
                            if (specificExpl?.isNotBlank() == true) {
                                Text(
                                    text = specificExpl,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF2C3E50)
                                )
                            }
                            if (question.explanation.isNotBlank()) {
                                Text(
                                    text = "💡 ${question.explanation}",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                    color = Color(0xFF7F8C8D)
                                )
                            }
                        }
                    }
                }
            } else {
                // Descriptive descriptive options
                val savedAnswerResult = answered ?: validationResult

                if (!isAnswered) {
                    // Mode Switcher Tab
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("type" to "⌨️ Type", "draw" to "✍️ Canvas", "camera" to "📷 Camera").forEach { (mode, label) ->
                            val active = answerMode == mode
                            Button(
                                onClick = {
                                    answerMode = mode
                                    if (mode == "draw") showCanvasDialog = true
                                    if (mode == "camera") cameraLauncher.launch(null)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (active) Color(0xFF2C3E50) else Color(0xFFECF0F1),
                                    contentColor = if (active) Color.White else Color(0xFF555555)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, if (active) Color(0xFF2C3E50) else Color(0xFFDDD)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(label, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                            }
                        }
                    }
                    
                    if (answerMode == "draw" && !validating) {
                        Button(
                            onClick = { showCanvasDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF3E5F5), contentColor = Color(0xFF8E44AD)),
                            border = BorderStroke(1.5.dp, Color(0xFF8E44AD)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text("✍️ Open Canvas", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                        }
                    }

                    if (answerMode == "camera" && !validating) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (cameraPhotoBitmap != null) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().border(2.dp, Color(0xFF1A6FA8), RoundedCornerShape(14.dp)),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Column {
                                        Image(
                                            bitmap = cameraPhotoBitmap!!.asImageBitmap(),
                                            contentDescription = "Capture Preview",
                                            modifier = Modifier.fillMaxWidth().aspectRatio(4f/3f)
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth().background(Color(0xFFEBF5FB)).padding(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    cameraPhotoBitmap = null
                                                    cameraLauncher.launch(null)
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("🔄 Retake")
                                            }
                                            Button(
                                                onClick = {
                                                    onCheckHandwritten(cameraPhotoBase64, "[camera]")
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A6FA8)),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("✓ Submit Answer")
                                            }
                                        }
                                    }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                                    Button(
                                        onClick = { cameraLauncher.launch(null) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEBF5FB), contentColor = Color(0xFF1A6FA8)),
                                        border = BorderStroke(1.5.dp, Color(0xFF1A6FA8)),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f).height(56.dp)
                                    ) {
                                        Text("📷 Camera Photo", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                                    }
                                    Button(
                                        onClick = { galleryLauncher.launch("image/*") },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEEF9F8), contentColor = Color(0xFF16A085)),
                                        border = BorderStroke(1.5.dp, Color(0xFF16A085)),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f).height(56.dp)
                                    ) {
                                        Text("🖼 Choose Gallery", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                                    }
                                }
                            }
                        }
                    }

                    if (validating) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = if (answerMode == "camera") Color(0xFF1A6FA8) else Color(0xFF8E44AD))
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = if (answerMode == "camera") "Evaluating your photo..." else "Reading your handwriting...",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (answerMode == "camera") Color(0xFF1A6FA8) else Color(0xFF8E44AD)
                                )
                            }
                        }
                    }
                }

                // Render Input Text Field if keyboard mode
                if (!isAnswered && answerMode == "type") {
                    Button(
                        onClick = { onCheckText(textValue.text) },
                        enabled = textValue.text.isNotBlank() && !validating,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        if (validating) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                        } else {
                            Text("Check Answer ✓", fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    OutlinedTextField(
                        value         = textValue,
                        onValueChange = { textValue = it },
                        modifier      = Modifier.fillMaxWidth(),
                        label         = { Text("Your answer") },
                        minLines      = 3,
                        enabled       = !validating,
                        placeholder   = {
                            Text(
                                text = when (question.type) {
                                    "fill_blank" -> "Write the complete sentence with blanks filled in..."
                                    "jumbled" -> "Type the sentence in the correct order..."
                                    "translation" -> "Write your translation here..."
                                    "error_correction" -> "Write the corrected sentence..."
                                    else -> "Write your answer here..."
                                }
                            )
                        }
                    )
                }

                // Render saved / validated result display
                if (isAnswered) {
                    val finalAnsText = restoredText.ifBlank { textValue.text }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFF8F9FA),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Your Answer:",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF7F8C8D)
                            )
                            
                            @Suppress("UNCHECKED_CAST")
                            val corrections = savedAnswerResult?.get("corrections") as? List<Map<String, String>> ?: emptyList()
                            if (finalAnsText == "[handwritten]") {
                                Text("✍️ Handwritten answer submitted (transcribed below)", fontStyle = FontStyle.Italic, color = Color(0xFF8E44AD))
                            } else if (finalAnsText == "[camera]") {
                                Text("📷 Photo answer submitted (transcribed below)", fontStyle = FontStyle.Italic, color = Color(0xFF1A6FA8))
                            } else if (corrections.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "✏️ Spelling / grammar mistakes highlighted:",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color(0xFFC0392B)
                                    )
                                    // Highlight mistakes inline using simple bullet list
                                    corrections.forEach { c ->
                                        val orig = c["original"] ?: ""
                                        val corr = c["corrected"] ?: ""
                                        val type = c["type"] ?: "spelling"
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = orig,
                                                style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.LineThrough),
                                                color = Color(0xFFE74C3C)
                                            )
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = "corrected to",
                                                tint = Color(0xFF7F8C8D),
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Text(
                                                text = corr,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = Color(0xFF27AE60)
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (type == "spelling") Color(0xFFEBF5FB) else Color(0xFFF3E5F5),
                                                contentColor = if (type == "spelling") Color(0xFF2980B9) else Color(0xFF8E44AD)
                                            ) {
                                                Text(
                                                    text = type.uppercase(),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = finalAnsText,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Color(0xFF2C3E50)
                                )
                            }
                        }
                    }

                    // AI Verdict Banner
                    if (savedAnswerResult != null) {
                        val verdict = savedAnswerResult["verdict"] as? String ?: "wrong"
                        val feedback = savedAnswerResult["feedback"] as? String ?: ""
                        
                        val verdictLabel = when (verdict) {
                            "correct" -> "Correct!"
                            "partial" -> "Partially Correct"
                            else -> "Not quite"
                        }
                        val verdictBg = when (verdict) {
                            "correct" -> Color(0xFFE8F8F0)
                            "partial" -> Color(0xFFFEF9E7)
                            else -> Color(0xFFFDEDEC)
                        }
                        val verdictBorder = when (verdict) {
                            "correct" -> Color(0xFFD4EFDF)
                            "partial" -> Color(0xFFFEF0E0)
                            else -> Color(0xFFFADBD8)
                        }
                        val verdictColor = when (verdict) {
                            "correct" -> Color(0xFF1A7A4A)
                            "partial" -> Color(0xFFE67E22)
                            else -> Color(0xFFC0392B)
                        }
                        val emoji = when (verdict) {
                            "correct" -> "🎉"
                            "partial" -> "🟡"
                            else -> "💪"
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = verdictBg,
                            border = BorderStroke(1.dp, verdictBorder)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "$emoji $verdictLabel",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = verdictColor
                                )
                                Text(
                                    text = feedback,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = verdictColor
                                )
                            }
                        }
                    }

                    // Retry button (One-time, descriptive question, > 2 marks, verdict != correct, not yet used)
                    val verdict = savedAnswerResult?.get("verdict") as? String ?: "wrong"
                    if (!retryUsed && marks > 2 && verdict != "correct") {
                        Button(
                            onClick = { onRetryText() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFF3CD), contentColor = Color(0xFF856404)),
                            border = BorderStroke(1.5.dp, Color(0xFFF0AD4E)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("🔄  Try Again (one more chance!)", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                    if (retryUsed) {
                        Text(
                            text = "🔄 Retry was used on this question",
                            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                            color = Color(0xFF856404),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }

                    // Correct model answer (Always shown after checked)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFF0F4FF),
                        border = BorderStroke(1.dp, Color(0xFFD0E0FF)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "✅ Correct Answer:",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF2980B9)
                            )
                            Text(
                                text = question.answer,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color(0xFF2C3E50)
                            )
                            if (question.explanation.isNotBlank()) {
                                Text(
                                    text = "Explanation: ${question.explanation}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                                    color = Color(0xFF7F8C8D)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Navigation Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 2.dp,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Prev Button
                OutlinedButton(
                    onClick = onPrev,
                    enabled = index > 0 && !validating,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.width(100.dp)
                ) {
                    Text("← Prev")
                }

                // Counter
                Text(
                    text = "${index + 1} / $total",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Next / Finish Button
                val nextLabel = when {
                    index == total - 1 -> "Finish 🎊"
                    isAnswered -> "Next →"
                    else -> "Skip →"
                }

                Button(
                    onClick = {
                        val currentTyped = textValue.text
                        onNext(currentTyped)
                    },
                    enabled = !validating,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.width(100.dp)
                ) {
                    Text(nextLabel)
                }
            }
        }
    }

    // Fullscreen Drawing Dialog
    if (showCanvasDialog) {
        DrawingCanvasDialog(
            questionText = question.question,
            onDismiss = { showCanvasDialog = false },
            onSubmit = { b64 ->
                showCanvasDialog = false
                onCheckHandwritten(b64, "[handwritten]")
            }
        )
    }
}

@Composable
private fun SummaryView(summary: String, scoreLabel: String, onFinish: () -> Unit) {
    Column(
        modifier            = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Assessment Complete!",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        Text(scoreLabel, style = MaterialTheme.typography.titleMedium)

        if (summary.isNotBlank()) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Feedback", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        } else {
            CircularProgressIndicator()
            Text(
                text = "Generating feedback.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DrawingCanvasDialog(
    questionText: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit // returns base64 PNG
) {
    val paths = remember { mutableStateListOf<Path>() }
    var currentPath by remember { mutableStateOf<Path?>(null) }
    
    val backgroundPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#FDFCF0")
            style = android.graphics.Paint.Style.FILL
        }
    }
    
    val linePaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#1A1A2E")
            strokeWidth = 8f
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
    }
    
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF1A1A2E)) {
            Column(Modifier.fillMaxSize()) {
                // Header
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = renderMarkdown(questionText),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                }
                
                // Canvas box
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFFFDFCF0))
                        .onSizeChanged { size = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val path = Path().apply { moveTo(offset.x, offset.y) }
                                    currentPath = path
                                    paths.add(path)
                                },
                                onDragEnd = { currentPath = null },
                                onDragCancel = { currentPath = null },
                                onDrag = { change, _ ->
                                    change.consume()
                                    currentPath?.lineTo(change.position.x, change.position.y)
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        paths.forEach { path ->
                            drawPath(
                                path = path,
                                color = Color(0xFF1A1A2E),
                                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }
                }
                
                // Footer controls
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = { paths.clear() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34495E)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🗑 Clear", color = Color.White)
                    }
                    Button(
                        onClick = {
                            val w = size.width.coerceAtLeast(1)
                            val h = size.height.coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(bmp)
                            
                            // Fill background
                            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), backgroundPaint)
                            
                            // Render paths
                            paths.forEach { composePath ->
                                canvas.drawPath(composePath.asAndroidPath(), linePaint)
                            }
                            
                            val stream = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.PNG, 90, stream)
                            val byteArr = stream.toByteArray()
                            val b64 = Base64.encodeToString(byteArr, Base64.NO_WRAP)
                            onSubmit(b64)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27AE60)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("✓ Submit Answer", color = Color.White)
                    }
                }
            }
        }
    }
}

private fun renderMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        if (text.startsWith("**", i)) {
            val end = text.indexOf("**", i + 2)
            if (end != -1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(text.substring(i + 2, end))
                }
                i = end + 2
                continue
            }
        } else if (text.startsWith("*", i)) {
            val end = text.indexOf("*", i + 1)
            if (end != -1) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        append(text[i])
        i++
    }
}
