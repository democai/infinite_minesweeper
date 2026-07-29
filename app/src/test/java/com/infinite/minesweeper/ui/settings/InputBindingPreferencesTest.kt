package com.infinite.minesweeper.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InputBindingPreferencesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun binding_defaultsToTapFlagWhenUnset() = runTest {
        val preferences = InputBindingPreferences(dataStore())

        assertEquals(InputBinding.Default, preferences.binding.first())
        assertEquals(InputBinding.TAP_FLAG_LONG_PRESS_REVEAL, preferences.binding.first())
    }

    @Test
    fun setBinding_persistsAndIsReadableBack() = runTest {
        val preferences = InputBindingPreferences(dataStore())

        preferences.setBinding(InputBinding.TAP_REVEAL_LONG_PRESS_FLAG)

        assertEquals(InputBinding.TAP_REVEAL_LONG_PRESS_FLAG, preferences.binding.first())
    }

    @Test
    fun setBinding_survivesAFreshInstanceOverTheSameFile() = runTest {
        val file = tempFolder.newFile("shared.preferences_pb")

        val firstScope = dataStoreScope()
        val first = InputBindingPreferences(PreferenceDataStoreFactory.create(scope = firstScope) { file })
        first.setBinding(InputBinding.TAP_REVEAL_LONG_PRESS_FLAG)
        firstScope.cancel()

        val secondScope = dataStoreScope()
        val second = InputBindingPreferences(PreferenceDataStoreFactory.create(scope = secondScope) { file })
        assertEquals(InputBinding.TAP_REVEAL_LONG_PRESS_FLAG, second.binding.first())
        secondScope.cancel()
    }

    @Test
    fun longPressDuration_defaultsToMedium() = runTest {
        val preferences = InputBindingPreferences(dataStore())

        assertEquals(LongPressDuration.Default, preferences.longPressDuration.first())
        assertEquals(LongPressDuration.MEDIUM, preferences.longPressDuration.first())
    }

    @Test
    fun setLongPressDuration_persistsAndIsReadableBack() = runTest {
        val preferences = InputBindingPreferences(dataStore())

        preferences.setLongPressDuration(LongPressDuration.LONG)

        assertEquals(LongPressDuration.LONG, preferences.longPressDuration.first())
    }

    // A plain, non-test-scheduled scope: PreferenceDataStoreFactory's own internal actor
    // coroutine never completes, so handing it `runTest`'s TestScope would make the test wait on
    // that job forever (UncompletedCoroutinesError). Dispatchers.Unconfined still runs suspend
    // calls eagerly enough for `first()`/`setBinding()` to resolve synchronously in these tests.
    private fun dataStoreScope(): CoroutineScope = CoroutineScope(Dispatchers.Unconfined + Job())

    private fun dataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = dataStoreScope()) { tempFolder.newFile("test.preferences_pb") }
}
