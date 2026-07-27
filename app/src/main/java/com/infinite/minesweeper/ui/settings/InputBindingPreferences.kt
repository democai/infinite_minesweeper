package com.infinite.minesweeper.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val InputBindingKey = stringPreferencesKey("input_binding")

/**
 * DataStore-backed adapter for the tap/long-press [InputBinding] preference.
 *
 * This is a preference, not game state, so it lives in `DataStore` rather than Room (plan §6) and
 * is independent of [com.infinite.minesweeper.core.model.GameMeta].
 */
class InputBindingPreferences(
    private val dataStore: DataStore<Preferences>,
) {
    val binding: Flow<InputBinding> = dataStore.data.map { preferences ->
        preferences[InputBindingKey]?.let { raw ->
            runCatching { InputBinding.valueOf(raw) }.getOrNull()
        } ?: InputBinding.Default
    }

    suspend fun setBinding(binding: InputBinding) {
        dataStore.edit { preferences -> preferences[InputBindingKey] = binding.name }
    }
}
