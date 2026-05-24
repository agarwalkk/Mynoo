package com.krishanagarwal.mynoo.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
fun LearnScreen(
    childState:                 ChildState,
    onNavigateToChapterList:    (classNum: String, subject: String, lang: String) -> Unit,
    onNavigateToAssessmentList: (lang: String, childName: String, subject: String) -> Unit,
) {
    val classNum = childState.classNum.ifBlank { "7" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            val name = childState.name.ifBlank { "there" }
            Text(
                text  = "What would you like to learn, $name?",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = "Class $classNum · ${childState.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))

        LazyVerticalGrid(
            columns               = GridCells.Fixed(2),
            verticalArrangement   = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
    val themeColor = remember(subject.name) {
        val slug = subject.name.lowercase().trim()
        when (slug) {
            "hindi" -> Color(0xFFE67E22)
            "english" -> Color(0xFF27AE60)
            "punjabi" -> Color(0xFF8E44AD)
            "mathematics", "math" -> Color(0xFF2980B9)
            "science" -> Color(0xFF16A085)
            "social studies", "social_studies" -> Color(0xFFC0392B)
            "computer" -> Color(0xFF7F8C8D)
            else -> Color(0xFF2980B9)
        }
    }

    ElevatedCard(
        shape          = RoundedCornerShape(16.dp),
        colors         = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation      = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        modifier       = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier            = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Subject identity row
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(subject.emoji, fontSize = 24.sp)
                Text(
                    text      = subject.name,
                    style     = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color     = MaterialTheme.colorScheme.onSurface,
                    maxLines  = 2,
                    textAlign = TextAlign.Start,
                    modifier  = Modifier.weight(1f),
                )
            }
            // Action buttons
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick         = onReadClick,
                    modifier        = Modifier.weight(1.2f).height(36.dp),
                    contentPadding  = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    shape           = RoundedCornerShape(8.dp),
                    colors          = ButtonDefaults.buttonColors(
                        containerColor = themeColor,
                        contentColor = Color.White
                    )
                ) {
                    Text("📖 Learn", style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                }
                OutlinedButton(
                    onClick         = onQuizClick,
                    modifier        = Modifier.weight(1.0f).height(36.dp),
                    contentPadding  = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    shape           = RoundedCornerShape(8.dp),
                    border          = BorderStroke(1.dp, themeColor.copy(alpha = 0.5f)),
                    colors          = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = themeColor
                    )
                ) {
                    Text("✏️ Quiz", style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
                }
            }
        }
    }
}
