package com.krishanagarwal.mynoo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krishanagarwal.mynoo.data.model.ChildState

private data class SubjectCard(val name: String, val emoji: String, val lang: String)

private val SUBJECTS = listOf(
    SubjectCard("Hindi",          "📖", "hi"),
    SubjectCard("English",        "🔤", "en"),
    SubjectCard("Punjabi",        "✍️", "pa"),
    SubjectCard("Mathematics",    "🔢", "en"),
    SubjectCard("Science",        "🔬", "en"),
    SubjectCard("Social Studies", "🌍", "en"),
    SubjectCard("Computer",       "💻", "en"),
)

@Composable
fun LibraryScreen(
    childState:                ChildState,
    onNavigateToChapterList:   (classNum: String, subject: String, lang: String) -> Unit,
    onNavigateToAssessmentList: (lang: String, childName: String, subject: String) -> Unit,
) {
    val classNum = childState.classNum.ifBlank { "7" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp),
    ) {
        Text(
            text  = "What would you like to read?",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text  = "Class $classNum · ${childState.name}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        LazyVerticalGrid(
            columns               = GridCells.Fixed(2),
            verticalArrangement   = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding        = PaddingValues(bottom = 24.dp),
        ) {
            items(SUBJECTS) { subj ->
                SubjectTile(
                    subject    = subj,
                    onReadClick  = { onNavigateToChapterList(classNum, subj.name, subj.lang) },
                    onQuizClick  = { onNavigateToAssessmentList(subj.lang, childState.name, subj.name) },
                )
            }
        }
    }
}

@Composable
private fun SubjectTile(
    subject:     SubjectCard,
    onReadClick: () -> Unit,
    onQuizClick: () -> Unit,
) {
    Surface(
        shape          = RoundedCornerShape(16.dp),
        color          = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp,
        modifier       = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier            = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Subject identity row
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(subject.emoji, fontSize = 22.sp)
                Text(
                    text      = subject.name,
                    style     = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color     = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines  = 2,
                    textAlign = TextAlign.Start,
                    modifier  = Modifier.weight(1f),
                )
            }
            // Action buttons
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilledTonalButton(
                    onClick         = onReadClick,
                    modifier        = Modifier.weight(1f),
                    contentPadding  = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    shape           = RoundedCornerShape(8.dp),
                ) {
                    Text("📖 Read", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                OutlinedButton(
                    onClick         = onQuizClick,
                    modifier        = Modifier.weight(1f),
                    contentPadding  = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    shape           = RoundedCornerShape(8.dp),
                    border          = ButtonDefaults.outlinedButtonBorder,
                ) {
                    Text("✏️ Quiz", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}
