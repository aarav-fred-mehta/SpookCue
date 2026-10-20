package dev.spookcue.data.local

import androidx.room.Entity
import androidx.room.Fts4

@Entity(tableName = "movies_fts")
@Fts4(contentEntity = MovieEntity::class)
data class MovieFtsEntity(
    val title: String
)
