package com.krishanagarwal.mynoo.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.krishanagarwal.mynoo.data.repository.Assessment
import com.krishanagarwal.mynoo.data.repository.AssessmentQuestion
import com.krishanagarwal.mynoo.ui.viewmodel.AssessmentViewModel

private val SUBJECTS = listOf(
    "Hindi", "English", "Punjabi", "Mathematics", "Science", "Social Studies", "Computer"
)

private fun getSubjectColor(subject: String, lang: String): Color {
    val slug = subject.lowercase().trim().replace(Regex("\\s+"), "_")
    return when (slug) {
        "hindi" -> Color(0xFFE67E22) // Orange
        "english" -> Color(0xFF27AE60) // Green
        "punjabi" -> Color(0xFF8E44AD) // Purple
        "mathematics", "math" -> Color(0xFF2980B9) // Blue
        "science" -> Color(0xFF16A085) // Teal
        "social_studies", "social studies" -> Color(0xFFC0392B) // Red
        "computer" -> Color(0xFF7F8C8D) // Grey
        else -> {
            when (lang.lowercase()) {
                "hi" -> Color(0xFFE67E22)
                "pa" -> Color(0xFF8E44AD)
                else -> Color(0xFF2980B9)
            }
        }
    }
}

private fun getSubjectTitle(subject: String, lang: String): String {
    val s = subject.ifBlank {
        when (lang.lowercase()) {
            "hi" -> "Hindi"
            "pa" -> "Punjabi"
            else -> "English"
        }
    }
    val emoji = when (s.lowercase().trim()) {
        "hindi" -> "🇮🇳 Hindi"
        "english" -> "🇬🇧 English"
        "punjabi" -> "✍️ Punjabi"
        "mathematics", "math" -> "📐 Mathematics"
        "science" -> "🔬 Science"
        "social studies", "social_studies" -> "🌍 Social Studies"
        "computer" -> "💻 Computer"
        else -> "📝 $s"
    }
    return emoji
}

private fun formatDate(isoString: String): String {
    if (isoString.isBlank()) return ""
    return try {
        val instant = java.time.Instant.parse(isoString)
        val formatter = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)
            .withZone(java.time.ZoneId.systemDefault())
        formatter.format(instant)
    } catch (_: Exception) {
        try {
            if (isoString.length >= 10 && isoString[4] == '-' && isoString[7] == '-') {
                val parts = isoString.take(10).split("-")
                val year = parts[0]
                val monthNum = parts[1].toIntOrNull() ?: 1
                val day = parts[2].toIntOrNull() ?: 1
                val months = listOf("", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                val monthStr = months.getOrNull(monthNum) ?: "Jan"
                "$day $monthStr $year"
            } else {
                isoString
            }
        } catch (_: Exception) {
            isoString
        }
    }
}

private fun copyDetail(context: Context, a: Assessment) {
    val qs = a.questions
    val ans = a.answers
    val date = formatDate(if (a.completedAt.isNotBlank()) a.completedAt else a.createdAt)
    val lines = mutableListOf<String>()
    lines.add("## Assessment: ${a.subject} · Class ${a.classNum} · $date · ${a.score?.toInt() ?: "?"}%")
    lines.add("Total questions: ${qs.size}")
    lines.add("")
    lines.add("### Per-question detail")
    
    qs.forEachIndexed { i, q ->
        val childAns = ans.getOrNull(i)
        var result = "unanswered"
        var givenStr = "—"
        if (childAns != null) {
            val ansType = childAns["type"] as? String ?: ""
            if (ansType == "mcq") {
                val selectedIndex = (childAns["selectedIndex"] as? Number)?.toInt()
                val chosen = if (selectedIndex != null) q.options.getOrNull(selectedIndex) ?: "opt$selectedIndex" else "—"
                givenStr = chosen
                val correct = childAns["correct"] as? Boolean ?: false
                val attempts = (childAns["attempts"] as? Number)?.toInt() ?: 1
                result = if (correct) {
                    if (attempts == 1) "correct" else "correct(2nd try)"
                } else {
                    "wrong"
                }
            } else {
                givenStr = (childAns["textAnswer"] as? String)?.trim() ?: "—"
                val selfGrade = childAns["selfGrade"] as? String ?: ""
                result = when (selfGrade) {
                    "got_it" -> "correct"
                    "partial" -> "partial"
                    else -> "wrong"
                }
            }
        }
        val retryTag = if (childAns?.get("retryUsed") == true) " [retry used]" else ""
        val correctStr = if (q.type == "mcq") {
            q.options.getOrNull(q.correctIndex) ?: "—"
        } else {
            q.answer
        }
        val qText = if (q.inputSentence.isNotBlank()) "${q.question} — \"${q.inputSentence}\"" else q.question
        lines.add("Q${i + 1} [${q.type}|${q.marks}m|$result$retryTag] $qText")
        lines.add("  ✓ $correctStr")
        if (result != "correct") {
            lines.add("  ✗ $givenStr")
        }
    }
    
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText("Assessment Detail", lines.joinToString("\n"))
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Detail copied!", Toast.LENGTH_SHORT).show()
}

