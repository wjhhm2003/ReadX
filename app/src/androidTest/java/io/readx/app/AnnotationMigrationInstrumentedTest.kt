package io.readx.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.readx.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnnotationMigrationInstrumentedTest {
    @Test fun version2KeepsAllDataAndMovesBookmarksWithoutDestruction()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>()
        val name="annotation-migration-${System.nanoTime()}.db"
        try {
            val source=InstrumentationRegistry.getInstrumentation().context.assets.open("library-schema-v2.json").bufferedReader().use {it.readText()}
            val schema=JSONObject(source).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name),null).use {old->
                val entities=schema.getJSONArray("entities")
                for(i in 0 until entities.length()) {
                    val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                    val indices=entity.optJSONArray("indices") ?: org.json.JSONArray()
                    for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
                }
                val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO books (id,fingerprint,title,author,format,sourceName,importedAt,lastReadAt,chapterIndex,scrollFraction,tags,coverPath,totalUnits) VALUES ('old-book','unique','保留原书','作者','EPUB','book.epub',1,2,0,0.5,'原标签','cover.png',1)")
                old.execSQL("INSERT INTO chapters VALUES ('old-book',0,'原章节','chapter.xhtml','原正文')")
                old.execSQL("INSERT INTO bookmarks VALUES ('old-mark','old-book',0,0.5,'原书签',3)")
                old.version=2
            }
            val db=Room.databaseBuilder(app,LibraryDatabase::class.java,name).addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5, MIGRATION_5_6).build()
            try {
                val book=db.library().book("old-book")!!
                assertEquals("原标签",book.tags);assertEquals("cover.png",book.coverPath);assertEquals(.5f,book.scrollFraction)
                assertEquals("chapter.xhtml",db.library().chapters(book.id).single().href)
                val mark=db.library().annotations(book.id).single();assertEquals("old-mark",mark.id);assertEquals("BOOKMARK",mark.kind);assertEquals("原书签",mark.label);assertEquals(.5f,mark.fraction)
                db.library().delete(book.id);assertTrue(db.library().annotations(book.id).isEmpty())
            } finally {db.close()}
        } finally {app.deleteDatabase(name)}
    }
}
