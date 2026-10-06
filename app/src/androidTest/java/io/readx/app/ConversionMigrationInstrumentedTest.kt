package io.readx.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.readx.app.data.*
import io.readx.app.conversion.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversionMigrationInstrumentedTest {
    @Test fun v4MigrationKeepsAllUserDataAndDoesNotCascadeConvertedBook()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>();val name="conversion-migration-${System.nanoTime()}.db"
        try {
            val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("library-schema-v4.json").reader().readText()).getJSONObject("database")
            SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(name),null).use {old->
                val entities=schema.getJSONArray("entities")
                for(i in 0 until entities.length()) {val e=entities.getJSONObject(i);val table=e.getString("tableName");old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table));val indices=e.optJSONArray("indices")?:org.json.JSONArray();for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))}
                val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO books VALUES ('old','hash','旧书','作者','PDF','source.pdf',1,2,4,0.3,'标签',NULL,12)")
                old.execSQL("INSERT INTO chapters VALUES ('old',0,'章','old.xhtml','原正文')")
                old.execSQL("INSERT INTO bookmarks VALUES ('mark','old',4,0.3,'旧书签',1)")
                for(i in 1..2) old.execSQL("INSERT INTO annotations VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any>("note$i","old","NOTE",4,.3,"旧位置","原引用","笔记$i","{}",i,"#FFD240","same",i))
                old.version=4
            }
            val db=Room.databaseBuilder(app,LibraryDatabase::class.java,name).addMigrations(MIGRATION_4_5, MIGRATION_5_6).build()
            try {
                val dao=db.library();assertEquals("标签",dao.book("old")!!.tags);assertEquals(4,dao.book("old")!!.chapterIndex);assertEquals(2,dao.annotations("old").size);assertEquals(1,dao.chapterCount("old"))
                dao.insertBook(Book("child","other","转换版",format="EPUB",sourceName="result.epub"))
                val conversions=db.conversions();conversions.insert(PdfConversion("id","old","hash","config",PdfConversionOptions().json(),resultBookId="child",stage="COMPLETE"))
                dao.delete("old");assertNotNull(dao.book("child"));assertNull(conversions.get("id")!!.sourceBookId);assertEquals("child",conversions.get("id")!!.resultBookId)
                dao.delete("child");assertNull(conversions.get("id")!!.resultBookId)
            } finally {db.close()}
        } finally {app.deleteDatabase(name)}
    }
}
