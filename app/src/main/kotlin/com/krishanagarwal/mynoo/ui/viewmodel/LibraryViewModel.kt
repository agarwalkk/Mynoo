package com.krishanagarwal.mynoo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krishanagarwal.mynoo.data.repository.ChapterContent
import com.krishanagarwal.mynoo.data.repository.ChapterMeta
import com.krishanagarwal.mynoo.data.repository.ChapterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val chapters:  List<ChapterMeta> = emptyList(),
    val loading:   Boolean           = false,
    val error:     String?           = null,
    val subject:   String            = "Hindi",
)

data class ReaderUiState(
    val content:  ChapterContent = ChapterContent(),
    val loading:  Boolean        = true,
    val error:    String?        = null,
    val title:    String         = "",
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: ChapterRepository,
) : ViewModel() {

    private val _lib    = MutableStateFlow(LibraryUiState())
    val lib: StateFlow<LibraryUiState> = _lib

    private val _reader = MutableStateFlow(ReaderUiState())
    val reader: StateFlow<ReaderUiState> = _reader

    fun loadChapters(classNum: String, subject: String) {
        _lib.update { it.copy(loading = true, error = null, subject = subject) }
        viewModelScope.launch {
            val chapters = try {
                repo.getChapters(classNum, subject)
            } catch (e: Exception) {
                _lib.update { it.copy(loading = false, error = e.message) }
                return@launch
            }
            _lib.update { it.copy(chapters = chapters, loading = false) }
        }
    }

    fun loadContent(classNum: String, subject: String, chapterId: String, title: String) {
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
}
