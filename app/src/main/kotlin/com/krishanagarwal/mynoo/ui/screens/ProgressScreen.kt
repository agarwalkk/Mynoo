package com.krishanagarwal.mynoo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.krishanagarwal.mynoo.data.model.ChildState
import com.krishanagarwal.mynoo.ui.viewmodel.ProgressViewModel
import java.time.LocalDate

@Composable
fun ProgressScreen(
    childState: ChildState,
    onNavigateToAssessmentList: (lang: String, childName: String, subject: String) -> Unit,
    onNavigateToAssessment: (assessmentId: String, childName: String) -> Unit,
    vm: ProgressViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()

    LaunchedEffect(childState.name) {
        if (childState.name.isNotBlank()) vm.load(childState.name)
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            text  = "Progress",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
        )
        Text("${childState.name}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        if (ui.loading) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (ui.error != null) {
            Text(ui.error!!, color = MaterialTheme.colorScheme.error)
        } else {
            // Stat cards
            Row(
                modifier            = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(Modifier.weight(1f), label = "Streak", value = "${ui.streak} days", emoji = "??")
                StatCard(Modifier.weight(1f), label = "Sessions", value = "${ui.sessions.size}", emoji = "??")
                StatCard(Modifier.weight(1f), label = "Minutes", value = "${ui.totalMinutes}", emoji = "?")
            }

            Spacer(Modifier.height(20.dp))

            // Mood distribution
            if (ui.moodGreat + ui.moodOkay + ui.moodHard > 0) {
                Text("Mood", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoodChip("Great ??", ui.moodGreat, MaterialTheme.colorScheme.primaryContainer)
                    MoodChip("Okay ??", ui.moodOkay,  MaterialTheme.colorScheme.secondaryContainer)
                    MoodChip("Hard ??", ui.moodHard,  MaterialTheme.colorScheme.tertiaryContainer)
                }
                Spacer(Modifier.height(20.dp))
            }

            // 30-day heatmap
            Text("Last 30 Days", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(8.dp))
            HeatmapGrid(heatmap = ui.heatmap)

            Spacer(Modifier.height(20.dp))

            // Start assessment button
            Button(
                onClick  = { onNavigateToAssessmentList("en", childState.name, "") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start an Assessment") }
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier, label: String, value: String, emoji: String) {
    ElevatedCard(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier            = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(emoji, style = MaterialTheme.typography.titleLarge)
            Text(value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MoodChip(label: String, count: Int, color: Color) {
    Surface(shape = RoundedCornerShape(20.dp), color = color) {
        Text(
            text     = "$label: $count",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style    = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun HeatmapGrid(heatmap: Map<LocalDate, Int>) {
    val today  = LocalDate.now()
    val days   = (29 downTo 0).map { today.minusDays(it.toLong()) }
    val maxVal = heatmap.values.maxOrNull() ?: 1

    LazyVerticalGrid(
        columns               = GridCells.Fixed(7),
        modifier              = Modifier.height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement   = Arrangement.spacedBy(4.dp),
    ) {
        items(days) { date ->
            val count   = heatmap[date] ?: 0
            val alpha   = if (count == 0) 0.08f else (count.toFloat() / maxVal).coerceIn(0.2f, 1f)
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
            )
        }
    }
}
