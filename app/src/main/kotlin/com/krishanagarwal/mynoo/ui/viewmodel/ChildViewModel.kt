package com.krishanagarwal.mynoo.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krishanagarwal.mynoo.data.model.Child
import com.krishanagarwal.mynoo.data.repository.ChildRepository
import com.krishanagarwal.mynoo.data.store.MynooPrefsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChildSelectUiState(
    val children:   List<Child> = emptyList(),
    val loading:    Boolean     = true,
    val error:      String?     = null,
    /** Name of the child that was last selected (from DataStore). */
    val lastChild:  String      = "",
)

@HiltViewModel
class ChildViewModel @Inject constructor(
    private val repo:  ChildRepository,
    private val prefs: MynooPrefsStore,
) : ViewModel() {

    private val _ui = MutableStateFlow(ChildSelectUiState())
    val ui: StateFlow<ChildSelectUiState> = _ui

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val last     = prefs.lastChildName.first()
                val children = repo.loadChildren()
                _ui.update { it.copy(children = children, loading = false, lastChild = last) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun addChild(name: String, age: String, classNum: String, onDone: (Child) -> Unit) {
        viewModelScope.launch {
            try {
                repo.addChild(name, age, classNum)
                val child = Child(name = name, age = age, classNum = classNum)
                _ui.update { it.copy(children = (it.children + child).sortedBy { c -> c.name.lowercase() }) }
                onDone(child)
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }

    fun deleteChild(name: String) {
        viewModelScope.launch {
            try {
                repo.deleteChild(name)
                _ui.update { it.copy(children = it.children.filter { c -> c.name != name }) }
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.message) }
            }
        }
    }

    fun saveLastChild(name: String, classNum: String) {
        viewModelScope.launch { prefs.saveLastChild(name, classNum) }
    }
}
