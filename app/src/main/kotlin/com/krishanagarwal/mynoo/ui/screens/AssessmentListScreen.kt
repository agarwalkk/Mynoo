package com.krishanagarwal.mynoo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.krishanagarwal.mynoo.ui.viewmodel.AssessmentViewModel

private val SUBJECTS = listOf(
    "Hindi", "English", "Punjabi", "Mathematics", "Science", "Social Studies", "Computer"
)

@Composable
fun AssessmentListScreen(
    lang:      String,
    childName: String,
    subject:   String,
    onStartAssessment: (assessmentId: String, childName: String) -> Unit,
    onNavigateToAssessment: (assessmentId: String, childName: String) -> Unit,
    vm: AssessmentViewModel = hiltViewModel(),
) {
    val listState by vm.list.collectAsState()
    val quizState by vm.quiz.collectAsState()

    var selectedSubject by remember { mutableStateOf(subject.ifBlank { "English" }) }

    LaunchedEffect(childName) { if (childName.isNotBlank()) vm.loadAssessments(childName) }

    // Navigate when assessment is generated
    LaunchedEffect(quizState.assessment) {
        val a = quizState.assessment
        if (a != null && a.id.isNotBlank() && !quizState.generating) {
            onStartAssessment(a.id, childName)
        }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    vm.generateAssessment(
                        childName = childName,
                        subject   = selectedSubject,
                        classNum  = "7", // from child state; passed if needed
                        lang      = lang.ifBlank { "en" },
                    )
                },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New Assessment") },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Assessments",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary)
            Text(childName, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SUBJECTS) { subj ->
                    FilterChip(selected = selectedSubject == subj,
                        onClick = { selectedSubject = subj },
                        label = { Text(subj) })
                }
            }
            Spacer(Modifier.height(16.dp))

            if (quizState.generating) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Generating $selectedSubject questions…")
                }
                Spacer(Modifier.height(8.dp))
            }

            when {
                listState.loading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                listState.assessments.isEmpty() -> Text("No assessments yet. Tap + to start one.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listState.assessments, key = { it.id }) { a ->
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth().clickable {
                                onNavigateToAssessment(a.id, childName)
                            },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(a.subject, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                                Text("${a.questions.size} questions · ${a.date.take(10)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (a.status == "completed" && a.summary.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(a.summary.take(80) + "…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
