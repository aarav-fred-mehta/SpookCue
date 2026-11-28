package dev.spookcue.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class SyncedMovieTimestamp(
    val tmdbId: Int,
    val lastSyncedAt: Long
)

@Dao
interface MovieDao {

    @Query("SELECT * FROM movies ORDER BY title ASC")
    fun getAllMovies(): Flow<List<MovieEntity>>

    @Query(
        """
        SELECT movies.* FROM movies
        JOIN movies_fts ON movies.rowid = movies_fts.rowid
        WHERE movies_fts MATCH :matchQuery
        ORDER BY movies.title ASC
        """
    )
    fun searchMoviesFts(matchQuery: String): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE title LIKE '%' || :query || '%' ORDER BY title ASC")
    fun searchMoviesLike(query: String): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE tmdbId = :tmdbId LIMIT 1")
    suspend fun getMovieById(tmdbId: Int): MovieEntity?

    @Query("SELECT COUNT(*) FROM movies")
    suspend fun getMovieCount(): Int

    @Query("SELECT tmdbId FROM movies")
    suspend fun getAllMovieIds(): List<Int>

    @Query("SELECT tmdbId, lastSyncedAt FROM movies WHERE lastSyncedAt IS NOT NULL")
    suspend fun getSyncedTimestamps(): List<SyncedMovieTimestamp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovies(movies: List<MovieEntity>)

    @Query("DELETE FROM movies WHERE tmdbId IN (:tmdbIds)")
    suspend fun deleteMoviesByIds(tmdbIds: List<Int>)

    @Query("UPDATE movies SET lastSyncedAt = :timestamp WHERE tmdbId = :tmdbId")
    suspend fun updateLastSynced(tmdbId: Int, timestamp: Long)

    @Query("UPDATE movies SET posterPath = :posterPath WHERE tmdbId = :tmdbId")
    suspend fun updatePosterPath(tmdbId: Int, posterPath: String)

    @Query("UPDATE movies SET syncType = :syncType WHERE tmdbId = :tmdbId")
    suspend fun updateSyncType(tmdbId: Int, syncType: String)

    @Query("UPDATE movies SET hasAcousticSync = :hasAcousticSync WHERE tmdbId = :tmdbId")
    suspend fun updateHasAcousticSync(tmdbId: Int, hasAcousticSync: Boolean)

    @Query(
        """
        UPDATE movies SET
            title = :title,
            releaseYear = :releaseYear,
            runtimeMinutes = :runtimeMinutes,
            scareCount = :scareCount,
            posterPath = COALESCE(:posterPath, posterPath),
            hasAcousticSync = :hasAcousticSync,
            syncType = :syncType,
            tags = :tags
        WHERE tmdbId = :tmdbId
        """
    )
    suspend fun updateCatalogMetadata(
        tmdbId: Int,
        title: String,
        releaseYear: Int,
        runtimeMinutes: Int,
        scareCount: Int,
        posterPath: String?,
        hasAcousticSync: Boolean,
        syncType: String,
        tags: String
    )

    @Transaction
    suspend fun reconcileCatalogMetadata(items: List<MovieEntity>) {
        for (item in items) {
            updateCatalogMetadata(
                tmdbId = item.tmdbId,
                title = item.title,
                releaseYear = item.releaseYear,
                runtimeMinutes = item.runtimeMinutes,
                scareCount = item.scareCount,
                posterPath = item.posterPath,
                hasAcousticSync = item.hasAcousticSync,
                syncType = item.syncType,
                tags = item.tags
            )
        }
    }

    @Transaction
    suspend fun replaceCatalogPreservingSync(items: List<MovieEntity>) {
        val syncedMap = getSyncedTimestamps().associate { it.tmdbId to it.lastSyncedAt }
        val targetIds = items.map { it.tmdbId }.toSet()
        val staleIds = getAllMovieIds().filter { it !in targetIds }
        for (chunk in staleIds.chunked(500)) {
            deleteMoviesByIds(chunk)
        }
        val merged = items.map { item ->
            val preservedSync = item.lastSyncedAt ?: syncedMap[item.tmdbId]
            if (preservedSync != item.lastSyncedAt) item.copy(lastSyncedAt = preservedSync) else item
        }
        insertMovies(merged)
    }

    @Query("DELETE FROM movies")
    suspend fun deleteAllMovies()
}
