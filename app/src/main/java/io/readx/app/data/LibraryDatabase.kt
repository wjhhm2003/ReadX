package io.readx.app.data

import androidx.room.*
import io.readx.app.conversion.PdfConversion
import io.readx.app.conversion.PdfConversionDao
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "books", indices = [Index(value = ["fingerprint"], unique = true)])
data class Book(
    @PrimaryKey val id: String,
    val fingerprint: String,
    val title: String,
    val author: String = "",
    val format: String,
    val sourceName: String,
    val importedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0,
    val chapterIndex: Int = 0,
    val scrollFraction: Float = 0f,
    val tags: String = "",
    val coverPath: String? = null,
    @ColumnInfo(defaultValue = "0") val totalUnits: Int = 0,
)

@Entity(tableName = "chapters", primaryKeys = ["bookId", "ordinal"],
    foreignKeys = [ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class Chapter(
    val bookId: String,
    val ordinal: Int,
    val title: String,
    val href: String,
    val text: String,
)

@Entity(tableName = "bookmarks", indices = [Index("bookId")],
    foreignKeys = [ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class Bookmark(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapter: Int,
    val fraction: Float,
    val label: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Sidecar annotations never modify the user's original document. Offsets are UTF-16 in the canonical DOM text. */
@Entity(tableName = "annotations", indices = [Index("bookId"), Index(value = ["bookId", "kind", "anchorKey"])],
    foreignKeys = [ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class Annotation(
    @PrimaryKey val id: String,
    val bookId: String,
    val kind: String,
    val chapter: Int,
    val fraction: Float,
    val label: String,
    val quote: String = "",
    val note: String = "",
    val locator: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "'#FFD240'") val color: String = "#FFD240",
    @ColumnInfo(defaultValue = "''") val anchorKey: String = "",
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM books ORDER BY lastReadAt DESC, importedAt DESC")
    fun observeBooks(): Flow<List<Book>>
    @Query("SELECT * FROM books WHERE id = :id") suspend fun book(id: String): Book?
    @Query("SELECT * FROM books WHERE fingerprint = :hash") suspend fun byFingerprint(hash: String): Book?
    @Query("SELECT bookId, ordinal, title, href, '' AS text FROM chapters WHERE bookId = :id ORDER BY ordinal")
    suspend fun chapters(id: String): List<Chapter>
    @Query("SELECT substr(text, :offset + 1, :length) FROM chapters WHERE bookId=:id AND ordinal=:ordinal")
    suspend fun textChunk(id: String, ordinal: Int, offset: Int, length: Int): String?
    @Query("SELECT COUNT(*) FROM chapters WHERE bookId=:id") suspend fun chapterCount(id: String): Int
    @Query("UPDATE books SET totalUnits=:count WHERE id=:id") suspend fun saveTotalUnits(id: String, count: Int)
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC") fun observeBookmarks(): Flow<List<Bookmark>>
    @Query("SELECT * FROM annotations ORDER BY createdAt DESC") fun observeAnnotations(): Flow<List<Annotation>>
    @Query("SELECT * FROM annotations WHERE bookId=:id ORDER BY createdAt") fun annotationsForBook(id: String): Flow<List<Annotation>>
    @Query("SELECT * FROM annotations WHERE bookId=:id ORDER BY createdAt") suspend fun annotations(id: String): List<Annotation>
    @Insert suspend fun insertAnnotation(annotation: Annotation)
    @Query("SELECT * FROM annotations WHERE bookId=:bookId AND kind=:kind AND anchorKey=:key ORDER BY updatedAt DESC, createdAt DESC LIMIT 1")
    suspend fun annotationByAnchor(bookId: String, kind: String, key: String): Annotation?
    @Update suspend fun replaceAnnotation(annotation: Annotation)
    @Transaction suspend fun upsertAnnotation(value: Annotation): String {
        val key=AnnotationIdentity.key(value.chapter,value.kind,value.locator,value.id)
        val old=annotationByAnchor(value.bookId,value.kind,key)
        val normalized=value.copy(color=MarkColor.normalize(value.color),anchorKey=key,updatedAt=System.currentTimeMillis())
        if(old==null) insertAnnotation(normalized)
        else replaceAnnotation(normalized.copy(id=old.id,createdAt=old.createdAt,note=if(value.kind=="NOTE") value.note else old.note))
        return old?.id ?: value.id
    }
    @Transaction suspend fun cancelMarkers(bookId: String,ids: List<String>) {
        annotations(bookId).filter {it.id in ids && it.kind in listOf("HIGHLIGHT","UNDERLINE")}.forEach {old->
            if(old.note.isBlank()) deleteAnnotation(old.id)
            else replaceAnnotation(old.copy(kind="NOTE",updatedAt=System.currentTimeMillis()))
        }
    }
    @Query("DELETE FROM annotations WHERE bookId=:bookId AND id IN (:ids)") suspend fun deleteAnnotations(bookId: String,ids: List<String>)

    @Query("UPDATE annotations SET note=:note, updatedAt=strftime('%s','now')*1000 WHERE id=:id") suspend fun updateAnnotationNote(id: String, note: String)
    @Query("DELETE FROM annotations WHERE id=:id") suspend fun deleteAnnotation(id: String)
    @Insert suspend fun insertBookmark(bookmark: Bookmark)
    @Transaction suspend fun insertPositionBookmark(mark: Bookmark) {
        insertBookmark(mark)
        insertAnnotation(Annotation(mark.id,mark.bookId,"BOOKMARK",mark.chapter,mark.fraction,mark.label,createdAt=mark.createdAt))
    }
    @Transaction suspend fun removePositionBookmark(id: String) { deleteAnnotation(id);deleteBookmark(id) }
    @Query("DELETE FROM bookmarks WHERE id=:id") suspend fun deleteBookmark(id: String)
    @Insert suspend fun insertBook(book: Book)
    @Insert suspend fun insertChapters(chapters: List<Chapter>)
    @Query("UPDATE books SET chapterIndex=:chapter, scrollFraction=:fraction, lastReadAt=:time WHERE id=:id")
    suspend fun savePosition(id: String, chapter: Int, fraction: Float, time: Long)
    @Query("UPDATE books SET title=:title, author=:author, tags=:tags WHERE id=:id")
    suspend fun edit(id: String, title: String, author: String, tags: String)
    @Query("DELETE FROM books WHERE id=:id") suspend fun delete(id: String)
}

@Database(entities = [Book::class, Chapter::class, Bookmark::class, Annotation::class, PdfConversion::class], version = 5, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() { abstract fun library(): LibraryDao; abstract fun conversions(): PdfConversionDao }

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN totalUnits INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE books SET totalUnits=(SELECT COUNT(*) FROM chapters WHERE bookId=books.id) WHERE format != 'PDF'")
        db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks (id TEXT NOT NULL, bookId TEXT NOT NULL, chapter INTEGER NOT NULL, fraction REAL NOT NULL, label TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_bookId ON bookmarks(bookId)")
    }
}

/** TXT/EPUB progress uses equal-weight sections; PDF progress uses real pages. */
fun Book.progress(): Int? {
    if (lastReadAt == 0L) return 0
    if (totalUnits <= 0) return null
    val read = if (format == "PDF") chapterIndex.toFloat() + 1f else chapterIndex + scrollFraction
    return (100f * read / totalUnits).toInt().coerceIn(0, 100)
}

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS annotations (id TEXT NOT NULL, bookId TEXT NOT NULL, kind TEXT NOT NULL, chapter INTEGER NOT NULL, fraction REAL NOT NULL, label TEXT NOT NULL, quote TEXT NOT NULL, note TEXT NOT NULL, locator TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_annotations_bookId ON annotations(bookId)")
        db.execSQL("INSERT INTO annotations (id,bookId,kind,chapter,fraction,label,quote,note,locator,createdAt) SELECT id,bookId,'BOOKMARK',chapter,fraction,label,'','','',createdAt FROM bookmarks")
    }
}

/** Additive migration: old duplicate records and all their notes remain intact. Rendering flattens their opacity. */
val MIGRATION_3_4 = object : androidx.room.migration.Migration(3,4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE annotations ADD COLUMN color TEXT NOT NULL DEFAULT '#FFD240'")
        db.execSQL("ALTER TABLE annotations ADD COLUMN anchorKey TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE annotations ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE annotations SET updatedAt=createdAt")
        db.query("SELECT id,chapter,kind,locator FROM annotations").use {cursor->
            while(cursor.moveToNext()) {
                val id=cursor.getString(0)
                val key=AnnotationIdentity.key(cursor.getInt(1),cursor.getString(2),cursor.getString(3),id)
                db.execSQL("UPDATE annotations SET anchorKey=? WHERE id=?",arrayOf(key,id))
            }
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS index_annotations_bookId_kind_anchorKey ON annotations(bookId,kind,anchorKey)")
    }
}

val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS pdf_conversions (id TEXT NOT NULL, sourceBookId TEXT, sourceFingerprint TEXT NOT NULL, configFingerprint TEXT NOT NULL, optionsJson TEXT NOT NULL, stage TEXT NOT NULL, completedPages INTEGER NOT NULL, totalPages INTEGER NOT NULL, imagePages INTEGER NOT NULL, resultBookId TEXT, error TEXT NOT NULL, updatedAt INTEGER NOT NULL, runId TEXT NOT NULL DEFAULT '', PRIMARY KEY(id), FOREIGN KEY(sourceBookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(resultBookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_pdf_conversions_sourceBookId ON pdf_conversions(sourceBookId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_pdf_conversions_resultBookId ON pdf_conversions(resultBookId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pdf_conversions_sourceFingerprint_configFingerprint ON pdf_conversions(sourceFingerprint,configFingerprint)")
    }
}
