package dev.spookcue.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.spookcue.data.local.AssetLoader
import dev.spookcue.data.local.AssetLoaderImpl
import dev.spookcue.data.local.CatalogDatabase
import dev.spookcue.data.local.MovieDao
import dev.spookcue.data.remote.CdnClient
import dev.spookcue.data.remote.CdnClientImpl
import dev.spookcue.data.remote.DeltaSyncManager
import dev.spookcue.data.repository.MovieRepository
import dev.spookcue.data.repository.MovieRepositoryImpl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    private fun resetCatalogTables(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `room_fts_content_sync_movies_fts_BEFORE_UPDATE`")
        db.execSQL("DROP TABLE IF EXISTS `room_fts_content_sync_movies_fts_BEFORE_DELETE`")
        db.execSQL("DROP TABLE IF EXISTS `room_fts_content_sync_movies_fts_AFTER_UPDATE`")
        db.execSQL("DROP TABLE IF EXISTS `room_fts_content_sync_movies_fts_AFTER_INSERT`")
        db.execSQL("DROP TABLE IF EXISTS `movies_fts`")
        db.execSQL("DROP TABLE IF EXISTS `movies`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `movies` (" +
                "`tmdbId` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`releaseYear` INTEGER NOT NULL, " +
                "`runtimeMinutes` INTEGER NOT NULL, " +
                "`scareCount` INTEGER NOT NULL, " +
                "`posterPath` TEXT, " +
                "`hasSubtitles` INTEGER NOT NULL, " +
                "`lastSyncedAt` INTEGER, " +
                "`tags` TEXT NOT NULL, " +
                "PRIMARY KEY(`tmdbId`)" +
            ")"
        )
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `movies_fts` USING FTS4(`title` TEXT NOT NULL, content=`movies`)")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_movies_fts_BEFORE_UPDATE BEFORE UPDATE ON `movies` BEGIN DELETE FROM `movies_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_movies_fts_BEFORE_DELETE BEFORE DELETE ON `movies` BEGIN DELETE FROM `movies_fts` WHERE `docid`=OLD.`rowid`; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_movies_fts_AFTER_UPDATE AFTER UPDATE ON `movies` BEGIN INSERT INTO `movies_fts`(`docid`, `title`) VALUES (NEW.`rowid`, NEW.`title`); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_movies_fts_AFTER_INSERT AFTER INSERT ON `movies` BEGIN INSERT INTO `movies_fts`(`docid`, `title`) VALUES (NEW.`rowid`, NEW.`title`); END")
    }

    private val MIGRATION_1_4 = object : Migration(1, 4) {
        override fun migrate(db: SupportSQLiteDatabase) = resetCatalogTables(db)
    }

    private val MIGRATION_2_4 = object : Migration(2, 4) {
        override fun migrate(db: SupportSQLiteDatabase) = resetCatalogTables(db)
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) = resetCatalogTables(db)
    }

    @Provides
    @Singleton
    fun provideCatalogDatabase(
        @ApplicationContext context: Context
    ): CatalogDatabase = Room.databaseBuilder(
        context,
        CatalogDatabase::class.java,
        CatalogDatabase.DATABASE_NAME
    )
        .addMigrations(MIGRATION_1_4, MIGRATION_2_4, MIGRATION_3_4)
        .fallbackToDestructiveMigration()
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()

    @Provides
    fun provideMovieDao(database: CatalogDatabase): MovieDao = database.movieDao()

    @Provides
    @Singleton
    fun provideAssetLoader(impl: AssetLoaderImpl): AssetLoader = impl

    @Provides
    @Singleton
    fun provideCdnClient(impl: CdnClientImpl): CdnClient = impl

    @Provides
    @Singleton
    fun provideMovieRepository(
        @ApplicationContext context: Context,
        movieDao: MovieDao,
        assetLoader: AssetLoader,
        cdnClient: CdnClient,
        deltaSyncManager: DeltaSyncManager,
        json: Json,
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): MovieRepository = MovieRepositoryImpl(
        context = context,
        movieDao = movieDao,
        assetLoader = assetLoader,
        cdnClient = cdnClient,
        deltaSyncManager = deltaSyncManager,
        json = json,
        ioDispatcher = ioDispatcher
    )
}
