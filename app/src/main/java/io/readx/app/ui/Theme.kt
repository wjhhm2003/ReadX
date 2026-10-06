@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package io.readx.app.ui

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.google.android.material.color.MaterialColors

/** Complete opaque schemes, not a handful of primary overrides on a permanently blue surface scheme. */
internal fun accentScheme(accent: ThemeAccent, custom: String, dark: Boolean): ColorScheme {
    val seed=if(custom.matches(Regex("#[0-9A-Fa-f]{6}"))) Color(("FF"+custom.drop(1)).toLong(16)) else Color(accent.light)
    // Public Material API: HCT tone-based accent pairs, reusing the existing Views dependency.
    // Do not depend on restricted color.utilities implementation classes.
    val primaryRoles=MaterialColors.getColorRoles(seed.toArgb(), !dark)
    val secondaryRoles=MaterialColors.getColorRoles(lerp(seed,Color(0xFF697080),.65f).toArgb(), !dark)
    val tertiaryRoles=MaterialColors.getColorRoles(MaterialColors.harmonize(0xFFB47650.toInt(),seed.toArgb()), !dark)
    val primary=Color(primaryRoles.accent)
    val surface=if(dark) lerp(Color(0xFF111318),seed,.06f) else lerp(Color(0xFFFAFAFC),seed,.015f)
    val container=Color(primaryRoles.accentContainer)
    val onSurface=if(dark) Color(0xFFE5E7ED) else Color(0xFF161B24)
    val base=if(dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary=primary,onPrimary=Color(primaryRoles.onAccent),primaryContainer=container,onPrimaryContainer=Color(primaryRoles.onAccentContainer),
        inversePrimary=Color(MaterialColors.getColorRoles(seed.toArgb(), dark).accent),
        secondary=Color(secondaryRoles.accent),onSecondary=Color(secondaryRoles.onAccent),
        secondaryContainer=Color(secondaryRoles.accentContainer),onSecondaryContainer=Color(secondaryRoles.onAccentContainer),
        tertiary=Color(tertiaryRoles.accent),onTertiary=Color(tertiaryRoles.onAccent),
        tertiaryContainer=Color(tertiaryRoles.accentContainer),onTertiaryContainer=Color(tertiaryRoles.onAccentContainer),
        inverseSurface=if(dark) Color(0xFFE5E7ED) else Color(0xFF2E3036),
        inverseOnSurface=if(dark) Color(0xFF2E3036) else Color(0xFFF2F3F8),
        background=surface,onBackground=onSurface,surface=surface,onSurface=onSurface,
        surfaceTint=primary,surfaceVariant=container,onSurfaceVariant=if(dark) Color(0xFFBCC1CC) else Color(0xFF606978),
        surfaceContainerLowest=if(dark) Color(0xFF080A0E) else Color.White,
        surfaceContainerLow=if(dark) lerp(surface,Color.White,.025f) else lerp(Color.White,seed,.018f),
        surfaceContainer=if(dark) lerp(surface,Color.White,.055f) else lerp(Color.White,seed,.065f),
        surfaceContainerHigh=if(dark) lerp(surface,Color.White,.085f) else lerp(Color.White,seed,.095f),
        surfaceContainerHighest=if(dark) lerp(surface,Color.White,.12f) else lerp(Color.White,seed,.14f),
        surfaceBright=if(dark) lerp(surface,Color.White,.15f) else Color.White,
        surfaceDim=if(dark) Color(0xFF0F1115) else lerp(surface,seed,.08f),
        outline=if(dark) Color(0xFF8B909C) else Color(0xFF7C8491),
        outlineVariant=if(dark) lerp(surface,Color.White,.2f) else lerp(Color.White,seed,.2f))
}
internal fun readingScheme(palette: ColorScheme, theme: ReadingTheme, dark: Boolean): ColorScheme {
    val paper=when(theme) {ReadingTheme.BLACK->Color.Black;ReadingTheme.WARM->Color(0xFFF6EBD5);ReadingTheme.MINT->Color(0xFFE0F0D9);else->if(dark) Color(0xFF151515) else Color(0xFFFCFCFA)}
    val ink=when(theme) {ReadingTheme.WARM->Color(0xFF433D31);ReadingTheme.MINT->Color(0xFF29392F);else->if(dark) Color(0xFFC4C4C4) else Color(0xFF202020)}
    val panel=if(dark) Color(0xFF242424) else lerp(paper,Color.Black,.055f)
    return palette.copy(background=paper,onBackground=ink,surface=paper,onSurface=ink,
        surfaceContainerLowest=paper,surfaceContainerLow=panel,surfaceContainer=panel,
        surfaceContainerHigh=if(dark) Color(0xFF303030) else lerp(paper,Color.Black,.085f),
        surfaceContainerHighest=if(dark) Color(0xFF3B3B3B) else lerp(paper,Color.Black,.12f),
        onSurfaceVariant=if(dark) Color(0xFF999999) else Color(0xFF545454))
}
@Composable
fun ReadXTheme(settings: ReaderSettings, reading: Boolean=false, content: @Composable ()->Unit) {
    val systemDark=isSystemInDarkTheme()
    val appDark=when(settings.appTheme) {
        ReadingTheme.DAY->false
        ReadingTheme.NIGHT, ReadingTheme.BLACK->true
        else->systemDark
    }
    val dark=if(reading) settings.theme==ReadingTheme.NIGHT || settings.theme==ReadingTheme.BLACK || (settings.theme==ReadingTheme.SYSTEM && systemDark) else appDark
    val context=LocalContext.current
    // Dynamic color owns the whole scheme. Reader paper/ink are an intentional, separate override.
    val palette=remember(settings.dynamicColors,settings.accent,settings.customAccent,dark,context) { if(settings.dynamicColors && Build.VERSION.SDK_INT>=31) {
        if(dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else accentScheme(settings.accent,settings.customAccent,dark) }
    val colors=if(reading) readingScheme(palette,settings.theme,dark) else if(!reading && settings.appTheme==ReadingTheme.BLACK && dark) palette.copy(background=Color.Black,surface=Color.Black,surfaceContainerLowest=Color.Black) else palette
    SideEffect {
        val activity=generateSequence(context) {(it as? ContextWrapper)?.baseContext}.filterIsInstance<Activity>().firstOrNull()
        activity?.window?.let {window->
            WindowCompat.getInsetsController(window,window.decorView).apply {isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}
            if(Build.VERSION.SDK_INT>=29) window.isNavigationBarContrastEnforced=false
        }
    }
    MaterialExpressiveTheme(colorScheme=colors, typography=ReadXDesign.typography, shapes=ReadXDesign.shapes, content=content)
}
@Composable
fun ReadXTheme(theme: ReadingTheme, content: @Composable ()->Unit)=ReadXTheme(ReaderSettings(theme=theme),reading=true,content=content)
