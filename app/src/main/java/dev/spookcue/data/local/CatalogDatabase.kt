package dev.spookcue.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MovieEntity::class, MovieFtsEntity::class],
    version = 2,
    exportSchema = false
)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun movieDao(): MovieDao

    companion object {
        const val DATABASE_NAME = "spookcue_catalog.db"
    }
}
