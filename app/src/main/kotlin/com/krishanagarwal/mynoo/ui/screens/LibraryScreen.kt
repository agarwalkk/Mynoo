package com.krishanagarwal.mynoo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.krishanagarwal.mynoo.data.model.ChildState

private val SUBJECTS = listOf(
    "Hindi", "English", "Punjabi", "Mathematics", "Science", "Social Studies", "Computer"
)

@Composable
fun LibraryScreen(
    childState: ChildState,
    onNavigateToChapterList: (classNum: String, subject: String, lang: String) -> Unit,
) {
    val classNum = childState.classNum.ifBlank { "7" }
    var selected by remember { mutableStateOf("Hindi") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text  = "Library",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text  = "Class $classNum · ${childState.name}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SUBJECTS) { subj ->
                FilterChip(
                    selected = selected == subj,
                    onClick  = { selected = subj },
                    label    = { Text(subj) },
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        val lang = when (selected) { "Hindi" -> "hi"; "Punjabi" -> "pa"; else -> "en" }

        Button(
            onClick  = { onNavigateToChapterList(classNum, selected, lang) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open $selected chapters") }
    }
}
