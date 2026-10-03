package io.readx.app

import android.app.Application
import androidx.room.Room
import io.readx.app.data.LibraryDatabase
import io.readx.app.data.LibraryRepository
import io.readx.app.data.MIGRATION_1_2

class ReadXApplication : Application() {
    val database by lazy {
        Room.databaseBuilder(this, LibraryDatabase::class.java, "readx.db").addMigrations(MIGRATION_1_2).build()
    }
    val repository by lazy { LibraryRepository(this, database) }
}
