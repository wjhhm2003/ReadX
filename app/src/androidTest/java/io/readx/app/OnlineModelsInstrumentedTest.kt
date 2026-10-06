package io.readx.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import io.readx.app.conversion.*
import io.readx.app.ui.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Opt-in, explicitly authorized fixed public model only. Restores model preferences afterwards. */
@RunWith(AndroidJUnit4::class)
class OnlineModelsInstrumentedTest {
    @Test fun fixedEnglishDownloadVerifiedAndSwitchOffCancelsNextRequest()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("onlineModelTest")=="true")
        val app=ApplicationProvider.getApplicationContext<ReadXApplication>();val prefs=ReaderPreferences(app);val old=prefs.settings.value
        val models=app.getSharedPreferences("ocr-models",android.content.Context.MODE_PRIVATE);val previous=models.getString("eng",null)
        val expected="7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
        val destination=app.ocrModels.model("eng",expected);val existed=destination.isFile
        val scenario=androidx.test.core.app.ActivityScenario.launch<MainActivity>(android.content.Intent(app,MainActivity::class.java))
        val manager=WorkManager.getInstance(app)
        try {
            OcrDownloadPolicy.cancel(app);manager.cancelUniqueWork(OcrDownloadPolicy.NAME).result.get()
            models.edit().remove("eng").commit();app.ocrModels.reload()
            prefs.update(old.copy(onlineModels=true))
            val id=OcrDownloadPolicy.enqueue(app,"eng")
            val info=withTimeout(60000) {var value:WorkInfo?=null;while(value?.state?.isFinished!=true) {delay(100);value=manager.getWorkInfoById(id).get();File(app.getExternalFilesDir(null),"qa/online-model-state.txt").apply {parentFile!!.mkdirs()}.writeText("state=${value?.state} bytes=${value?.progress?.getLong("bytes",0)}")};value!!}
            assertEquals("Download failed: ${info.outputData.getString("error")}",WorkInfo.State.SUCCEEDED,info.state)
            assertEquals(4113088L,destination.length());assertEquals(expected,app.ocrModels.models.value["eng"])
            val metrics=File(app.getExternalFilesDir(null),"qa/online-model-result.txt").apply {parentFile!!.mkdirs()}
            metrics.writeText("fixed_english_bytes=${destination.length()}\nsha256=$expected\nnetwork_source=official_fixed_commit\n")
            val next=OcrDownloadPolicy.enqueue(app,"chi_sim+eng")
            prefs.update(old.copy(onlineModels=false))
            withTimeout(15000) {while(manager.getWorkInfoById(next).get()?.state?.isFinished!=true)delay(50)}
            assertFalse(OcrDownloadPolicy.allowed(app,OcrDownloadPolicy.generation(app)))
        } finally {
            OcrDownloadPolicy.cancel(app);manager.cancelUniqueWork(OcrDownloadPolicy.NAME).result.get()
            models.edit().apply {if(previous==null)remove("eng") else putString("eng",previous)}.commit();app.ocrModels.reload()
            if(!existed && previous!=expected)destination.delete()
            prefs.update(old);scenario.close()
        }
    }
}
