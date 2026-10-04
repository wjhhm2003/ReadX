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
class ColorMigrationInstrumentedTest {
    @Test fun v3MigrationKeepsDuplicatesNotesAndUpsertDoesNotCreateNewCopies()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>();val name="color-migration-${System.nanoTime()}.db"
        val locator="""{"start":10,"end":14,"quote":"原书文字","prefix":"a","suffix":"b"}"""
        try {
            val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("library-schema-v3.json").bufferedReader().use {it.readText()}).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name),null).use {old->
                val entities=schema.getJSONArray("entities")
                for(i in 0 until entities.length()) {val e=entities.getJSONObject(i);val table=e.getString("tableName");old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table));val indices=e.optJSONArray("indices")?:org.json.JSONArray();for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))}
                val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO books VALUES ('old-book','hash','保留旧书','作者','EPUB','book.epub',1,2,0,0.5,'原标签',NULL,1)")
                old.execSQL("INSERT INTO chapters VALUES ('old-book',0,'原章节','chapter.xhtml','原正文')")
                for(i in 1..2) old.execSQL("INSERT INTO annotations VALUES (?,?,?,?,?,?,?,?,?,?)",arrayOf<Any>("old-$i","old-book","HIGHLIGHT",0,.5,"原章节","原书文字","旧笔记$i",locator,i))
                old.version=3
            }
            val db=Room.databaseBuilder(app,LibraryDatabase::class.java,name).addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5).build()
            try {
                val dao=db.library();val rows=dao.annotations("old-book")
                assertEquals(2,rows.size);assertEquals(setOf("旧笔记1","旧笔记2"),rows.map {it.note}.toSet());assertTrue(rows.all {it.color=="#FFD240" && it.anchorKey.isNotBlank()});assertEquals(rows[0].anchorKey,rows[1].anchorKey)
                assertEquals(.5f,dao.book("old-book")!!.scrollFraction);assertEquals("原标签",dao.book("old-book")!!.tags)
                val candidate=rows[1].copy(id="new-id",note="",color="#75BEFF")
                val id=dao.upsertAnnotation(candidate);assertEquals("old-2",id)
                dao.upsertAnnotation(candidate.copy(id="other-id"));assertEquals(2,dao.annotations("old-book").size)
                assertEquals("旧笔记2",dao.annotations("old-book").first {it.id==id}.note)
                dao.cancelMarkers("old-book",listOf(id));assertEquals("NOTE",dao.annotations("old-book").first {it.id==id}.kind)
                dao.delete("old-book");assertTrue(dao.annotations("old-book").isEmpty())
            } finally {db.close()}
        } finally {app.deleteDatabase(name)}
    }
}
