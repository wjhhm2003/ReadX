package io.readx.app.data

import androidx.room.*
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
    @Insert suspend fun insertBookmark(bookmark: Bookmark)
    @Query("DELETE FROM bookmarks WHERE id=:id") suspend fun deleteBookmark(id: String)
    @Insert suspend fun insertBook(book: Book)
    @Insert suspend fun insertChapters(chapters: List<Chapter>)
    @Query("UPDATE books SET chapterIndex=:chapter, scrollFraction=:fraction, lastReadAt=:time WHERE id=:id")
    suspend fun savePosition(id: String, chapter: Int, fraction: Float, time: Long)
    @Query("UPDATE books SET title=:title, author=:author, tags=:tags WHERE id=:id")
    suspend fun edit(id: String, title: String, author: String, tags: String)
    @Query("DELETE FROM books WHERE id=:id") suspend fun delete(id: String)
}

@Database(entities = [Book::class, Chapter::class, Bookmark::class], version = 2, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() { abstract fun library(): LibraryDao }

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
