package dev.spookcue.di

import android.content.Context
import androidx.room.Room
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

    @Provides
    @Singleton
    fun provideCatalogDatabase(
        @ApplicationContext context: Context
    ): CatalogDatabase = Room.databaseBuilder(
        context,
        CatalogDatabase::class.java,
        CatalogDatabase.DATABASE_NAME
    ).fallbackToDestructiveMigration().build()

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
