package com.krishanagarwal.mynoo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import com.krishanagarwal.mynoo.ui.viewmodel.LearnViewModel

@Composable
fun ChapterListScreen(
    classNum: String,
    subject:  String,
    lang:     String,
    onChapterClick: (chapterId: String, title: String) -> Unit,
    vm: LearnViewModel = hiltViewModel(),
) {
    val ui by vm.learn.collectAsState()
    val themeColor = remember(subject) {
        val slug = subject.lowercase().trim()
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

    LaunchedEffect(classNum, subject) { vm.loadChapters(classNum, subject) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))

        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.error != null -> Text(ui.error!!, color = MaterialTheme.colorScheme.error)
            ui.chapters.isEmpty() -> Text(
                "No chapters available for this subject yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ui.chapters, key = { it.id }) { chapter ->
                    ElevatedCard(
                        modifier  = Modifier
                            .fillMaxWidth()
                            .clickable { onChapterClick(chapter.id, chapter.title) },
                        shape     = RoundedCornerShape(14.dp),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                    ) {
                        Row(
                            modifier          = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Chapter number badge
                            Box(
                                modifier        = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(themeColor.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text  = if (chapter.order > 0) "${chapter.order}" else "",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = themeColor,
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            // Title
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text  = chapter.title,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            // Chevron
                            Icon(
                                imageVector        = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
