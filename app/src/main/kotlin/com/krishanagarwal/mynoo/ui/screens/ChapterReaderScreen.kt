package com.krishanagarwal.mynoo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.content.Intent
import android.net.Uri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.krishanagarwal.mynoo.data.repository.ChapterParagraph
import com.krishanagarwal.mynoo.data.repository.ChapterSentence
import com.krishanagarwal.mynoo.data.repository.MediaItem
import com.krishanagarwal.mynoo.ui.viewmodel.LearnViewModel
import com.krishanagarwal.mynoo.ui.viewmodel.VocabPopupState

@OptIn(ExperimentalMaterial3Api::class)

@Composable
fun ChapterReaderScreen(
    classNum:  String,
    subject:   String,
    chapterId: String,
    lang:      String,
    title:     String,
    onBackClick: () -> Unit,
    vm: LearnViewModel = hiltViewModel(),
) {
    val ui        by vm.reader.collectAsState()
    val listState  = rememberLazyListState()

    var meaningPopup       by remember { mutableStateOf<ChapterSentence?>(null) }
    var mediaPopup         by remember { mutableStateOf<MediaItem?>(null) }
    var sliderDragPosition by remember { mutableStateOf<Float?>(null) }
    val onWordTap: (String, String) -> Unit = { word, sentText -> vm.tapWord(word, sentText, subject) }

    LaunchedEffect(classNum, subject, chapterId) {
        vm.loadContent(classNum, subject, chapterId, title)
    }

    // Check audio existence once content finishes loading
    LaunchedEffect(ui.loading) {
        if (!ui.loading && ui.error == null) {
            vm.checkAudio(classNum, subject, chapterId)
        }
    }

    // Auto-scroll to the paragraph that contains the active sentence
    val paragraphs = ui.content.paragraphs
    LaunchedEffect(ui.activeSentenceId) {
        val id = ui.activeSentenceId ?: return@LaunchedEffect
        val paraIdx = paragraphs.indexOfFirst { para ->
            para.sentences.any { it.id == id } || para.id == id
        }
        if (paraIdx >= 0) listState.animateScrollToItem(paraIdx.coerceAtLeast(0))
    }

    // Stop playback when the screen leaves composition
    DisposableEffect(Unit) {
        onDispose { vm.stopPlayback() }
    }

    Column(Modifier.fillMaxSize()) {

        // ── Fixed header: chapter title + listen icon ─────────────────────────
        ChapterHeader(
            title         = ui.title,
            audioChecking = ui.audioChecking,
            loading       = ui.loading,
            hasError      = ui.error != null,
            hasAudio      = ui.hasAudio,
            isPlaying     = ui.isPlaying,
            onListenClick = {
                if (ui.isPlaying) vm.stopPlayback()
                else vm.playChapter(classNum, subject, chapterId)
            },
            onBackClick   = onBackClick,
        )
        HorizontalDivider()

        Box(Modifier.weight(1f)) {
            when {
                ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                ui.error != null -> Box(
                    Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ui.error!!, color = MaterialTheme.colorScheme.error)
                }
                else -> LazyColumn(
                    state          = listState,
                    modifier       = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(
                        top    = 16.dp,
                        bottom = if (ui.isPlaying || ui.isPaused) 120.dp else 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(paragraphs, key = { it.id }) { para ->
                        ParagraphBlock(
                            para             = para,
                            activeSentenceId = ui.activeSentenceId,
                            // Suppress word highlight on underlying text while popup is playing
                            activeWordIndex  = if (ui.popupIsPlaying) null else ui.activeWordIndex,
                            subject          = subject,
                            onLongPress      = {
                                meaningPopup = it
                                vm.setResumePoint(it.id)
                            },
                            onWordTap        = onWordTap,
                            onMediaClick     = { item -> mediaPopup = item },
                        )
                    }
                }
            }

            // ── Playback control bar (visible only while chapter is playing) ───
            if ((ui.isPlaying || ui.isPaused) && !ui.popupIsPlaying) {
                Surface(
                    modifier       = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    color          = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 4.dp,
                ) {
                    Column(
                        modifier            = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Speed chips
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0.5f, 0.75f, 1.0f).forEach { speed ->
                                FilterChip(
                                    selected = ui.playbackSpeed == speed,
                                    onClick  = { vm.setSpeed(speed) },
                                    label    = { Text(if (speed == 1.0f) "1×" else "${speed}×") },
                                )
                            }
                        }
                        // Seek slider
                        if (ui.totalDurationMs > 0L) {
                            Slider(
                                value         = sliderDragPosition
                                    ?: (ui.playbackPositionMs.toFloat() / ui.totalDurationMs.toFloat()).coerceIn(0f, 1f),
                                onValueChange = { sliderDragPosition = it },
                                onValueChangeFinished = {
                                    sliderDragPosition?.let { pos ->
                                        vm.seekTo((pos * ui.totalDurationMs).toLong())
                                    }
                                    sliderDragPosition = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        // Pause / stop controls
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            IconButton(onClick = { vm.pauseResume() }) {
                                Icon(
                                    imageVector        = if (ui.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (ui.isPaused) "Resume" else "Pause",
                                )
                            }
                            IconButton(onClick = { vm.stopPlayback() }) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop")
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text  = if (ui.isPaused) "Paused" else "Playing…",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Vocab word popup (single tap) ─────────────────────────────────────────
    ui.vocabPopup?.let { popup ->
        VocabPopupDialog(
            state     = popup,
            playing   = ui.vocabWordPlaying,
            subject   = subject,
            onGenerate = { word, sentence -> vm.generateWordMeaning(word, sentence, subject) },
            onRetry    = { word, sentence -> vm.retryWordMeaning(word, sentence, subject) },
            onPlayStop = {
                when (val p = popup) {
                    is VocabPopupState.Found -> vm.toggleVocabAudio(p.entry.audioPath)
                    else -> {}
                }
            },
            onDismiss  = { vm.dismissVocabPopup() },
        )
    }

    // ── Photo viewer dialog ─────────────────────────────────────────────────────────
    mediaPopup?.let { item ->
        val ctx = LocalContext.current
        Dialog(
            onDismissRequest = { mediaPopup = null },
            properties       = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                AsyncImage(
                    model             = ImageRequest.Builder(ctx)
                        .data(item.url)
                        .addHeader("User-Agent", "Mozilla/5.0")
                        .build(),
                    contentDescription = item.caption.ifBlank { "Photo" },
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier.fillMaxSize(),
                )
                if (item.caption.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                        color    = Color.Black.copy(alpha = 0.6f),
                    ) {
                        Text(
                            text      = item.caption,
                            style     = MaterialTheme.typography.bodyMedium,
                            color     = Color.White,
                            textAlign = TextAlign.Center,
                            modifier  = Modifier.padding(12.dp),
                        )
                    }
                }
                IconButton(
                    onClick  = { mediaPopup = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    Icon(
                        imageVector        = Icons.Default.Close,
                        contentDescription = "Close",
                        tint               = Color.White,
                    )
                }
            }
        }
    }

    // ── Sentence meaning bottom sheet (long press) ────────────────────────────
    meaningPopup?.let { sent ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                meaningPopup = null
                if (ui.popupIsPlaying) vm.stopPlayback()
            },
            sheetState = sheetState,
        ) {
            Column(
                modifier            = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Sentence with word highlight when playing
                val wordHighBg = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
                val sentDisplay = if (ui.popupIsPlaying && ui.activeWordIndex != null) {
                    buildWordHighlightedText(sent.text, ui.activeWordIndex!!, wordHighBg)
                } else {
                    renderInline(sent.text)
                }
                Text(
                    text  = sentDisplay,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                if (sent.meaning.isNotBlank()) {
                    HorizontalDivider()
                    Text(
                        text  = renderMeaning(sent.meaning),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                HorizontalDivider()
                // Speed chips
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.5f, 0.75f, 1.0f).forEach { speed ->
                        FilterChip(
                            selected = ui.playbackSpeed == speed,
                            onClick  = { vm.setSpeed(speed) },
                            label    = { Text(if (speed == 1.0f) "1×" else "${speed}×") },
                        )
                    }
                }
                // Play / stop sentence
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledIconButton(
                        onClick = {
                            if (ui.popupIsPlaying) vm.stopPlayback()
                            else vm.playSentence(classNum, subject, chapterId, sent.id)
                        },
                    ) {
                        Icon(
                            imageVector        = if (ui.popupIsPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (ui.popupIsPlaying) "Stop" else "Play sentence",
                        )
                    }
                    Text(
                        text  = if (ui.popupIsPlaying) "Playing…" else "Listen to sentence",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

// ── Paragraph renderer ────────────────────────────────────────────────────────

@Composable
private fun ParagraphBlock(
    para:             ChapterParagraph,
    activeSentenceId: String?,
    activeWordIndex:  Int?,
    subject:          String,
    onLongPress:      (ChapterSentence) -> Unit,
    onWordTap:        (word: String, sentenceText: String) -> Unit,
    onMediaClick:     (MediaItem) -> Unit,
) {
    val context = LocalContext.current
    when (para.type) {
        "heading" -> Text(
            text  = para.text.stripMd(),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        )
        "subheading" -> Text(
            text  = para.text.stripMd(),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        "attribution" -> Text(
            text      = renderInline(para.text),
            style     = MaterialTheme.typography.bodyMedium.copy(
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.End,
            ),
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier  = Modifier.fillMaxWidth(),
        )
        "verse" -> {
            val verseText = if (para.sentences.isNotEmpty())
                para.sentences.joinToString("\n") { it.text }
            else para.text
            Text(
                text     = renderInline(verseText),
                style    = MaterialTheme.typography.bodyMedium.copy(
                    fontStyle  = FontStyle.Italic,
                    lineHeight = 28.sp,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        "equation" -> OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text      = para.text,
                style     = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier  = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            )
        }
        "blockquote" -> Surface(
            shape    = RoundedCornerShape(8.dp),
            color    = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ParagraphText(
                sentences        = para.sentences,
                activeSentenceId = activeSentenceId,
                activeWordIndex  = activeWordIndex,
                subject          = subject,
                onLongPress      = onLongPress,
                onWordTap        = onWordTap,
                paraId           = para.id,
                modifier         = Modifier.padding(12.dp),
                style            = MaterialTheme.typography.bodyMedium.copy(
                    fontStyle  = FontStyle.Italic,
                    lineHeight = 26.sp,
                ),
            )
        }
        "activity", "callout", "note" -> {
            val label = para.title.ifBlank { para.type.replaceFirstChar { it.uppercase() } }
            Surface(
                shape    = RoundedCornerShape(12.dp),
                color    = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text  = label,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    ParagraphText(
                        sentences        = para.sentences,
                        activeSentenceId = activeSentenceId,
                        activeWordIndex  = activeWordIndex,
                        subject          = subject,
                        onLongPress      = onLongPress,
                        onWordTap        = onWordTap,
                        paraId           = para.id,
                        style            = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
                    )
                }
            }
        }
        "list" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            para.items.forEachIndexed { i, item ->
                Row {
                    Text(
                        text  = if (para.ordered) "${i + 1}. " else "• ",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(renderInline(item), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        "media" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            para.mediaItems.forEach { item ->
                OutlinedCard(
                    onClick  = {
                        if (item.mediaType == "video") {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.url))
                            context.startActivity(intent)
                        } else {
                            onMediaClick(item)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier              = Modifier.padding(12.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text  = if (item.mediaType == "video") "▶" else "🖼️",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text  = item.caption.ifBlank { if (item.mediaType == "video") "Video" else "Photo" },
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            )
                            Text(
                                text  = if (item.mediaType == "video") "Tap to play video" else "Tap to view photo",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
        else -> {
            if (para.sentences.isNotEmpty()) {
                ParagraphText(
                    sentences        = para.sentences,
                    activeSentenceId = activeSentenceId,
                    activeWordIndex  = activeWordIndex,
                    subject          = subject,
                    onLongPress      = onLongPress,
                    onWordTap        = onWordTap,
                    paraId           = para.id,
                )
            } else if (para.text.isNotBlank()) {
                // Treat the paragraph text as a single sentence so tap/long-press work
                ParagraphText(
                    sentences        = listOf(ChapterSentence(id = para.id, text = para.text)),
                    activeSentenceId = activeSentenceId,
                    activeWordIndex  = activeWordIndex,
                    subject          = subject,
                    onLongPress      = onLongPress,
                    onWordTap        = onWordTap,
                    paraId           = para.id,
                )
            }
        }
    }
}

// ── Flow-text paragraph (all sentences combined inline) ───────────────────────

@Composable
private fun ParagraphText(
    sentences:        List<ChapterSentence>,
    activeSentenceId: String?,
    activeWordIndex:  Int?,
    subject:          String,
    onLongPress:      (ChapterSentence) -> Unit,
    onWordTap:        (word: String, sentenceText: String) -> Unit,
    paraId:           String   = "",
    modifier:         Modifier  = Modifier,
    style:            TextStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
) {
    if (sentences.isEmpty()) return

    val activeBg   = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    val wordHighBg = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)

    // Paragraph-level active: activeSentenceId is the paragraph's own ID
    // (happens when all sentence IDs are blank and we fall back to paragraph-level audio)
    val isParaActive = paraId.isNotBlank() &&
                       activeSentenceId == paraId &&
                       sentences.none { it.id == activeSentenceId }

    val (annotated, sentRanges) = remember(
        sentences, activeSentenceId, activeWordIndex, activeBg, wordHighBg, isParaActive,
    ) {
        val ranges  = mutableListOf<IntRange>()
        val builder = AnnotatedString.Builder()

        sentences.forEachIndexed { idx, sent ->
            val start    = builder.length
            val isActive = isParaActive || sent.id == activeSentenceId

            if (isActive) {
                builder.pushStyle(SpanStyle(background = activeBg))
                // Word highlight only applies to single-sentence active, not para-level
                if (!isParaActive && activeWordIndex != null) {
                    builder.append(buildWordHighlightedText(sent.text, activeWordIndex, wordHighBg))
                } else {
                    builder.append(renderInline(sent.text))
                }
                builder.pop()
            } else {
                builder.append(renderInline(sent.text))
            }

            ranges.add(start until builder.length)
            if (idx < sentences.lastIndex) builder.append(" ")
        }

        builder.toAnnotatedString() to ranges.toList()
    }

    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text         = annotated,
        style        = style,
        onTextLayout = { layoutResult = it },
        modifier     = modifier
            .fillMaxWidth()
            .pointerInput(sentences, sentRanges, subject) {
                detectTapGestures(
                    onTap = { offset ->
                        val charIdx = layoutResult?.getOffsetForPosition(offset)
                            ?: return@detectTapGestures
                        val sentIdx = sentRanges.indexOfFirst { charIdx in it }
                        if (sentIdx < 0) return@detectTapGestures
                        val sent     = sentences[sentIdx]
                        val posInSent = charIdx - sentRanges[sentIdx].first
                        val word     = findWordAtPos(renderInline(sent.text).text, posInSent, subject)
                        if (word != null) onWordTap(word, sent.text)
                    },
                    onLongPress = { offset ->
                        val charIdx = layoutResult?.getOffsetForPosition(offset)
                            ?: return@detectTapGestures
                        val sentIdx = sentRanges.indexOfFirst { charIdx in it }
                        if (sentIdx >= 0) onLongPress(sentences[sentIdx])
                    },
                )
            },
    )
}

// ── Text builders ─────────────────────────────────────────────────────────────

/**
 * Builds an AnnotatedString where the word at [highlightIdx] (0-based, whitespace-split)
 * gets a background highlight. Inline markdown (**bold** / *italic*) is stripped.
 */
private fun buildWordHighlightedText(text: String, highlightIdx: Int, highlightColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var wordCount = 0
        var pos = 0
        while (pos < text.length) {
            // Collect whitespace run
            val wsStart = pos
            while (pos < text.length && text[pos].isWhitespace()) pos++
            if (pos > wsStart) append(text.substring(wsStart, pos))
            // Collect non-whitespace token
            val tokStart = pos
            while (pos < text.length && !text[pos].isWhitespace()) pos++
            if (pos > tokStart) {
                val raw      = text.substring(tokStart, pos)
                val stripped = raw.replace(Regex("""\*+"""), "")
                val isBold   = raw.startsWith("**") && raw.endsWith("**") && raw.length > 4
                if (wordCount == highlightIdx) {
                    withStyle(SpanStyle(background = highlightColor)) {
                        append(stripped)
                    }
                } else {
                    if (isBold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(stripped) }
                    else append(stripped)
                }
                wordCount++
            }
        }
    }
}

private fun renderInline(text: String): AnnotatedString = buildAnnotatedString {
    val boldRe   = Regex("""\*\*(.+?)\*\*""")
    val italicRe = Regex("""\*(.+?)\*""")
    var last = 0
    val combined = buildList {
        boldRe.findAll(text).forEach   { add(Triple(it.range.first, it.range.last + 1, "bold"   to it.groupValues[1])) }
        italicRe.findAll(text).forEach { add(Triple(it.range.first, it.range.last + 1, "italic" to it.groupValues[1])) }
    }.sortedBy { it.first }
    for ((start, end, pair) in combined) {
        if (start >= last) {
            append(text.substring(last, start))
            val (style, content) = pair
            when (style) {
                "bold"   -> withStyle(SpanStyle(fontWeight = FontWeight.Bold))  { append(content) }
                "italic" -> withStyle(SpanStyle(fontStyle  = FontStyle.Italic)) { append(content) }
            }
            last = end
        }
    }
    if (last < text.length) append(text.substring(last))
}

private fun String.stripMd() = this
    .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
    .replace(Regex("""\*(.+?)\*"""), "$1")

/**
 * Renders meaning text: replaces " | " separators with newlines,
 * then applies inline markdown (** = bold, * = italic).
 */
private fun renderMeaning(text: String): AnnotatedString {
    val normalized = text.replace(Regex("""\s*\|\s*"""), "\n")
    return renderInline(normalized)
}

// ── Fixed chapter header ──────────────────────────────────────────────────────

@Composable
private fun ChapterHeader(
    title:         String,
    audioChecking: Boolean,
    loading:       Boolean,
    hasError:      Boolean,
    hasAudio:      Boolean,
    isPlaying:     Boolean,
    onListenClick: () -> Unit,
    onBackClick:   () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
            Text(
                text     = title,
                style    = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                loading || hasError -> {
                    // No audio control while loading or error
                }
                audioChecking -> CircularProgressIndicator(
                    modifier    = Modifier.size(24.dp).padding(start = 8.dp),
                    strokeWidth = 2.dp,
                )
                hasAudio -> IconButton(onClick = onListenClick) {
                    Icon(
                        imageVector        = if (isPlaying) Icons.Default.Stop else Icons.Default.VolumeUp,
                        contentDescription = if (isPlaying) "Stop" else "Listen to chapter",
                        tint               = if (isPlaying) MaterialTheme.colorScheme.error
                                             else MaterialTheme.colorScheme.primary,
                    )
                }
                else -> IconButton(onClick = {}, enabled = false) {
                    Icon(
                        imageVector        = Icons.Default.VolumeOff,
                        contentDescription = "No audio available",
                        tint               = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    )
                }
            }
        }
    }
}

// ── Vocab word popup dialog ────────────────────────────────────────────────────

@Composable
private fun VocabPopupDialog(
    state:      VocabPopupState,
    playing:    Boolean,
    subject:    String,
    onGenerate: (word: String, sentence: String) -> Unit,
    onRetry:    (word: String, sentence: String) -> Unit,
    onPlayStop: () -> Unit,
    onDismiss:  () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text  = when (state) {
                    is VocabPopupState.Loading    -> state.word
                    is VocabPopupState.Found      -> state.word
                    is VocabPopupState.Ask        -> state.word
                    is VocabPopupState.Generating -> state.word
                    is VocabPopupState.Error      -> state.word
                },
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            when (state) {
                is VocabPopupState.Loading -> Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Finding meaning…", style = MaterialTheme.typography.bodyMedium)
                }

                is VocabPopupState.Found -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text  = state.entry.meaning,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (state.entry.translation.isNotBlank()) {
                        HorizontalDivider()
                        Text(
                            text  = state.entry.translation,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontStyle = FontStyle.Italic,
                                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    FilledTonalIconButton(onClick = onPlayStop) {
                        Icon(
                            imageVector        = if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Stop" else "Listen to word",
                        )
                    }
                }

                is VocabPopupState.Ask -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text  = "Meaning not found in dictionary.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text  = "Generate meaning using AI?",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                is VocabPopupState.Generating -> Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Generating meaning…", style = MaterialTheme.typography.bodyMedium)
                }

                is VocabPopupState.Error -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text  = "Could not generate meaning.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text  = state.msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                is VocabPopupState.Ask ->
                    Button(onClick = { onGenerate(state.word, state.sentence) }) {
                        Text("Yes, generate!")
                    }
                is VocabPopupState.Error ->
                    Button(onClick = { onRetry(state.word, state.sentence) }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Try again")
                    }
                else -> {}
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

// ── Word tap helper ───────────────────────────────────────────────────────────

private val PUNCT_STRIP = Regex("""^[,!?:;—"'"'«»()\[\]।॥।.]+|[,!?:;—"'"'«»()\[\]।॥।.]+$""")

/**
 * Given the rendered (markdown-stripped) text of a sentence and a character position
 * [pos] within it, returns the tappable word at that position or null.
 */
private fun findWordAtPos(text: String, pos: Int, subject: String): String? {
    if (pos < 0) return null
    var charPos = 0
    for (match in Regex("""\S+|\s+""").findAll(text)) {
        val part = match.value
        val end  = charPos + part.length
        if (!part.isBlank() && pos in charPos until end) {
            val stripped = part.replace(PUNCT_STRIP, "")
            // Accept any word that contains at least one Unicode letter (works for all scripts)
            return stripped.takeIf { w -> w.any { c -> c.isLetter() } }
        }
        charPos = end
    }
    return null
}
