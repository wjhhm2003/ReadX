package io.readx.app.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared tokens: 8dp rhythm, tonal depth, and generous (not fixed-height) content. */
internal object ReadXDesign {
    val gutter = 24.dp
    val gap = 16.dp
    val compactGap = 8.dp
    val contentWidth = 840.dp
    val railBreakpoint = 600.dp
    val readerPanelShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )
    // System sans-serif also provides the device's Chinese fallback, without bundling a font.
    val typography = Typography(
        displaySmall = Typography().displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineLarge = Typography().headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineSmall = Typography().headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = Typography().bodyLarge.copy(lineHeight = 26.sp),
        bodyMedium = Typography().bodyMedium.copy(lineHeight = 22.sp),
    )
}

@Composable
internal fun SectionHeading(title: String, detail: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        detail?.let {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(it, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}


/** Paint behind the gesture handle, without adding padding or changing the reader viewport. */
@Composable
internal fun SystemNavigationProtection(color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    SideEffect {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        @Suppress("DEPRECATION")
        activity?.window?.navigationBarColor = color.toArgb()
    }
    Spacer(modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars).background(color))
}