private fun copySummary(context: Context, a: Assessment) {
    val date = formatDate(if (a.completedAt.isNotBlank()) a.completedAt else a.createdAt)
    val lines = mutableListOf<String>()
    lines.add("📋 Assessment Summary — ${a.subject} (Class ${a.classNum})")
    lines.add("Date: $date")
    a.score?.let { lines.add("Overall Score: ${it.toInt()}%") }
    lines.add("")
    if (a.summary.isNotBlank()) {
        lines.add(a.summary)
    }
    
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText("Assessment Summary", lines.joinToString("\n"))
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Summary copied!", Toast.LENGTH_SHORT).show()
}

private fun getProgressLine(assessment: Assessment): String? {
    if (assessment.status != "in_progress" || assessment.questions.isEmpty()) return null
    val answers = assessment.answers
    val questions = assessment.questions
    val answered = answers.count { it != null && it.isNotEmpty() }
    val total = questions.size

    var earned = 0.0
    var answeredMarks = 0

    answers.forEachIndexed { i, ansMap ->
        if (ansMap == null || ansMap.isEmpty()) return@forEachIndexed
        val q = questions.getOrNull(i) ?: return@forEachIndexed
        val marks = q.marks
        val halfMarks = marks / 2.0
        answeredMarks += marks.toInt()

        val ansType = ansMap["type"] as? String ?: ""
        if (ansType == "mcq") {
            val correct = ansMap["correct"] as? Boolean ?: false
            val attempts = (ansMap["attempts"] as? Number)?.toInt() ?: 1
            if (correct) {
                earned += if (attempts == 2) halfMarks else marks
            }
        } else {
            val aiEarned = (ansMap["aiEarnedMarks"] as? Number)?.toDouble()
            if (aiEarned != null) {
                earned += aiEarned
            } else {
                val selfGrade = ansMap["selfGrade"] as? String ?: ""
                if (selfGrade == "got_it") {
                    earned += marks
                } else if (selfGrade == "partial") {
                    earned += halfMarks
                }
            }
        }
    }
    
    val earnedStr = if (earned % 1.0 == 0.0) earned.toInt().toString() else earned.toString()
    return "$answered / $total answered  ·  $earnedStr / $answeredMarks marks"
}

