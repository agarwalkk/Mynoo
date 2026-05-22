package com.krishanagarwal.mynoo.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "mynoo_prefs")

@Singleton
class MynooPrefsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val LAST_CHILD_NAME  = stringPreferencesKey("last_child_name")
    private val LAST_CHILD_CLASS = stringPreferencesKey("last_child_class")

    val lastChildName: Flow<String> =
        context.dataStore.data.map { it[LAST_CHILD_NAME] ?: "" }

    val lastChildClass: Flow<String> =
        context.dataStore.data.map { it[LAST_CHILD_CLASS] ?: "" }

    suspend fun saveLastChild(name: String, classNum: String) {
        context.dataStore.edit { prefs ->
            prefs[LAST_CHILD_NAME]  = name
            prefs[LAST_CHILD_CLASS] = classNum
        }
    }

    suspend fun clearLastChild() {
        context.dataStore.edit { prefs ->
            prefs.remove(LAST_CHILD_NAME)
            prefs.remove(LAST_CHILD_CLASS)
        }
    }
}
