package io.readx.app.reader

import org.json.JSONObject

/** Canonical prepared DOM UTF-16 offsets + exact quote and contextual fallback; never guess ambiguous matches. */
data class TextAnchor(val start: Int, val end: Int, val quote: String, val prefix: String = "", val suffix: String = "") {
    fun json(): JSONObject = JSONObject().put("start", start).put("end", end).put("quote", quote).put("prefix", prefix).put("suffix", suffix)
    companion object {
        fun parse(value: String): TextAnchor? = runCatching { fromJson(JSONObject(value)) }.getOrNull()
        fun fromJson(value: JSONObject): TextAnchor? {
            val start = value.optInt("start", -1); val end = value.optInt("end", -1)
            val quote = value.optString("quote"); val prefix = value.optString("prefix"); val suffix = value.optString("suffix")
            if (start < 0 || end <= start || end > 64_000_000 || quote.length !in 1..16384 || end - start != quote.length || prefix.length > 40 || suffix.length > 40) return null
            return TextAnchor(start,end,quote,prefix,suffix)
        }
    }
}
