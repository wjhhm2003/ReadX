package io.readx.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.readx.app.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderV6MigrationInstrumentedTest {
    @Test fun additiveV5ToV6RetainsAllRowsAndStoresPreferencesAndAnchors()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>();val name="reader-v6-${System.nanoTime()}.db"
        try {
            val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("library-schema-v5.json").reader().readText()).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name),null).use {old->
                val entities=schema.getJSONArray("entities")
                for(i in 0 until entities.length()) {val e=entities.getJSONObject(i);val table=e.getString("tableName");old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table));val indices=e.optJSONArray("indices") ?: org.json.JSONArray();for(j in 0 until indices.length())old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))}
                val queries=schema.getJSONArray("setupQueries");for(i in 0 until queries.length())old.execSQL(queries.getString(i))
                old.execSQL("INSERT INTO books VALUES ('old','hash','旧书','作者','TXT','source.txt',1,2,1,0.57,'标签',NULL,2)")
                old.execSQL("INSERT INTO chapters VALUES ('old',0,'第一章','chapter-0.html','中文😀')")
                old.execSQL("INSERT INTO chapters VALUES ('old',1,'第二章','chapter-1.html','超长正文')")
                old.execSQL("INSERT INTO bookmarks VALUES ('mark','old',1,0.57,'书签',3)")
                for(i in 1..2)old.execSQL("INSERT INTO annotations VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any>("note$i","old","NOTE",1,.57,"旧批注","引用😀","历史笔记$i","{}",i,"#FFD240","same",i))
                old.version=5
            }
            val db=Room.databaseBuilder(app,LibraryDatabase::class.java,name).addMigrations(MIGRATION_5_6).build()
            try {val dao=db.library();val b=dao.book("old")!!
                assertEquals("标签",b.tags);assertEquals(1,b.chapterIndex);assertEquals(.57f,b.scrollFraction);assertNull(b.textEngine);assertNull(b.readingAnchor);assertEquals("",b.pdfCropConfig)
                assertEquals(2,dao.chapterCount("old"));assertEquals(2,dao.annotations("old").size)
                val anchor="{\"start\":1,\"end\":3,\"quote\":\"😀\"}"
                dao.saveTextEngine("old","WEBVIEW");dao.saveTextPosition("old",1,.58f,4,anchor);dao.savePdfCrop("old","crop-test")
                assertEquals(anchor,dao.book("old")!!.readingAnchor);assertEquals("WEBVIEW",dao.book("old")!!.textEngine)
                val bookmark=db.openHelper.readableDatabase.query("SELECT textAnchor FROM bookmarks WHERE id='mark'").use {it.moveToFirst();it.getString(0)};assertNull(bookmark)
                assertEquals(listOf("历史笔记1","历史笔记2"),dao.annotations("old").map {it.note})
                dao.delete("old");assertEquals(0,dao.chapterCount("old"));assertTrue(dao.annotations("old").isEmpty())
            } finally {db.close()}
        } finally {app.deleteDatabase(name)}
    }
}
