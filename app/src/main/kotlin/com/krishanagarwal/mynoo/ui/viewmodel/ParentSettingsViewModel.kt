package com.krishanagarwal.mynoo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krishanagarwal.mynoo.data.model.GlobalSettings
import com.krishanagarwal.mynoo.data.repository.GlobalSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SaveStatus { IDLE, SAVING, SAVED, ERROR }

@HiltViewModel
class ParentSettingsViewModel @Inject constructor(
    private val globalSettingsRepo: GlobalSettingsRepository,
) : ViewModel() {

    private val _settings = MutableStateFlow(GlobalSettings())
    val settings: StateFlow<GlobalSettings> = _settings

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _saveStatus = MutableStateFlow(SaveStatus.IDLE)
    val saveStatus: StateFlow<SaveStatus> = _saveStatus

    init {
        viewModelScope.launch {
            _settings.value = globalSettingsRepo.load()
            _isLoading.value = false
        }
    }

    fun updateSettings(newSettings: GlobalSettings) {
        _settings.value = newSettings
        _saveStatus.value = SaveStatus.SAVING
        viewModelScope.launch {
            try {
                globalSettingsRepo.save(newSettings)
                _saveStatus.value = SaveStatus.SAVED
                delay(2000)
                _saveStatus.value = SaveStatus.IDLE
            } catch (_: Exception) {
                _saveStatus.value = SaveStatus.ERROR
            }
        }
    }

    /** Returns true if old PIN matched and the new PIN was saved. */
    fun changePin(oldPin: String, newPin: String): Boolean {
        val current = _settings.value
        if (current.pin != oldPin) return false
        updateSettings(current.copy(pin = newPin))
        return true
    }
}
