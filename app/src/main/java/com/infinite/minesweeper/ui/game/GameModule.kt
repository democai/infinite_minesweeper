package com.infinite.minesweeper.ui.game

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.infinite.minesweeper.ui.settings.InputBindingPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.inputBindingDataStore by preferencesDataStore(
    name = "input_binding_preferences",
)

@Module
@InstallIn(SingletonComponent::class)
object GameModule {
    @Provides
    @Singleton
    fun provideInputBindingPreferences(
        @ApplicationContext context: Context,
    ): InputBindingPreferences = InputBindingPreferences(context.inputBindingDataStore)
}
