package com.krishanagarwal.mynoo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.krishanagarwal.mynoo.data.model.ChildState

@Composable
fun ParentDashboardScreen(
    childState: ChildState,
    onNavigateToProgress: () -> Unit
) {
    val models   = listOf("gemini-2.0-flash", "gemini-1.5-pro", "gemini-1.5-flash")
    val langs    = listOf("en" to "English", "hi" to "Hindi", "pa" to "Punjabi")

    var selectedModel by remember { mutableStateOf(models[0]) }
    var enDiff  by remember { mutableStateOf(5f) }
    var hiDiff  by remember { mutableStateOf(5f) }
    var paDiff  by remember { mutableStateOf(5f) }

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
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = "View Progress",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Model selector
        ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("AI Model", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                models.forEach { model ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = selectedModel == model, onClick = { selectedModel = model })
                        Text(model, style = MaterialTheme.typography.bodyMedium)
                    }
                }
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

        Button(onClick = { /* TODO: persist to Firestore kids/{name}/settings */ }, modifier = Modifier.fillMaxWidth()) {
            Text("Save Settings")
        }

        Text("Settings saved to Firestore at kids/${childState.name}/settings",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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
