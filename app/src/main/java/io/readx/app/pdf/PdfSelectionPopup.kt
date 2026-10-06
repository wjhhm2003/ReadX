package io.readx.app.pdf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*

/** Positions outside the selected text when space permits; native root already owns system insets. */
@Composable
internal fun PdfSelectionPopup(selection:PdfSelection,dismiss:()->Unit,content:@Composable ()->Unit) {
    val density=LocalDensity.current
    val view=LocalView.current
    val position=remember(selection.windowBounds,density) {object:PopupPositionProvider {
        override fun calculatePosition(anchorBounds:IntRect,windowSize:IntSize,layoutDirection:LayoutDirection,popupContentSize:IntSize):IntOffset {
            val margin=with(density) {12.dp.roundToPx()};val rect=selection.windowBounds
            val insets=ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            val safeTop=(insets?.top ?: 0)+margin
            val safeBottom=windowSize.height-(insets?.bottom ?: 0)-margin
            val x=((rect?.center?.x ?: windowSize.width/2f)-popupContentSize.width/2).toInt().coerceIn(margin,(windowSize.width-popupContentSize.width-margin).coerceAtLeast(margin))
            val above=(rect?.top ?: windowSize.height*.5f)-popupContentSize.height-margin
            val below=(rect?.bottom ?: 0f)+margin
            val y=(if(above>=safeTop)above else below).toInt().coerceIn(safeTop,(safeBottom-popupContentSize.height).coerceAtLeast(safeTop))
            return IntOffset(x,y)
        }
    }}
    Popup(popupPositionProvider=position,onDismissRequest=dismiss,properties=PopupProperties(focusable=false,dismissOnBackPress=true)) {
        Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainerHighest,shadowElevation=8.dp,modifier=Modifier.widthIn(max=340.dp)) {
            Column(Modifier.padding(12.dp)) {content()}
        }
    }
}
