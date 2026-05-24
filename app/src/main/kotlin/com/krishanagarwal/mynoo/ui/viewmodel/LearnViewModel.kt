package com.krishanagarwal.mynoo.ui.viewmodel

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krishanagarwal.mynoo.data.repository.ChapterContent
import com.krishanagarwal.mynoo.data.repository.ChapterMeta
import com.krishanagarwal.mynoo.data.repository.ChapterRepository
import com.krishanagarwal.mynoo.data.repository.VocabEntry
import com.krishanagarwal.mynoo.data.repository.VocabRepository
import com.krishanagarwal.mynoo.data.repository.WordTiming
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class LearnUiState(
    val chapters:  List<ChapterMeta> = emptyList(),
    val loading:   Boolean           = false,
    val error:     String?           = null,
    val subject:   String            = "Hindi",
)

sealed class VocabPopupState {
    data class Loading(val word: String)                                        : VocabPopupState()
    data class Found(val word: String, val entry: VocabEntry, val sentence: String) : VocabPopupState()
    data class Ask(val word: String, val sentence: String)                      : VocabPopupState()
    data class Generating(val word: String, val sentence: String, val status: String = "Generating meaning...") : VocabPopupState()
    data class Error(val word: String, val sentence: String, val msg: String)   : VocabPopupState()
}

data class ReaderUiState(
    val content:             ChapterContent    = ChapterContent(),
    val loading:             Boolean           = true,
    val error:               String?           = null,
    val title:               String            = "",
    val hasAudio:            Boolean           = false,
    val audioChecking:       Boolean           = false,
    val isPlaying:           Boolean           = false,
    val isPaused:            Boolean           = false,
    val activeSentenceId:    String?           = null,
    val activeWordIndex:     Int?              = null,
    val playbackSpeed:       Float             = 1.0f,
    val playbackPositionMs:  Long              = 0L,
    val totalDurationMs:     Long              = 0L,
    val popupIsPlaying:      Boolean           = false,
    val vocabPopup:          VocabPopupState?  = null,
    val vocabWordPlaying:    Boolean           = false,
    val resumeFromId:        String?           = null,
)

private data class AudioSegment(val id: String, val kind: String)

