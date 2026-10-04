package io.readx.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.googlecode.tesseract.android.TessBaseAPI
import io.readx.app.conversion.OcrModelManager
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class BundledOcrInstrumentedTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReadXApplication>()
    @Test fun packagingMatchesTheExplicitBuildOption() {
        val names = app.assets.list("ocr").orEmpty().filter { it.endsWith(".traineddata") }.toSet()
        assertEquals(if (BuildConfig.BUNDLED_OCR) setOf("chi_sim.traineddata", "chi_tra.traineddata", "eng.traineddata") else emptySet<String>(), names)
    }
    @Test fun threeModelsDeployFromEmptyPreferencesAndRemainIdempotent() {
        assumeTrue("Only the explicitly bundled build installs packaged models", BuildConfig.BUNDLED_OCR)
        val directory = File(app.cacheDir, "bundled-test-${System.nanoTime()}").apply {mkdirs()}
        val key = "bundled-test-${System.nanoTime()}"
        val context = object: ContextWrapper(app) {
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = app.getSharedPreferences(key, mode)
        }
        try {
            val manager = OcrModelManager(context)
            assertTrue(manager.models.value.isEmpty())
            runBlocking {manager.ensureBundledModels()}
            val manifest = app.assets.open("ocr/manifest.json").reader().use {JSONObject(it.readText()).getJSONObject("models")}
            assertEquals(setOf("chi_sim", "chi_tra", "eng"), manager.models.value.keys)
            val times = mutableMapOf<String, Long>()
            for (name in OcrModelManager.SUPPORTED) {
                val hash = manager.models.value.getValue(name)
                val model = manager.model(name, hash)
                assertEquals(manifest.getJSONObject(name).getLong("bytes"), model.length())
                assertEquals(manifest.getJSONObject(name).getString("sha256"), hash)
                assertEquals(hash, MessageDigest.getInstance("SHA-256").digest(model.readBytes()).joinToString("") {"%02x".format(it)})
                val engine = TessBaseAPI()
                try {assertTrue(engine.init(model.parentFile!!.parentFile!!.absolutePath, name, TessBaseAPI.OEM_LSTM_ONLY))}
                finally {engine.recycle()}
                times[name] = model.lastModified()
            }
            runBlocking {manager.ensureBundledModels()}
            for ((name, time) in times) assertEquals(time, manager.model(name, manager.models.value.getValue(name)).lastModified())
        } finally {
            check(directory.canonicalFile.parentFile == app.cacheDir.canonicalFile)
            directory.deleteRecursively()
            app.deleteSharedPreferences(key)
        }
    }
    @Test fun traditionalAndEnglishRecognitionDoesNotNeedManualModelImport() {
        assumeTrue(BuildConfig.BUNDLED_OCR)
        runBlocking {app.ocrModels.ensureBundledModels()}
        val session = File(app.cacheDir,"bundled-recognizer-${System.nanoTime()}").apply {mkdirs()}
        val data = File(session,"tessdata").apply {mkdirs()}
        for(name in listOf("chi_tra","eng")) app.ocrModels.model(name,app.ocrModels.models.value.getValue(name)).copyTo(File(data,"$name.traineddata"))
        val image = Bitmap.createBitmap(1200, 1700, Bitmap.Config.ARGB_8888)
        val engine = TessBaseAPI()
        try {
            val canvas = Canvas(image);canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=42f}
            for (line in 0..12) canvas.drawText(if (line%2==0) "這是繁體中文離線閱讀測試。" else "ReadX OFFLINE OCR", 80f, 160f+line*95, paint)
            assertTrue(engine.init(session.absolutePath, "chi_tra+eng", TessBaseAPI.OEM_LSTM_ONLY))
            engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            engine.setImage(image)
            val text = io.readx.app.conversion.PdfTextFlow.normalize(engine.utF8Text).replace(Regex("\\s+"), "")
            assertTrue(text.contains("繁體") && text.contains("閱讀"))
            assertTrue(text.contains("OFFLINE", true))
        } finally {engine.recycle();image.recycle();data.listFiles()?.forEach {it.delete()};data.delete();session.delete()}
    }
}
