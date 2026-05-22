package com.krishanagarwal.mynoo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.krishanagarwal.mynoo.data.repository.AssessmentQuestion
import com.krishanagarwal.mynoo.ui.viewmodel.AssessmentViewModel
import com.krishanagarwal.mynoo.ui.viewmodel.currentQuestion
import com.krishanagarwal.mynoo.ui.viewmodel.score

@Composable
fun AssessmentScreen(
    assessmentId: String,
    childName:    String,
    vm: AssessmentViewModel = hiltViewModel(),
) {
    val quiz by vm.quiz.collectAsState()

    LaunchedEffect(assessmentId, childName) {
        if (quiz.assessment == null) vm.loadAssessment(childName, assessmentId)
    }

    when {
        quiz.generating || quiz.assessment == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Loading assessment…")
            }
        }
        quiz.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(quiz.error!!, color = MaterialTheme.colorScheme.error)
        }
        quiz.finished -> SummaryView(quiz.summary, quiz.score, quiz.assessment?.questions?.size ?: 0)
        else -> {
            val q   = quiz.currentQuestion ?: return
            val idx = quiz.currentIndex
            val total = quiz.assessment?.questions?.size ?: 1
            QuestionView(
                question     = q,
                index        = idx,
                total        = total,
                answered     = quiz.answers[idx],
                revealed     = idx in quiz.revealed,
                onAnswer     = { vm.answer(it) },
                onReveal     = { vm.revealAnswer() },
                onNext       = { vm.next() },
            )
        }
    }
}

@Composable
private fun QuestionView(
    question:  AssessmentQuestion,
    index:     Int,
    total:     Int,
    answered:  String?,
    revealed:  Boolean,
    onAnswer:  (String) -> Unit,
    onReveal:  () -> Unit,
    onNext:    () -> Unit,
) {
    var textValue by remember(index) { mutableStateOf(TextFieldValue(answered ?: "")) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Progress
        LinearProgressIndicator(
            progress = { (index + 1f) / total },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Question ${index + 1} of $total",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        // Type badge
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(question.type.replace('_', ' ').uppercase(),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall)
        }

        // Input sentence (if any)
        if (question.inputSentence.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(question.inputSentence,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyLarge)
            }
        }

        // Question text
        Text(question.question, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))

        // Blanks hint
        if (question.blanks.isNotEmpty()) {
            Text("Word bank: ${question.blanks.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }

        // MCQ options
        if (question.type == "mcq") {
            val labels = listOf("A", "B", "C", "D")
            question.options.forEachIndexed { i, opt ->
                val isSelected = answered == i.toString()
                val isCorrect  = revealed && i == question.correctIndex
                val isWrong    = revealed && isSelected && i != question.correctIndex
                val containerColor = when {
                    isCorrect -> MaterialTheme.colorScheme.primaryContainer
                    isWrong   -> MaterialTheme.colorScheme.errorContainer
                    isSelected -> MaterialTheme.colorScheme.secondaryContainer
                    else       -> MaterialTheme.colorScheme.surface
                }
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick  = { if (!revealed) onAnswer(i.toString()) },
                    colors   = CardDefaults.elevatedCardColors(containerColor = containerColor),
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(labels.getOrElse(i) { "$i" },
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                        Text(opt, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else {
            // Text answer
            OutlinedTextField(
                value         = textValue,
                onValueChange = { textValue = it; onAnswer(it.text) },
                modifier      = Modifier.fillMaxWidth(),
                label         = { Text("Your answer") },
                minLines      = 2,
                enabled       = !revealed,
            )
        }

        // Reveal / Next
        if (question.type == "mcq") {
            if (answered != null && !revealed) {
                Button(onClick = onReveal, modifier = Modifier.fillMaxWidth()) { Text("Check Answer") }
            }
            if (revealed) {
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                    Text(if (index + 1 < 8) "Next Question" else "Finish")
                }
            }
        } else {
            if (!revealed) {
                OutlinedButton(onClick = onReveal, modifier = Modifier.fillMaxWidth()) { Text("Reveal Answer") }
            }
            if (revealed) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Correct answer:", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(question.answer, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                    Text(if (index + 1 < 8) "Next Question" else "Finish")
                }
            }
        }
    }
}

@Composable
private fun SummaryView(summary: String, score: Int, total: Int) {
    Column(
        modifier            = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Assessment Complete!", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary)
        Text("MCQ Score: $score / $total auto-graded",
            style = MaterialTheme.typography.titleMedium)

        if (summary.isNotBlank()) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Feedback", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Text(summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            CircularProgressIndicator()
            Text("Generating feedback…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
