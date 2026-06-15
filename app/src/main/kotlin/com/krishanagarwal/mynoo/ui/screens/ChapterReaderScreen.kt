package com.krishanagarwal.mynoo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
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
import com.krishanagarwal.mynoo.data.repository.WordTiming
import com.krishanagarwal.mynoo.ui.theme.LocalMynooExtras
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

    var meaningPopup       by remember { mutableStateOf<ChapterSentence?>(null) }
    var mediaPopup         by remember { mutableStateOf<MediaItem?>(null) }
    var sliderDragPosition by remember { mutableStateOf<Float?>(null) }
    var fontSizeOffset     by remember { mutableStateOf(0) }
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
            title              = ui.title,
            themeColor         = themeColor,
            audioChecking      = ui.audioChecking,
            loading            = ui.loading,
            hasError           = ui.error != null,
            hasAudio           = ui.hasAudio,
            isPlaying          = ui.isPlaying,
            onListenClick      = {
                if (ui.isPlaying) vm.stopPlayback()
                else vm.playChapter(classNum, subject, chapterId)
            },
            onBackClick        = onBackClick,
            onFontSizeDecrease = { fontSizeOffset = (fontSizeOffset - 1).coerceAtLeast(-8) },
            onFontSizeIncrease = { fontSizeOffset = (fontSizeOffset + 1).coerceAtMost(12) },
        )

        if (ui.cacheDownloadProgress != null) {
            CacheDownloadProgressBand(
                progress = ui.cacheDownloadProgress!!,
                complete = ui.cacheDownloadComplete,
                themeColor = themeColor
            )
        }

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
                else -> {
                    val pullToRefreshState = rememberPullToRefreshState()
                    Box(
                        modifier = Modifier
                            .pullToRefresh(
                                state = pullToRefreshState,
                                isRefreshing = ui.isRefreshing,
                                onRefresh = { vm.refreshContent(classNum, subject, chapterId, title) },
                                threshold = 160.dp
                            )
                            .fillMaxSize()
                    ) {
                        LazyColumn(
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
                                    themeColor       = themeColor,
                                    fontSizeOffset   = fontSizeOffset,
                                    onLongPress      = {
                                        meaningPopup = it
                                        vm.setResumePoint(it.id)
                                    },
                                    onWordTap        = onWordTap,
                                    onMediaClick     = { item -> mediaPopup = item },
                                )
                            }
                        }
                        PullToRefreshDefaults.Indicator(
                            state = pullToRefreshState,
                            isRefreshing = ui.isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter)
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
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = themeColor,
                                        selectedLabelColor = Color.White
                                    )
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
                                colors = SliderDefaults.colors(
                                    thumbColor = themeColor,
                                    activeTrackColor = themeColor,
                                    inactiveTrackColor = themeColor.copy(alpha = 0.24f)
                                )
                            )
                        }
                        // Pause / stop / skip controls
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            IconButton(onClick = { vm.playPreviousSentence(classNum, subject, chapterId) }) {
                                Icon(
                                    imageVector        = Icons.Default.SkipPrevious,
                                    contentDescription = "Previous sentence",
                                )
                            }
                            IconButton(onClick = { vm.pauseResume() }) {
                                Icon(
                                    imageVector        = if (ui.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (ui.isPaused) "Resume" else "Pause",
                                )
                            }
                            IconButton(onClick = { vm.stopPlayback() }) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop")
                            }
                            IconButton(onClick = { vm.playNextSentence(classNum, subject, chapterId) }) {
                                Icon(
                                    imageVector        = Icons.Default.SkipNext,
                                    contentDescription = "Next sentence",
                                )
                            }
                            Spacer(Modifier.width(12.dp))
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
            state                = popup,
            playing              = ui.vocabWordPlaying,
            vocabActiveWordIndex = ui.vocabActiveWordIndex,
            vocabTimings         = ui.vocabTimings,
            subject              = subject,
            fontSizeOffset       = fontSizeOffset,
            onGenerate           = { word, sentence -> vm.generateWordMeaning(word, sentence, subject) },
            onRetry              = { word, sentence -> vm.retryWordMeaning(word, sentence, subject) },
            onPlayStop           = {
                when (val p = popup) {
                    is VocabPopupState.Found -> vm.toggleVocabAudio(p.entry)
                    else -> {}
                }
            },
            onDismiss            = { vm.dismissVocabPopup() },
        )
    }

    // ── Photo viewer dialog ─────────────────────────────────────────────────────────
    mediaPopup?.let { item ->
        val ctx = LocalContext.current
        val imgSource = remember(item.url) {
            vm.getImageSource(classNum, subject, chapterId, item.url)
        }
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
                        .data(imgSource)
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
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier            = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontStyle  = FontStyle.Italic,
                        color      = LocalMynooExtras.current.textLight,
                        lineHeight = 24.sp
                    ).scale(fontSizeOffset),
                )
                
                if (sent.meaning.isNotBlank()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        thickness = 2.dp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    Text(
                        text  = renderMeaning(sent.meaning),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Normal,
                            color      = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 26.sp,
                        ).scale(fontSizeOffset),
                    )
                }
                
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                
                // Play / stop & speed controls in same line
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledIconButton(
                            onClick = {
                                if (ui.popupIsPlaying) vm.pauseResume()
                                else vm.playSentence(classNum, subject, chapterId, sent.id)
                            },
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector        = if (ui.popupIsPlaying && !ui.isPaused) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (ui.popupIsPlaying && !ui.isPaused) "Pause" else "Play sentence",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        if (ui.popupIsPlaying) {
                            IconButton(
                                onClick = { vm.stopPlayback() },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector        = Icons.Default.Stop,
                                    contentDescription = "Stop",
                                    tint               = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    // Speed controls on the right (styled filter chips with teal brand accent)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        listOf(0.5f, 0.75f, 1.0f).forEach { speed ->
                            val isSelected = ui.playbackSpeed == speed
                            FilterChip(
                                selected = isSelected,
                                onClick  = { vm.setSpeed(speed) },
                                label    = {
                                    Text(
                                        text = when (speed) {
                                            1.0f -> "1×"
                                            0.75f -> "¾×"
                                            else -> "½×"
                                        },
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color.Transparent,
                                    labelColor = MaterialTheme.colorScheme.secondary,
                                    selectedContainerColor = MaterialTheme.colorScheme.secondary,
                                    selectedLabelColor = Color.White,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    selectedBorderColor = MaterialTheme.colorScheme.secondary,
                                    borderColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
                                    borderWidth = 1.dp,
                                    selectedBorderWidth = 1.dp
                                ),
                                shape = RoundedCornerShape(50)
                            )
                        }
                    }
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
    themeColor:       Color,
    fontSizeOffset:   Int,
    onLongPress:      (ChapterSentence) -> Unit,
    onWordTap:        (word: String, sentenceText: String) -> Unit,
    onMediaClick:     (MediaItem) -> Unit,
) {
    val context = LocalContext.current
    val defaultStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp).scale(fontSizeOffset)

    val sentences = remember(para.sentences, para.meaning) {
        if (para.sentences.isEmpty() && para.text.isNotBlank()) {
            listOf(ChapterSentence(id = para.id, text = para.text, meaning = para.meaning))
        } else {
            para.sentences.map { sent ->
                if (sent.meaning.isBlank()) {
                    sent.copy(meaning = para.meaning)
                } else {
                    sent
                }
            }
        }
    }

    val isTimeline = remember(para.title, para.type) {
        para.type == "note" && para.title.isNotBlank() && (
            para.title.contains(Regex("""\d+""")) && (
                para.title.contains("BCE", ignoreCase = true) ||
                para.title.contains("CE", ignoreCase = true) ||
                para.title.contains("BC", ignoreCase = true) ||
                para.title.contains("AD", ignoreCase = true) ||
                para.title.contains("century", ignoreCase = true) ||
                para.title.contains("-") ||
                para.title.contains("–")
            )
        )
    }

    when (para.type) {
        "heading" -> Text(
            text  = para.text.stripMd(),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold).scale(fontSizeOffset),
        )
        "subheading" -> Text(
            text  = para.text.stripMd(),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold).scale(fontSizeOffset),
        )
        "attribution" -> Text(
            text      = renderInline(para.text),
            style     = MaterialTheme.typography.bodyMedium.copy(
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.End,
            ).scale(fontSizeOffset),
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier  = Modifier.fillMaxWidth(),
        )
        "verse" -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 12.dp, bottomEnd = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFCF8F2)),
                border = BorderStroke(1.dp, Color(0xFFF0E4D2)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.drawBehind {
                        drawRect(
                            color = themeColor,
                            size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)
                        )
                    }.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "❝",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = themeColor.copy(alpha = 0.6f)
                        )
                    )
                    ParagraphText(
                        sentences        = sentences,
                        activeSentenceId = activeSentenceId,
                        activeWordIndex  = activeWordIndex,
                        subject          = subject,
                        themeColor       = themeColor,
                        onLongPress      = onLongPress,
                        onWordTap        = onWordTap,
                        paraId           = para.id,
                        separator        = "\n",
                        style            = MaterialTheme.typography.bodyLarge.copy(
                            fontStyle  = FontStyle.Italic,
                            fontFamily = FontFamily.Serif,
                            lineHeight = 28.sp,
                            color      = Color(0xFF4E3629)
                        ).scale(fontSizeOffset),
                        modifier         = Modifier.weight(1f)
                    )
                }
            }
        }
        "equation" -> Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E272C)),
            border = BorderStroke(1.5.dp, Color(0xFF37474F)),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text      = para.text,
                    style     = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color      = Color(0xFFE0F7FA)
                    ).scale(fontSizeOffset),
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.fillMaxWidth()
                )
            }
        }
        "blockquote" -> Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFAF4E8)),
            border = BorderStroke(2.dp, Color(0xFFD4C5A9)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "📜",
                    style = MaterialTheme.typography.titleLarge
                )
                ParagraphText(
                    sentences        = sentences,
                    activeSentenceId = activeSentenceId,
                    activeWordIndex  = activeWordIndex,
                    subject          = subject,
                    themeColor       = themeColor,
                    onLongPress      = onLongPress,
                    onWordTap        = onWordTap,
                    paraId           = para.id,
                    style            = MaterialTheme.typography.bodyLarge.copy(
                        fontStyle  = FontStyle.Italic,
                        fontFamily = FontFamily.Serif,
                        lineHeight = 28.sp,
                        color      = Color(0xFF5C4E37)
                    ).scale(fontSizeOffset),
                    modifier         = Modifier.weight(1f)
                )
            }
        }
        "activity", "callout", "note" -> {
            if (isTimeline) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(72.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(themeColor, RoundedCornerShape(50))
                                .padding(4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = para.title,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 12.sp
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(60.dp)
                                .background(themeColor.copy(alpha = 0.3f))
                        )
                    }
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp, bottom = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAF8F5)),
                        border = BorderStroke(1.dp, themeColor.copy(alpha = 0.2f))
                    ) {
                        ParagraphText(
                            sentences        = sentences,
                            activeSentenceId = activeSentenceId,
                            activeWordIndex  = activeWordIndex,
                            subject          = subject,
                            themeColor       = themeColor,
                            onLongPress      = onLongPress,
                            onWordTap        = onWordTap,
                            paraId           = para.id,
                            style            = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp).scale(fontSizeOffset),
                            modifier         = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                val boxBg = when (para.type) {
                    "activity" -> Color(0xFFEBF5FB)
                    "callout" -> Color(0xFFFDF2E9)
                    else -> Color(0xFFFEF9E7)
                }
                val boxBorder = when (para.type) {
                    "activity" -> Color(0xFF3498DB)
                    "callout" -> Color(0xFFE67E22)
                    else -> Color(0xFFF1C40F)
                }
                val icon = when (para.type) {
                    "activity" -> "✍️"
                    "callout" -> "💡"
                    else -> "📌"
                }
                val label = para.title.ifBlank { para.type.replaceFirstChar { it.uppercase() } }
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = boxBg),
                    border = BorderStroke(2.dp, boxBorder),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(text = icon, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text  = label,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = boxBorder
                                ).scale(fontSizeOffset),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        ParagraphText(
                            sentences        = sentences,
                            activeSentenceId = activeSentenceId,
                            activeWordIndex  = activeWordIndex,
                            subject          = subject,
                            themeColor       = themeColor,
                            onLongPress      = onLongPress,
                            onWordTap        = onWordTap,
                            paraId           = para.id,
                            style            = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp).scale(fontSizeOffset),
                        )
                    }
                }
            }
        }
        "list" -> Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            sentences.forEachIndexed { i, sent ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp, top = 2.dp)
                            .size(24.dp)
                            .background(themeColor.copy(alpha = 0.12f), RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (para.ordered) "${i + 1}" else "•",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = themeColor
                            ).scale(fontSizeOffset)
                        )
                    }
                    ParagraphText(
                        sentences        = listOf(sent),
                        activeSentenceId = activeSentenceId,
                        activeWordIndex  = activeWordIndex,
                        subject          = subject,
                        themeColor       = themeColor,
                        onLongPress      = onLongPress,
                        onWordTap        = onWordTap,
                        paraId           = para.id,
                        style            = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp).scale(fontSizeOffset),
                        modifier         = Modifier.weight(1f)
                    )
                }
            }
        }
        "table" -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFDFBF7)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = BorderStroke(1.dp, themeColor.copy(alpha = 0.3f))
            ) {
                Column {
                    if (para.caption.isNotBlank()) {
                        Text(
                            text = para.caption,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = themeColor
                            ).scale(fontSizeOffset),
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        Column {
                            if (para.headers.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .background(themeColor)
                                        .padding(vertical = 12.dp, horizontal = 16.dp)
                                ) {
                                    para.headers.forEach { header ->
                                        Text(
                                            text = header,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            ).scale(fontSizeOffset),
                                            modifier = Modifier.widthIn(min = 120.dp).padding(end = 16.dp)
                                        )
                                    }
                                }
                            }
                            para.rows.forEachIndexed { rowIndex, rowData ->
                                val rowBg = if (rowIndex % 2 == 0) Color.Transparent else Color(0xFFF5F2EB)
                                Row(
                                    modifier = Modifier
                                        .background(rowBg)
                                        .padding(vertical = 12.dp, horizontal = 16.dp)
                                ) {
                                    rowData.forEach { cell ->
                                        Text(
                                            text = renderInline(cell),
                                            style = MaterialTheme.typography.bodyMedium.scale(fontSizeOffset),
                                            modifier = Modifier.widthIn(min = 120.dp).padding(end = 16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
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
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold).scale(fontSizeOffset),
                            )
                            Text(
                                text  = if (item.mediaType == "video") "Tap to play video" else "Tap to view photo",
                                style = MaterialTheme.typography.labelSmall.scale(fontSizeOffset),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
        else -> {
            ParagraphText(
                sentences        = sentences,
                activeSentenceId = activeSentenceId,
                activeWordIndex  = activeWordIndex,
                subject          = subject,
                themeColor       = themeColor,
                onLongPress      = onLongPress,
                onWordTap        = onWordTap,
                paraId           = para.id,
                style            = defaultStyle,
            )
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
    themeColor:       Color,
    onLongPress:      (ChapterSentence) -> Unit,
    onWordTap:        (word: String, sentenceText: String) -> Unit,
    paraId:           String   = "",
    modifier:         Modifier  = Modifier,
    style:            TextStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
    separator:        String   = " ",
) {
    if (sentences.isEmpty()) return

    val activeBg   = themeColor.copy(alpha = 0.15f)
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
            if (idx < sentences.lastIndex) builder.append(separator)
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
                val isItalic = !isBold && raw.startsWith("*") && raw.endsWith("*") && raw.length > 2
                if (wordCount == highlightIdx) {
                    // Preserve existing bold/italic alongside the highlight background
                    withStyle(SpanStyle(
                        background  = highlightColor,
                        fontWeight  = if (isBold) FontWeight.Bold else null,
                        fontStyle   = if (isItalic) FontStyle.Italic else null,
                    )) { append(stripped) }
                } else {
                    if (isBold)   withStyle(SpanStyle(fontWeight = FontWeight.Bold))  { append(stripped) }
                    else if (isItalic) withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(stripped) }
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
    themeColor:    Color,
    audioChecking: Boolean,
    loading:       Boolean,
    hasError:      Boolean,
    hasAudio:      Boolean,
    isPlaying:     Boolean,
    onListenClick: () -> Unit,
    onBackClick:   () -> Unit,
    onFontSizeDecrease: () -> Unit,
    onFontSizeIncrease: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color    = themeColor,
        contentColor = Color.White
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
                    contentDescription = "Back",
                    tint               = Color.White
                )
            }
            Text(
                text     = title,
                style    = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                color    = Color.White
            )

            IconButton(onClick = onFontSizeDecrease) {
                Icon(
                    imageVector        = Icons.Default.Remove,
                    contentDescription = "Decrease Font Size",
                    tint               = Color.White,
                )
            }
            IconButton(onClick = onFontSizeIncrease) {
                Icon(
                    imageVector        = Icons.Default.Add,
                    contentDescription = "Increase Font Size",
                    tint               = Color.White,
                )
            }

            when {
                loading || hasError -> {
                    // No audio control while loading or error
                }
                audioChecking -> CircularProgressIndicator(
                    modifier    = Modifier.size(24.dp).padding(start = 8.dp),
                    strokeWidth = 2.dp,
                    color       = Color.White
                )
                hasAudio -> IconButton(onClick = onListenClick) {
                    Icon(
                        imageVector        = if (isPlaying) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isPlaying) "Stop" else "Listen to chapter",
                        tint               = if (isPlaying) Color(0xFFFADBD8)
                                             else Color.White,
                    )
                }
                else -> IconButton(onClick = {}, enabled = false) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = "No audio available",
                        tint               = Color.White.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

// ── Vocab word popup dialog ────────────────────────────────────────────────────

@Composable
private fun VocabPopupDialog(
    state:               VocabPopupState,
    playing:             Boolean,
    vocabActiveWordIndex: Int?,
    vocabTimings:        List<WordTiming>?,
    subject:             String,
    fontSizeOffset:      Int,
    onGenerate:          (word: String, sentence: String) -> Unit,
    onRetry:             (word: String, sentence: String) -> Unit,
    onPlayStop:          () -> Unit,
    onDismiss:           () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Top header row: Refresh button on top left, title/word centered
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        val canRefresh = when (state) {
                            is VocabPopupState.Found -> true
                            is VocabPopupState.Error -> true
                            else -> false
                        }
                        if (canRefresh) {
                            IconButton(
                                onClick = {
                                    val word = when (state) {
                                        is VocabPopupState.Found -> state.word
                                        is VocabPopupState.Ask -> state.word
                                        is VocabPopupState.Error -> state.word
                                        else -> ""
                                    }
                                    val sentence = when (state) {
                                        is VocabPopupState.Found -> state.sentence
                                        is VocabPopupState.Ask -> state.sentence
                                        is VocabPopupState.Error -> state.sentence
                                        else -> ""
                                    }
                                    if (word.isNotBlank()) {
                                        onRetry(word, sentence)
                                    }
                                },
                                modifier = Modifier
                                    .size(32.dp)
                                    .align(Alignment.CenterStart)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        val wordTitle = when (state) {
                            is VocabPopupState.Loading -> state.word
                            is VocabPopupState.Found -> state.word
                            is VocabPopupState.Ask -> state.word
                            is VocabPopupState.Generating -> state.word
                            is VocabPopupState.Error -> state.word
                        }
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = wordTitle,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold).scale(fontSizeOffset)
                            )
                            if (state is VocabPopupState.Found) {
                                val tintColor = if (playing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                IconButton(
                                    onClick = onPlayStop,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (playing) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = if (playing) "Stop" else "Listen to word",
                                        tint = tintColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        if (state is VocabPopupState.Found && !state.entry.llmInputJson.isNullOrBlank()) {
                            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                            val context = androidx.compose.ui.platform.LocalContext.current
                            IconButton(
                                onClick = {
                                    val llmInputBeautified = try { org.json.JSONObject(state.entry.llmInputJson).toString(4) } catch (_: Exception) { state.entry.llmInputJson }
                                    val llmOutputBeautified = try { org.json.JSONObject(state.entry.llmOutputJson ?: "{}").toString(4) } catch (_: Exception) { state.entry.llmOutputJson ?: "" }
                                    val ttsModel = state.entry.ttsModelUsed ?: "N/A"
                                    val ttsTokens = state.entry.ttsTokenUsage ?: 0

                                    val textToCopy = """
                                        === LLM INPUT ===
                                        $llmInputBeautified
                                        
                                        === LLM OUTPUT ===
                                        $llmOutputBeautified
                                        
                                        === TTS MODEL ===
                                        $ttsModel
                                        
                                        === TTS TOKEN USAGE ===
                                        $ttsTokens
                                    """.trimIndent()

                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(textToCopy))
                                    android.widget.Toast.makeText(context, "Copied LLM & TTS metadata to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .size(32.dp)
                                    .align(Alignment.CenterEnd)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Info",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else if (state is VocabPopupState.Error) {
                            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                            val context = androidx.compose.ui.platform.LocalContext.current
                            IconButton(
                                onClick = {
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.msg))
                                    android.widget.Toast.makeText(context, "Copied error details to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .size(32.dp)
                                    .align(Alignment.CenterEnd)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy Error",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight()
                    ) {
                        when (state) {
                            is VocabPopupState.Loading -> Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("Finding meaning…", style = MaterialTheme.typography.bodyMedium.scale(fontSizeOffset))
                            }

                            is VocabPopupState.Found -> Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // speakText with word-by-word highlight when ElevenLabs timings are available
                                if (state.entry.speakText.isNotBlank()) {
                                    val annotated = if (vocabTimings != null) {
                                        buildAnnotatedString {
                                            for ((idx, timing) in vocabTimings.withIndex()) {
                                                if (idx > 0) append(" ")
                                                if (idx == vocabActiveWordIndex) {
                                                    withStyle(SpanStyle(
                                                        background  = MaterialTheme.colorScheme.primaryContainer,
                                                        color       = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        fontWeight  = FontWeight.Bold,
                                                    )) { append(timing.word) }
                                                } else {
                                                    append(timing.word)
                                                }
                                            }
                                        }
                                    } else {
                                        AnnotatedString(state.entry.speakText)
                                    }
                                    Text(
                                        text     = annotated,
                                        style    = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ).scale(fontSizeOffset),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                }
                                Text(
                                    text  = state.entry.meaning,
                                    style = MaterialTheme.typography.bodyLarge.scale(fontSizeOffset),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (state.entry.translation.isNotBlank()) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                    Text(
                                        text  = state.entry.translation,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontStyle = FontStyle.Italic,
                                            color     = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ).scale(fontSizeOffset),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            is VocabPopupState.Ask -> Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text  = "Meaning not found in dictionary.",
                                    style = MaterialTheme.typography.bodyMedium.scale(fontSizeOffset),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text  = "Generate meaning using AI?",
                                    style = MaterialTheme.typography.bodyMedium.scale(fontSizeOffset),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            is VocabPopupState.Generating -> Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(state.status, style = MaterialTheme.typography.bodyMedium.scale(fontSizeOffset))
                            }

                            is VocabPopupState.Error -> Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text  = "Could not generate meaning.",
                                    style = MaterialTheme.typography.bodyMedium.scale(fontSizeOffset),
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                val scrollState = rememberScrollState()
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .padding(8.dp)
                                        .verticalScroll(scrollState)
                                ) {
                                    Text(
                                        text  = state.msg,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Close", style = MaterialTheme.typography.labelLarge)
                        }

                        val hasAction = state is VocabPopupState.Ask || state is VocabPopupState.Error
                        if (hasAction) {
                            Spacer(Modifier.width(12.dp))
                            when (state) {
                                is VocabPopupState.Ask ->
                                    Button(
                                        onClick = { onGenerate(state.word, state.sentence) },
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Text("Yes, generate!")
                                    }
                                is VocabPopupState.Error ->
                                    Button(
                                        onClick = { onRetry(state.word, state.sentence) },
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Try again")
                                    }
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
    }
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

private fun TextStyle.scale(offset: Int): TextStyle {
    val newFontSize = if (this.fontSize.isSp) {
        (this.fontSize.value + offset).coerceAtLeast(8f).sp
    } else {
        this.fontSize
    }
    val newLineHeight = if (this.lineHeight.isSp) {
        (this.lineHeight.value + offset).coerceAtLeast(10f).sp
    } else {
        this.lineHeight
    }
    return this.copy(fontSize = newFontSize, lineHeight = newLineHeight)
}

@Composable
private fun CacheDownloadProgressBand(
    progress: Float,
    complete: Boolean,
    themeColor: Color,
) {
    val backgroundColor = if (complete) Color(0xFFE8F5E9) else themeColor.copy(alpha = 0.08f)
    val contentColor = if (complete) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
    val statusText = if (complete) "Chapter available offline!" else "Syncing for offline use... ${(progress * 100).toInt()}%"
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = backgroundColor,
        contentColor = contentColor
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (complete) Icons.Default.CheckCircle else Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = if (complete) Color(0xFF2E7D32) else themeColor,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f)
                )
            }
            if (!complete) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = themeColor,
                    trackColor = themeColor.copy(alpha = 0.15f)
                )
            }
        }
    }
}
