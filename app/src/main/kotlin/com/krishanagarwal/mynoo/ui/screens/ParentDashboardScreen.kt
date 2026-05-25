package com.krishanagarwal.mynoo.ui.screens

import android.widget.Toast
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.krishanagarwal.mynoo.data.model.*
import com.krishanagarwal.mynoo.ui.viewmodel.AssessmentViewModel
import com.krishanagarwal.mynoo.ui.viewmodel.ParentSettingsViewModel
import com.krishanagarwal.mynoo.ui.viewmodel.SaveStatus

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
fun ParentDashboardScreen(
    childState:             ChildState,
    onNavigateToProgress:   () -> Unit,
    onNavigateToAssessment: (assessmentId: String, childName: String) -> Unit,
    assessmentVm:           AssessmentViewModel     = hiltViewModel(),
    settingsVm:             ParentSettingsViewModel = hiltViewModel(),
) {
    val context    = LocalContext.current
    val settings   by settingsVm.settings.collectAsState()
    val isLoading  by settingsVm.isLoading.collectAsState()
    val saveStatus by settingsVm.saveStatus.collectAsState()
    val saveError  by settingsVm.saveError.collectAsState()
    val listState  by assessmentVm.list.collectAsState()
    val quizState  by assessmentVm.quiz.collectAsState()

    var selectedFunction by remember { mutableStateOf(0) }
    var selectedLang     by remember { mutableStateOf(0) }
    var showPinDialog    by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var createSubject    by remember { mutableStateOf("Hindi") }
    var createClass      by remember { mutableStateOf(childState.classNum.ifBlank { "7" }) }
    var createLang       by remember { mutableStateOf("hi") }

    LaunchedEffect(childState.name) {
        if (childState.name.isNotBlank()) assessmentVm.loadAssessments(childState.name)
    }
    LaunchedEffect(quizState.assessment) {
        val a = quizState.assessment
        if (a != null && a.id.isNotBlank() && !quizState.generating) showCreateDialog = false
    }

    fun currentFn(): FunctionConfig = when (selectedFunction) { 1 -> settings.read; 2 -> settings.quiz; else -> settings.talk }
    fun currentLangConfig(): LangConfig = when (selectedLang) { 1 -> currentFn().hi; 2 -> currentFn().pa; else -> currentFn().en }

    fun saveLangConfig(lc: LangConfig) {
        val fn    = currentFn()
        val newFn = when (selectedLang) { 1 -> fn.copy(hi = lc); 2 -> fn.copy(pa = lc); else -> fn.copy(en = lc) }
        val newSettings = when (selectedFunction) { 1 -> settings.copy(read = newFn); 2 -> settings.copy(quiz = newFn); else -> settings.copy(talk = newFn) }
        settingsVm.updateSettings(newSettings)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text("Parent Dashboard", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                Text("Child: ${childState.name} · Class ${childState.classNum}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { showPinDialog = true }) { Text("Change PIN") }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth().clickable { onNavigateToProgress() }, shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("📈 View Learner Progress", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text("See streaks, heatmap, and analytics for ${childState.name}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "View Progress", tint = MaterialTheme.colorScheme.primary)
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("📝 Subject Assessments", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                    Button(onClick = { showCreateDialog = true }, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        Text("+ Create", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }
                val pending = remember(listState.assessments) { listState.assessments.filter { it.status != "completed" } }
                if (listState.loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).size(24.dp))
                } else if (pending.isEmpty()) {
                    Text(if (listState.assessments.isNotEmpty()) "No pending assessments." else "No assessments yet. Tap Create to generate one with AI.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pending.forEach { a ->
                            val statusColor = if (a.status == "in_progress") Color(0xFFE67E22) else Color(0xFF2980B9)
                            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.Top) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${a.subject} · Class ${a.classNum}", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                                            val langLabel = when (a.lang) { "hi" -> "Hindi"; "pa" -> "Punjabi"; else -> "English" }
                                            Text("$langLabel · ${a.questions.size} Qs · ${formatDate(if (a.createdAt.isNotBlank()) a.createdAt else a.date)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text(if (a.status == "in_progress") "▶ In Progress" else "⏳ Ready", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = statusColor)
                                    }
                                    Button(onClick = { onNavigateToAssessment(a.id, childState.name) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = statusColor)) {
                                        Text(if (a.status == "in_progress") "Resume Assessment" else "Start Assessment (hand to child)", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("AI Settings", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            when (saveStatus) {
                SaveStatus.SAVING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    Text("Saving…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SaveStatus.SAVED  -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    Text("Saved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                SaveStatus.ERROR  -> Text("Save failed: ${saveError ?: "unknown error"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                else              -> {}
            }
        }

        if (isLoading) {
            Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
        } else {
            TabRow(selectedTabIndex = selectedFunction) {
                listOf("🗣 Talk", "📖 Read", "📝 Quiz").forEachIndexed { i, title ->
                    Tab(selected = selectedFunction == i, onClick = { selectedFunction = i; selectedLang = 0 }, text = { Text(title, style = MaterialTheme.typography.labelMedium) })
                }
            }

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("EN", "HI", "PA").forEachIndexed { i, lbl ->
                    FilterChip(selected = selectedLang == i, onClick = { selectedLang = i }, label = { Text(lbl) })
                }
            }

            val langCfg = currentLangConfig()

            if (selectedLang > 0) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column {
                        Text("Inherit from English", style = MaterialTheme.typography.bodyMedium)
                        Text(if (langCfg.inherit) "Using English AI settings" else "Custom settings enabled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = langCfg.inherit,
                        onCheckedChange = { inherit ->
                            saveLangConfig(if (inherit) langCfg.copy(inherit = true) else currentFn().en.copy(inherit = false))
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
        Spacer(Modifier.height(32.dp))
    }

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

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { if (!quizState.generating) showCreateDialog = false },
            title = { Text("Create AI Assessment", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Generate a new assessment for ${childState.name}.")
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
                if (!quizState.generating) Button(onClick = { assessmentVm.generateAssessment(childState.name, createSubject, createClass, createLang) }) { Text("Generate") }
            },
            dismissButton = { if (!quizState.generating) TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } },
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
