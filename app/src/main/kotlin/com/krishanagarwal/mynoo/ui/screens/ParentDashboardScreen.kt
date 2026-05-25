package com.krishanagarwal.mynoo.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.krishanagarwal.mynoo.data.model.*
import com.krishanagarwal.mynoo.ui.viewmodel.AssessmentViewModel
import com.krishanagarwal.mynoo.ui.viewmodel.ParentSettingsViewModel
import com.krishanagarwal.mynoo.ui.viewmodel.ChildViewModel
import com.krishanagarwal.mynoo.ui.viewmodel.ChapterManagementViewModel
import com.krishanagarwal.mynoo.ui.viewmodel.ChapterUiItem
import com.krishanagarwal.mynoo.ui.viewmodel.SaveStatus
import com.krishanagarwal.mynoo.ui.viewmodel.AudioJobState
import com.krishanagarwal.mynoo.data.repository.Assessment
import com.krishanagarwal.mynoo.data.repository.AssessmentQuestion

data class CategoryScore(
    val category: String,
    val score: Int,
    val earnedMarks: Double,
    val totalMarks: Double
)

data class StructuredSummary(
    val encouragement: String,
    val recommendations: String,
    val strengths: List<String>,
    val weaknesses: List<String>,
    val categoryBreakdown: List<CategoryScore>
)

private fun parseSummary(raw: String): StructuredSummary? {
    if (raw.isBlank()) return null
    return try {
        val json = org.json.JSONObject(raw)
        val encouragement = json.optString("encouragement", "")
        val recommendations = json.optString("recommendations", "")

        val strengthsList = mutableListOf<String>()
        val strengths = json.optJSONArray("strengths")
        if (strengths != null) {
            for (i in 0 until strengths.length()) {
                strengthsList.add(strengths.getString(i))
            }
        }

        val weaknessesList = mutableListOf<String>()
        val weaknesses = json.optJSONArray("weaknesses")
        if (weaknesses != null) {
            for (i in 0 until weaknesses.length()) {
                weaknessesList.add(weaknesses.getString(i))
            }
        }

        val breakdownList = mutableListOf<CategoryScore>()
        val categoryBreakdown = json.optJSONArray("categoryBreakdown")
        if (categoryBreakdown != null) {
            for (i in 0 until categoryBreakdown.length()) {
                val cat = categoryBreakdown.getJSONObject(i)
                breakdownList.add(
                    CategoryScore(
                        category = cat.optString("category", ""),
                        score = cat.optInt("score", 0),
                        earnedMarks = cat.optDouble("earnedMarks", 0.0),
                        totalMarks = cat.optDouble("totalMarks", 0.0)
                    )
                )
            }
        }

        StructuredSummary(
            encouragement = encouragement,
            recommendations = recommendations,
            strengths = strengthsList,
            weaknesses = weaknessesList,
            categoryBreakdown = breakdownList
        )
    } catch (_: Exception) {
        null
    }
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
                val year = parts[0]; val monthNum = parts[1].toIntOrNull() ?: 1; val day = parts[2].toIntOrNull() ?: 1
                val months = listOf("", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                "$day ${months.getOrNull(monthNum) ?: "Jan"} $year"
            } else isoString
        } catch (_: Exception) { isoString }
    }
}

