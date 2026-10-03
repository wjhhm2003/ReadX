package io.readx.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.readx.app.data.LibraryDatabase
import io.readx.app.data.MIGRATION_1_2
import io.readx.app.data.MIGRATION_2_3
import io.readx.app.data.MIGRATION_3_4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationInstrumentedTest {
    @Test fun migrationKeepsBooksChaptersAndProgress() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReadXApplication>()
        val name = "readx-migration-test.db"
        app.deleteDatabase(name)
        try {
            val source = InstrumentationRegistry.getInstrumentation().context.assets.open("library-schema-v1.json").bufferedReader().use { it.readText() }
            val schema = JSONObject(source).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name), null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO books (id,fingerprint,title,author,format,sourceName,importedAt,lastReadAt,chapterIndex,scrollFraction,tags,coverPath) VALUES ('test-id','hash','旧书','作者','TXT','book.txt',1,2,0,0.5,'旧标签',NULL)")
                old.execSQL("INSERT INTO chapters (bookId,ordinal,title,href,text) VALUES ('test-id',0,'第一章','chapter.html','正文')")
                old.version = 1
            }
            val db = Room.databaseBuilder(app, LibraryDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
            try {
                val book = db.library().book("test-id")!!
                assertEquals("旧书", book.title)
                assertEquals(.5f, book.scrollFraction)
                assertEquals(1, book.totalUnits)
                assertEquals("旧标签", book.tags)
                assertEquals(1, db.library().chapters("test-id").size)
            } finally { db.close() }
        } finally { app.deleteDatabase(name) }
    }
}
