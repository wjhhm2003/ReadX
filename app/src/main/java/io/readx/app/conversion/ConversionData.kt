package io.readx.app.conversion

import androidx.room.*
import io.readx.app.data.Book
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.security.MessageDigest

@Entity(tableName = "pdf_conversions", foreignKeys = [
    ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["sourceBookId"], onDelete = ForeignKey.SET_NULL),
    ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["resultBookId"], onDelete = ForeignKey.SET_NULL)
], indices = [Index("sourceBookId"), Index("resultBookId"), Index(value = ["sourceFingerprint", "configFingerprint"], unique = true)])
data class PdfConversion(
    @PrimaryKey val id: String,
    val sourceBookId: String?,
    val sourceFingerprint: String,
    val configFingerprint: String,
    val optionsJson: String,
    val stage: String = "QUEUED",
    val completedPages: Int = 0,
    val totalPages: Int = 0,
    val imagePages: Int = 0,
    val resultBookId: String? = null,
    val error: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val runId: String = "",
) {
    val active get() = stage in setOf("QUEUED", "EXTRACTING", "OCR", "PACKAGING", "IMPORTING")
    val label get() = when(stage) {
        "QUEUED" -> "排队中"; "EXTRACTING" -> "提取文字"; "OCR" -> "离线 OCR"
        "WAITING_MODEL" -> "等待 OCR 模型"; "PACKAGING" -> "生成 EPUB"; "IMPORTING" -> "导入书库"
        "COMPLETE" -> "转换完成"; "CANCELLED" -> "已取消"; else -> "转换失败"
    }
}

@Dao
interface PdfConversionDao {
    @Query("SELECT * FROM pdf_conversions WHERE id=:id") suspend fun get(id: String): PdfConversion?
    @Query("SELECT * FROM pdf_conversions WHERE id=:id") fun observe(id: String): Flow<PdfConversion?>
    @Query("SELECT * FROM pdf_conversions ORDER BY updatedAt DESC") fun observeAll(): Flow<List<PdfConversion>>
    @Query("SELECT * FROM pdf_conversions WHERE resultBookId=:bookId LIMIT 1") suspend fun sourceOf(bookId: String): PdfConversion?
    @Query("SELECT * FROM pdf_conversions WHERE resultBookId=:bookId LIMIT 1") fun observeSourceOf(bookId: String): Flow<PdfConversion?>
    @Query("SELECT * FROM pdf_conversions WHERE sourceBookId=:bookId") suspend fun forSource(bookId: String): List<PdfConversion>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(value: PdfConversion): Long
    @Query("UPDATE pdf_conversions SET stage=:stage, completedPages=:completed, totalPages=:total, imagePages=:images, error=:error, updatedAt=:time WHERE id=:id")
    suspend fun progress(id: String, stage: String, completed: Int, total: Int, images: Int, error: String = "", time: Long = System.currentTimeMillis())
    @Query("UPDATE pdf_conversions SET runId=:runId, stage='QUEUED', error='', updatedAt=:time WHERE id=:id") suspend fun setRun(id: String, runId: String, time: Long = System.currentTimeMillis())
    @Query("UPDATE pdf_conversions SET stage=:stage, completedPages=:completed, totalPages=:total, imagePages=:images, error=:error, updatedAt=:time WHERE id=:id AND runId=:runId AND stage!='CANCELLED'")
    suspend fun progressForRun(id: String, runId: String, stage: String, completed: Int, total: Int, images: Int, error: String = "", time: Long = System.currentTimeMillis()): Int
    @Query("DELETE FROM pdf_conversions WHERE id=:id") suspend fun discard(id: String)
    @Query("UPDATE pdf_conversions SET resultBookId=:bookId, stage='COMPLETE', error='', updatedAt=:time WHERE id=:id") suspend fun complete(id: String, bookId: String, time: Long = System.currentTimeMillis())
}

data class PdfConversionOptions(val languages: String = "chi_sim+eng", val models: Map<String, String> = emptyMap()) {
    init { require(languages in listOf("chi_sim+eng", "chi_tra+eng", "eng")); require(models.values.all { it.matches(Regex("[a-f0-9]{64}")) }) }
    fun json(): String = JSONObject().put("version", 2).put("languages", languages).put("models", JSONObject(models.toSortedMap())).toString()
    fun key(): String = digest(json())
    companion object {
        fun parse(json: String): PdfConversionOptions {
            val o = JSONObject(json); require(o.getInt("version") in 1..2)
            val m = o.getJSONObject("models")
            return PdfConversionOptions(o.getString("languages"), m.keys().asSequence().associateWith { m.getString(it) })
        }
    }
}
internal fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
