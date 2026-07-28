package com.infinite.minesweeper.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val InputBindingKey = stringPreferencesKey("input_binding")
private val LongPressDurationKey = stringPreferencesKey("long_press_duration")

/**
 * DataStore-backed adapter for input preferences (binding + long-press timing).
 *
 * Preferences live in `DataStore` rather than Room (plan §6) and stay independent of
 * [com.infinite.minesweeper.core.model.GameMeta].
 */
class InputBindingPreferences(
    private val dataStore: DataStore<Preferences>,
) {
    val binding: Flow<InputBinding> = dataStore.data.map { preferences ->
        preferences[InputBindingKey]?.let { raw ->
            runCatching { InputBinding.valueOf(raw) }.getOrNull()
        } ?: InputBinding.Default
    }

    val longPressDuration: Flow<LongPressDuration> = dataStore.data.map { preferences ->
        preferences[LongPressDurationKey]?.let { raw ->
            runCatching { LongPressDuration.valueOf(raw) }.getOrNull()
        } ?: LongPressDuration.Default
    }

    suspend fun setBinding(binding: InputBinding) {
        dataStore.edit { preferences -> preferences[InputBindingKey] = binding.name }
    }

    suspend fun setLongPressDuration(duration: LongPressDuration) {
        dataStore.edit { preferences -> preferences[LongPressDurationKey] = duration.name }
    }
}
