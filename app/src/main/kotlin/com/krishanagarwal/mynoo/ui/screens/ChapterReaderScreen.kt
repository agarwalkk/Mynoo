package com.krishanagarwal.mynoo.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.krishanagarwal.mynoo.data.repository.ChapterParagraph
import com.krishanagarwal.mynoo.ui.viewmodel.LibraryViewModel

@Composable
fun ChapterReaderScreen(
    classNum:  String,
    subject:   String,
    chapterId: String,
    lang:      String,
    title:     String,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val ui by vm.reader.collectAsState()

    LaunchedEffect(classNum, subject, chapterId) {
        vm.loadContent(classNum, subject, chapterId, title)
    }

    when {
        ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        ui.error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(ui.error!!, color = MaterialTheme.colorScheme.error)
        }
        else -> LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.title.isNotBlank()) {
                item {
                    Text(ui.title,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(ui.content.paragraphs, key = { it.id }) { para ->
                ParagraphBlock(para)
            }
        }
    }
}

@Composable
private fun ParagraphBlock(para: ChapterParagraph) {
    when (para.type) {
        "heading" -> Text(
            text  = para.text.stripMd(),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        )
        "subheading" -> Text(
            text  = para.text.stripMd(),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        "blockquote" -> Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                para.sentences.forEach { sent ->
                    Text(sent.text.stripMd(), style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic))
                }
            }
        }
        "activity", "callout", "note" -> {
            val label = para.title.ifBlank { para.type.replaceFirstChar { it.uppercase() } }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.height(4.dp))
                    para.sentences.forEach { sent ->
                        Text(sent.text.stripMd(), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
        }
        "list" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            para.items.forEachIndexed { i, item ->
                Row {
                    Text("${i + 1}. ", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    Text(item.stripMd(), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            para.sentences.forEach { sent ->
                Text(
                    text  = renderInline(sent.text),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                )
            }
            if (para.sentences.isEmpty() && para.text.isNotBlank()) {
                Text(renderInline(para.text), style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp))
            }
        }
    }
}

@Composable
private fun renderInline(text: String) = buildAnnotatedString {
    val boldRe   = Regex("""\*\*(.+?)\*\*""")
    val italicRe = Regex("""\*(.+?)\*""")
    var last = 0
    val combined = buildList {
        boldRe.findAll(text).forEach { add(Triple(it.range.first, it.range.last + 1, "bold" to it.groupValues[1])) }
        italicRe.findAll(text).forEach { add(Triple(it.range.first, it.range.last + 1, "italic" to it.groupValues[1])) }
    }.sortedBy { it.first }
    for ((start, end, pair) in combined) {
        if (start >= last) {
            append(text.substring(last, start))
            val (style, content) = pair
            when (style) {
                "bold"   -> withStyle(SpanStyle(fontWeight = FontWeight.Bold))   { append(content) }
                "italic" -> withStyle(SpanStyle(fontStyle  = FontStyle.Italic))  { append(content) }
            }
            last = end
        }
    }
    if (last < text.length) append(text.substring(last))
}

private fun String.stripMd() = this
    .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
    .replace(Regex("""\*(.+?)\*"""), "$1")
