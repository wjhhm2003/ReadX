package io.readx.app

import android.os.Bundle
import android.view.ActionMode
import android.view.View
import android.view.ViewGroup
import io.readx.app.reader.LocalWebReader
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.readx.app.ui.LibraryViewModel
import io.readx.app.ui.ReadXApp

class MainActivity : ComponentActivity() {
    private val model: LibraryViewModel by viewModels()
    private fun readerView(view: View = window.decorView): LocalWebReader? {
        if(view is LocalWebReader && view.isEnabled) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) readerView(view.getChildAt(i))?.let {return it}
        return null
    }
    override fun onActionModeStarted(mode: ActionMode) {
        super.onActionModeStarted(mode)
        if(model.reader.value!=null) readerView()?.takeIf {it.hasWindowFocus()}?.bindWindowActionMode(mode)
    }
    override fun onActionModeFinished(mode: ActionMode) {
        readerView()?.unbindWindowActionMode(mode)
        super.onActionModeFinished(mode)
    }
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent);intent.getStringExtra("conversionId")?.let(model::showConversion) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent.getStringExtra("conversionId")?.let(model::showConversion)
        setContent { ReadXApp(model) }
    }
}