@HiltViewModel
class LearnViewModel @Inject constructor(
    private val repo:      ChapterRepository,
    private val vocabRepo: VocabRepository,
) : ViewModel() {

    private val _learn  = MutableStateFlow(LearnUiState())
    val learn: StateFlow<LearnUiState> = _learn

    private val _reader = MutableStateFlow(ReaderUiState())
    val reader: StateFlow<ReaderUiState> = _reader

    private var audioExt:       String        = "mp3"
    private var mediaPlayer:    MediaPlayer?  = null
    private var playJob:        Job?          = null

    // ── Vocab word tap ────────────────────────────────────────────────────────
    private val vocabCachePresent = mutableSetOf<String>()
    private val vocabCacheValues  = mutableMapOf<String, VocabEntry?>()
    private var tapIdCounter      = 0
    private var vocabPlayer:      MediaPlayer? = null
    // Pre-scheduled word highlight coroutines
    private var wordScope:      CoroutineScope? = null
    private var currentTimings: List<WordTiming>? = null
    private var pausePosSec:    Double        = 0.0
    // Playback speed (1.0 = normal; delays scale as 1/speed)
    private var playbackSpeed: Float = 1.0f
    private var positionPollingScope: CoroutineScope? = null

    // ── Chapter list ──────────────────────────────────────────────────────────

    fun loadChapters(classNum: String, subject: String) {
        _learn.update { it.copy(loading = true, error = null, subject = subject) }
        viewModelScope.launch {
            val chapters = try {
                repo.getChapters(classNum, subject)
            } catch (e: Exception) {
                _learn.update { it.copy(loading = false, error = e.message) }
                return@launch
            }
            _learn.update { it.copy(chapters = chapters, loading = false) }
        }
    }

    // ── Chapter content ───────────────────────────────────────────────────────

    fun loadContent(classNum: String, subject: String, chapterId: String, title: String) {
        stopPlayback()
        playbackSpeed = 1.0f
        _reader.update { ReaderUiState(loading = true, title = title) }
        viewModelScope.launch {
            try {
                val content = repo.getContent(classNum, subject, chapterId)
                _reader.update { it.copy(content = content, loading = false) }
            } catch (e: Exception) {
                _reader.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    // ── Audio availability ────────────────────────────────────────────────────

    fun checkAudio(classNum: String, subject: String, chapterId: String) {
        _reader.update { it.copy(audioChecking = true) }
        viewModelScope.launch {
            val (exists, ext) = repo.checkAudioExists(classNum, subject, chapterId)
            audioExt = ext
            _reader.update { it.copy(hasAudio = exists, audioChecking = false) }
        }
    }

    // ── Playback control ──────────────────────────────────────────────────────

    fun playChapter(classNum: String, subject: String, chapterId: String) {
        if (_reader.value.isPlaying) return
        val allSegs = buildSegments(_reader.value.content)
        if (allSegs.isEmpty()) return
        // Start from the sentence the user long-pressed (if any)
        val startId  = _reader.value.resumeFromId
        val startIdx = if (startId != null) allSegs.indexOfFirst { it.id == startId }.coerceAtLeast(0) else 0
        val segs     = if (startIdx > 0) allSegs.drop(startIdx) else allSegs
        _reader.update { it.copy(isPlaying = true, isPaused = false, activeSentenceId = null) }

        playJob = viewModelScope.launch(Dispatchers.Main) {
            var completedNaturally = false
            try {
                for (seg in segs) {
                    if (!isActive) break
                    _reader.update {
                        it.copy(
                            activeSentenceId = seg.id,
                            activeWordIndex = null,
                            resumeFromId = seg.id
                        )
                    }
                    val timings = withContext(Dispatchers.IO) {
                        repo.getWordTimings(classNum, subject, chapterId, seg.id, seg.kind)
                    }
                    val url = repo.audioUrl(classNum, subject, chapterId, seg.id, seg.kind, audioExt)
                    playAndWait(url, timings)
                }
                completedNaturally = true
            } finally {
                _reader.update {
                    it.copy(
                        isPlaying = false,
                        isPaused = false,
                        activeSentenceId = null,
                        activeWordIndex = null,
                        resumeFromId = if (completedNaturally) null else it.resumeFromId,
                    )
                }
            }
        }
    }

    fun setResumePoint(sentenceId: String?) {
        _reader.update { it.copy(resumeFromId = sentenceId) }
    }

    fun setSpeed(speed: Float) {
        playbackSpeed = speed
        _reader.update { it.copy(playbackSpeed = speed) }
        val mp = mediaPlayer ?: return
        // Apply to running MediaPlayer immediately
        try { mp.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1.0f) } catch (_: Exception) {}
        // Re-schedule word highlights from current position so delays match new speed
        if (_reader.value.isPlaying && !_reader.value.isPaused) {
            val fromSec = try { mp.currentPosition / 1000.0 } catch (_: Exception) { 0.0 }
            scheduleWordHighlights(currentTimings, fromSec)
        }
    }

    fun pauseResume() {
        val mp = mediaPlayer ?: return
        if (_reader.value.isPaused) {
            // Resume audio at the stored speed
            try {
                mp.start()
                if (playbackSpeed != 1.0f) {
                    mp.playbackParams = PlaybackParams().setSpeed(playbackSpeed).setPitch(1.0f)
                }
            } catch (_: Exception) {}
            // Re-schedule highlights for remaining words from where we paused
            scheduleWordHighlights(currentTimings, fromSec = pausePosSec)
            _reader.update { it.copy(isPaused = false) }
        } else {
            // Record position then cancel pending highlights before pausing audio
            pausePosSec = try { mp.currentPosition / 1000.0 } catch (_: Exception) { 0.0 }
            wordScope?.cancel(); wordScope = null
            try { mp.pause() } catch (_: Exception) {}
            _reader.update { it.copy(isPaused = true) }
        }
    }

    fun stopPlayback() {
        playJob?.cancel(); playJob = null
        wordScope?.cancel(); wordScope = null
        positionPollingScope?.cancel(); positionPollingScope = null
        currentTimings = null
        mediaPlayer?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        mediaPlayer = null
        _reader.update {
            it.copy(
                isPlaying = false, isPaused = false, activeSentenceId = null, activeWordIndex = null,
                popupIsPlaying = false, playbackPositionMs = 0L, totalDurationMs = 0L,
            )
        }
    }

    private suspend fun stopPlaybackAndJoin() {
        wordScope?.cancel(); wordScope = null
        positionPollingScope?.cancel(); positionPollingScope = null
        currentTimings = null
        mediaPlayer?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        mediaPlayer = null
        playJob?.let {
            try {
                it.cancelAndJoin()
            } catch (_: Exception) {}
        }
        playJob = null
        _reader.update {
            it.copy(
                isPlaying = false, isPaused = false, activeSentenceId = null, activeWordIndex = null,
                popupIsPlaying = false, playbackPositionMs = 0L, totalDurationMs = 0L,
            )
        }
    }

    fun playNextSentence(classNum: String, subject: String, chapterId: String) {
        val allSegs = buildSegments(_reader.value.content)
        if (allSegs.isEmpty()) return
        val currentId = _reader.value.activeSentenceId ?: _reader.value.resumeFromId
        val currentIdx = if (currentId != null) allSegs.indexOfFirst { it.id == currentId } else -1
        val nextIdx = currentIdx + 1
        if (nextIdx in allSegs.indices) {
            val nextId = allSegs[nextIdx].id
            viewModelScope.launch(Dispatchers.Main) {
                stopPlaybackAndJoin()
                setResumePoint(nextId)
                playChapter(classNum, subject, chapterId)
            }
        }
    }

    fun playPreviousSentence(classNum: String, subject: String, chapterId: String) {
        val allSegs = buildSegments(_reader.value.content)
        if (allSegs.isEmpty()) return
        val currentId = _reader.value.activeSentenceId ?: _reader.value.resumeFromId
        val currentIdx = if (currentId != null) allSegs.indexOfFirst { it.id == currentId } else -1
        val prevIdx = currentIdx - 1
        if (prevIdx in allSegs.indices) {
            val prevId = allSegs[prevIdx].id
            viewModelScope.launch(Dispatchers.Main) {
                stopPlaybackAndJoin()
                setResumePoint(prevId)
                playChapter(classNum, subject, chapterId)
            }
        }
    }

    // ── Internal playback (must run on Dispatchers.Main) ─────────────────────

    /**
      * Pre-schedules one coroutine per word that fires at the precise wall-clock moment
      * derived from the timing data, scaled by [playbackSpeed].
      * Cancels any previously running word-highlight scope first.
      *
      * @param fromSec  audio position (in seconds) at which scheduling starts
      *                 (0.0 for fresh play, pausePosSec for resume after pause)
      */
    private fun scheduleWordHighlights(timings: List<WordTiming>?, fromSec: Double = 0.0) {
        wordScope?.cancel()
        if (timings.isNullOrEmpty()) { wordScope = null; return }
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        wordScope = scope
        val speed = playbackSpeed.toDouble()
        for ((index, timing) in timings.withIndex()) {
            if (timing.start < fromSec) continue          // already passed
            val delayMs = ((timing.start - fromSec) / speed * 1000.0).toLong().coerceAtLeast(0L)
            scope.launch {
                delay(delayMs)
                _reader.update { it.copy(activeWordIndex = index) }
            }
        }
        // Clear highlight after the last word ends
        timings.lastOrNull()?.let { last ->
            val clearMs = ((last.end - fromSec) / speed * 1000.0).toLong().coerceAtLeast(0L)
            scope.launch {
                delay(clearMs)
                _reader.update { it.copy(activeWordIndex = null) }
            }
        }
    }

    private suspend fun playAndWait(url: String, timings: List<WordTiming>?) {
        currentTimings = timings
        pausePosSec    = 0.0

        val mp = MediaPlayer()
        mediaPlayer = mp

        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(url)
        } catch (_: Exception) {
            mp.release()
            if (mediaPlayer == mp) mediaPlayer = null
            return
        }

        val done = CompletableDeferred<Unit>()
        mp.setOnErrorListener     { _, _, _ -> done.complete(Unit); true }
        mp.setOnCompletionListener { done.complete(Unit) }
        mp.setOnPreparedListener  { player ->
            try {
                if (!done.isCompleted) {
                    player.start()
                    _reader.update { it.copy(totalDurationMs = player.duration.toLong().coerceAtLeast(0L)) }
                    // Apply speed if not default (must be after start())
                    if (playbackSpeed != 1.0f) {
                        try { player.playbackParams = PlaybackParams().setSpeed(playbackSpeed).setPitch(1.0f) } catch (_: Exception) {}
                    }
                    // Kick off pre-scheduled word highlights the instant audio starts
                    scheduleWordHighlights(timings, fromSec = 0.0)
                    // Poll playback position for the seek slider
                    positionPollingScope?.cancel()
                    val ps = CoroutineScope(Dispatchers.Main + SupervisorJob())
                    positionPollingScope = ps
                    ps.launch {
                        while (isActive) {
                            try {
                                _reader.update { it.copy(playbackPositionMs = player.currentPosition.toLong().coerceAtLeast(0L)) }
                            } catch (_: Exception) {}
                            delay(200)
                        }
                    }
                }
            } catch (_: Exception) { done.complete(Unit) }
        }

        try {
            mp.prepareAsync()
        } catch (_: Exception) {
            mp.release()
            if (mediaPlayer == mp) mediaPlayer = null
            return
        }

        try {
            done.await()
        } finally {
            positionPollingScope?.cancel(); positionPollingScope = null
            wordScope?.cancel(); wordScope = null
            currentTimings = null
            _reader.update { it.copy(activeWordIndex = null, playbackPositionMs = 0L) }
            try { mp.stop(); mp.release() } catch (_: Exception) {}
            if (mediaPlayer == mp) mediaPlayer = null
        }
    }

    fun seekTo(posMs: Long) {
        val mp = mediaPlayer ?: return
        try {
            mp.seekTo(posMs.toInt())
            val fromSec = posMs / 1000.0
            pausePosSec = fromSec
            _reader.update { it.copy(playbackPositionMs = posMs) }
            if (_reader.value.isPlaying && !_reader.value.isPaused) {
                scheduleWordHighlights(currentTimings, fromSec)
            }
        } catch (_: Exception) {}
    }

    fun playSentence(classNum: String, subject: String, chapterId: String, sentenceId: String) {
        viewModelScope.launch(Dispatchers.Main) {
            if (_reader.value.isPlaying) {
                stopPlaybackAndJoin()
            }
            _reader.update {
                it.copy(
                    isPlaying = true, isPaused = false, popupIsPlaying = true,
                    activeSentenceId = sentenceId, activeWordIndex = null,
                )
            }
            val kind = if (_reader.value.content.paragraphs.any { it.id == sentenceId }) "paragraph" else "sentence"
            playJob = viewModelScope.launch(Dispatchers.Main) {
                try {
                    val timings = withContext(Dispatchers.IO) {
                        repo.getWordTimings(classNum, subject, chapterId, sentenceId, kind)
                    }
                    val url = repo.audioUrl(classNum, subject, chapterId, sentenceId, kind, audioExt)
                    playAndWait(url, timings)
                } finally {
                    _reader.update {
                        it.copy(
                            isPlaying = false, isPaused = false, activeSentenceId = null,
                            activeWordIndex = null, popupIsPlaying = false,
                        )
                    }
                }
            }
        }
    }

    private fun buildSegments(content: ChapterContent): List<AudioSegment> {
        val noAudio = setOf("heading", "subheading", "attribution", "table", "media")
        val result  = mutableListOf<AudioSegment>()
        for (para in content.paragraphs) {
            if (para.type in noAudio) continue
            // Only include sentences that have a non-blank ID; sentences with blank
            // IDs produce malformed audio URLs (".../sentences/.mp3") that 404.
            // If no valid sentence IDs exist, fall back to paragraph-level audio.
            val validSents = para.sentences.filter { it.id.isNotBlank() }
            when {
                validSents.isNotEmpty() ->
                    validSents.forEach { sent -> result += AudioSegment(sent.id, "sentence") }
                para.id.isNotBlank() ->
                    result += AudioSegment(para.id, "paragraph")
            }
        }
        return result
    }

    // ── Word tap: lookup → show popup ─────────────────────────────────────────

    fun tapWord(word: String, sentenceText: String, subject: String) {
        val tapId    = ++tapIdCounter
        val cacheKey = "$word::$sentenceText"
        // Return cached result immediately
        if (cacheKey in vocabCachePresent) {
            val entry = vocabCacheValues[cacheKey]
            _reader.update {
                it.copy(vocabPopup = if (entry != null)
                    VocabPopupState.Found(word, entry, sentenceText) else VocabPopupState.Ask(word, sentenceText))
            }
            if (entry != null) startVocabAudio(entry.audioPath)
            return
        }
        _reader.update { it.copy(vocabPopup = VocabPopupState.Loading(word)) }
        viewModelScope.launch {
            val entry = try { vocabRepo.lookup(word, sentenceText) } catch (_: Exception) { null }
            vocabCachePresent.add(cacheKey)
            vocabCacheValues[cacheKey] = entry
            if (tapIdCounter != tapId) return@launch
            _reader.update {
                it.copy(vocabPopup = if (entry != null)
                    VocabPopupState.Found(word, entry, sentenceText) else VocabPopupState.Ask(word, sentenceText))
            }
            if (entry != null) startVocabAudio(entry.audioPath)
        }
    }

    private fun getFullErrorDescription(e: Throwable): String {
        val sw = java.io.StringWriter()
        val pw = java.io.PrintWriter(sw)
        e.printStackTrace(pw)
        val stackTrace = sw.toString()

        var details = ""
        if (e is retrofit2.HttpException) {
            val errorBody = try {
                e.response()?.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            if (!errorBody.isNullOrBlank()) {
                details = "HTTP Error Body:\n$errorBody\n\n"
            }
        }
        return "$details${e.javaClass.name}: ${e.message ?: "Unknown error"}\n\n$stackTrace"
    }

    fun generateWordMeaning(word: String, sentence: String, subject: String) {
        val tapId = ++tapIdCounter
        _reader.update { it.copy(vocabPopup = VocabPopupState.Generating(word, sentence, "Connecting...")) }
        viewModelScope.launch {
            runCatching {
                vocabRepo.generate(word, sentence, subject) { progressStatus ->
                    if (tapIdCounter == tapId) {
                        _reader.update { state ->
                            if (state.vocabPopup is VocabPopupState.Generating) {
                                state.copy(vocabPopup = (state.vocabPopup as VocabPopupState.Generating).copy(status = progressStatus))
                            } else {
                                state
                            }
                        }
                    }
                }
            }
                .onSuccess { entry ->
                    val key = "$word::$sentence"
                    vocabCachePresent.add(key); vocabCacheValues[key] = entry
                    if (tapIdCounter != tapId) return@onSuccess
                    _reader.update { it.copy(vocabPopup = VocabPopupState.Found(word, entry, sentence)) }
                    startVocabAudio(entry.audioPath)
                }
                .onFailure { e ->
                    if (tapIdCounter != tapId) return@onFailure
                    _reader.update { it.copy(vocabPopup = VocabPopupState.Error(word, sentence, getFullErrorDescription(e))) }
                }
        }
    }

    fun retryWordMeaning(word: String, sentence: String, subject: String) {
        val key = "$word::$sentence"
        vocabCachePresent.remove(key); vocabCacheValues.remove(key)
        viewModelScope.launch { runCatching { vocabRepo.delete(word) } }
        generateWordMeaning(word, sentence, subject)
    }

    fun dismissVocabPopup() {
        ++tapIdCounter
        stopVocabAudio()
        _reader.update { it.copy(vocabPopup = null) }
    }

    fun toggleVocabAudio(audioPath: String) {
        if (_reader.value.vocabWordPlaying) stopVocabAudio() else startVocabAudio(audioPath)
    }

    private fun startVocabAudio(audioPath: String) {
        stopVocabAudio()
        val url = vocabRepo.audioUrl(audioPath)
        _reader.update { it.copy(vocabWordPlaying = true) }
        val mp = MediaPlayer()
        vocabPlayer = mp
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        mp.setDataSource(url)
        mp.setOnPreparedListener  { it.start() }
        mp.setOnCompletionListener { _reader.update { s -> s.copy(vocabWordPlaying = false) } }
        mp.setOnErrorListener     { _, _, _ -> _reader.update { s -> s.copy(vocabWordPlaying = false) }; true }
        mp.prepareAsync()
    }

    fun stopVocabAudio() {
        try { vocabPlayer?.stop(); vocabPlayer?.release() } catch (_: Exception) {}
        vocabPlayer = null
        _reader.update { it.copy(vocabWordPlaying = false) }
    }

    override fun onCleared() {
        super.onCleared()
        positionPollingScope?.cancel(); positionPollingScope = null
        wordScope?.cancel(); wordScope = null
        mediaPlayer?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        mediaPlayer = null
        try { vocabPlayer?.stop(); vocabPlayer?.release() } catch (_: Exception) {}
        vocabPlayer = null
    }
}
