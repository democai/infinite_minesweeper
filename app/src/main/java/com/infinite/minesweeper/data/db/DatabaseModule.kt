package com.infinite.minesweeper.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/** Adds `GameMeta.hasEverRevealed`, defaulting existing rows to false (an untouched board). */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE game_meta ADD COLUMN hasEverRevealed INTEGER NOT NULL DEFAULT 0",
        )
    }
}

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
        )
            .addMigrations(MIGRATION_1_2)
            .build()

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