@Composable
fun AssessmentListScreen(
    lang:                   String,
    childName:              String,
    subject:                String,
    onStartAssessment:      (assessmentId: String, childName: String) -> Unit,
    onNavigateToAssessment: (assessmentId: String, childName: String) -> Unit,
    onBackClick:            () -> Unit,
    vm:                     AssessmentViewModel = hiltViewModel(),
) {
    val listState by vm.list.collectAsState()
    val quizState by vm.quiz.collectAsState()

    var selectedSubject by remember { mutableStateOf(subject.ifBlank { "English" }) }
    var detailAssessment by remember { mutableStateOf<Assessment?>(null) }
    val themeColor = getSubjectColor(selectedSubject, lang)

    LaunchedEffect(childName) { if (childName.isNotBlank()) vm.loadAssessments(childName) }

    // Navigate when assessment is generated
    LaunchedEffect(quizState.assessment) {
        val a = quizState.assessment
        if (a != null && a.id.isNotBlank() && !quizState.generating) {
            onStartAssessment(a.id, childName)
        }
    }

    Scaffold(
        topBar = {
            Surface(
                color = themeColor,
                contentColor = Color.White,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "Back",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier
                            .clickable { onBackClick() }
                            .padding(end = 12.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getSubjectTitle(selectedSubject, lang),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                        Text(
                            text = childName,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        },
        containerColor = Color(0xFFFFFDF9) // Warm cream background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))

            if (subject.isBlank()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SUBJECTS) { subj ->
                        FilterChip(
                            selected = selectedSubject == subj,
                            onClick = { selectedSubject = subj },
                            label = { Text(subj) }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (quizState.generating) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = themeColor)
                    Text("Generating $selectedSubject questions.")
                }
            }

            val filteredAssessments = remember(listState.assessments, selectedSubject) {
                listState.assessments.filter { it.subject.equals(selectedSubject, ignoreCase = true) }
            }

            val inProgress = remember(filteredAssessments) {
                filteredAssessments.filter { it.status == "in_progress" }
            }
            val ready = remember(filteredAssessments) {
                filteredAssessments.filter { it.status == "ready" }
            }
            val completed = remember(filteredAssessments) {
                filteredAssessments.filter { it.status == "completed" }
            }

            when {
                listState.loading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = themeColor)
                }
                filteredAssessments.isEmpty() -> Text("No assessments yet. Tap + to start one.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    if (inProgress.isNotEmpty()) {
                        item { SectionHeader(title = "▶ In Progress", color = themeColor) }
                        items(inProgress, key = { it.id }) { a ->
                            AssessmentCard(a, themeColor, onNavigate = { _, _ -> detailAssessment = a }, childName)
                        }
                    }

                    if (ready.isNotEmpty()) {
                        item { SectionHeader(title = "🆕 New Tests", color = themeColor) }
                        items(ready, key = { it.id }) { a ->
                            AssessmentCard(a, themeColor, onNavigate = { id, name -> onNavigateToAssessment(id, name) }, childName)
                        }
                    }

                    if (completed.isNotEmpty()) {
                        item { SectionHeader(title = "✓ Completed", color = Color(0xFF7F8C8D)) }
                        items(completed, key = { it.id }) { a ->
                            AssessmentCard(a, themeColor, onNavigate = { _, _ -> detailAssessment = a }, childName)
                        }
                    }
                }
            }

            val currentDetail = detailAssessment
            if (currentDetail != null) {
                AssessmentDetailDialog(
                    a = currentDetail,
                    themeColor = themeColor,
                    onDismiss = { detailAssessment = null },
                    onResume = {
                        onNavigateToAssessment(currentDetail.id, childName)
                    }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, color: Color) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = color,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun AssessmentCard(
    a: Assessment,
    themeColor: Color,
    onNavigate: (assessmentId: String, childName: String) -> Unit,
    childName: String
) {
    val context = LocalContext.current
    val pct = a.score?.toInt()
    val scoreColour = when {
        pct == null -> Color(0xFF999999)
        pct >= 70 -> Color(0xFF27AE60)
        pct >= 40 -> Color(0xFFE67E22)
        else -> Color(0xFFE74C3C)
    }

    val progressLine = getProgressLine(a)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigate(a.id, childName) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(
            width = if (a.status == "in_progress") 2.dp else 1.dp,
            color = if (a.status == "in_progress") themeColor else Color(0xFFE2E8F0)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = a.subject,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFF2C3E50)
                )

                // Chapter titles / description
                val chapterDesc = if (a.title.isNotBlank()) a.title else a.chapterTitles.joinToString("  ·  ")
                if (chapterDesc.isNotBlank()) {
                    Text(
                        text = chapterDesc,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        ),
                        color = Color(0xFF7F8C8D),
                        maxLines = 2,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Text(
                    text = "Class ${a.classNum}  ·  ${formatDate(if (a.createdAt.isNotBlank()) a.createdAt else a.date)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF95A5A6),
                    modifier = Modifier.padding(top = 4.dp)
                )

                if (progressLine != null) {
                    Text(
                        text = progressLine,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = themeColor,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Right Action Column/Row
            if (pct != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (a.status == "completed") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "🔍",
                                fontSize = 18.sp,
                                modifier = Modifier
                                    .clickable { copyDetail(context, a) }
                                    .padding(4.dp)
                            )
                            if (a.summary.isNotBlank()) {
                                Text(
                                    text = "📋",
                                    fontSize = 18.sp,
                                    modifier = Modifier
                                        .clickable { copySummary(context, a) }
                                        .padding(4.dp)
                                )
                            }
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "$pct%",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                            color = scoreColour
                        )
                        Text(
                            text = if (a.status == "in_progress") "Resume" else "View →",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = themeColor
                        )
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (a.status == "in_progress") "▶ Resume" else "Start →",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = themeColor
                    )
                    if (a.status == "in_progress") {
                        Text(
                            text = "👁 answers",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF95A5A6),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssessmentDetailDialog(
    a: Assessment,
    themeColor: Color,
    onDismiss: () -> Unit,
    onResume: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFFFFDF9)
        ) {
            Scaffold(
                topBar = {
                    Surface(
                        color = themeColor,
                        contentColor = Color.White,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .statusBarsPadding()
                                .height(56.dp)
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val displayTitle = if (a.title.isNotBlank()) a.title else a.subject
                                Text(
                                    text = displayTitle,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                val subtitle = if (a.title.isNotBlank()) "${a.subject} · Class ${a.classNum}" else "Class ${a.classNum}"
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }
                            Text(
                                text = "✕ Close",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                modifier = Modifier
                                    .clickable { onDismiss() }
                                    .padding(8.dp)
                            )
                        }
                    }
                },
                containerColor = Color(0xFFFFFDF9)
            ) { innerPadding ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Score/Status Banner
                    item {
                        val isCompleted = a.status == "completed"
                        val bannerBg = if (isCompleted) Color(0xFFF0F4FF) else Color(0xFFFFF8EE)
                        val formattedDate = formatDate(if (a.completedAt.isNotBlank()) a.completedAt else a.createdAt)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = bannerBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = if (isCompleted) "Completed · $formattedDate" else "▶ In Progress · Created: $formattedDate",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF555555)
                                )
                                if (isCompleted && a.score != null) {
                                    val pct = a.score.toInt()
                                    val scoreColor = when {
                                        pct >= 70 -> Color(0xFF27AE60)
                                        pct >= 40 -> Color(0xFFE67E22)
                                        else -> Color(0xFFE74C3C)
                                    }
                                    Text(
                                        text = "$pct%",
                                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
                                        color = scoreColor,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                } else if (!isCompleted) {
                                    val answered = a.answers.count { it != null && it.isNotEmpty() }
                                    val total = a.questions.size
                                    Text(
                                        text = "$answered / $total answered",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color(0xFFE67E22),
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 2. Resume Assessment Button (if in progress)
                    if (a.status == "in_progress") {
                        item {
                            Button(
                                onClick = {
                                    onDismiss()
                                    onResume()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE67E22)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text(
                                    text = "▶ Resume Assessment",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                            }
                        }
                    }

                    // 3. AI Feedback Summary (if completed and summary is present)
                    if (a.status == "completed" && a.summary.isNotBlank()) {
                        item {
                            Column {
                                Text(
                                    text = "📊 Summary",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF2C3E50),
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFFEEF9F8),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = a.summary,
                                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                        color = Color(0xFF2C3E50),
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 4. Questions Header
                    item {
                        val answeredCount = if (a.status == "in_progress") {
                            a.answers.count { it != null && it.isNotEmpty() }
                        } else {
                            a.questions.size
                        }
                        Text(
                            text = "📋 Questions ($answeredCount)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF2C3E50),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    // 5. Questions list
                    val answeredList = a.questions.mapIndexed { idx, q ->
                        val ans = a.answers.find { it != null && it["id"] == q.id } ?: a.answers.getOrNull(idx)
                        Triple(q, ans, idx)
                    }.filter { (_, ans, _) ->
                        if (a.status == "in_progress") {
                            ans != null && ans.isNotEmpty()
                        } else {
                            true
                        }
                    }

                    items(answeredList) { (q, ans, idx) ->
                        QuestionDetailCard(q, ans, idx)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionDetailCard(
    q: AssessmentQuestion,
    ans: Map<String, Any>?,
    index: Int
) {
    val answered = ans != null && ans.isNotEmpty()
    val isMCQ = q.type == "mcq"

    var result = "unanswered"
    if (answered) {
        if (isMCQ) {
            val correct = ans["correct"] as? Boolean ?: false
            result = if (correct) "correct" else "wrong"
        } else {
            val selfGrade = ans["selfGrade"] as? String ?: ""
            result = when (selfGrade) {
                "got_it" -> "correct"
                "partial" -> "partial"
                else -> "wrong"
            }
        }
    }

    val cardBg = when (result) {
        "correct" -> Color(0xFFF0FBF5)
        "partial" -> Color(0xFFFFF8EE)
        "wrong" -> Color(0xFFFDF0F0)
        else -> Color(0xFFF8F9FA)
    }

    val borderColor = when (result) {
        "correct" -> Color(0xFF27AE60)
        "partial" -> Color(0xFFE67E22)
        "wrong" -> Color(0xFFE74C3C)
        else -> Color(0xFFE2E8F0)
    }

    val badgeColor = when (result) {
        "correct" -> Color(0xFF27AE60)
        "partial" -> Color(0xFFE67E22)
        else -> Color(0xFFE74C3C)
    }

    val badgeText = when (result) {
        "correct" -> "✓"
        "partial" -> "½"
        "wrong" -> "✗"
        else -> ""
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
        ) {
            Surface(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight(),
                color = borderColor
            ) {}

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val infoText = buildString {
                        append("Q${index + 1}")
                        if (q.category.isNotBlank()) append(" · ${q.category}")
                        if (q.difficulty.isNotBlank()) append(" · ${q.difficulty}")
                        append(" · ${q.marks.toInt()}m")
                    }
                    Text(
                        text = infoText,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF2980B9)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (ans?.get("retryUsed") == true) {
                            Text(
                                text = "🔄 retry",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                ),
                                color = Color(0xFF856404)
                            )
                        }
                        if (badgeText.isNotBlank()) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                color = badgeColor
                            )
                        }
                    }
                }

                if (q.inputSentence.isNotBlank()) {
                    Text(
                        text = "\"${q.inputSentence}\"",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        ),
                        color = Color(0xFF666666)
                    )
                }

                Text(
                    text = q.question,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 19.sp),
                    color = Color(0xFF2C3E50)
                )

                if (isMCQ) {
                    val selectedIndex = (ans?.get("selectedIndex") as? Number)?.toInt() ?: -1
                    q.options.forEachIndexed { oi, opt ->
                        val isCorrect = oi == q.correctIndex
                        val isSelected = oi == selectedIndex
                        val optColor = when {
                            isCorrect -> Color(0xFF1A7A4A)
                            isSelected && !isCorrect -> Color(0xFFC0392B)
                            else -> Color(0xFF555555)
                        }
                        val optWeight = if (isCorrect || (isSelected && !isCorrect)) FontWeight.Bold else FontWeight.Normal
                        val suffix = when {
                            isCorrect -> " ✓"
                            isSelected && !isCorrect -> " ✗"
                            else -> ""
                        }
                        Text(
                            text = "${(65 + oi).toChar()}. $opt$suffix",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = optWeight,
                                color = optColor
                            ),
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                        )
                    }
                } else {
                    if (answered) {
                        val textAns = (ans?.get("textAnswer") as? String)?.trim() ?: ""
                        if (textAns.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.Black.copy(alpha = 0.04f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = "Child's answer:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF888888)
                                    )
                                    Text(
                                        text = textAns,
                                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 19.sp),
                                        color = Color(0xFF2C3E50),
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }

                        val correctedAns = (ans?.get("correctedAnswer") as? String)?.trim() ?: ""
                        if (correctedAns.isNotBlank() && !correctedAns.equals(textAns, ignoreCase = true)) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFFEBF5FB),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = "Corrected:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF2471A3)
                                    )
                                    Text(
                                        text = correctedAns,
                                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 19.sp),
                                        color = Color(0xFF1A5276),
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (q.answer.isNotBlank()) {
                        Row(
                            modifier = Modifier.padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Correct: ",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF27AE60)
                            )
                            Text(
                                text = q.answer,
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                color = Color(0xFF27AE60)
                            )
                        }
                    }
                }

                if (result == "partial") {
                    val aiEarned = ans?.get("aiEarnedMarks") as? Number
                    val earnedStr = if (aiEarned != null) {
                        if (aiEarned.toDouble() % 1.0 == 0.0) aiEarned.toInt().toString() else aiEarned.toString()
                    } else {
                        "½"
                    }
                    Text(
                        text = "$earnedStr / ${q.marks.toInt()} marks",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFE67E22),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                if (answered && q.explanation.isNotBlank()) {
                    Text(
                        text = "💡 ${q.explanation}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        ),
                        color = Color(0xFF7F6A00),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