@Composable
fun AnnotatedAnswer(text: String, corrections: List<*>?, modifier: Modifier = Modifier) {
    if (corrections.isNullOrEmpty()) {
        Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium)
        return
    }

    data class Match(val start: Int, val end: Int, val original: String, val corrected: String)
    val matches = mutableListOf<Match>()
    val lowerText = text.lowercase()
    for (item in corrections) {
        val c = item as? Map<*, *> ?: continue
        val orig = c["original"] as? String ?: ""
        val corr = c["corrected"] as? String ?: ""
        if (orig.isNotEmpty()) {
            val idx = lowerText.indexOf(orig.lowercase())
            if (idx != -1) {
                matches.add(Match(idx, idx + orig.length, text.substring(idx, idx + orig.length), corr))
            }
        }
    }

    matches.sortBy { it.start }
    val deduped = mutableListOf<Match>()
    var cursor = 0
    for (m in matches) {
        if (m.start >= cursor) {
            deduped.add(m)
            cursor = m.end
        }
    }

    val annotatedString = buildAnnotatedString {
        var pos = 0
        for (m in deduped) {
            if (m.start > pos) {
                append(text.substring(pos, m.start))
            }
            withStyle(style = SpanStyle(textDecoration = TextDecoration.LineThrough, color = Color(0xFFC0392B))) {
                append(m.original)
            }
            withStyle(style = SpanStyle(color = Color(0xFF27AE60), fontStyle = FontStyle.Italic)) {
                append(" → ${m.corrected}")
            }
            pos = m.end
        }
        if (pos < text.length) {
            append(text.substring(pos))
        }
    }

    Text(annotatedString, modifier = modifier, style = MaterialTheme.typography.bodyMedium)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentDashboardScreen(
    childState:             ChildState,
    onNavigateToProgress:   (childName: String, classNum: String) -> Unit,
    onNavigateToAssessment: (assessmentId: String, childName: String) -> Unit,
    assessmentVm:           AssessmentViewModel     = hiltViewModel(),
    settingsVm:             ParentSettingsViewModel = hiltViewModel(),
    childVm:                ChildViewModel          = hiltViewModel(),
    chapterVm:              ChapterManagementViewModel = hiltViewModel(),
) {
    val context    = LocalContext.current
    val settings   by settingsVm.settings.collectAsState()
    val isLoading  by settingsVm.isLoading.collectAsState()
    val saveStatus by settingsVm.saveStatus.collectAsState()
    val saveError  by settingsVm.saveError.collectAsState()
    val listState  by assessmentVm.list.collectAsState()
    val quizState  by assessmentVm.quiz.collectAsState()

    // Child management states
    val childUiState by childVm.ui.collectAsState()
    var selectedChild by remember { mutableStateOf<Child?>(null) }
    var showAddChildDialog by remember { mutableStateOf(false) }
    var newChildName by remember { mutableStateOf("") }
    var newChildAge by remember { mutableStateOf("") }
    var newChildClass by remember { mutableStateOf("7") }

    // Language progress/retest states
    val langLevels by assessmentVm.langLevels.collectAsState()
    val langExpertise by assessmentVm.langExpertise.collectAsState()
    val quizHistory by assessmentVm.quizHistory.collectAsState()
    val levelsAssessed by assessmentVm.levelsAssessed.collectAsState()
    var showRetestDialog by remember { mutableStateOf(false) }
    var retestEn by remember { mutableStateOf(true) }
    var retestHi by remember { mutableStateOf(true) }
    var retestPa by remember { mutableStateOf(true) }
    val retestSaving by assessmentVm.retestSaving.collectAsState()

    // Quiz Q&A dialog states
    var showQuizHistoryDialog by remember { mutableStateOf(false) }
    var quizHistoryLang by remember { mutableStateOf("en") }

    // Subject Assessment details/creation states
    var showCreateDialog by remember { mutableStateOf(false) }
    var createSubject    by remember { mutableStateOf("Hindi") }
    var createClass      by remember { mutableStateOf(selectedChild?.classNum?.ifBlank { "7" } ?: "7") }
    var createLang       by remember { mutableStateOf("hi") }
    var showAssessmentDetail by remember { mutableStateOf(false) }
    var selectedAssessment by remember { mutableStateOf<Assessment?>(null) }

    // Chapter Management states
    var chapMgmtClass by remember { mutableStateOf("7") }
    var chapMgmtSubject by remember { mutableStateOf("Hindi") }
    val chaptersList by chapterVm.chaptersList.collectAsState()
    val chaptersLoading by chapterVm.loading.collectAsState()
    val audioJobState by chapterVm.audioJobState.collectAsState()
    var showUploadChapterDialog by remember { mutableStateOf(false) }
    var chapterSubject by remember { mutableStateOf("Hindi") }
    var chapterClassNum by remember { mutableStateOf("7") }
    var chapterOrder by remember { mutableStateOf("1") }
    var chapterTitle by remember { mutableStateOf("") }
    var chapterJsonInput by remember { mutableStateOf("") }
    val uploadingChapter by chapterVm.uploading.collectAsState()
    val uploadChapterError by chapterVm.uploadError.collectAsState()
    var chapMgmtProvider by remember { mutableStateOf("google") }
    var showDeleteChapterConfirmId by remember { mutableStateOf<String?>(null) }
    var showDeleteChapterConfirmTitle by remember { mutableStateOf<String?>(null) }

    // Usage Analytics states
    var usagePeriod by remember { mutableStateOf(7) }
    val usageStatsState by assessmentVm.usageStats.collectAsState()
    val usageLoading by assessmentVm.usageLoading.collectAsState()

    // Global settings states
    var selectedFunction by remember { mutableStateOf(0) }
    var selectedLang     by remember { mutableStateOf(0) }
    var showPinDialog    by remember { mutableStateOf(false) }
    var showGlobalSettingsDialog by remember { mutableStateOf(false) }
    var selectedSubjectFilter by remember { mutableStateOf("All") }

    // File Picker for Upload Chapter
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                }
                if (content != null) {
                    chapterJsonInput = content
                    try {
                        val obj = org.json.JSONObject(content)
                        chapterTitle = obj.optString("title", "")
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error reading file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Set initial selectedChild when list is loaded
    LaunchedEffect(childUiState.children, childUiState.lastChild) {
        if (selectedChild == null && childUiState.children.isNotEmpty()) {
            val lastUsed = childUiState.children.find { it.name == childUiState.lastChild }
            selectedChild = lastUsed ?: childUiState.children.first()
        }
    }

    // Trigger data reload for the selected child
    LaunchedEffect(selectedChild?.name) {
        selectedChild?.name?.let { name ->
            assessmentVm.loadAssessments(name)
            assessmentVm.loadChildPlacementData(name)
            assessmentVm.loadUsageStats(name, periodDays = usagePeriod)
        }
    }

    // Reload usage stats when period changes
    LaunchedEffect(selectedChild?.name, usagePeriod) {
        selectedChild?.name?.let { name ->
            assessmentVm.loadUsageStats(name, periodDays = usagePeriod)
        }
    }

    LaunchedEffect(quizState.assessment) {
        val a = quizState.assessment
        if (a != null && a.id.isNotBlank() && !quizState.generating) showCreateDialog = false
    }

    fun currentFn(): FunctionConfig = when (selectedFunction) { 1 -> settings?.read ?: FunctionConfig(); 2 -> settings?.quiz ?: FunctionConfig(); else -> settings?.talk ?: FunctionConfig() }
    fun currentLangConfig(): LangConfig = when (selectedLang) { 1 -> currentFn().hi; 2 -> currentFn().pa; else -> currentFn().en }

    fun saveLangConfig(lc: LangConfig) {
        val s = settings ?: return
        val fn    = currentFn()
        val newFn = when (selectedLang) { 1 -> fn.copy(hi = lc); 2 -> fn.copy(pa = lc); else -> fn.copy(en = lc) }
        val newSettings = when (selectedFunction) { 1 -> s.copy(read = newFn); 2 -> s.copy(quiz = newFn); else -> s.copy(talk = newFn) }
        settingsVm.updateSettings(newSettings)
    }

    if (showGlobalSettingsDialog) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Header Row with Close icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Global Settings",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    // Save status indicators
                    when (saveStatus) {
                        SaveStatus.SAVING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        SaveStatus.SAVED  -> Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        SaveStatus.ERROR  -> Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        else              -> {}
                    }
                }
                IconButton(onClick = { showGlobalSettingsDialog = false }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close settings",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Text(
                text = "Configure parameters for text, reading, and quizzes across all languages.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // AI Settings Section
            Text(
                text = "AI Settings",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )

            if (isLoading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
            } else {
                TabRow(selectedTabIndex = selectedFunction) {
                    listOf("🗣 Talk", "📖 Read", "📝 Quiz").forEachIndexed { i, title ->
                        Tab(
                            selected = selectedFunction == i,
                            onClick = { selectedFunction = i; selectedLang = 0 },
                            text = { Text(title, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("EN", "HI", "PA").forEachIndexed { i, lbl ->
                        FilterChip(
                            selected = selectedLang == i,
                            onClick = { selectedLang = i },
                            label = { Text(lbl) }
                        )
                    }
                }

                val langCfg = currentLangConfig()

                if (selectedLang > 0) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Column {
                            Text("Inherit from English", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = if (langCfg.inherit) "Using English AI settings" else "Custom settings enabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = langCfg.inherit,
                            onCheckedChange = { inherit ->
                                saveLangConfig(if (inherit) langCfg.copy(inherit = true) else (settings?.let { when (selectedFunction) { 1 -> it.read; 2 -> it.quiz; else -> it.talk } }?.en?.copy(inherit = false) ?: LangConfig()))
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(top = 4.dp))
                }

                if (!langCfg.inherit) {
                    LlmSettingsCard(config = langCfg.llm, onChange = { saveLangConfig(langCfg.copy(llm = it)) })
                    TtsSettingsCard(config = langCfg.tts, onChange = { saveLangConfig(langCfg.copy(tts = it)) })
                    if (selectedFunction != 1) {
                        SttSettingsCard(config = langCfg.stt, onChange = { saveLangConfig(langCfg.copy(stt = it)) })
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Change Parent PIN Card at the bottom
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPinDialog = true }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔑", fontSize = 20.sp)
                        Column {
                            Text("Change Parent PIN", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                            Text("Update PIN code used for dashboard gate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        // --- HEADER ---
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Parent Dashboard", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                Text(
                    text = if (selectedChild != null) "Manage learning parameters and view detailed reports." else "Select or add a learner to start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showGlobalSettingsDialog = true }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Global Settings",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // --- LEARNER SELECTOR ROW ---
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Learners", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                childUiState.children.forEach { child ->
                    val isSelected = selectedChild?.name == child.name
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedChild = child
                            childVm.saveLastChild(child.name, child.classNum)
                        },
                        label = { Text("${child.name} (Class ${child.classNum})") }
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { showAddChildDialog = true },
                    label = { Text("+ Add Learner") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = "Add Learner", modifier = Modifier.size(16.dp)) }
                )
            }
        }

        if (selectedChild == null) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("🌱", style = MaterialTheme.typography.displayMedium)
                    Text("No Learner Selected", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text("Select a child from the chips above or tap \"+ Add Learner\" to create one.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            val child = selectedChild!!

            // --- VIEW LEARNER PROGRESS CARD ---
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().clickable { onNavigateToProgress(child.name, child.classNum) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("📈 View Learner Progress", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        Text("See streaks, heatmap, and session logs for ${child.name}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "View Progress", tint = MaterialTheme.colorScheme.primary)
                }
            }


            // --- LEVEL ASSESSMENT QUIZ (Quiz History) ---
            if (quizHistory != null) {
                val qHistory = quizHistory!!
                val assessedAt = qHistory["assessedAt"] as? String ?: ""
                ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("📊 Level Assessment Placement History", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                        if (assessedAt.isNotEmpty()) {
                            Text("Last Assessed: ${formatDate(assessedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        listOf(
                            Triple("🇬🇧 English", "en", Color(0xFF27AE60)),
                            Triple("🇮🇳 Hindi", "hi", Color(0xFFE67E22)),
                            Triple("☬ Punjabi", "pa", Color(0xFF8E44AD))
                        ).forEach { (label, key, color) ->
                            val entries = qHistory[key] as? List<*>
                            if (!entries.isNullOrEmpty()) {
                                val correct = entries.count {
                                    val m = it as? Map<*, *>
                                    m?.get("correct") == true
                                }
                                val total = entries.size
                                val score = if (total > 0) Math.round((correct.toDouble() / total) * 100).toInt() else 0
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        quizHistoryLang = key
                                        showQuizHistoryDialog = true
                                    },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                ) {
                                    Row(Modifier.padding(12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                        Column {
                                            Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                            val expertise = langExpertise?.get(key) as? String
                                            if (!expertise.isNullOrBlank()) {
                                                Text(expertise, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text("$correct/$total", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                            Text("$score%", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = color)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // --- SUBJECT ASSESSMENTS CARD ---
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("📝 Subject Assessments", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                        Button(onClick = { showCreateDialog = true }, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                            Text("+ Create", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                        }
                    }

                    // Subject filter chips
                    val filterSubjects = listOf("All", "Hindi", "English", "Punjabi", "Mathematics", "Science", "Social Studies", "Computer")
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        filterSubjects.forEach { subj ->
                            FilterChip(
                                selected = selectedSubjectFilter == subj,
                                onClick = { selectedSubjectFilter = subj },
                                label = { Text(subj) }
                            )
                        }
                    }

                    val assessmentsList = listState.assessments.filter { a ->
                        a.status != "completed" && (selectedSubjectFilter == "All" || a.subject.equals(selectedSubjectFilter, ignoreCase = true))
                    }

                    if (listState.loading) {
                        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).size(24.dp))
                    } else if (assessmentsList.isEmpty()) {
                        Text("No pending assessments found.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            assessmentsList.forEach { a ->
                                val statusColor = when (a.status) {
                                    "completed" -> Color(0xFF27AE60)
                                    "in_progress" -> Color(0xFFE67E22)
                                    else -> Color(0xFF2980B9)
                                }
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        selectedAssessment = a
                                        showAssessmentDetail = true
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.Top) {
                                            Column(Modifier.weight(1f)) {
                                                Text("${a.subject} · Class ${a.classNum}", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                                                val langLabel = when (a.lang) { "hi" -> "Hindi"; "pa" -> "Punjabi"; else -> "English" }
                                                Text("$langLabel · ${a.questions.size} Qs · ${formatDate(if (a.createdAt.isNotBlank()) a.createdAt else a.date)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = when (a.status) {
                                                        "completed" -> "✓ Done"
                                                        "in_progress" -> "▶ In Progress"
                                                        else -> "⏳ Ready"
                                                    },
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                    color = statusColor
                                                )
                                                if (a.status == "completed" && a.score != null) {
                                                    Text("${a.score.toInt()}%", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = statusColor))
                                                }
                                            }
                                        }
                                        Button(
                                            onClick = { assessmentVm.deleteAssessment(child.name, a.id) },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Text("Delete Assessment", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // --- CHAPTER MANAGEMENT CARD ---
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("📚 Chapter Management", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                        Button(onClick = { showUploadChapterDialog = true }, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                            Text("+ Upload", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                        }
                    }

                    // Class and Subject filters
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Class:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(60.dp))
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("6", "7", "8", "9", "10").forEach { cls ->
                                    FilterChip(
                                        selected = chapMgmtClass == cls,
                                        onClick = { chapMgmtClass = cls },
                                        label = { Text(cls) }
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Subject:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(60.dp))
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("English", "Hindi", "Punjabi").forEach { sub ->
                                    FilterChip(
                                        selected = chapMgmtSubject == sub,
                                        onClick = { chapMgmtSubject = sub },
                                        label = { Text(sub) }
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = { chapterVm.loadChapters(chapMgmtClass, chapMgmtSubject) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Load Chapters")
                        }
                    }

                    // Voice Provider for Generation
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("🔊 Voice Provider for Generation", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("google", "elevenlabs", "sarvam").forEach { provider ->
                                    FilterChip(
                                        selected = chapMgmtProvider == provider,
                                        onClick = { chapMgmtProvider = provider },
                                        label = { Text(provider.replaceFirstChar { it.uppercase() }) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    if (chaptersLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).size(24.dp))
                    } else if (chaptersList.isEmpty()) {
                        Text("No chapters loaded. Tap \"Load Chapters\" above.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            chaptersList.forEach { ch ->
                                val activeJob = audioJobState
                                val isActiveJob = activeJob != null && activeJob.chapterId == ch.id && activeJob.subjectSlug == chapMgmtSubject.lowercase()
                                val isDone = activeJob?.phase == "done"
                                val isError = activeJob?.phase == "error" || activeJob?.phase == "aborted"

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isActiveJob) {
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                        }
                                    ),
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = when {
                                            isActiveJob -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                            ch.audioStatus == "complete" -> Color(0xFF27AE60).copy(alpha = 0.2f)
                                            ch.audioStatus == "partial" -> Color(0xFFE67E22).copy(alpha = 0.2f)
                                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                        }
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    var menuExpanded by remember { mutableStateOf(false) }

                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        // Top row: Title, status badge, and More options button
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Ch ${ch.order}. ${ch.title}",
                                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                val details = remember(ch) {
                                                    val items = mutableListOf<String>()
                                                    if (ch.wordCount > 0) {
                                                        items.add("${ch.wordCount} words")
                                                    }
                                                    if (ch.segmentsTotal > 0) {
                                                        if (ch.audioStatus == "complete") {
                                                            items.add("${ch.segmentsTotal} segments generated")
                                                        } else {
                                                            items.add("${ch.segmentsUploaded}/${ch.segmentsTotal} segments")
                                                        }
                                                    } else if (ch.sentenceCount > 0) {
                                                        items.add("${ch.sentenceCount} sentences")
                                                    }
                                                    items
                                                }

                                                if (details.isNotEmpty()) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        details.forEachIndexed { index, text ->
                                                            Text(
                                                                text = text,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                            if (index < details.size - 1) {
                                                                Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                // Status pill
                                                Surface(
                                                    color = when (ch.audioStatus) {
                                                        "complete" -> Color(0xFFEAFAF1)
                                                        "partial" -> Color(0xFFFEF9E7)
                                                        else -> Color(0xFFF2F4F4)
                                                    },
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        if (ch.audioStatus == "complete") {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = null,
                                                                tint = Color(0xFF27AE60),
                                                                modifier = Modifier.size(12.dp)
                                                            )
                                                        }
                                                        Text(
                                                            text = if (ch.audioStatus == "complete") {
                                                                "Voice Ready"
                                                            } else if (ch.audioStatus == "partial") {
                                                                "${ch.voicePct}% Ready"
                                                            } else {
                                                                "No Voice"
                                                            },
                                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                            color = when (ch.audioStatus) {
                                                                "complete" -> Color(0xFF27AE60)
                                                                "partial" -> Color(0xFFA04000)
                                                                else -> Color(0xFF7F8C8D)
                                                            }
                                                        )
                                                    }
                                                }

                                                // Dropdown menu for Regenerate and Delete
                                                Box {
                                                    IconButton(
                                                        onClick = { menuExpanded = true },
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.MoreVert,
                                                            contentDescription = "More actions",
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                    DropdownMenu(
                                                        expanded = menuExpanded,
                                                        onDismissRequest = { menuExpanded = false }
                                                    ) {
                                                        DropdownMenuItem(
                                                            text = {
                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.Refresh,
                                                                        contentDescription = null,
                                                                        tint = MaterialTheme.colorScheme.primary,
                                                                        modifier = Modifier.size(18.dp)
                                                                    )
                                                                    Text("Regenerate Voice")
                                                                }
                                                            },
                                                            onClick = {
                                                                menuExpanded = false
                                                                chapterVm.generateVoiceForChapter(
                                                                    classNum = chapMgmtClass,
                                                                    subject = chapMgmtSubject,
                                                                    chapterId = ch.id,
                                                                    provider = chapMgmtProvider,
                                                                    forceRegenerate = true
                                                                )
                                                            }
                                                        )
                                                        DropdownMenuItem(
                                                            text = {
                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.Delete,
                                                                        contentDescription = null,
                                                                        tint = MaterialTheme.colorScheme.error,
                                                                        modifier = Modifier.size(18.dp)
                                                                    )
                                                                    Text("Delete Chapter", color = MaterialTheme.colorScheme.error)
                                                                }
                                                            },
                                                            onClick = {
                                                                menuExpanded = false
                                                                showDeleteChapterConfirmId = ch.id
                                                                showDeleteChapterConfirmTitle = ch.title
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // Progress bar if active voice job is running
                                        if (isActiveJob) {
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(14.dp),
                                                            strokeWidth = 2.dp,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                        Text(
                                                            text = if (activeJob!!.phase == "fetching") {
                                                                "Fetching content..."
                                                            } else {
                                                                "Generating: ${activeJob.done}/${activeJob.total}"
                                                            },
                                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                    if (activeJob.phase == "generating") {
                                                        TextButton(
                                                            onClick = { chapterVm.stopVoiceJob() },
                                                            contentPadding = PaddingValues(0.dp),
                                                            modifier = Modifier.height(24.dp)
                                                        ) {
                                                            Text(
                                                                text = "Stop",
                                                                color = MaterialTheme.colorScheme.error,
                                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                                            )
                                                        }
                                                    }
                                                }
                                                if (activeJob.phase == "generating" && activeJob.total > 0) {
                                                    LinearProgressIndicator(
                                                        progress = { activeJob.done.toFloat() / activeJob.total.toFloat() },
                                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                                    )
                                                    if (activeJob.currentText.isNotEmpty()) {
                                                        Text(
                                                            text = activeJob.currentText,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            maxLines = 1,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        if (isActiveJob && isDone) {
                                            Text(
                                                text = "✓ Voice generation complete!",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF27AE60),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        if (isActiveJob && isError && activeJob?.abortError != null) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Warning,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Text(
                                                    text = "Error: ${activeJob.abortError}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }

                                        // Main action buttons (only if not active, or stopped/error)
                                        if (!isActiveJob || isDone || isError || activeJob?.phase == "stopped") {
                                            if (ch.audioStatus == "partial" || ch.audioStatus == "none") {
                                                Button(
                                                    onClick = {
                                                        chapterVm.generateVoiceForChapter(
                                                            classNum = chapMgmtClass,
                                                            subject = chapMgmtSubject,
                                                            chapterId = ch.id,
                                                            provider = chapMgmtProvider,
                                                            forceRegenerate = false
                                                        )
                                                    },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27AE60)),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.PlayArrow,
                                                            contentDescription = null,
                                                            tint = Color.White,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                        Text(
                                                            text = if (ch.audioStatus == "partial") "Resume Generation" else "Generate Voice",
                                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // --- AI USAGE STATS CARD ---
            ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("📊 AI Token Usage", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1, 7, 30).forEach { p ->
                                FilterChip(
                                    selected = usagePeriod == p,
                                    onClick = { usagePeriod = p },
                                    label = { Text(if (p == 1) "Today" else "${p}d") }
                                )
                            }
                        }
                    }

                    if (usageLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).size(24.dp))
                    } else if (usageStatsState != null) {
                        val stats = usageStatsState!!
                        val recent = stats.days.takeLast(7)
                        val maxTok = recent.maxOfOrNull { it.llmTokens } ?: 1

                        if (recent.isNotEmpty() && recent.any { it.llmTokens > 0 }) {
                            Text("7-Day Token Trend", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                            Row(
                                modifier = Modifier.fillMaxWidth().height(80.dp).padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                recent.forEach { d ->
                                    val fraction = if (maxTok > 0) d.llmTokens.toFloat() / maxTok.toFloat() else 0f
                                    val dayLabel = try {
                                        val date = java.time.LocalDate.parse(d.date)
                                        date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH).take(2)
                                    } catch (_: Exception) {
                                        d.date.takeLast(2)
                                    }
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = if (d.llmTokens >= 1000) "${(d.llmTokens / 1000f).toInt()}k" else "${d.llmTokens}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Box(
                                            modifier = Modifier
                                                .weight(1f, fill = false)
                                                .fillMaxHeight(fraction.coerceIn(0.1f, 1f))
                                                .width(16.dp)
                                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(dayLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            HorizontalDivider()
                        }

                        // LLM details
                        Text("🤖 Language Models (LLM)", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Calls: ${stats.llm.calls}", style = MaterialTheme.typography.bodySmall)
                            Text("Tokens: ${stats.llm.inputTokens + stats.llm.outputTokens}", style = MaterialTheme.typography.bodySmall)
                        }
                        stats.llm.byProvider.forEach { (provider, data) ->
                            Text("$provider: ${data.calls} calls (${data.inputTokens + data.outputTokens} tokens)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        // TTS details
                        if (stats.tts.calls > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("🔊 Text to Speech (TTS)", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Calls: ${stats.tts.calls}", style = MaterialTheme.typography.bodySmall)
                                Text("Characters: ${stats.tts.charCount}", style = MaterialTheme.typography.bodySmall)
                            }
                            stats.tts.byProvider.forEach { (provider, data) ->
                                Text("$provider: ${data.calls} calls (${data.charCount} chars)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        // STT details
                        if (stats.stt.calls > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("🎙️ Speech to Text (STT)", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Calls: ${stats.stt.calls}", style = MaterialTheme.typography.bodySmall)
                                Text("Duration: ${stats.stt.durationMs / 1000}s", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        Text("No usage data for this child.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }


        }
    }

    // 1. Add Child Dialog
    if (showAddChildDialog) {
        AlertDialog(
            onDismissRequest = { showAddChildDialog = false },
            title = { Text("Add Learner", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newChildName,
                        onValueChange = { newChildName = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newChildAge,
                        onValueChange = { newChildAge = it },
                        label = { Text("Age") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Class", style = MaterialTheme.typography.labelMedium)
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("6", "7", "8", "9", "10").forEach { cls ->
                            FilterChip(
                                selected = newChildClass == cls,
                                onClick = { newChildClass = cls },
                                label = { Text(cls) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newChildName.isBlank() || newChildAge.isBlank()) {
                            Toast.makeText(context, "Fill in all fields", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        childVm.addChild(newChildName, newChildAge, newChildClass) { child ->
                            selectedChild = child
                            showAddChildDialog = false
                            newChildName = ""
                            newChildAge = ""
                            newChildClass = "7"
                        }
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddChildDialog = false }) { Text("Cancel") }
            }
        )
    }

    // 2. Retest Dialog
    if (showRetestDialog && selectedChild != null) {
        AlertDialog(
            onDismissRequest = { showRetestDialog = false },
            title = { Text("Request Retest", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Select which languages ${selectedChild!!.name} should redo:")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = retestEn, onCheckedChange = { retestEn = it })
                        Text("🇬🇧 English", modifier = Modifier.padding(start = 8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = retestHi, onCheckedChange = { retestHi = it })
                        Text("🇮🇳 Hindi", modifier = Modifier.padding(start = 8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = retestPa, onCheckedChange = { retestPa = it })
                        Text("☬ Punjabi", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val langs = mutableListOf<String>()
                        if (retestEn) langs.add("en")
                        if (retestHi) langs.add("hi")
                        if (retestPa) langs.add("pa")
                        if (langs.isEmpty()) {
                            Toast.makeText(context, "Select at least one language", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        assessmentVm.saveRetestRequest(selectedChild!!.name, langs) {
                            showRetestDialog = false
                            Toast.makeText(context, "Retest scheduled", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !retestSaving
                ) {
                    if (retestSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRetestDialog = false }) { Text("Cancel") }
            }
        )
    }

    // 3. Quiz History Q&A Dialog
    if (showQuizHistoryDialog && quizHistory != null) {
        val entries = (quizHistory?.get(quizHistoryLang) as? List<*>) ?: emptyList<Any>()
        val assessedAt = quizHistory?.get("assessedAt") as? String ?: ""
        val langLabel = when (quizHistoryLang) { "hi" -> "Hindi"; "pa" -> "Punjabi"; else -> "English" }

        AlertDialog(
            onDismissRequest = { showQuizHistoryDialog = false },
            title = { Text("$langLabel Placement Test Details", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (assessedAt.isNotEmpty()) {
                        Text("Date: ${formatDate(assessedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    val correctCount = entries.count {
                        val m = it as? Map<*, *>
                        m?.get("correct") == true
                    }
                    val totalCount = entries.size
                    val scorePct = if (totalCount > 0) Math.round((correctCount.toDouble() / totalCount) * 100).toInt() else 0

                    Text(
                        text = "Score: $correctCount/$totalCount correct ($scorePct%)",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (scorePct >= 70) Color(0xFF27AE60) else if (scorePct >= 40) Color(0xFFE67E22) else Color(0xFFE74C3C)
                    )

                    val expertiseText = langExpertise?.get(quizHistoryLang) as? String
                    if (!expertiseText.isNullOrBlank()) {
                        Text("AI Assessment summary:", style = MaterialTheme.typography.titleSmall)
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(expertiseText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                        }
                    }

                    HorizontalDivider()

                    entries.forEachIndexed { i, entryObj ->
                        val entry = entryObj as? Map<*, *> ?: return@forEachIndexed
                        val isCorrect = entry["correct"] == true
                        val prompt = entry["prompt"] as? String ?: ""
                        val difficulty = (entry["difficulty"] as? Number)?.toInt() ?: 1
                        val topic = entry["topic"] as? String ?: ""
                        val options = (entry["options"] as? List<*>)?.map { it.toString() } ?: emptyList()
                        val correctIdx = (entry["correctIndex"] as? Number)?.toInt() ?: 0
                        val userAns = entry["userAnswer"] as? String ?: ""

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = if (isCorrect) Color(0xFFF0FBF5) else Color(0xFFFDF0F0)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Q${i + 1} · Level $difficulty · $topic", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Text(if (isCorrect) "✅ Correct" else "❌ Wrong", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = if (isCorrect) Color(0xFF27AE60) else Color(0xFFE74C3C))
                                }
                                Text(prompt, style = MaterialTheme.typography.bodyMedium)
                                options.forEachIndexed { oIdx, opt ->
                                    val isOptCorrect = oIdx == correctIdx
                                    val isOptChosen = opt == userAns
                                    val textStyle = if (isOptCorrect) {
                                        MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFF27AE60))
                                    } else if (isOptChosen) {
                                        MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough, color = Color(0xFFE74C3C))
                                    } else {
                                        MaterialTheme.typography.bodySmall
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                                        Text("${(65 + oIdx).toChar()}. ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(opt, style = textStyle)
                                        if (isOptCorrect) {
                                            Text(" ✓", style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF27AE60), fontWeight = FontWeight.Bold))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showQuizHistoryDialog = false }) { Text("Close") }
            }
        )
    }

    // 4. Create Subject Assessment Dialog
    if (showCreateDialog && selectedChild != null) {
        AlertDialog(
            onDismissRequest = { if (!quizState.generating) showCreateDialog = false },
            title = { Text("Create AI Assessment", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Generate a new assessment for ${selectedChild!!.name}.")
                    Text("Subject", style = MaterialTheme.typography.labelLarge)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Hindi", "English", "Punjabi").forEach { subj ->
                                FilterChip(selected = createSubject == subj, onClick = { createSubject = subj; createLang = when (subj) { "Hindi" -> "hi"; "Punjabi" -> "pa"; else -> "en" } }, label = { Text(subj) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Mathematics", "Science", "Social Studies").forEach { subj ->
                                FilterChip(selected = createSubject == subj, onClick = { createSubject = subj; createLang = "en" }, label = { Text(subj) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = createSubject == "Computer", onClick = { createSubject = "Computer"; createLang = "en" }, label = { Text("Computer") })
                        }
                    }
                    Text("Class", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("6", "7", "8", "9", "10").forEach { cls -> FilterChip(selected = createClass == cls, onClick = { createClass = cls }, label = { Text(cls) }) }
                    }
                    if (quizState.generating) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Generating questions via AI…")
                        }
                    }
                    quizState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                if (!quizState.generating) {
                    Button(onClick = { assessmentVm.generateAssessment(selectedChild!!.name, createSubject, createClass, createLang) }) {
                        Text("Generate")
                    }
                }
            },
            dismissButton = { if (!quizState.generating) TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } },
        )
    }

    // 5. Subject Assessment Detail Dialog (Reset / Delete / AI Summary)
    if (showAssessmentDetail && selectedAssessment != null && selectedChild != null) {
        val a = selectedAssessment!!
        val childName = selectedChild!!.name
        val validating by assessmentVm.quiz.collectAsState()

        AlertDialog(
            onDismissRequest = { if (!validating.validating) showAssessmentDetail = false },
            title = {
                Column {
                    Text(a.subject, fontWeight = FontWeight.Bold)
                    Text("Class ${a.classNum} · ${if (a.lang == "hi") "Hindi" else if (a.lang == "pa") "Punjabi" else "English"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Status, Created At, and Score
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Created: ${formatDate(if (a.createdAt.isNotBlank()) a.createdAt else a.date)}", style = MaterialTheme.typography.bodySmall)
                            Text("Status: ${a.status.uppercase()}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                            if (a.status == "completed" && a.score != null) {
                                Text("Score: ${a.score.toInt()}%", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = if (a.score >= 70) Color(0xFF27AE60) else Color(0xFFE67E22)))
                            }
                        }
                    }

                    // Display AI Summary if Completed
                    if (a.status == "completed" && a.summary.isNotEmpty()) {
                        Text("AI Summary Analysis", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        Surface(
                            color = Color(0xFFEEF9F8),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val parsed = remember(a.summary) { parseSummary(a.summary) }
                                if (parsed != null) {
                                    if (parsed.encouragement.isNotEmpty()) {
                                        Text(parsed.encouragement, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    if (parsed.categoryBreakdown.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("Category Breakdown:", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                                        parsed.categoryBreakdown.forEach { cat ->
                                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text(cat.category, style = MaterialTheme.typography.labelSmall)
                                                    Text("${cat.earnedMarks}/${cat.totalMarks} (${cat.score}%)", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                                }
                                                LinearProgressIndicator(
                                                    progress = { cat.score.toFloat() / 100f },
                                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                                    color = if (cat.score >= 70) Color(0xFF27AE60) else if (cat.score >= 40) Color(0xFFE67E22) else Color(0xFFE74C3C)
                                                )
                                            }
                                        }
                                    }
                                    if (parsed.strengths.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("✅ Strengths", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFF27AE60)))
                                        parsed.strengths.forEach { s ->
                                            Text("• $s", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    if (parsed.weaknesses.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("🎯 Areas to Improve", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFFE74C3C)))
                                        parsed.weaknesses.forEach { w ->
                                            Text("• $w", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    if (parsed.recommendations.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text("💡 Recommendations", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFFE67E22)))
                                        Text(parsed.recommendations, style = MaterialTheme.typography.labelSmall)
                                    }
                                } else {
                                    Text(a.summary, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }

                    // Graded Questions list
                    Text("Graded Questions & Answers", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    a.questions.forEachIndexed { i, q ->
                        val ans = a.answers.getOrNull(i)
                        val isMCQ = q.type == "mcq"

                        val result = if (ans == null) {
                            "unanswered"
                        } else if (isMCQ) {
                            if (ans["correct"] == true) "correct" else "wrong"
                        } else {
                            val selfGrade = ans["selfGrade"] as? String ?: ""
                            if (selfGrade == "got_it") "correct" else if (selfGrade == "partial") "partial" else "wrong"
                        }

                        val cardBg = when (result) {
                            "correct" -> Color(0xFFF0FBF5)
                            "partial" -> Color(0xFFFFF8EE)
                            else -> Color(0xFFFDF0F0)
                        }

                        val borderColor = when (result) {
                            "correct" -> Color(0xFF27AE60)
                            "partial" -> Color(0xFFE67E22)
                            else -> Color(0xFFE74C3C)
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, RoundedCornerShape(8.dp)),
                            colors = CardDefaults.cardColors(containerColor = cardBg),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Q${i + 1} · ${q.category} · ${q.marks}m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        text = when (result) {
                                            "correct" -> "✓ Correct"
                                            "partial" -> "½ Partial"
                                            else -> "✗ Wrong"
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = borderColor
                                    )
                                }
                                Text(q.question, style = MaterialTheme.typography.bodyMedium)

                                if (isMCQ) {
                                    q.options.forEachIndexed { oIdx, opt ->
                                        val isCorrect = oIdx == q.correctIndex
                                        val isSelected = oIdx == (ans?.get("selectedIndex") as? Number)?.toInt()
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                                            Text("${(65 + oIdx).toChar()}. ", style = MaterialTheme.typography.bodySmall)
                                            Text(
                                                text = opt + if (isCorrect) " ✓" else if (isSelected == true) " ✗" else "",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontWeight = if (isCorrect || isSelected == true) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isCorrect) Color(0xFF27AE60) else if (isSelected == true) Color(0xFFE74C3C) else Color.Unspecified
                                                )
                                            )
                                        }
                                    }
                                } else if (ans != null) {
                                    val textAnswer = ans["textAnswer"] as? String ?: ""
                                    val corrections = ans["corrections"] as? List<*>
                                    if (textAnswer.isNotEmpty()) {
                                        Text("Child's Answer:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                        AnnotatedAnswer(text = textAnswer, corrections = corrections)
                                    }
                                    val correctedAnswer = ans["correctedAnswer"] as? String ?: ""
                                    if (correctedAnswer.isNotEmpty() && correctedAnswer.trim() != textAnswer.trim()) {
                                        Text("Corrected:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFF2471A3)))
                                        Text(correctedAnswer, style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF1A5276)))
                                    }
                                    if (q.answer.isNotEmpty()) {
                                        Text("Model Answer:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = Color(0xFF27AE60)))
                                        Text(q.answer, style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF27AE60)))
                                    }
                                }

                                if (q.explanation.isNotEmpty()) {
                                    Text("Explanation: ${q.explanation}", style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    // Reset / Delete / AI Summary actions
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                        if (a.status == "completed") {
                            Button(
                                onClick = { assessmentVm.regenerateSummaryForAssessment(childName, a) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !validating.validating,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF39C12))
                            ) {
                                if (validating.validating) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text("Regenerate Summary (AI)")
                            }
                        }
                        if (a.status != "ready") {
                            Button(
                                onClick = {
                                    assessmentVm.resetAssessment(childName, a.id)
                                    showAssessmentDetail = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE67E22))
                            ) {
                                Text("Reset — Clear Results & Retake")
                            }
                        }
                        Button(
                            onClick = {
                                assessmentVm.deleteAssessment(childName, a.id)
                                showAssessmentDetail = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE74C3C))
                        ) {
                            Text("Delete Assessment")
                        }
                    }
                }
            },
            confirmButton = {
                if (!validating.validating) {
                    TextButton(onClick = { showAssessmentDetail = false }) { Text("Close") }
                }
            }
        )
    }

    // 6. Upload Chapter Dialog
    if (showUploadChapterDialog) {
        AlertDialog(
            onDismissRequest = { if (!uploadingChapter) showUploadChapterDialog = false },
            title = { Text("Upload Chapter JSON File", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Class", style = MaterialTheme.typography.labelMedium)
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("6", "7", "8", "9", "10").forEach { cls ->
                            FilterChip(
                                selected = chapterClassNum == cls,
                                onClick = { chapterClassNum = cls },
                                label = { Text(cls) }
                            )
                        }
                    }
                    Text("Subject", style = MaterialTheme.typography.labelMedium)
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("English", "Hindi", "Punjabi").forEach { sub ->
                            FilterChip(
                                selected = chapterSubject == sub,
                                onClick = { chapterSubject = sub },
                                label = { Text(sub) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = chapterOrder,
                        onValueChange = { chapterOrder = it },
                        label = { Text("Chapter Order (e.g. 1, 2, 3...)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = chapterTitle,
                        onValueChange = { chapterTitle = it },
                        label = { Text("Chapter Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { filePickerLauncher.launch("application/json") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (chapterJsonInput.isEmpty()) "📂 Pick JSON File" else "📄 File Picked ✓")
                    }

                    uploadChapterError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (chapterTitle.isBlank() || chapterJsonInput.isBlank()) {
                            Toast.makeText(context, "Please pick a file and fill in title", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        chapterVm.uploadChapter(
                            classNum = chapterClassNum,
                            subject = chapterSubject,
                            title = chapterTitle,
                            order = chapterOrder.toIntOrNull() ?: 1,
                            jsonContent = chapterJsonInput
                        ) {
                            showUploadChapterDialog = false
                            chapterTitle = ""
                            chapterJsonInput = ""
                            chapterOrder = "1"
                            Toast.makeText(context, "Chapter uploaded successfully!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !uploadingChapter
                ) {
                    if (uploadingChapter) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text("Upload")
                }
            },
            dismissButton = {
                if (!uploadingChapter) {
                    TextButton(onClick = { showUploadChapterDialog = false }) { Text("Cancel") }
                }
            }
        )
    }

    // Delete Chapter Confirmation Dialog
    if (showDeleteChapterConfirmId != null && showDeleteChapterConfirmTitle != null) {
        val chapterId = showDeleteChapterConfirmId!!
        val chapterTitle = showDeleteChapterConfirmTitle!!
        AlertDialog(
            onDismissRequest = {
                showDeleteChapterConfirmId = null
                showDeleteChapterConfirmTitle = null
            },
            title = { Text("Delete Chapter", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete \"$chapterTitle\"? This will permanently remove the chapter text and all voice generated audio files from storage.") },
            confirmButton = {
                Button(
                    onClick = {
                        chapterVm.deleteChapter(chapMgmtClass, chapMgmtSubject, chapterId) {
                            Toast.makeText(context, "Chapter deleted", Toast.LENGTH_SHORT).show()
                            showDeleteChapterConfirmId = null
                            showDeleteChapterConfirmTitle = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteChapterConfirmId = null
                    showDeleteChapterConfirmTitle = null
                }) { Text("Cancel") }
            }
        )
    }

    // 7. PIN Change / Verification Dialog
    if (showPinDialog) {
        ChangePinDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = { old, new ->
                val ok = settingsVm.changePin(old, new)
                if (ok) Toast.makeText(context, "PIN changed successfully", Toast.LENGTH_SHORT).show()
                ok
            },
        )
    }
}

@Composable
private fun LlmSettingsCard(config: ModelConfig, onChange: (ModelConfig) -> Unit) {
    val selectedProvider = AiCatalogue.llmProviders.find { it.id == config.provider } ?: AiCatalogue.llmProviders.first()
    val selectedModel    = selectedProvider.models.find { it.id == config.model } ?: selectedProvider.models.first()

    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Language Model (LLM)", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
            SettingLabel("Provider")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiCatalogue.llmProviders.forEach { provider ->
                    FilterChip(
                        selected = selectedProvider.id == provider.id,
                        onClick = {
                            if (selectedProvider.id != provider.id) {
                                val m = provider.models.first()
                                onChange(config.copy(provider = provider.id, model = m.id, temperature = if (m.supportsTemperature) config.temperature else 0.7, reasoningLevel = if (m.supportsReasoning) config.reasoningLevel else 0))
                            }
                        },
                        label = { Text(provider.name) },
                    )
                }
            }
            ModelDropdown(label = "Model", models = selectedProvider.models, selected = selectedModel, onSelected = { m -> onChange(config.copy(model = m.id, temperature = if (m.supportsTemperature) config.temperature else 0.7)) })
            if (selectedModel.supportsReasoning) {
                SettingLabel("Reasoning")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AiCatalogue.reasoningLabels.forEachIndexed { index, label ->
                        FilterChip(selected = config.reasoningLevel == index, onClick = { onChange(config.copy(reasoningLevel = index)) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (selectedModel.supportsTemperature) {
                var sliderTemp by remember(config.temperature) { mutableStateOf(config.temperature.toFloat()) }
                Column {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        SettingLabel("Temperature")
                        Text("%.2f".format(sliderTemp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Slider(value = sliderTemp, onValueChange = { sliderTemp = it }, onValueChangeFinished = { onChange(config.copy(temperature = sliderTemp.toDouble())) }, valueRange = 0f..1f, steps = 19)
                }
            }
        }
    }
}

@Composable
private fun TtsSettingsCard(config: TtsConfig, onChange: (TtsConfig) -> Unit) {
    val selectedProvider = AiCatalogue.ttsProviders.find { it.id == config.provider } ?: AiCatalogue.ttsProviders.first()
    val selectedModel    = selectedProvider.models.find { it.id == config.model } ?: selectedProvider.models.first()

    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Text to Speech (TTS)", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
            SettingLabel("Provider")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiCatalogue.ttsProviders.forEach { provider ->
                    FilterChip(selected = selectedProvider.id == provider.id, onClick = { if (selectedProvider.id != provider.id) { val m = provider.models.first(); onChange(TtsConfig(provider.id, m.id)) } }, label = { Text(provider.name) })
                }
            }
            ModelDropdown(label = "Model", models = selectedProvider.models, selected = selectedModel, onSelected = { m -> onChange(config.copy(model = m.id)) })
        }
    }
}

@Composable
private fun SttSettingsCard(config: SttConfig, onChange: (SttConfig) -> Unit) {
    val selectedProvider = AiCatalogue.sttProviders.find { it.id == config.provider } ?: AiCatalogue.sttProviders.first()
    val selectedModel    = selectedProvider.models.find { it.id == config.model } ?: selectedProvider.models.first()

    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Speech to Text (STT)", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
            SettingLabel("Provider")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiCatalogue.sttProviders.forEach { provider ->
                    FilterChip(selected = selectedProvider.id == provider.id, onClick = { if (selectedProvider.id != provider.id) { val m = provider.models.first(); onChange(SttConfig(provider.id, m.id)) } }, label = { Text(provider.name) })
                }
            }
            ModelDropdown(label = "Model", models = selectedProvider.models, selected = selectedModel, onSelected = { m -> onChange(config.copy(model = m.id)) })
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ModelDropdown(label: String, models: List<ModelInfo>, selected: ModelInfo, onSelected: (ModelInfo) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        SettingLabel(label)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(selected.name, style = MaterialTheme.typography.bodyMedium)
                    selected.badge?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                }
                Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Column { Text(model.name); model.badge?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } } },
                        onClick = { onSelected(model); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangePinDialog(onDismiss: () -> Unit, onConfirm: (oldPin: String, newPin: String) -> Boolean) {
    var currentPin by remember { mutableStateOf("") }
    var newPin     by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error      by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change PIN", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = currentPin, onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) currentPin = it }, label = { Text("Current PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = newPin, onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) newPin = it }, label = { Text("New PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = confirmPin, onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) confirmPin = it }, label = { Text("Confirm New PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                error = when {
                    newPin.length < 4    -> "PIN must be at least 4 digits"
                    newPin != confirmPin -> "PINs don't match"
                    else -> { val ok = onConfirm(currentPin, newPin); if (!ok) "Incorrect current PIN" else null }
                }
            }) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
