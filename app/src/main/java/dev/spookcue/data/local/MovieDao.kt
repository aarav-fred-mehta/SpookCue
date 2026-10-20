package dev.spookcue.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovies(movies: List<MovieEntity>)

    @Query("UPDATE movies SET lastSyncedAt = :timestamp WHERE tmdbId = :tmdbId")
    suspend fun updateLastSynced(tmdbId: Int, timestamp: Long)

    @Query("DELETE FROM movies")
    suspend fun deleteAllMovies()
}
