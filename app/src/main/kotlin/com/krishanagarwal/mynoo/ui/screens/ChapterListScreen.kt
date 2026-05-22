package com.krishanagarwal.mynoo.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.krishanagarwal.mynoo.ui.viewmodel.LibraryViewModel

@Composable
fun ChapterListScreen(
    classNum: String,
    subject:  String,
    lang:     String,
    onChapterClick: (chapterId: String, title: String) -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val ui by vm.lib.collectAsState()

    LaunchedEffect(classNum, subject) { vm.loadChapters(classNum, subject) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text  = subject,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
        )
        Text("Class $classNum", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ui.error != null -> Text(ui.error!!, color = MaterialTheme.colorScheme.error)
            ui.chapters.isEmpty() -> Text("No chapters available for this subject yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.chapters, key = { it.id }) { chapter ->
                    ElevatedCard(
                        modifier  = Modifier.fillMaxWidth().clickable {
                            onChapterClick(chapter.id, chapter.title)
                        },
                        shape     = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier            = Modifier.padding(16.dp),
                            verticalAlignment   = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, null,
                                tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(chapter.title,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
                                if (chapter.wordCount > 0) {
                                    Text("${chapter.wordCount} words",
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
