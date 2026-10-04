package io.readx.app

import android.app.Application
import kotlinx.coroutines.*
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
    // Only the finite asset deployment belongs to the application lifecycle; no polling loop.
    private val assetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.BUNDLED_OCR) assetScope.launch {
            try { ocrModels.ensureBundledModels() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Model manager exposes the failure and UI retry. */ }
        }
    }

    val database by lazy {
        Room.databaseBuilder(this, LibraryDatabase::class.java, "readx.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }
    val repository by lazy { LibraryRepository(this, database) }
    val ocrModels by lazy { OcrModelManager(this) }
    val conversions by lazy { PdfConversionRepository(this, database, repository, ocrModels) }
}
