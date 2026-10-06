package io.readx.app.reader

import java.security.MessageDigest

/** All inputs that affect Chromium layout. Colours/annotation choices are intentionally excluded. */
data class LayoutConfig(
    val bookFingerprint: String,
    val chapterHrefs: List<String>,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val density: Float,
    val fontScale: Float,
    val fontSize: Float,
    val lineHeight: Float,
    val margin: Float,
    val serif: Boolean,
    val webViewVersion: String,
    val systemVersion: String,
    val locales: String,
    val engineVersion: String = "columns-v8",
    val fontId:String?=null,
    val textScript:String="ORIGINAL",
) {
    fun generateKey(): String {
        // Length-prefix components: hrefs containing separators must not alias another chapter list.
        val values = listOf(engineVersion, bookFingerprint, viewportWidth, viewportHeight, density,
            fontScale, fontSize, lineHeight, margin, serif, fontId.orEmpty(), textScript, webViewVersion, systemVersion, locales).map { it.toString() } + chapterHrefs
        return layoutDigest(values.joinToString("") { "${it.length}:$it" })
    }
}

internal fun layoutDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
