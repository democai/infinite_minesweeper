package com.infinite.minesweeper.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val InputBindingKey = stringPreferencesKey("input_binding")
private val LongPressDurationKey = stringPreferencesKey("long_press_duration")
private val LimitCascadeToSelectorKey = booleanPreferencesKey("limit_cascade_to_selector")

/**
 * DataStore-backed adapter for player preferences (input + cascade behavior).
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

    /** When true, 0-cascade flood-fill stays inside the start cell's selector. Default false. */
    val limitCascadeToSelector: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[LimitCascadeToSelectorKey] ?: false
    }

    suspend fun setBinding(binding: InputBinding) {
        dataStore.edit { preferences -> preferences[InputBindingKey] = binding.name }
    }

    suspend fun setLongPressDuration(duration: LongPressDuration) {
        dataStore.edit { preferences -> preferences[LongPressDurationKey] = duration.name }
    }

    suspend fun setLimitCascadeToSelector(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[LimitCascadeToSelectorKey] = enabled }
    }
}
