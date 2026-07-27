package com.infinite.minesweeper.data.db

import android.content.Context
import androidx.room.Room
import com.infinite.minesweeper.core.model.ChunkRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MinesweeperDatabase =
        Room.databaseBuilder(
            context,
            MinesweeperDatabase::class.java,
            DATABASE_NAME,
        ).build()

    @Provides
    fun provideChunkDao(database: MinesweeperDatabase): ChunkDao = database.chunkDao()

    @Provides
    fun provideGameMetaDao(database: MinesweeperDatabase): GameMetaDao = database.gameMetaDao()

    @Provides
    @Singleton
    fun provideChunkRepository(
        chunkDao: ChunkDao,
        gameMetaDao: GameMetaDao,
    ): ChunkRepository {
        val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
        return RoomChunkRepository(
            chunkDao = chunkDao,
            gameMetaDao = gameMetaDao,
            scope = scope,
            ioDispatcher = ioDispatcher,
        )
    }

    const val DATABASE_NAME: String = "infinite_minesweeper.db"
}
