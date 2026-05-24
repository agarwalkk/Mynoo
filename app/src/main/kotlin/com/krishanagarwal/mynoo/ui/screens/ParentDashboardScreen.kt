package com.krishanagarwal.mynoo.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.krishanagarwal.mynoo.data.model.ChildState
import com.krishanagarwal.mynoo.ui.viewmodel.AssessmentViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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

@Composable
fun ParentDashboardScreen(
    childState:             ChildState,
    onNavigateToProgress:   () -> Unit,
    onNavigateToAssessment: (assessmentId: String, childName: String) -> Unit,
    assessmentVm:           AssessmentViewModel = hiltViewModel()
) {
    val db = remember { FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val allModels = listOf(
        "gemini-3.5-flash" to "Gemini 3.5 Flash",
        "gemini-3.1-flash-lite-preview" to "Gemini 3.1 Flash Lite",
        "gemini-3-flash-preview" to "Gemini 3 Flash Preview",
        "gemini-2.5-flash" to "Gemini 2.5 Flash",
        "gemini-2.5-flash-lite" to "Gemini 2.5 Flash Lite",
        "grok-4-1-fast-reasoning" to "Grok 4.1 Fast (recommended)",
        "grok-3-mini-fast" to "Grok 3 Mini Fast",
        "gpt-4.1" to "GPT-4.1",
        "gpt-4.1-mini" to "GPT-4.1 Mini",
        "gpt-5" to "GPT-5",
        "gpt-5.1" to "GPT-5.1",
        "gpt-5.4-mini" to "GPT-5.4 Mini",
        "gpt-5.4" to "GPT-5.4",
        "sarvam-m" to "Sarvam-M (Hindi/Punjabi)"
    )
    val overrideModels = listOf(null to "Same as Tutor") + allModels

    val ttsOptions = listOf(
        null to "Auto",
        "google" to "Google Gemini TTS",
        "sarvam" to "Sarvam AI TTS",
        "elevenlabs" to "ElevenLabs TTS"
    )
    val sttOptions = listOf(
        null to "Auto",
        "google" to "Google STT V1",
        "sarvam" to "Sarvam STT"
    )

    // Talk settings
    var globalModel by remember { mutableStateOf("gemini-3.5-flash") }
    var hiModel by remember { mutableStateOf<String?>(null) }
    var paModel by remember { mutableStateOf<String?>(null) }
    var tutorTts by remember { mutableStateOf<String?>(null) }
    var tutorStt by remember { mutableStateOf<String?>(null) }

    // Read settings
    var readerModel by remember { mutableStateOf<String?>(null) }
    var readerTts by remember { mutableStateOf<String?>(null) }

    // Quiz settings
    var assessmentModel by remember { mutableStateOf<String?>(null) }
    var assessmentTts by remember { mutableStateOf<String?>(null) }
    var assessmentStt by remember { mutableStateOf<String?>(null) }

    var enDiff  by remember { mutableStateOf(5f) }
    var hiDiff  by remember { mutableStateOf(5f) }
    var paDiff  by remember { mutableStateOf(5f) }

    // Subject Assessments Dialog & State
    var showCreateDialog by remember { mutableStateOf(false) }
    var createSubject by remember { mutableStateOf("Hindi") }
    var createClass by remember { mutableStateOf(childState.classNum.ifBlank { "7" }) }
    var createLang by remember { mutableStateOf("hi") }

    val listState by assessmentVm.list.collectAsState()
    val quizState by assessmentVm.quiz.collectAsState()

    LaunchedEffect(childState.name) {
        if (childState.name.isNotBlank()) {
            assessmentVm.loadAssessments(childState.name)
            try {
                val doc = db.collection("kids").document(childState.name)
                    .collection("config").document("settings").get().await()
                if (doc.exists()) {
                    globalModel = doc.getString("geminiModel") ?: "gemini-3.5-flash"
                    hiModel = doc.getString("hiModel")
                    paModel = doc.getString("paModel")
                    assessmentModel = doc.getString("assessmentModel")
                    readerModel = doc.getString("readerModel")

                    enDiff = doc.getDouble("enDiff")?.toFloat() ?: 5f
                    hiDiff = doc.getDouble("hiDiff")?.toFloat() ?: 5f
                    paDiff = doc.getDouble("paDiff")?.toFloat() ?: 5f

                    val ttsMap = doc.get("ttsProvider") as? Map<*, *>
                    tutorTts = ttsMap?.get("tutorSession") as? String
                    assessmentTts = ttsMap?.get("assessmentQuiz") as? String
                    readerTts = ttsMap?.get("chapterReading") as? String

                    val sttMap = doc.get("sttProvider") as? Map<*, *>
                    tutorStt = sttMap?.get("tutorSession") as? String
                    assessmentStt = sttMap?.get("assessmentQuiz") as? String
                }
            } catch (e: Exception) {
                Log.e("ParentDashboard", "Error loading settings", e)
            }
        }
    }

    LaunchedEffect(quizState.assessment) {
        val a = quizState.assessment
        if (a != null && a.id.isNotBlank() && !quizState.generating) {
            showCreateDialog = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Parent Dashboard",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary)
        Text("Child: ${childState.name} · Class ${childState.classNum}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Progress Card
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToProgress() },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "📈 View Learner Progress",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "See streaks, learning heatmap, and analytics for ${childState.name}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "View Progress",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Subject Assessments Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📝 Subject Assessments",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Button(
                        onClick = { showCreateDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("+ Create", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }

                val pendingAssessments = remember(listState.assessments) {
                    listState.assessments.filter { it.status != "completed" }
                }

                if (listState.loading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).size(24.dp))
                } else if (pendingAssessments.isEmpty()) {
                    Text(
                        text = if (listState.assessments.isNotEmpty()) 
                            "No pending assessments. All are completed — view them from the child's assessment list."
                        else 
                            "No assessments yet. Tap Create to generate one with AI.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pendingAssessments.forEach { a ->
                            val statusColor = when (a.status) {
                                "in_progress" -> Color(0xFFE67E22)
                                else -> Color(0xFF2980B9)
                            }
                            val statusLabel = when (a.status) {
                                "in_progress" -> "▶ In Progress"
                                else -> "⏳ Ready"
                            }
                            
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "${a.subject} · Class ${a.classNum}",
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            val langLabel = when (a.lang) {
                                                "hi" -> "Hindi"
                                                "pa" -> "Punjabi"
                                                else -> "English"
                                            }
                                            Text(
                                                text = "$langLabel · ${a.questions.size} Qs · ${formatDate(if (a.createdAt.isNotBlank()) a.createdAt else a.date)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = statusLabel,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = statusColor
                                        )
                                    }
                                    
                                    Button(
                                        onClick = { onNavigateToAssessment(a.id, childState.name) },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = statusColor)
                                    ) {
                                        Text(
                                            text = if (a.status == "in_progress") "Resume Assessment" else "Start Assessment (hand to child)",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Talk (AI Tutor) Settings Card
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("🗣 Talk (AI Tutor) Settings", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                PremiumDropdownSelector("Global/English Tutor Model", globalModel, allModels) { globalModel = it }
                PremiumDropdownSelector("Hindi Tutor Override", hiModel, overrideModels) { hiModel = it }
                PremiumDropdownSelector("Punjabi Tutor Override", paModel, overrideModels) { paModel = it }
                PremiumDropdownSelector("Tutor TTS Provider", tutorTts, ttsOptions) { tutorTts = it }
                PremiumDropdownSelector("Tutor STT Provider", tutorStt, sttOptions) { tutorStt = it }
            }
        }

        // Read (Chapter Reader) Settings Card
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("📖 Read (Chapter Reader) Settings", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                PremiumDropdownSelector("Chapter Reader Model Override", readerModel, overrideModels) { readerModel = it }
                PremiumDropdownSelector("Chapter Reader TTS Provider", readerTts, ttsOptions) { readerTts = it }
            }
        }

        // Quiz (Assessments) Settings Card
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("📝 Quiz (Assessments) Settings", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                PremiumDropdownSelector("Assessment Generator Model Override", assessmentModel, overrideModels) { assessmentModel = it }
                PremiumDropdownSelector("Assessment TTS Provider", assessmentTts, ttsOptions) { assessmentTts = it }
                PremiumDropdownSelector("Assessment STT Provider", assessmentStt, sttOptions) { assessmentStt = it }
            }
        }

        // Language difficulty sliders
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Language Difficulty", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))

                DifficultySlider("English", enDiff) { enDiff = it }
                DifficultySlider("Hindi",   hiDiff) { hiDiff = it }
                DifficultySlider("Punjabi", paDiff) { paDiff = it }
            }
        }

        Button(
            onClick = {
                scope.launch {
                    if (childState.name.isNotBlank()) {
                        try {
                            val settingsMap = mutableMapOf<String, Any>()
                            settingsMap["geminiModel"] = globalModel
                            settingsMap["hiModel"] = hiModel ?: FieldValue.delete()
                            settingsMap["paModel"] = paModel ?: FieldValue.delete()
                            settingsMap["assessmentModel"] = assessmentModel ?: FieldValue.delete()
                            settingsMap["readerModel"] = readerModel ?: FieldValue.delete()
                            
                            settingsMap["enDiff"] = enDiff.toDouble()
                            settingsMap["hiDiff"] = hiDiff.toDouble()
                            settingsMap["paDiff"] = paDiff.toDouble()

                            val ttsMap = mapOf(
                                "tutorSession" to tutorTts,
                                "assessmentQuiz" to assessmentTts,
                                "chapterReading" to readerTts
                            )
                            settingsMap["ttsProvider"] = ttsMap

                            val sttMap = mapOf(
                                "tutorSession" to tutorStt,
                                "assessmentQuiz" to assessmentStt
                            )
                            settingsMap["sttProvider"] = sttMap

                            db.collection("kids").document(childState.name)
                                .collection("config").document("settings")
                                .set(settingsMap, SetOptions.merge()).await()

                            Toast.makeText(context, "Settings saved successfully", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Log.e("ParentDashboard", "Error saving settings", e)
                            Toast.makeText(context, "Failed to save settings: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Settings")
        }

        Text("Settings saved to Firestore at kids/${childState.name}/config/settings",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { if (!quizState.generating) showCreateDialog = false },
            title = { Text("Create AI Assessment", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Select options to generate a new custom assessment for ${childState.name}.")
                    
                    // Subject Selection
                    Text("Subject", style = MaterialTheme.typography.labelLarge)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Hindi", "English", "Punjabi").forEach { subj ->
                                FilterChip(
                                    selected = createSubject == subj,
                                    onClick = { 
                                        createSubject = subj 
                                        createLang = when (subj) {
                                            "Hindi" -> "hi"
                                            "Punjabi" -> "pa"
                                            else -> "en"
                                        }
                                    },
                                    label = { Text(subj) }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Mathematics", "Science", "Social Studies").forEach { subj ->
                                FilterChip(
                                    selected = createSubject == subj,
                                    onClick = { 
                                        createSubject = subj 
                                        createLang = "en"
                                    },
                                    label = { Text(subj) }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Computer").forEach { subj ->
                                FilterChip(
                                    selected = createSubject == subj,
                                    onClick = { 
                                        createSubject = subj 
                                        createLang = "en"
                                    },
                                    label = { Text(subj) }
                                )
                            }
                        }
                    }
                    
                    // Class Selection
                    Text("Class", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("6", "7", "8", "9", "10").forEach { cls ->
                            FilterChip(
                                selected = createClass == cls,
                                onClick = { createClass = cls },
                                label = { Text(cls) }
                            )
                        }
                    }
                    
                    if (quizState.generating) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Generating questions via Gemini AI...")
                        }
                    }
                    
                    quizState.error?.let { err ->
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                if (!quizState.generating) {
                    Button(
                        onClick = {
                            assessmentVm.generateAssessment(
                                childName = childState.name,
                                subject = createSubject,
                                classNum = createClass,
                                lang = createLang
                            )
                        }
                    ) {
                        Text("Generate")
                    }
                }
            },
            dismissButton = {
                if (!quizState.generating) {
                    TextButton(onClick = { showCreateDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

@Composable
private fun DifficultySlider(lang: String, value: Float, onValueChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(lang, style = MaterialTheme.typography.bodyMedium)
            Text("${value.toInt()}/10", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value         = value,
            onValueChange = onValueChange,
            valueRange    = 1f..10f,
            steps         = 8,
        )
    }
}

@Composable
private fun <T> PremiumDropdownSelector(
    label: String,
    selected: T,
    options: List<Pair<T, String>>,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: selected?.toString() ?: "None"

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                )
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(selectedLabel, style = MaterialTheme.typography.bodyMedium)
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Expand dropdown",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (value, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onSelected(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
