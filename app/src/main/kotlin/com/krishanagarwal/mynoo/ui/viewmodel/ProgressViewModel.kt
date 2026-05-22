package com.krishanagarwal.mynoo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krishanagarwal.mynoo.data.repository.SessionRecord
import com.krishanagarwal.mynoo.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class ProgressUiState(
    val sessions:      List<SessionRecord> = emptyList(),
    val streak:        Int           = 0,
    val totalMinutes:  Int           = 0,
    val moodGreat:     Int           = 0,
    val moodOkay:      Int           = 0,
    val moodHard:      Int           = 0,
    val heatmap:       Map<LocalDate, Int> = emptyMap(),
    val loading:       Boolean       = true,
    val error:         String?       = null,
)

@HiltViewModel
class ProgressViewModel @Inject constructor(
    private val repo: SessionRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(ProgressUiState())
    val ui: StateFlow<ProgressUiState> = _ui

    fun load(childName: String) {
        _ui.update { ProgressUiState(loading = true) }
        viewModelScope.launch {
            try {
                val sessions = repo.getSessions(childName)
                val streak   = repo.getStreak(childName)
                val total    = sessions.sumOf { it.durationMin }.toInt()
                val great    = sessions.count { it.mood == "great" }
                val okay     = sessions.count { it.mood == "okay" }
                val hard     = sessions.count { it.mood == "hard" }
                val fmt      = DateTimeFormatter.ISO_DATE
                val heatmap  = sessions.groupingBy {
                    runCatching { LocalDate.parse(it.date.substring(0, 10), fmt) }
                        .getOrDefault(LocalDate.now())
                }.eachCount()
                _ui.update {
                    ProgressUiState(
                        sessions     = sessions,
                        streak       = streak,
                        totalMinutes = total,
                        moodGreat    = great,
                        moodOkay     = okay,
                        moodHard     = hard,
                        heatmap      = heatmap,
                        loading      = false,
                    )
                }
            } catch (e: Exception) {
                _ui.update { ProgressUiState(loading = false, error = e.message) }
            }
        }
    }
}
