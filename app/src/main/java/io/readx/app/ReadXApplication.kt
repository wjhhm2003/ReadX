package io.readx.app

import android.app.Application
import androidx.room.Room
import io.readx.app.data.LibraryDatabase
import io.readx.app.data.LibraryRepository
import io.readx.app.data.MIGRATION_1_2
import io.readx.app.data.MIGRATION_2_3
import io.readx.app.data.MIGRATION_3_4
import io.readx.app.data.MIGRATION_4_5
import io.readx.app.conversion.PdfConversionRepository
import io.readx.app.conversion.OcrModelManager

class ReadXApplication : Application() {
    val database by lazy {
        Room.databaseBuilder(this, LibraryDatabase::class.java, "readx.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
    val repository by lazy { LibraryRepository(this, database) }
    val ocrModels by lazy { OcrModelManager(this) }
    val conversions by lazy { PdfConversionRepository(this, database, repository, ocrModels) }
}
